import com.roadconnect.mirror.MirrorWire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** JVM checks run independently of the Android SDK and Gradle. */
public final class MirrorChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        handshakeChecks();
        frameChecks();
        formatChecks();
        addressAndFingerprintChecks();
        concurrentWriterChecks();
        System.out.println("MirrorChecks: " + checks + " protocol/security checks passed.");
    }

    private static void handshakeChecks() throws Exception {
        byte[] hello = output(out -> MirrorWire.writeHello(out, "004829"));
        check(hello.length == 10, "Hello has a fixed 10-byte size");
        MirrorWire.readHello(input(hello), "004829");
        check(true, "Hello round trip");
        deniedIo(() -> MirrorWire.readHello(input(hello), "004828"), "Wrong pairing code");
        deniedIo(() -> MirrorWire.readHello(input(hello), "4829"), "Short expected code");
        byte[] wrongMagic = hello.clone();
        wrongMagic[0] ^= 1;
        deniedIo(() -> MirrorWire.readHello(input(wrongMagic), "004829"), "Wrong hello magic");
        byte[] letterCode = hello.clone();
        letterCode[9] = 'x';
        deniedIo(() -> MirrorWire.readHello(input(letterCode), "004829"), "Nonnumeric received code");
        deniedIo(() -> MirrorWire.readHello(input(Arrays.copyOf(hello, 9)), "004829"), "Truncated hello");
        for (String code : new String[] {"", "12345", "1234567", "１２３４５６", "12a456", " 12345"}) {
            deniedIo(() -> MirrorWire.writeHello(new DataOutputStream(new ByteArrayOutputStream()), code),
                    "Reject malformed local pairing code");
        }
        deniedIo(() -> MirrorWire.writeHello(new DataOutputStream(new ByteArrayOutputStream()), null),
                "Reject null pairing code");
        for (int[] size : new int[][] {{1920, 1080}, {1280, 720}}) {
            byte[] welcome = output(out -> MirrorWire.writeWelcome(out, size[0], size[1]));
            check(welcome.length == 12, "Welcome has a fixed 12-byte size");
            check(Arrays.equals(size, MirrorWire.readWelcome(input(welcome))), "Welcome round trip");
        }
        deniedIo(() -> MirrorWire.writeWelcome(new DataOutputStream(new ByteArrayOutputStream()), 3840, 2160),
                "Reject unsupported welcome size");
        deniedIo(() -> MirrorWire.readWelcome(input(output(out -> {
            out.writeInt(MirrorWire.MAGIC); out.writeInt(1920); out.writeInt(720);
        }))), "Reject mismatched welcome dimensions");
        deniedIo(() -> MirrorWire.readWelcome(input(new byte[11])), "Truncated welcome");
        deniedIo(() -> MirrorWire.readWelcome(input(new byte[12])), "Wrong welcome magic");
    }

    private static void frameChecks() throws Exception {
        int[] types = {MirrorWire.FORMAT, MirrorWire.VIDEO, MirrorWire.AUDIO, MirrorWire.STATUS, MirrorWire.STOP};
        byte[][] payloads = {
            MirrorWire.formatPayload(1920, 1080, new byte[] {1, 2}, new byte[] {3}),
            new byte[] {0, 0, 0, 1, 101}, new byte[] {1, 0, -1, 0},
            "Conexão pronta".getBytes(StandardCharsets.UTF_8), new byte[0]
        };
        for (int i = 0; i < types.length; i++) {
            final int index = i;
            byte[] encoded = output(out -> MirrorWire.writeFrame(out, types[index], 987654321L, payloads[index]));
            MirrorWire.Frame frame = MirrorWire.readFrame(input(encoded));
            check(frame.type == types[i] && frame.ptsUs == 987654321L
                    && Arrays.equals(frame.data, payloads[i]), "Frame round trip type " + types[i]);
        }
        for (int type : new int[] {-1, 0, 6, 256}) {
            deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()), type, 0,
                    new byte[0]), "Unknown outgoing type");
        }
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(0, 0, 0, new byte[0]))), "Unknown incoming type");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(255, 0, 0, new byte[0]))), "Unsigned unknown type");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.VIDEO, -1, 0, new byte[0]))),
                "Negative incoming timestamp");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.VIDEO, -1, new byte[0]), "Negative outgoing timestamp");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.VIDEO, 0, -1, new byte[0]))),
                "Negative payload length");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.VIDEO, 0, Integer.MAX_VALUE, new byte[0]))),
                "Huge length rejected before allocation");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.VIDEO, 0, MirrorWire.MAX_PAYLOAD + 1,
                new byte[0]))), "Payload above global limit");
        byte[] maximum = new byte[MirrorWire.MAX_PAYLOAD];
        check(MirrorWire.readFrame(input(output(out -> MirrorWire.writeFrame(out, MirrorWire.VIDEO, 0,
                maximum)))).data.length == MirrorWire.MAX_PAYLOAD, "Maximum video payload accepted");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.VIDEO, 0, new byte[MirrorWire.MAX_PAYLOAD + 1]), "Oversized outgoing video");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.VIDEO, 0, null), "Null outgoing payload");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.VIDEO, 0, 4, new byte[3]))),
                "Truncated frame payload");
        deniedIo(() -> MirrorWire.readFrame(input(new byte[12])), "Truncated frame header");
        deniedIo(() -> MirrorWire.readFrame(input(new byte[0])), "EOF ends stream");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.AUDIO, 0, 16386, new byte[0]))),
                "Oversized incoming audio before payload read");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.AUDIO, 0, 3, new byte[3]))),
                "Odd PCM byte count");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.AUDIO, 0, new byte[3]), "Odd outgoing PCM byte count");
        check(MirrorWire.readFrame(input(output(out -> MirrorWire.writeFrame(out, MirrorWire.AUDIO, 0,
                new byte[16384])))).data.length == 16384, "Audio boundary accepted");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.STATUS, 0, 1025, new byte[0]))),
                "Oversized incoming status before payload read");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.STATUS, 0, "ç".repeat(513).getBytes(StandardCharsets.UTF_8)),
                "Status limited by UTF-8 bytes");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.STATUS, 0, 2,
                new byte[] {(byte) 0xc3, 0x28}))), "Malformed UTF-8 status");
        check(MirrorWire.readFrame(input(output(out -> MirrorWire.writeFrame(out, MirrorWire.STATUS, 0,
                new byte[1024])))).data.length == 1024, "Status boundary accepted");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.STOP, 0, 1, new byte[1]))),
                "Nonempty stop rejected");
        deniedIo(() -> MirrorWire.writeFrame(new DataOutputStream(new ByteArrayOutputStream()),
                MirrorWire.STOP, 0, new byte[1]), "Nonempty outgoing stop rejected");
        deniedIo(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.FORMAT, 0, 1, new byte[1]))),
                "Malformed frame format rejected");
        deniedHeader(() -> MirrorWire.readFrame(input(rawFrame(MirrorWire.FORMAT, 0, 131089, new byte[0]))),
                "Oversized format before allocation");
        byte[] joined = output(out -> {
            MirrorWire.writeFrame(out, MirrorWire.VIDEO, 1, new byte[] {7});
            MirrorWire.writeFrame(out, MirrorWire.STOP, 2, new byte[0]);
        });
        DataInputStream in = input(joined);
        check(MirrorWire.readFrame(in).ptsUs == 1 && MirrorWire.readFrame(in).ptsUs == 2 && in.available() == 0,
                "Frames consume exact payload bytes");
    }

    private static void formatChecks() throws Exception {
        byte[] csd0 = {0, 0, 0, 1, 0x67};
        byte[] csd1 = {0, 0, 0, 1, 0x68};
        for (int[] size : new int[][] {{1920, 1080}, {1280, 720}}) {
            byte[] data = MirrorWire.formatPayload(size[0], size[1], csd0, csd1);
            MirrorWire.VideoFormat format = MirrorWire.parseFormat(data);
            check(format.width == size[0] && format.height == size[1]
                    && Arrays.equals(format.csd0, csd0) && Arrays.equals(format.csd1, csd1), "Format round trip");
            check(data.length == 16 + csd0.length + csd1.length, "Exact format payload size");
            deniedIo(() -> MirrorWire.parseFormat(Arrays.copyOf(data, data.length - 1)), "Truncated PPS");
            deniedIo(() -> MirrorWire.parseFormat(Arrays.copyOf(data, data.length + 1)), "Trailing format bytes");
        }
        deniedArgument(() -> MirrorWire.formatPayload(1080, 1920, csd0, csd1), "Portrait dimensions rejected");
        deniedArgument(() -> MirrorWire.formatPayload(1920, 720, csd0, csd1), "Mismatched dimensions rejected");
        deniedArgument(() -> MirrorWire.formatPayload(1280, 720, new byte[65537], csd1), "Oversized local SPS");
        deniedArgument(() -> MirrorWire.formatPayload(1280, 720, csd0, new byte[65537]), "Oversized local PPS");
        deniedArgument(() -> MirrorWire.formatPayload(1280, 720, null, csd1), "Null local CSD");
        MirrorWire.VideoFormat maximum = MirrorWire.parseFormat(MirrorWire.formatPayload(1920, 1080,
                new byte[65536], new byte[65536]));
        check(maximum.csd0.length == 65536 && maximum.csd1.length == 65536, "CSD boundaries accepted");
        deniedIo(() -> MirrorWire.parseFormat(output(out -> {
            out.writeInt(1920); out.writeInt(1080); out.writeInt(Integer.MAX_VALUE);
        })), "Huge SPS length before allocation");
        deniedIo(() -> MirrorWire.parseFormat(output(out -> {
            out.writeInt(1280); out.writeInt(720); out.writeInt(-1);
        })), "Negative SPS length");
        deniedIo(() -> MirrorWire.parseFormat(output(out -> {
            out.writeInt(1280); out.writeInt(720); out.writeInt(0); out.writeInt(65537);
        })), "Oversized PPS length");
        deniedIo(() -> MirrorWire.parseFormat(output(out -> {
            out.writeInt(320); out.writeInt(240); out.writeInt(0); out.writeInt(0);
        })), "Unsupported incoming dimensions");
        deniedIo(() -> MirrorWire.parseFormat(new byte[7]), "Truncated dimensions");
        deniedIo(() -> MirrorWire.parseFormat(null), "Null format payload");
    }

    private static void addressAndFingerprintChecks() throws Exception {
        for (String address : new String[] {"10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
                "192.168.0.0", "192.168.255.255", " 192.168.1.2 "}) {
            check(MirrorWire.requirePrivateIpv4(address).equals(address.trim()), "Private IPv4 " + address);
        }
        for (String address : new String[] {"", "0.0.0.0", "127.0.0.1", "8.8.8.8", "172.15.255.255",
                "172.32.0.0", "192.167.255.255", "192.169.0.0", "169.254.1.1", "224.0.0.1",
                "255.255.255.255", "10.256.0.1", "10.-1.0.1", "10.0.0", "10.0.0.1.1",
                "10.0.0.1:8787", "192.168.001.1", "0x0a.0.0.1", "192.168.1.1/24", "localhost",
                "receiver.local", "::1", "::ffff:192.168.1.1", "https://192.168.1.1", "10.1.2.3\n.evil"}) {
            deniedArgument(() -> MirrorWire.requirePrivateIpv4(address), "Reject unsafe address " + address);
        }
        deniedArgument(() -> MirrorWire.requirePrivateIpv4(null), "Null address");
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        check(digest.equals(MirrorWire.fingerprint("abc".getBytes(StandardCharsets.UTF_8))),
                "SHA-256 known vector");
        String separated = "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD";
        check(digest.equals(MirrorWire.requireFingerprint(" " + separated + " ")), "Fingerprint normalization");
        check(digest.equals(MirrorWire.requireFingerprint(digest)), "Canonical fingerprint unchanged");
        deniedArgument(() -> MirrorWire.requireFingerprint("0".repeat(63)), "Short fingerprint");
        deniedArgument(() -> MirrorWire.requireFingerprint("0".repeat(65)), "Long fingerprint");
        deniedArgument(() -> MirrorWire.requireFingerprint("g".repeat(64)), "Nonhex fingerprint");
        deniedArgument(() -> MirrorWire.requireFingerprint("0".repeat(32) + "-" + "0".repeat(32)),
                "Unexpected fingerprint punctuation");
        deniedArgument(() -> MirrorWire.requireFingerprint(null), "Null fingerprint");
        deniedArgument(() -> MirrorWire.fingerprint(null), "Null certificate");
    }

    private static void concurrentWriterChecks() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] writers = new Thread[4];
        for (int thread = 0; thread < writers.length; thread++) {
            final int threadId = thread;
            writers[thread] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        int id = threadId * 200 + i;
                        byte[] payload = new byte[97];
                        Arrays.fill(payload, (byte) id);
                        MirrorWire.writeFrame(out, MirrorWire.VIDEO, id, payload);
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            writers[thread].start();
        }
        start.countDown();
        for (Thread writer : writers) writer.join(10000);
        for (Thread writer : writers) check(!writer.isAlive(), "Concurrent writer finishes");
        if (failure.get() != null) throw new AssertionError("Concurrent write failed", failure.get());
        DataInputStream in = input(bytes.toByteArray());
        Set<Long> ids = new HashSet<>();
        while (in.available() > 0) {
            MirrorWire.Frame frame = MirrorWire.readFrame(in);
            check(frame.type == MirrorWire.VIDEO && frame.data.length == 97, "Concurrent frame remains intact");
            for (byte value : frame.data) {
                if (value != (byte) frame.ptsUs) throw new AssertionError("Concurrent payload interleaved");
            }
            check(ids.add(frame.ptsUs), "Concurrent frames are neither duplicated nor lost");
        }
        check(ids.size() == 800, "All concurrent frames received");
    }

    private static byte[] output(IoWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static byte[] rawFrame(int type, long pts, int length, byte[] bytes) throws Exception {
        return output(out -> { out.writeByte(type); out.writeLong(pts); out.writeInt(length); out.write(bytes); });
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static void deniedIo(IoAction action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, message); return; }
        throw new AssertionError(message + " was accepted");
    }

    private static void deniedHeader(IoAction action, String message) throws Exception {
        try {
            action.run();
        } catch (EOFException readPayload) {
            throw new AssertionError(message + " attempted to read the invalid payload", readPayload);
        } catch (IOException expected) {
            check(true, message);
            return;
        }
        throw new AssertionError(message + " was accepted");
    }

    private static void deniedArgument(IoAction action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message + " was accepted");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private interface IoWriter { void write(DataOutputStream out) throws Exception; }
    private interface IoAction { void run() throws Exception; }
}
