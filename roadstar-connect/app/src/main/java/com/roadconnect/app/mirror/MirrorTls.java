package com.roadconnect.app.mirror;

import com.roadconnect.mirror.MirrorWire;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Local TLS uses an explicitly compared certificate, never a trust-all media connection. */
final class MirrorTls {
    private MirrorTls() {}

    static SSLSocket create(String pin, X509Certificate[] inspected) throws GeneralSecurityException, IOException {
        String expected = pin == null ? null : MirrorWire.requireFingerprint(pin);
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Autenticação de cliente não suportada.");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Receptor sem certificado.");
                chain[0].checkValidity();
                if (expected == null) {
                    if (inspected == null || inspected.length == 0) throw new CertificateException("Inspeção inválida.");
                    inspected[0] = chain[0];
                    return;
                }
                try {
                    if (!expected.equals(MirrorWire.fingerprint(chain[0].getEncoded())))
                        throw new CertificateException("O certificado da central mudou. Confira e conecte novamente.");
                } catch (IllegalArgumentException failure) {
                    throw new CertificateException("Certificado inválido.", failure);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        List<String> protocols = new ArrayList<>();
        for (String protocol : socket.getSupportedProtocols()) {
            if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
        }
        if (protocols.isEmpty()) { socket.close(); throw new IOException("TLS 1.2 indisponível."); }
        socket.setEnabledProtocols(protocols.toArray(new String[0]));
        return socket;
    }

    static void handshake(SSLSocket socket, String host, int port) throws IOException {
        socket.connect(new InetSocketAddress(MirrorWire.requirePrivateIpv4(host), port), 5_000);
        socket.setSoTimeout(8_000);
        socket.startHandshake();
    }
}
