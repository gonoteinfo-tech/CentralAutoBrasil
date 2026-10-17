package com.roadconnect.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void preservesValidHttpsStream() {
        assertEquals("https://radio.example/live.mp3",
                UrlPolicy.requireStreamUrl(" https://radio.example/live.mp3 "));
        assertEquals("https://radio.example/live.m3u8?token=test",
                UrlPolicy.requireStreamUrl("https://radio.example/live.m3u8?token=test"));
    }

    @Test public void rejectsUnsafeAndYouTubeStreams() {
        String[] invalid = {"", "not a URL", "http://radio.example/live", "file:///audio.mp3",
                "https://user:password@radio.example/live", "https://radio.example/live#fragment",
                "https://www.youtube.com/watch?v=123", "https://music.youtube.com/",
                "https://youtu.be/123", "https://youtube.com./watch?v=123",
                "https://rr1.googlevideo.com/videoplayback", "https://radio.example:99999/live"};
        for (String value : invalid) {
            assertThrows(value, IllegalArgumentException.class, () -> UrlPolicy.requireStreamUrl(value));
        }
        assertThrows(IllegalArgumentException.class, () -> UrlPolicy.requireStreamUrl(null));
    }

    @Test public void onlyAllowsExactHttpsYouTubeNavigation() {
        assertTrue(UrlPolicy.isInternalYouTubeUrl("https://m.youtube.com/watch?v=123"));
        assertTrue(UrlPolicy.isInternalYouTubeUrl("https://www.youtube.com/"));
        assertTrue(UrlPolicy.isInternalYouTubeUrl("https://youtu.be/abc"));
        String[] invalid = {"http://m.youtube.com", "https://youtube.com.example/watch",
                "https://m.youtube.com@evil.example/", "javascript:alert(1)", "intent://youtube",
                "https://youtube.com:444/watch", "file:///etc/passwd", "broken", null};
        for (String value : invalid) assertFalse(String.valueOf(value), UrlPolicy.isInternalYouTubeUrl(value));
    }
}
