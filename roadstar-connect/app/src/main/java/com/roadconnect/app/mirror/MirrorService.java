package com.roadconnect.app.mirror;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.roadconnect.app.R;
import com.roadconnect.mirror.MirrorWire;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;

/** Foreground-only screen capture. No projection token or media is stored on disk. */
public final class MirrorService extends Service {
    public static final String ACTION_START = "com.roadconnect.app.mirror.START";
    public static final String ACTION_STOP = "com.roadconnect.app.mirror.STOP";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    public static final String EXTRA_HOST = "receiver_host";
    public static final String EXTRA_PORT = "receiver_port";
    public static final String EXTRA_PAIR_CODE = "pair_code";
    public static final String EXTRA_FINGERPRINT = "receiver_fingerprint";
    public static final String EXTRA_WIDTH = "capture_width";
    public static final String EXTRA_HEIGHT = "capture_height";
    public static final String EXTRA_AUDIO = "capture_audio";
    public static final String PREFERENCES = "screen_mirror";
    public static final String STATUS_MESSAGE = "status_message";
    public static final int PORT = 8999;
    private static final String CHANNEL = "screen_mirroring";
    private static final int NOTIFICATION_ID = 4201;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private boolean receiverRegistered;
    private boolean foreground;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Session current = session;
            if (current != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))
                current.stop("Transmissão encerrada ao bloquear ou apagar a tela. Autorize uma nova sessão para reconectar.");
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Session current = session;
            if (current == null || current.stopped.get()) return;
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                current.stop("Transmissão encerrada porque o telefone foi bloqueado.");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!current.capturing && now - current.startedAt > 30_000) {
                current.stop("A central demorou para conectar. Confira a rede e autorize uma nova sessão.");
                return;
            }
            if (current.capturing && now - current.lastVideoSentAt > 12_000) {
                current.stop("Transmissão interrompida: a central ou o codificador parou de responder. Tente novamente ou selecione 720p.");
                return;
            }
            main.postDelayed(this, 2_000);
        }
    };

    public static boolean isRunning() { return ACTIVE.get(); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Espelhamento de tela", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Captura de tela ativa com controle para parar.");
            notifications.createNotificationChannel(channel);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            if (session != null) session.stop("Transmissão encerrada. Para reconectar, autorize uma nova sessão.");
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(startId); return START_NOT_STICKY; }
        // The OS permission result is never reused by a second start request.
        if (session != null || !ACTIVE.compareAndSet(false, true)) return START_NOT_STICKY;
        try {
            Notification notification = notification("Preparando captura autorizada…");
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
            session = new Session(intent);
            status("Conectando à central pelo Wi-Fi…");
            main.postDelayed(watchdog, 2_000);
            Thread worker = new Thread(session::run, "mirror-video");
            worker.start();
        } catch (RuntimeException invalid) {
            ACTIVE.set(false);
            status("Não foi possível iniciar a captura. Autorize uma nova sessão com o app aberto.");
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MirrorActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MirrorService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Roadstar Connect • tela compartilhada")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(R.drawable.ic_stop, "Parar", stop).build();
    }

    private void status(String message) {
        main.post(() -> getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS_MESSAGE, message).apply());
    }

    @Override public void onDestroy() {
        main.removeCallbacks(watchdog);
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        if (session != null) session.stop("Transmissão encerrada pelo Android. Autorize uma nova sessão para reconectar.");
        ACTIVE.set(false);
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putLong("status_revision", System.nanoTime()).apply();
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private final class Session {
        final AtomicBoolean stopped = new AtomicBoolean();
        final Object resources = new Object();
        final long startedAt = SystemClock.elapsedRealtime();
        final String host;
        final String code;
        final String pin;
        final int width;
        final int height;
        final boolean wantsAudio;
        final int resultCode;
        Intent resultData;
        SSLSocket socket;
        MediaProjection projection;
        MediaProjection.Callback projectionCallback;
        MediaCodec codec;
        Surface surface;
        VirtualDisplay display;
        AudioRecord audioRecord;
        volatile DataOutputStream output;
        volatile boolean capturing;
        volatile long lastVideoSentAt = SystemClock.elapsedRealtime();

        @SuppressWarnings("deprecation")
        Session(Intent intent) {
            host = MirrorWire.requirePrivateIpv4(intent.getStringExtra(EXTRA_HOST));
            pin = MirrorWire.requireFingerprint(intent.getStringExtra(EXTRA_FINGERPRINT));
            code = intent.getStringExtra(EXTRA_PAIR_CODE);
            if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Código inválido.");
            if (intent.getIntExtra(EXTRA_PORT, PORT) != PORT) throw new IllegalArgumentException("Porta inválida.");
            width = intent.getIntExtra(EXTRA_WIDTH, 0);
            height = intent.getIntExtra(EXTRA_HEIGHT, 0);
            if (!((width == 1920 && height == 1080) || (width == 1280 && height == 720)))
                throw new IllegalArgumentException("Resolução inválida.");
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            resultData = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != Activity.RESULT_OK || resultData == null) throw new IllegalArgumentException("Captura não autorizada.");
            wantsAudio = Build.VERSION.SDK_INT >= 29 && intent.getBooleanExtra(EXTRA_AUDIO, false);
        }

        void run() {
            try {
                connect();
                if (stopped.get()) return;
                prepareVideo();
                if (stopped.get()) return;
                capturing = true;
                lastVideoSentAt = SystemClock.elapsedRealtime();
                status("Transmitindo " + width + " × " + height + (wantsAudio ? " • áudio interno permitido" : " • somente vídeo") + ". Pare pela notificação ou por esta tela.");
                drainVideo();
            } catch (UnsupportedOperationException incompatible) {
                stop(incompatible.getMessage());
            } catch (Exception failure) {
                stop("Transmissão interrompida. Confira código, certificado e Wi-Fi; depois autorize uma nova sessão. Se necessário, selecione 720p.");
            } finally {
                resultData = null;
                if (!stopped.get()) stop("Transmissão encerrada. Autorize uma nova sessão para reconectar.");
            }
        }

        void connect() throws Exception {
            SSLSocket candidate = MirrorTls.create(pin, null);
            synchronized (resources) {
                if (stopped.get()) { candidate.close(); return; }
                socket = candidate;
            }
            MirrorTls.handshake(candidate, host, PORT);
            if (stopped.get()) return;
            DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(candidate.getOutputStream(), 64 * 1024));
            output = stream;
            MirrorWire.writeHello(stream, code);
            int[] maximum = MirrorWire.readWelcome(new DataInputStream(candidate.getInputStream()));
            if (maximum[0] < width || maximum[1] < height)
                throw new UnsupportedOperationException("A central não aceita esta resolução. Selecione 720p e autorize uma nova sessão.");
            candidate.setSoTimeout(0);
        }

        void prepareVideo() throws Exception {
            MediaCodec encoder = createEncoder(width, height);
            synchronized (resources) {
                if (stopped.get()) { encoder.release(); return; }
                codec = encoder;
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, width == 1920 ? 6_000_000 : 3_000_000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_333L);
                if (Build.VERSION.SDK_INT >= 29) format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = encoder.createInputSurface();
                encoder.start();
                if (stopped.get()) return;
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                if (manager == null) throw new IOException("Captura indisponível.");
                // Must follow startForeground; Android 14 grants are single-use.
                projection = manager.getMediaProjection(resultCode, resultData);
                resultData = null;
                if (projection == null) throw new IOException("Permissão de captura encerrada.");
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() { stop("Compartilhamento encerrado pelo Android. Autorize uma nova sessão para reconectar."); }
                    @Override public void onCapturedContentResize(int contentWidth, int contentHeight) {
                        // Keep the negotiated canvas. Android 12L+ scales and letterboxes captured content.
                    }
                };
                projection.registerCallback(projectionCallback, main);
                display = projection.createVirtualDisplay("Roadstar Connect", width, height,
                        getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, main);
            }
        }

        void drainVideo() throws IOException {
            MediaCodec encoder = codec;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean formatted = false;
            boolean audioStarted = false;
            while (!stopped.get()) {
                int index;
                try { index = encoder.dequeueOutputBuffer(info, 10_000); }
                catch (IllegalStateException ended) { if (stopped.get()) return; throw ended; }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = encoder.getOutputFormat();
                    byte[] csd0 = configuration(format, "csd-0");
                    byte[] csd1 = configuration(format, "csd-1");
                    MirrorWire.writeFrame(output, MirrorWire.FORMAT, 0, MirrorWire.formatPayload(width, height, csd0, csd1));
                    formatted = true;
                    // The receiver requires FORMAT before any media, including PCM audio.
                    if (wantsAudio && !audioStarted && !stopped.get()) {
                        audioStarted = true;
                        new Thread(this::captureAudio, "mirror-audio").start();
                    }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (!formatted) throw new IOException("Codificador sem configuração.");
                            if (info.size > MirrorWire.MAX_PAYLOAD) throw new IOException("Quadro acima do limite.");
                            ByteBuffer buffer = encoder.getOutputBuffer(index);
                            if (buffer == null || info.offset < 0 || info.size < 0 || info.offset > buffer.capacity() - info.size)
                                throw new IOException("Quadro de vídeo inválido.");
                            ByteBuffer copy = buffer.duplicate();
                            copy.position(info.offset);
                            copy.limit(info.offset + info.size);
                            byte[] frame = new byte[info.size];
                            copy.get(frame);
                            MirrorWire.writeFrame(output, MirrorWire.VIDEO, Math.max(0, info.presentationTimeUs), frame);
                            lastVideoSentAt = SystemClock.elapsedRealtime();
                        }
                    } finally {
                        try { encoder.releaseOutputBuffer(index, false); } catch (IllegalStateException ended) { if (!stopped.get()) throw ended; }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        @SuppressLint("MissingPermission")
        void captureAudio() {
            if (Build.VERSION.SDK_INT < 29 || stopped.get()) return;
            if (ContextCompat.checkSelfPermission(MirrorService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioUnavailable(); return;
            }
            AudioRecord recorder = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) { audioUnavailable(); return; }
                synchronized (resources) {
                    if (stopped.get() || projection == null) return;
                    AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                    AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                    recorder = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                            .setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum * 2, 16_384)).build();
                    audioRecord = recorder;
                    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Áudio indisponível.");
                    recorder.startRecording();
                }
                byte[] buffer = new byte[4096];
                long framesRead = 0;
                long previousPts = -1;
                AudioTimestamp timestamp = new AudioTimestamp();
                while (!stopped.get()) {
                    int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (count <= 0) { if (!stopped.get()) audioUnavailable(); return; }
                    count &= ~1;
                    if (count == 0) continue;
                    int frames = count / 2;
                    long pts;
                    if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                        pts = timestamp.nanoTime / 1000 + ((framesRead - timestamp.framePosition) * 1_000_000L / 48_000);
                    else pts = System.nanoTime() / 1000 - frames * 1_000_000L / 48_000;
                    pts = Math.max(Math.max(0, previousPts + 1), pts);
                    previousPts = pts;
                    framesRead += frames;
                    byte[] chunk = new byte[count];
                    System.arraycopy(buffer, 0, chunk, 0, count);
                    try { MirrorWire.writeFrame(output, MirrorWire.AUDIO, pts, chunk); }
                    catch (IOException disconnected) { stop("Áudio e vídeo interrompidos: conexão com a central encerrada."); return; }
                }
            } catch (RuntimeException captureUnavailable) {
                if (!stopped.get()) audioUnavailable();
            } finally {
                synchronized (resources) {
                    if (audioRecord == recorder) audioRecord = null;
                    else recorder = null; // Cleanup already took ownership.
                }
                if (recorder != null) {
                    try { recorder.stop(); } catch (RuntimeException ignored) {}
                    recorder.release();
                }
            }
        }

        void audioUnavailable() {
            if (!stopped.get()) status("Transmitindo vídeo " + width + " × " + height + ". Áudio interno indisponível ou não autorizado neste aparelho/aplicativo.");
        }

        void stop(String message) {
            if (!stopped.compareAndSet(false, true)) return;
            status(message == null ? "Transmissão encerrada." : message);
            main.post(() -> {
                main.removeCallbacks(watchdog);
                if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
                stopSelf();
            });
            // Never wait for a blocked socket write or codec on the main thread.
            new Thread(this::release, "mirror-cleanup").start();
        }

        void release() {
            SSLSocket connected;
            synchronized (resources) { connected = socket; socket = null; }
            if (connected != null) {
                try { connected.close(); } catch (IOException ignored) {}
            }
            synchronized (resources) {
                output = null;
                resultData = null;
                if (audioRecord != null) {
                    try { audioRecord.stop(); } catch (RuntimeException ignored) {}
                    audioRecord.release();
                    audioRecord = null;
                }
                if (display != null) { display.release(); display = null; }
                if (projection != null) {
                    if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                    try { projection.stop(); } catch (RuntimeException ignored) {}
                    projection = null;
                }
                if (codec != null) {
                    try { codec.stop(); } catch (RuntimeException ignored) {}
                    try { codec.release(); } catch (RuntimeException ignored) {}
                    codec = null;
                }
                if (surface != null) { surface.release(); surface = null; }
            }
        }
    }

    private static MediaCodec createEncoder(int width, int height) throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                    boolean surface = false;
                    for (int color : capabilities.colorFormats)
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                    if (surface && video != null && video.areSizeAndRateSupported(width, height, 30))
                        return MediaCodec.createByCodecName(info.getName());
                } catch (IllegalArgumentException ignored) { /* Try another installed encoder. */ }
            }
        }
        throw new UnsupportedOperationException("Este telefone não codifica " + width + " × " + height + " a 30 fps. Selecione 720p e tente novamente.");
    }

    private static byte[] configuration(MediaFormat format, String key) throws IOException {
        ByteBuffer data = format.getByteBuffer(key);
        if (data == null) return new byte[0];
        ByteBuffer copy = data.duplicate();
        if (copy.remaining() > 65_536) throw new IOException("Configuração de vídeo acima do limite.");
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
