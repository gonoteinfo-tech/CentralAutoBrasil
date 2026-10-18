package com.roadconnect.receiver;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.roadconnect.mirror.MirrorWire;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded decoder queues and one common monotonic timeline for video and PCM. */
final class ReceiverPlayback implements AutoCloseable {
    private static final int SAMPLE_RATE = 48_000;
    private final Context context;
    private final Surface surface;
    private final String codecName;
    private final MirrorWire.VideoFormat format;
    private final Runnable onFailure;
    private final ArrayBlockingQueue<MirrorWire.Frame> videoQueue = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<MirrorWire.Frame> audioQueue = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean audioStarted = new AtomicBoolean();
    private long basePtsUs = Long.MIN_VALUE;
    private long baseNanos;
    private long lastVideoPts = Long.MIN_VALUE;
    private long lastAudioPts = Long.MIN_VALUE;
    private Thread videoThread;
    private Thread audioThread;
    private volatile AudioTrack audioTrack;

    ReceiverPlayback(Context context, Surface surface, String codecName, MirrorWire.VideoFormat format,
                     Runnable onFailure) {
        this.context = context;
        this.surface = surface;
        this.codecName = codecName;
        this.format = format;
        this.onFailure = onFailure;
    }

    void start() {
        videoThread = new Thread(this::decodeVideo, "receiver-video");
        videoThread.start();
    }

    void enqueue(MirrorWire.Frame frame) throws IOException, InterruptedException {
        if (closed.get()) throw new IOException("Reprodução encerrada");
        // Establish the timeline once, shared across the independent audio and video streams.
        synchronized (this) {
            if (basePtsUs == Long.MIN_VALUE) {
                basePtsUs = frame.ptsUs;
                baseNanos = System.nanoTime() + 150_000_000L;
            }
            long previous = frame.type == MirrorWire.VIDEO ? lastVideoPts : lastAudioPts;
            if (previous != Long.MIN_VALUE && frame.ptsUs < previous)
                throw new IOException("Timestamp fora de ordem");
            if (frame.type == MirrorWire.VIDEO) lastVideoPts = frame.ptsUs;
            else lastAudioPts = frame.ptsUs;
        }
        presentationNanos(frame.ptsUs);
        ArrayBlockingQueue<MirrorWire.Frame> queue = frame.type == MirrorWire.VIDEO ? videoQueue : audioQueue;
        if (!queue.offer(frame, 300, TimeUnit.MILLISECONDS))
            throw new IOException("Central não acompanha a transmissão. Tente 720p.");
        if (frame.type == MirrorWire.AUDIO && audioStarted.compareAndSet(false, true)) {
            audioThread = new Thread(this::playAudio, "receiver-audio");
            audioThread.start();
        }
    }

    private synchronized long presentationNanos(long ptsUs) throws IOException {
        // Avoid Math.*Exact (API 24) because this receiver supports Android 6/API 23.
        long deltaUs = ptsUs - basePtsUs; // Both wire timestamps are nonnegative.
        if (deltaUs > Long.MAX_VALUE / 1000 || deltaUs < Long.MIN_VALUE / 1000)
            throw new IOException("Timestamp inválido");
        long deltaNanos = deltaUs * 1000;
        long nanos = baseNanos + deltaNanos;
        if (((baseNanos ^ nanos) & (deltaNanos ^ nanos)) < 0)
            throw new IOException("Timestamp inválido");
        long ahead = nanos - System.nanoTime();
        if (ahead > 3_000_000_000L || ahead < -3_000_000_000L)
            throw new IOException("Transmissão atrasada. Reconecte em 720p.");
        return nanos;
    }

    private void decodeVideo() {
        MediaCodec codec = null;
        try {
            MediaFormat config = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height);
            config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MirrorWire.MAX_PAYLOAD);
            config.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            if (format.csd0.length > 0) config.setByteBuffer("csd-0", ByteBuffer.wrap(format.csd0));
            if (format.csd1.length > 0) config.setByteBuffer("csd-1", ByteBuffer.wrap(format.csd1));
            codec = MediaCodec.createByCodecName(codecName);
            codec.configure(config, surface, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MirrorWire.Frame pending = null;
            while (!closed.get()) {
                if (pending == null) pending = videoQueue.poll(5, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    int input = codec.dequeueInputBuffer(5_000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        if (buffer == null || buffer.capacity() < pending.data.length)
                            throw new IOException("Frame maior que a capacidade do decodificador");
                        buffer.clear();
                        buffer.put(pending.data);
                        codec.queueInputBuffer(input, 0, pending.data.length, pending.ptsUs, 0);
                        pending = null;
                    }
                }
                int output;
                while ((output = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(output, false);
                    } else {
                        long target = presentationNanos(info.presentationTimeUs);
                        codec.releaseOutputBuffer(output, target);
                    }
                    if (closed.get()) break;
                }
            }
        } catch (Exception failure) {
            if (!closed.get()) android.util.Log.w("RoadstarReceiver", "Video decoder failed", failure);
            fail();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
        }
    }

    private void playAudio() {
        AudioTrack track = null;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest focusRequest = null;
        AudioManager.OnAudioFocusChangeListener focus = change -> {
            AudioTrack current = audioTrack;
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                android.util.Log.i("RoadstarReceiver", "Receiver audio focus lost: " + change);
                fail();
            } else if (current != null) {
                try { current.setVolume(change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.2f : 1f); }
                catch (IllegalStateException ignored) { }
            }
        };
        boolean focusHeld = false;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            int focusResult;
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focus,
                                new android.os.Handler(android.os.Looper.getMainLooper())).build();
                focusResult = manager.requestAudioFocus(focusRequest);
            } else {
                focusResult = manager.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw new IOException("Áudio ocupado por outro aplicativo");
            focusHeld = true;
            int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Saída PCM indisponível");
            // Keep the hardware queue small; pending network PCM is bounded separately.
            track = new AudioTrack(attributes,
                    new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    Math.max(minimum, 9_600), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Não foi possível abrir áudio");
            audioTrack = track;
            boolean playing = false;
            while (!closed.get()) {
                MirrorWire.Frame frame = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                long target = presentationNanos(frame.ptsUs);
                if (!playing) {
                    waitUntil(target);
                    if (closed.get()) break;
                    track.play();
                    playing = true;
                }
                // AudioTrack supplies the sample clock. Limit how far its PCM may lead video.
                waitUntil(target - 100_000_000L);
                int offset = 0;
                while (!closed.get() && offset < frame.data.length) {
                    int written = track.write(frame.data, offset, frame.data.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) throw new IOException("Falha ao tocar áudio PCM");
                    offset += written;
                    if (written == 0) Thread.sleep(5);
                }
            }
        } catch (Exception failure) {
            if (!closed.get()) android.util.Log.w("RoadstarReceiver", "PCM playback failed", failure);
            fail();
        } finally {
            audioTrack = null;
            if (track != null) {
                try { track.pause(); track.flush(); } catch (RuntimeException ignored) { }
                track.release();
            }
            if (focusHeld) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) manager.abandonAudioFocusRequest(focusRequest);
                else manager.abandonAudioFocus(focus);
            }
        }
    }

    private void waitUntil(long targetNanos) throws InterruptedException {
        while (!closed.get()) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) return;
            Thread.sleep(Math.min(remaining / 1_000_000L + 1, 20));
        }
    }

    private void fail() {
        if (closed.compareAndSet(false, true)) {
            interruptWorkers();
            onFailure.run();
        }
    }

    private void interruptWorkers() {
        Thread video = videoThread;
        Thread audio = audioThread;
        if (video != null && video != Thread.currentThread()) video.interrupt();
        if (audio != null && audio != Thread.currentThread()) audio.interrupt();
    }

    @Override public void close() {
        closed.set(true);
        interruptWorkers();
        videoQueue.clear();
        audioQueue.clear();
        // Workers own codec/track release. Bounded polls and nonblocking writes make stop prompt.
    }

    static final class Decoders {
        final String decoder1080;
        final String decoder720;
        final int maxWidth;
        final int maxHeight;
        Decoders(String decoder1080, String decoder720) {
            this.decoder1080 = decoder1080;
            this.decoder720 = decoder720;
            maxWidth = decoder1080 != null ? 1920 : 1280;
            maxHeight = decoder1080 != null ? 1080 : 720;
        }
        String decoderFor(int width, int height) {
            if (width == 1920 && height == 1080) return decoder1080;
            if (width == 1280 && height == 720) return decoder720;
            return null;
        }
    }

    static Decoders findDecoders() throws IOException {
        String fullHd = findDecoder(1920, 1080);
        String hd = findDecoder(1280, 720);
        if (fullHd == null && hd == null) throw new IOException("H.264 720p/30 fps indisponível nesta central");
        return new Decoders(fullHd, hd);
    }

    private static String findDecoder(int width, int height) {
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                        || caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) continue;
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null && video.areSizeAndRateSupported(width, height, 30)) return codec.getName();
            } catch (IllegalArgumentException ignored) {
                // This component does not decode AVC at the offered dimensions/rate.
            }
        }
        return null;
    }
}
