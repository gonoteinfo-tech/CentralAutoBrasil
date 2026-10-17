package com.roadconnect.app.media;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionError;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.roadconnect.app.R;
import com.roadconnect.app.data.Station;
import com.roadconnect.app.data.StationStore;
import com.roadconnect.app.ui.MainActivity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaLibraryService {
    private ExoPlayer player;
    private MediaLibrarySession session;
    private StationStore store;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener libraryChanged = (prefs, key) -> {
        if (StationStore.STATIONS_KEY.equals(key)) getMainExecutor().execute(this::refreshLibrary);
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new StationStore(this);
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        PendingIntent activity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaLibrarySession.Builder(this, player, new LibraryCallback())
                .setSessionActivity(activity).build();
        DefaultMediaNotificationProvider notification = new DefaultMediaNotificationProvider(this);
        notification.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(notification);
        preferences = getSharedPreferences(StationStore.PREFERENCES_NAME, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(libraryChanged);
    }

    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override public void onDestroy() {
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(libraryChanged);
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }

    private void refreshLibrary() {
        if (session == null || player == null) return;
        try {
            List<Station> stations = store.load();
            MediaItem current = player.getCurrentMediaItem();
            if (current != null) {
                Station selected = find(stations, current.mediaId);
                if (selected == null || current.localConfiguration == null
                        || !selected.url.equals(current.localConfiguration.uri.toString())) {
                    player.stop(); player.clearMediaItems();
                } else {
                    for (int i = player.getMediaItemCount() - 1; i >= 0; i--) {
                        Station queued = find(stations, player.getMediaItemAt(i).mediaId);
                        if (queued == null) player.removeMediaItem(i);
                        else player.replaceMediaItem(i, playable(queued));
                    }
                }
            }
            session.notifyChildrenChanged("root", 2, null);
            session.notifyChildrenChanged("stations", stations.size(), null);
            int favorites = 0;
            for (Station station : stations) if (station.favorite) favorites++;
            session.notifyChildrenChanged("favorites", favorites, null);
        } catch (IllegalStateException unreadable) {
            player.stop(); player.clearMediaItems();
            session.notifyChildrenChanged("stations", 0, null);
            session.notifyChildrenChanged("favorites", 0, null);
        }
    }

    private MediaItem folder(String id, int title) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(getString(title)).setIsBrowsable(true).setIsPlayable(false).build()).build();
    }

    private MediaItem playable(Station station) {
        return new MediaItem.Builder().setMediaId(station.id).setUri(station.url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(station.name)
                        .setArtist("Rádio online").setIsBrowsable(false).setIsPlayable(true).build()).build();
    }

    private Station find(List<Station> stations, String id) {
        for (Station station : stations) if (station.id.equals(id)) return station;
        return null;
    }

    private List<MediaItem> items(String parent, @Nullable String query) {
        if (parent.equals("root")) return Arrays.asList(folder("stations", R.string.all_radios), folder("favorites", R.string.favorites));
        if (!parent.equals("stations") && !parent.equals("favorites")) throw new IllegalArgumentException("Unknown folder");
        List<MediaItem> items = new ArrayList<>();
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Station station : store.load()) {
            if ((parent.equals("stations") || station.favorite)
                    && station.name.toLowerCase(Locale.ROOT).contains(search)) items.add(playable(station));
        }
        return items;
    }

    private List<MediaItem> page(List<MediaItem> list, int page, int pageSize) {
        if (page < 0 || pageSize < 1) throw new IllegalArgumentException("Invalid page");
        long offset = (long) page * pageSize;
        if (offset >= list.size()) return Collections.emptyList();
        return list.subList((int) offset, (int) Math.min(offset + pageSize, list.size()));
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, @Nullable LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(folder("root", R.string.app_name), params));
        }

        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String parentId,
                int page, int pageSize, @Nullable LibraryParams params) {
            try { return Futures.immediateFuture(LibraryResult.ofItemList(page(items(parentId, null), page, pageSize), params)); }
            catch (IllegalArgumentException badRequest) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)); }
            catch (IllegalStateException unreadable) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN)); }
        }

        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String mediaId) {
            if (mediaId.equals("root")) return Futures.immediateFuture(LibraryResult.ofItem(folder("root", R.string.app_name), null));
            if (mediaId.equals("stations")) return Futures.immediateFuture(LibraryResult.ofItem(folder("stations", R.string.all_radios), null));
            if (mediaId.equals("favorites")) return Futures.immediateFuture(LibraryResult.ofItem(folder("favorites", R.string.favorites), null));
            try {
                Station station = find(store.load(), mediaId);
                return Futures.immediateFuture(station == null ? LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        : LibraryResult.ofItem(playable(station), null));
            } catch (IllegalStateException unreadable) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN)); }
        }

        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,
                MediaSession.ControllerInfo controller, List<MediaItem> requested) {
            try {
                List<Station> stations = store.load();
                List<MediaItem> resolved = new ArrayList<>();
                for (MediaItem item : requested) {
                    Station station = find(stations, item.mediaId);
                    String query = item.requestMetadata.searchQuery;
                    if (station == null && query != null && !query.trim().isEmpty()) {
                        List<MediaItem> matches = items("stations", query);
                        if (!matches.isEmpty()) station = find(stations, matches.get(0).mediaId);
                    }
                    if (station == null) throw new IllegalArgumentException("Rádio indisponível.");
                    resolved.add(playable(station));
                }
                return Futures.immediateFuture(resolved);
            } catch (IllegalArgumentException | IllegalStateException invalid) { return Futures.immediateFailedFuture(invalid); }
        }

        @Override public ListenableFuture<LibraryResult<Void>> onSearch(MediaLibrarySession session,
                MediaSession.ControllerInfo browser, String query, @Nullable LibraryParams params) {
            try {
                session.notifySearchResultChanged(browser, query, items("stations", query).size(), params);
                return Futures.immediateFuture(LibraryResult.ofVoid());
            } catch (IllegalStateException unreadable) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN)); }
        }

        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String query,
                int page, int pageSize, @Nullable LibraryParams params) {
            try { return Futures.immediateFuture(LibraryResult.ofItemList(page(items("stations", query), page, pageSize), params)); }
            catch (IllegalArgumentException badRequest) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)); }
            catch (IllegalStateException unreadable) { return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN)); }
        }
    }
}
