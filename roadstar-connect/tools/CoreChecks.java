import com.roadconnect.app.data.UrlPolicy;
import com.roadconnect.app.data.Station;
import com.roadconnect.app.data.StationCatalog;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        Station saved = new Station("my-station", "Meu nome editado", "https://radio.example/live", true);
        Station duplicateUrl = new Station("catalog-same-url", "Nome do catálogo", saved.url, false);
        Station duplicateId = new Station(saved.id, "Outro nome", "https://other.example/live", false);
        Station local = new Station("anapolis", "Rádio de Anápolis", "https://local.example/stream", false);
        List<Station> original = Collections.singletonList(saved);
        List<Station> merged = StationCatalog.merge(original, Arrays.asList(duplicateUrl, duplicateId, local, local));
        check(merged.size() == 2, "Catalog merge must not duplicate IDs or stream URLs");
        check(merged.get(0) == saved && saved.favorite, "Catalog merge must preserve edited names, IDs and favorites");
        check(merged.get(1) == local, "Catalog merge must append new stations");
        check(original.size() == 1, "Catalog merge must not mutate the saved list");
        check(StationCatalog.merge(Collections.emptyList(), Arrays.asList(local)).size() == 1,
                "A new installation must receive the bundled catalog");
        check(StationCatalog.merge(merged, Arrays.asList(local, duplicateId)).size() == 2,
                "Catalog merge must be idempotent");
        System.out.println("CoreChecks: 26 URL checks and 6 catalog migration checks passed.");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
