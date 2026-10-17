package com.roadconnect.app.data;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class UrlPolicy {
    private static final Set<String> YOUTUBE_HOSTS = new HashSet<>(Arrays.asList(
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"));

    private UrlPolicy() {}

    public static String requireStreamUrl(String raw) {
        URI uri = httpsUri(raw);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getRawFragment() != null || isDomain(host, "youtube.com")
                || isDomain(host, "youtu.be") || isDomain(host, "youtube-nocookie.com")
                || isDomain(host, "googlevideo.com")) {
            throw new IllegalArgumentException("Use um endereço HTTPS direto de áudio, não um link do YouTube.");
        }
        return uri.normalize().toASCIIString();
    }

    public static boolean isInternalYouTubeUrl(String raw) {
        try {
            URI uri = httpsUri(raw);
            return (uri.getPort() == -1 || uri.getPort() == 443)
                    && YOUTUBE_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static URI httpsUri(String raw) {
        try {
            if (raw == null) throw new URISyntaxException("", "Empty URL");
            URI uri = new URI(raw.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.endsWith(".")
                    || uri.getRawUserInfo() != null || uri.getPort() == 0
                    || uri.getPort() > 65535 || uri.getPort() < -1) {
                throw new URISyntaxException(raw, "Invalid HTTPS address");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Informe um endereço HTTPS válido, sem usuário ou senha.");
        }
    }

    private static boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
