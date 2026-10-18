package com.roadconnect.receiver;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Surface;

import com.roadconnect.mirror.MirrorWire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.math.BigInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;

/** A single authenticated sender, TLS 1.2+, and no remote input or external server. */
final class ReceiverServer implements AutoCloseable {
    static final int PORT = 8999;
    private static final String KEY_ALIAS = "roadstar_receiver_tls_v1";
    interface Listener {
        void onStatus(String message);
        void onCertificate(String sha256);
    }
    private final Context context;
    private final Surface surface;
    private final String code;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile SSLServerSocket serverSocket;
    private volatile SSLSocket client;
    private volatile ReceiverPlayback playback;
    

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
            serverSocket = opened;
            List<String> allowed = new ArrayList<>();
            for (String protocol : opened.getSupportedProtocols()) {
                if (protocol.equals("TLSv1.2")) allowed.add(protocol);
            }
            if (allowed.isEmpty()) throw new IOException("TLS 1.2 indisponível nesta central");
            opened.setEnabledProtocols(allowed.toArray(new String[0]));
            opened.setNeedClientAuth(false);
            if (!running) return;
            listener.onStatus(ready(decoders));
            while (running) {
                SSLSocket accepted = (SSLSocket) opened.accept();
                if (!running) { accepted.close(); break; }
                client = accepted;
                // Only RFC 1918 IPv4 addresses can send to the local receiver.
                if (!(accepted.getInetAddress() instanceof Inet4Address)
                        || !accepted.getInetAddress().isSiteLocalAddress()) {
                    accepted.close();
                    client = null;
                    continue;
                }
                serveClient(accepted, decoders);
                client = null;
                if (running) listener.onStatus(ready(decoders));
            }
        } catch (Exception error) {
            if (running) listener.onStatus("Não foi possível iniciar: " + message(error));
        } finally {
            disconnectSender();
            SSLServerSocket opened = serverSocket;
            serverSocket = null;
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
        }
    }

    private void serveClient(SSLSocket socket, ReceiverPlayback.Decoders decoders) {
        boolean authenticated = false;
        try (SSLSocket ignored = socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setSoTimeout(15_000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            // A fingerprint-only TLS probe closes here and never appears as an active sender.
            MirrorWire.readHello(input, code);
            authenticated = true;
            MirrorWire.writeWelcome(output, decoders.maxWidth, decoders.maxHeight);
            output.flush();
            socket.setSoTimeout(30_000);
            listener.onStatus("Telefone autorizado • aguardando vídeo");
            ReceiverPlayback active = null;
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
                                () -> closeSocket(socket));
                        playback = active;
                        active.start();
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
            if (running && authenticated) listener.onStatus("Conexão encerrada: " + message(error));
        } finally {
            ReceiverPlayback old = playback;
            playback = null;
            if (old != null) old.close();
        }
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
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
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
        SSLSocket socket = client;
        if (socket != null) closeSocket(socket);
        ReceiverPlayback current = playback;
        if (current != null) current.close();
    }
    @Override public void close() {
        running = false;
        SSLServerSocket opened = serverSocket;
        if (opened != null) try { opened.close(); } catch (IOException ignored) { }
        disconnectSender();
    }
}
