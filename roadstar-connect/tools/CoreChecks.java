import com.roadconnect.app.data.UrlPolicy;

/** Portable checks: run with a JDK even when the Android SDK is unavailable. */
public class CoreChecks {
    public static void main(String[] args) {
        check("https://radio.example/live.mp3".equals(
                UrlPolicy.requireStreamUrl(" https://radio.example/live.mp3 ")), "HTTPS normalization");
        String[] rejected = {null, "", "broken", "http://radio.example/live", "file:///a.mp3",
                "https://user:secret@radio.example/live", "https://radio.example/live#part",
                "https://www.youtube.com/watch?v=123", "https://music.youtube.com/",
                "https://youtube.com./watch", "https://youtu.be/id",
                "https://rr1.googlevideo.com/videoplayback", "https://radio.example:99999/live"};
        for (String value : rejected) {
            boolean denied = false;
            try { UrlPolicy.requireStreamUrl(value); } catch (IllegalArgumentException expected) { denied = true; }
            check(denied, "Reject stream: " + value);
        }
        String[] allowed = {"https://m.youtube.com/", "https://www.youtube.com/watch?v=id", "https://youtu.be/id"};
        for (String value : allowed) check(UrlPolicy.isInternalYouTubeUrl(value), "Allow YouTube: " + value);
        String[] external = {null, "http://m.youtube.com", "https://youtube.com.example/",
                "https://m.youtube.com@evil.example/", "javascript:alert(1)", "intent://youtube",
                "https://youtube.com:444/watch", "file:///etc/passwd", "broken"};
        for (String value : external) check(!UrlPolicy.isInternalYouTubeUrl(value), "External URL: " + value);
        System.out.println("CoreChecks: 26 URL checks passed.");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
