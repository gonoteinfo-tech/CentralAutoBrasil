package com.roadconnect.receiver;

import android.content.Context;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Surface;

import com.roadconnect.mirror.MirrorWire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.math.BigInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;

/** A single authenticated sender, TLS 1.2+, and no remote input or external server. */
final class ReceiverServer implements AutoCloseable {
    static final int PORT = 8999;
    private static final String KEY_ALIAS = "roadstar_receiver_tls_v2";
    interface Listener {
        void onStatus(String message);
        void onCertificate(String sha256);
    }
    private final Context context;
    private final Surface surface;
    private final String code;
    private final Listener listener;
    private final Object connectionLock = new Object();
    private volatile boolean running = true;
    private volatile SSLServerSocket serverSocket;
    private volatile SSLSocket client;
    private volatile ReceiverPlayback playback;
    // Only the server thread reads/writes these authentication counters.
    private int failedCodeAttempts;
    private long codeCooldownUntil;
    

    ReceiverServer(Context context, Surface surface, String code, Listener listener) {
        this.context = context;
        this.surface = surface;
        this.code = code;
        this.listener = listener;
    }

    void start() {
        new Thread(this::serve, "receiver-tls").start();
    }

    private void serve() {
        try {
            ReceiverPlayback.Decoders decoders = ReceiverPlayback.findDecoders();
            SSLContext ssl = createTlsContext();
            SSLServerSocket opened = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(PORT, 1);
            synchronized (connectionLock) {
                if (running) serverSocket = opened;
            }
            if (!running) { opened.close(); return; }
            List<String> allowed = new ArrayList<>();
            for (String protocol : opened.getSupportedProtocols()) {
                if (protocol.equals("TLSv1.2")) allowed.add(protocol);
            }
            if (allowed.isEmpty()) throw new IOException("TLS 1.2 indisponível nesta central");
            opened.setEnabledProtocols(allowed.toArray(new String[0]));
            opened.setNeedClientAuth(false);
            opened.setSoTimeout(500);
            if (!running) return;
            listener.onStatus(ready(decoders));
            while (running) {
                if (codeCooldownUntil != 0 && SystemClock.elapsedRealtime() >= codeCooldownUntil) {
                    codeCooldownUntil = 0;
                    failedCodeAttempts = 0;
                    listener.onStatus(ready(decoders));
                }
                SSLSocket accepted;
                try { accepted = (SSLSocket) opened.accept(); }
                catch (SocketTimeoutException waiting) { continue; }
                boolean acceptClient;
                synchronized (connectionLock) {
                    acceptClient = running;
                    if (acceptClient) client = accepted;
                }
                if (!acceptClient) { accepted.close(); break; }
                // Only RFC 1918 IPv4 addresses can send to the local receiver.
                if (!(accepted.getInetAddress() instanceof Inet4Address)
                        || !accepted.getInetAddress().isSiteLocalAddress()) {
                    accepted.close();
                    synchronized (connectionLock) {
                        if (client == accepted) client = null;
                    }
                    continue;
                }
                serveClient(accepted, decoders);
                if (running) listener.onStatus(connectionStatus(decoders));
            }
        } catch (Exception error) {
            if (running) listener.onStatus("Não foi possível iniciar: " + message(error));
        } finally {
            disconnectSender();
            SSLServerSocket opened;
            synchronized (connectionLock) {
                opened = serverSocket;
                serverSocket = null;
            }
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
        }
    }

    private void serveClient(SSLSocket socket, ReceiverPlayback.Decoders decoders) {
        boolean authenticated = false;
        ReceiverPlayback active = null;
        try (SSLSocket ignored = socket) {
            socket.setSoTimeout(15_000);
            socket.setTcpNoDelay(true);
            // Conscrypt can handshake inside getInputStream: configure the timeout first.
            socket.startHandshake();
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            // A fingerprint-only TLS probe closes here and never appears as an active sender.
            if (!authenticateHello(input, decoders)) return;
            authenticated = true;
            MirrorWire.writeWelcome(output, decoders.maxWidth, decoders.maxHeight);
            output.flush();
            socket.setSoTimeout(30_000);
            listener.onStatus("Telefone autorizado • aguardando vídeo");
            boolean formatSeen = false;
            while (running && !socket.isClosed()) {
                MirrorWire.Frame frame = MirrorWire.readFrame(input);
                switch (frame.type) {
                    case MirrorWire.FORMAT:
                        if (formatSeen) throw new IOException("Formato duplicado na sessão");
                        MirrorWire.VideoFormat format = MirrorWire.parseFormat(frame.data);
                        String codecName = decoders.decoderFor(format.width, format.height);
                        if (codecName == null) throw new IOException("Resolução não suportada pela central");
                        active = new ReceiverPlayback(context, surface, codecName, format,
                                () -> releaseAsync(null, socket, null));
                        synchronized (connectionLock) {
                            if (!running || client != socket) throw new IOException("Sessão encerrada");
                            playback = active;
                            active.start();
                        }
                        formatSeen = true;
                        listener.onStatus("Espelhando • " + format.width + " × " + format.height);
                        break;
                    case MirrorWire.VIDEO:
                    case MirrorWire.AUDIO:
                        if (active == null) throw new IOException("Mídia recebida antes do formato");
                        active.enqueue(frame);
                        break;
                    case MirrorWire.STATUS:
                        // Sender status does not grant permissions or modify receiver state.
                        break;
                    case MirrorWire.STOP:
                        return;
                    default:
                        throw new IOException("Tipo de frame desconhecido");
                }
            }
        } catch (Exception error) {
            if (running && !authenticated && error instanceof SSLException)
                android.util.Log.w("RoadstarReceiver", "TLS or pairing handshake failed", error);
            if (running && authenticated) listener.onStatus("Conexão encerrada: " + message(error));
        } finally {
            synchronized (connectionLock) {
                if (playback == active) playback = null;
                if (client == socket) client = null;
            }
            if (active != null) active.close();
        }
    }

    private boolean authenticateHello(DataInputStream input, ReceiverPlayback.Decoders decoders)
            throws IOException, InterruptedException {
        if (SystemClock.elapsedRealtime() < codeCooldownUntil) {
            listener.onStatus(connectionStatus(decoders));
            return false;
        }
        // Count only a complete HELLO. Certificate probes and rejected TLS handshakes do
        // not supply these ten bytes, so neither consumes a pairing attempt.
        byte[] hello = new byte[10];
        input.readFully(hello);
        try {
            MirrorWire.readHello(new DataInputStream(new ByteArrayInputStream(hello)), code);
        } catch (IOException wrongCode) {
            failedCodeAttempts++;
            if (failedCodeAttempts >= 3) codeCooldownUntil = SystemClock.elapsedRealtime() + 30_000;
            listener.onStatus(connectionStatus(decoders));
            // A small bounded delay also limits retries before the three-attempt cutoff.
            if (running) Thread.sleep(250);
            return false;
        }
        failedCodeAttempts = 0;
        return true;
    }

    private String connectionStatus(ReceiverPlayback.Decoders decoders) {
        long remaining = codeCooldownUntil - SystemClock.elapsedRealtime();
        if (remaining > 0) return "Muitas tentativas de código • aguarde "
                + ((remaining + 999) / 1000) + " s e confira o código na central";
        if (failedCodeAttempts > 0) return "Código incorreto • tentativas restantes: "
                + (3 - failedCodeAttempts) + " • confira os seis dígitos";
        return ready(decoders);
    }

    private SSLContext createTlsContext() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            long now = System.currentTimeMillis();
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    // Conscrypt hashes/pads TLS signatures itself and delegates the raw RSA operation.
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=RoadstarReceiver"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(now - 86_400_000L))
                    .setCertificateNotAfter(new Date(now + 20L * 365 * 86_400_000L))
                    .build());
            generator.generateKeyPair();
        }
        Certificate certificate = keys.getCertificate(KEY_ALIAS);
        listener.onCertificate(MirrorWire.fingerprint(certificate.getEncoded()));
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, null);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(managers.getKeyManagers(), null, null);
        return ssl;
    }

    private static String ready(ReceiverPlayback.Decoders decoders) {
        return "Pronto • até " + decoders.maxWidth + " × " + decoders.maxHeight + " • porta " + PORT;
    }
    private static String message(Exception error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }
    private static void closeSocket(SSLSocket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
    void disconnectSender() {
        SSLSocket oldClient;
        ReceiverPlayback oldPlayback;
        synchronized (connectionLock) {
            oldClient = client;
            oldPlayback = playback;
            client = null;
            playback = null;
        }
        releaseAsync(null, oldClient, oldPlayback);
    }

    @Override public void close() {
        SSLServerSocket oldServer;
        SSLSocket oldClient;
        ReceiverPlayback oldPlayback;
        synchronized (connectionLock) {
            running = false;
            oldServer = serverSocket;
            oldClient = client;
            oldPlayback = playback;
            serverSocket = null;
            client = null;
            playback = null;
        }
        releaseAsync(oldServer, oldClient, oldPlayback);
    }

    private static void releaseAsync(SSLServerSocket oldServer, SSLSocket oldClient,
                                     ReceiverPlayback oldPlayback) {
        if (oldServer == null && oldClient == null && oldPlayback == null) return;
        // TLS close may write close_notify. Snapshot the old objects before leaving the UI
        // thread: a delayed cleanup must never look up and close a newer connection.
        new Thread(() -> {
            if (oldPlayback != null) oldPlayback.close();
            if (oldServer != null) try { oldServer.close(); } catch (IOException ignored) { }
            if (oldClient != null) closeSocket(oldClient);
        }, "receiver-cleanup").start();
    }
}
