package com.roadconnect.mirror;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded wire format shared by the phone and the local Android receiver. */
public final class MirrorWire {
    public static final int MAGIC = 0x52434d31;
    public static final int MAX_PAYLOAD = 2 * 1024 * 1024;
    public static final int FORMAT = 1;
    public static final int VIDEO = 2;
    public static final int AUDIO = 3;
    public static final int STATUS = 4;
    public static final int STOP = 5;
    private static final int MAX_CSD = 65536;
    private static final int MAX_FORMAT = 16 + MAX_CSD * 2;

    private MirrorWire() { }

    public static void writeHello(DataOutputStream out, String code) throws IOException {
        byte[] digits = codeBytes(code);
        synchronized (out) {
            out.writeInt(MAGIC);
            out.write(digits);
            out.flush();
        }
    }

    /** Must be called within the authenticated TLS connection, never on a plain socket. */
    public static void readHello(DataInputStream in, String expectedCode) throws IOException {
        byte[] expected = codeBytes(expectedCode);
        if (in.readInt() != MAGIC) throw new IOException("Invalid protocol magic");
        byte[] received = new byte[6];
        in.readFully(received);
        int difference = 0;
        // Always compare all six bytes, independently of where the first difference occurs.
        for (int i = 0; i < received.length; i++) difference |= expected[i] ^ received[i];
        if (difference != 0) throw new IOException("Invalid pairing code");
    }

    public static void writeWelcome(DataOutputStream out, int maxWidth, int maxHeight) throws IOException {
        requireSize(maxWidth, maxHeight);
        synchronized (out) {
            out.writeInt(MAGIC);
            out.writeInt(maxWidth);
            out.writeInt(maxHeight);
            out.flush();
        }
    }

    public static int[] readWelcome(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid protocol magic");
        int width = in.readInt();
        int height = in.readInt();
        requireSize(width, height);
        return new int[] {width, height};
    }

    /** The output stream is the lock, so audio, video and stop cannot interleave their headers. */
    public static void writeFrame(DataOutputStream out, int type, long ptsUs, byte[] data) throws IOException {
        if (data == null) throw new IOException("Missing frame payload");
        validateHeader(type, ptsUs, data.length);
        validatePayload(type, data);
        synchronized (out) {
            out.writeByte(type);
            out.writeLong(ptsUs);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        }
    }

    public static Frame readFrame(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        long ptsUs = in.readLong();
        int length = in.readInt();
        // Validate all limits before allocating memory from the peer's length field.
        validateHeader(type, ptsUs, length);
        byte[] data = new byte[length];
        in.readFully(data);
        validatePayload(type, data);
        return new Frame(type, ptsUs, data);
    }

    public static byte[] formatPayload(int width, int height, byte[] csd0, byte[] csd1) {
        if (csd0 == null || csd1 == null || csd0.length > MAX_CSD || csd1.length > MAX_CSD) {
            throw new IllegalArgumentException("Invalid codec configuration");
        }
        try {
            requireSize(width, height);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + csd0.length + csd1.length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(csd0.length);
            out.write(csd0);
            out.writeInt(csd1.length);
            out.write(csd1);
            return bytes.toByteArray();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid video format", invalid);
        }
    }

    public static VideoFormat parseFormat(byte[] data) throws IOException {
        if (data == null || data.length < 16 || data.length > MAX_FORMAT) {
            throw new IOException("Invalid video format length");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int width = in.readInt();
        int height = in.readInt();
        requireSize(width, height);
        byte[] csd0 = readCsd(in);
        byte[] csd1 = readCsd(in);
        if (in.available() != 0) throw new IOException("Trailing video format data");
        return new VideoFormat(width, height, csd0, csd1);
    }

    /** Accept canonical dotted decimal only; this method never performs DNS resolution. */
    public static String requirePrivateIpv4(String value) {
        if (value == null) throw new IllegalArgumentException("Enter a private IPv4 address");
        String address = value.trim();
        String[] pieces = address.split("\\.", -1);
        if (pieces.length != 4) throw new IllegalArgumentException("Enter a private IPv4 address");
        int[] octets = new int[4];
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.isEmpty() || piece.length() > 3 || (piece.length() > 1 && piece.charAt(0) == '0')) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            int number = 0;
            for (int j = 0; j < piece.length(); j++) {
                char digit = piece.charAt(j);
                if (digit < '0' || digit > '9') throw new IllegalArgumentException("Invalid IPv4 address");
                number = number * 10 + digit - '0';
            }
            if (number > 255) throw new IllegalArgumentException("Invalid IPv4 address");
            octets[i] = number;
        }
        boolean isPrivate = octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
        if (!isPrivate) throw new IllegalArgumentException("Use an address on the local private network");
        return address;
    }

    public static String fingerprint(byte[] encodedCertificate) {
        if (encodedCertificate == null) throw new IllegalArgumentException("Missing certificate");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedCertificate);
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = alphabet[value >>> 4];
                hex[i * 2 + 1] = alphabet[value & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String requireFingerprint(String value) {
        if (value == null) throw new IllegalArgumentException("Enter the receiver certificate fingerprint");
        StringBuilder normalized = new StringBuilder(64);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == ':' || Character.isWhitespace(character)) continue;
            if (character >= 'A' && character <= 'F') character += 'a' - 'A';
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException("Invalid certificate fingerprint");
            }
            normalized.append(character);
            if (normalized.length() > 64) throw new IllegalArgumentException("Invalid certificate fingerprint");
        }
        if (normalized.length() != 64) throw new IllegalArgumentException("Invalid certificate fingerprint");
        return normalized.toString();
    }

    private static byte[] codeBytes(String code) throws IOException {
        if (code == null || code.length() != 6) throw new IOException("Pairing code must contain six digits");
        byte[] digits = new byte[6];
        for (int i = 0; i < digits.length; i++) {
            char digit = code.charAt(i);
            if (digit < '0' || digit > '9') throw new IOException("Pairing code must contain six digits");
            digits[i] = (byte) digit;
        }
        return digits;
    }

    private static void requireSize(int width, int height) throws IOException {
        if (!((width == 1920 && height == 1080) || (width == 1280 && height == 720))) {
            throw new IOException("Only 1920x1080 or 1280x720 is supported");
        }
    }

    private static byte[] readCsd(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_CSD || length > in.available()) {
            throw new IOException("Invalid codec configuration length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void validateHeader(int type, long ptsUs, int length) throws IOException {
        if (ptsUs < 0) throw new IOException("Negative frame timestamp");
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Invalid frame payload length");
        switch (type) {
            case FORMAT:
                if (length < 16 || length > MAX_FORMAT) throw new IOException("Invalid video format length");
                break;
            case VIDEO:
                break;
            case AUDIO:
                if (length > 16384 || (length & 1) != 0) throw new IOException("Invalid PCM audio length");
                break;
            case STATUS:
                if (length > 1024) throw new IOException("Status text exceeds limit");
                break;
            case STOP:
                if (length != 0) throw new IOException("Stop frame must be empty");
                break;
            default:
                throw new IOException("Unknown frame type");
        }
    }

    private static void validatePayload(int type, byte[] data) throws IOException {
        if (type == FORMAT) parseFormat(data);
        if (type == STATUS) {
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            } catch (CharacterCodingException invalid) {
                throw new IOException("Status payload is not UTF-8", invalid);
            }
        }
    }

    public static final class Frame {
        public final int type;
        public final long ptsUs;
        public final byte[] data;

        private Frame(int type, long ptsUs, byte[] data) {
            this.type = type;
            this.ptsUs = ptsUs;
            this.data = data;
        }
    }

    public static final class VideoFormat {
        public final int width;
        public final int height;
        public final byte[] csd0;
        public final byte[] csd1;

        private VideoFormat(int width, int height, byte[] csd0, byte[] csd1) {
            this.width = width;
            this.height = height;
            this.csd0 = csd0;
            this.csd1 = csd1;
        }
    }
}
