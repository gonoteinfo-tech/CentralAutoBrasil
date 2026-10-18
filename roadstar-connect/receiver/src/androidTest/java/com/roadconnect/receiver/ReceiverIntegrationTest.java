package com.roadconnect.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.roadconnect.mirror.MirrorWire;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Real TLS, H.264 encoding/decoding, Surface pixels and AudioTrack on an Android device. */
@RunWith(AndroidJUnit4.class)
public final class ReceiverIntegrationTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;

    @Test public void pinnedTlsRendersAvcPlaysPcmAndStopsWhenActivityCloses() throws Exception {
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try (ActivityScenario<ReceiverActivity> scenario = ActivityScenario.launch(ReceiverActivity.class)) {
            ReceiverUi ready = awaitReady(scenario);
            try (SSLSocket socket = connectPinned(ready.fingerprint, null)) {
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                MirrorWire.writeHello(output, ready.code);
                output.flush();
                int[] capability = MirrorWire.readWelcome(input);
                assertTrue("The emulator must provide a real 720p AVC decoder", capability[0] >= WIDTH && capability[1] >= HEIGHT);

                Future<?> stream = producer.submit(() -> {
                    try { sendAvcAndPcm(output); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                try {
                    awaitVideoAndAudio(scenario, stream);
                    stream.get(12, TimeUnit.SECONDS);
                    String status = snapshot(scenario).status;
                    assertFalse("Receiver reported an error: " + status,
                            status.contains("Não foi possível") || status.contains("encerrada"));
                    // Keep the sender connected: only closing the visible receiver should end TLS.
                    scenario.close();
                    assertPeerClosed(input, socket);
                } finally {
                    stream.cancel(true);
                }
            }
        } finally {
            producer.shutdownNow();
            assertTrue("Encoder thread did not release its codec", producer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void wrongCertificateFingerprintIsRejectedBeforeSendingTheCode() throws Exception {
        try (ActivityScenario<ReceiverActivity> scenario = ActivityScenario.launch(ReceiverActivity.class)) {
            ReceiverUi ready = awaitReady(scenario);
            String wrongPin = (ready.fingerprint.charAt(0) == '0' ? "1" : "0") + ready.fingerprint.substring(1);
            AtomicBoolean rejectedByPin = new AtomicBoolean();
            try (SSLSocket unexpected = connectPinned(wrongPin, rejectedByPin)) {
                fail("TLS accepted a certificate whose SHA-256 does not match the displayed fingerprint");
            } catch (SSLException expected) {
                assertTrue("Handshake failed for a reason other than pin validation", rejectedByPin.get());
            }
        }
    }

    private static ReceiverUi awaitReady(ActivityScenario<ReceiverActivity> scenario) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        ReceiverUi state;
        do {
            state = snapshot(scenario);
            if (state.code.matches("[0-9]{6}") && state.fingerprint.matches("[0-9a-f]{64}")
                    && state.surfaceReady && state.centerX > 0 && state.centerY > 0) return state;
            assertFalse("Receiver could not start: " + state.status, state.status.contains("Não foi possível"));
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Receiver UI did not become ready: " + state.status);
    }

    private static ReceiverUi snapshot(ActivityScenario<ReceiverActivity> scenario) {
        AtomicReference<ReceiverUi> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            TextView code = activity.findViewById(R.id.receiver_code);
            TextView pin = activity.findViewById(R.id.receiver_fingerprint);
            TextView status = activity.findViewById(R.id.receiver_status);
            SurfaceView video = activity.findViewById(R.id.receiver_video);
            assertNotNull("Receiver must expose its pairing code", code);
            assertNotNull("Receiver must expose its certificate fingerprint", pin);
            assertNotNull(status);
            assertNotNull(video);
            String normalized = pin.getText().toString().replaceAll("\\s", "").toLowerCase(java.util.Locale.US);
            int[] position = new int[2];
            video.getLocationOnScreen(position);
            value.set(new ReceiverUi(code.getText().toString(), normalized, status.getText().toString(),
                    video.getHolder().getSurface().isValid(), position[0] + video.getWidth() / 2,
                    position[1] + video.getHeight() / 2));
        });
        return value.get();
    }

    private static SSLSocket connectPinned(String fingerprint, AtomicBoolean rejectedByPin) throws Exception {
        String expected = MirrorWire.requireFingerprint(fingerprint);
        X509TrustManager manager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                chain[0].checkValidity();
                if (!expected.equals(MirrorWire.fingerprint(chain[0].getEncoded()))) {
                    if (rejectedByPin != null) rejectedByPin.set(true);
                    throw new CertificateException("Server fingerprint does not match the receiver screen");
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] { manager }, null);
        InetAddress address = privateAddress();
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (true) {
            SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket();
            try {
                socket.connect(new InetSocketAddress(address, 8999), 2_000);
                socket.setSoTimeout(5_000);
                socket.setTcpNoDelay(true);
                socket.startHandshake();
                return socket;
            } catch (ConnectException notListeningYet) {
                socket.close();
                if (SystemClock.elapsedRealtime() >= deadline) throw notListeningYet;
                Thread.sleep(100);
            } catch (Exception error) {
                socket.close();
                throw error;
            }
        }
    }

    private static InetAddress privateAddress() throws Exception {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address;
            }
        }
        throw new AssertionError("Emulator requires a private IPv4 interface for the local receiver");
    }

    private static void awaitVideoAndAudio(ActivityScenario<ReceiverActivity> scenario, Future<?> stream) throws Exception {
        AudioManager audio = (AudioManager) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(Context.AUDIO_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        boolean redSeen = false;
        boolean audioActive = false;
        do {
            if (stream.isDone()) stream.get(); // Surface encoder failures must fail the test, never skip it.
            ReceiverUi ui = snapshot(scenario);
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Android screenshot must include the decoded SurfaceView", screenshot);
            try {
                assertTrue(ui.centerX < screenshot.getWidth() && ui.centerY < screenshot.getHeight());
                int pixel = screenshot.getPixel(ui.centerX, ui.centerY);
                redSeen |= Color.red(pixel) > 130 && Color.green(pixel) < 100 && Color.blue(pixel) < 100;
            } finally {
                screenshot.recycle();
            }
            audioActive |= audio.isMusicActive();
            if (redSeen && audioActive) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("A real decoded red AVC frame never reached the receiver SurfaceView", redSeen);
        assertTrue("PCM frames never activated media audio playback on the receiver", audioActive);
    }

    private static void sendAvcAndPcm(DataOutputStream output) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        Surface input = null;
        EncoderEgl egl = null;
        boolean started = false;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = encoder.createInputSurface();
            egl = new EncoderEgl(input);
            encoder.start();
            started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean formatSent = false;
            long originNs = System.nanoTime();
            for (int frame = 0; frame < 150; frame++) {
                long ptsNs = originNs + frame * 1_000_000_000L / FPS;
                long waitNs = ptsNs - System.nanoTime();
                if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                egl.renderRed(ptsNs);
                formatSent = drainEncoder(encoder, info, output, formatSent);
                if (formatSent) MirrorWire.writeFrame(output, MirrorWire.AUDIO, ptsNs / 1_000,
                        tonePcm(frame * 1600));
                output.flush();
            }
            encoder.signalEndOfInputStream();
            long deadline = SystemClock.elapsedRealtime() + 3_000;
            while ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0 && SystemClock.elapsedRealtime() < deadline) {
                formatSent = drainEncoder(encoder, info, output, formatSent);
                output.flush();
            }
            assertTrue("AVC encoder did not produce its format", formatSent);
            assertTrue("AVC encoder did not finish", (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0);
        } finally {
            if (egl != null) egl.close();
            if (started) {
                try { encoder.stop(); } catch (IllegalStateException ignored) { }
            }
            encoder.release();
            if (input != null) input.release();
        }
    }

    private static boolean drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo info,
                                        DataOutputStream output, boolean formatSent) throws IOException {
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, 10_000);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return formatSent;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                assertFalse("Unexpected second AVC format", formatSent);
                MediaFormat encoded = encoder.getOutputFormat();
                MirrorWire.writeFrame(output, MirrorWire.FORMAT, 0,
                        MirrorWire.formatPayload(WIDTH, HEIGHT, bytes(encoded.getByteBuffer("csd-0")),
                                bytes(encoded.getByteBuffer("csd-1"))));
                formatSent = true;
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        assertTrue("Video arrived before codec configuration", formatSent);
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        assertNotNull(buffer);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        MirrorWire.writeFrame(output, MirrorWire.VIDEO, Math.max(0, info.presentationTimeUs), bytes(buffer));
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return formatSent;
            }
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        assertNotNull("AVC codec-specific data is required", buffer);
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }

    private static byte[] tonePcm(int firstSample) {
        byte[] data = new byte[3_200]; // PCM16 little-endian, mono, 48 kHz: one 30 fps interval.
        for (int sample = 0; sample < 1600; sample++) {
            short value = (short) (4_000 * Math.sin(2 * Math.PI * 440 * (firstSample + sample) / 48_000));
            data[2 * sample] = (byte) value;
            data[2 * sample + 1] = (byte) (value >> 8);
        }
        return data;
    }

    private static void assertPeerClosed(DataInputStream input, SSLSocket socket) throws Exception {
        socket.setSoTimeout(1_000);
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (input.read() == -1) return;
            } catch (SocketTimeoutException stillOpen) {
                // Allow the receiver worker a bounded interval to release TLS and its media resources.
            } catch (IOException closed) {
                return;
            }
        }
        fail("Leaving the receiver Activity did not close the active sender connection");
    }

    private static final class ReceiverUi {
        final String code;
        final String fingerprint;
        final String status;
        final boolean surfaceReady;
        final int centerX;
        final int centerY;
        ReceiverUi(String code, String fingerprint, String status, boolean surfaceReady, int centerX, int centerY) {
            this.code = code;
            this.fingerprint = fingerprint;
            this.status = status;
            this.surfaceReady = surfaceReady;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private static final class EncoderEgl implements AutoCloseable {
        private final EGLDisplay display;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface window = EGL14.EGL_NO_SURFACE;
        EncoderEgl(Surface surface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            try {
                assertTrue("EGL initialization failed", EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
                int[] attributes = {
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] count = new int[1];
                assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0));
                assertTrue("No recordable EGL config on emulator", count[0] > 0);
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
                assertFalse("Cannot create GLES2 context", context.equals(EGL14.EGL_NO_CONTEXT));
                window = EGL14.eglCreateWindowSurface(display, configs[0], surface, new int[] { EGL14.EGL_NONE }, 0);
                assertFalse("Cannot create codec input EGL surface", window.equals(EGL14.EGL_NO_SURFACE));
                assertTrue(EGL14.eglMakeCurrent(display, window, window, context));
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }
        void renderRed(long ptsNs) {
            GLES20.glViewport(0, 0, WIDTH, HEIGHT);
            GLES20.glClearColor(1, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            assertEquals("GLES draw failed", GLES20.GL_NO_ERROR, GLES20.glGetError());
            assertTrue(EGLExt.eglPresentationTimeANDROID(display, window, ptsNs));
            assertTrue("Codec input swap failed", EGL14.eglSwapBuffers(display, window));
        }
        @Override public void close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, window);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
    }
}
