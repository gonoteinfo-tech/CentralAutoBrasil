package com.roadconnect.app.ui;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.common.util.concurrent.ListenableFuture;
import com.roadconnect.app.R;
import com.roadconnect.app.data.Station;
import com.roadconnect.app.data.StationStore;
import com.roadconnect.app.data.UrlPolicy;
import com.roadconnect.app.media.PlaybackService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public final class MainActivity extends AppCompatActivity {
    private StationStore store;
    private List<Station> stations = new ArrayList<>();
    private boolean readable = true;
    private FrameLayout content;
    private BottomNavigationView navigation;
    private BrowserPane browser;
    private int currentTab = R.id.nav_home;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private boolean controllerFailed;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { renderPlayer(); }
    };

    @Override protected void onCreate(Bundle savedState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        store = new StationStore(this);
        loadStations();
        content = findViewById(R.id.content);
        navigation = findViewById(R.id.bottom_navigation);
        navigation.setOnItemSelectedListener(item -> { showTab(item.getItemId()); return true; });
        findViewById(R.id.help_button).setOnClickListener(view -> showHelp());
        findViewById(R.id.play_pause).setOnClickListener(view -> togglePlayback());
        findViewById(R.id.stop_audio).setOnClickListener(view -> { if (controller != null) controller.stop(); });
        int initial = savedState == null ? R.id.nav_home : savedState.getInt("tab", R.id.nav_home);
        if (initial == R.id.nav_browser) {
            ensureBrowser();
            browser.restoreUrl(savedState.getString("browser_url"));
        }
        navigation.setSelectedItemId(initial);
        if (content.getChildCount() == 0) showTab(initial);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (currentTab == R.id.nav_browser && browser != null && browser.handleBack()) return;
                if (currentTab != R.id.nav_home) navigation.setSelectedItemId(R.id.nav_home);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        controllerFuture = new MediaController.Builder(this,
                new SessionToken(this, new ComponentName(this, PlaybackService.class))).buildAsync();
        controllerFuture.addListener(() -> {
            if (isDestroyed()) return;
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                if (currentTab == R.id.nav_browser) controller.pause();
            } catch (ExecutionException | java.util.concurrent.CancellationException failed) {
                controllerFailed = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                controllerFailed = true;
            }
            renderPlayer();
            handleMediaSearch(getIntent());
        }, ContextCompat.getMainExecutor(this));
        renderPlayer();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMediaSearch(intent);
    }

    private void handleMediaSearch(Intent request) {
        if (request == null || controller == null
                || !MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(request.getAction())) return;
        request.setAction(null);
        navigation.setSelectedItemId(R.id.nav_radios);
        String query = request.getStringExtra(SearchManager.QUERY);
        if (query == null || query.trim().isEmpty()) {
            toast("Diga o nome de uma rádio cadastrada ou escolha uma na lista.");
            return;
        }
        controller.setMediaItem(new MediaItem.Builder().setRequestMetadata(
                new MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build());
        controller.prepare();
        controller.play();
    }

    @Override protected void onStart() {
        super.onStart();
        if (store != null) {
            loadStations();
            if (content != null && (currentTab == R.id.nav_radios || currentTab == R.id.nav_favorites)) showTab(currentTab);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (browser != null && currentTab == R.id.nav_browser) browser.onHostResume();
    }

    @Override protected void onPause() {
        if (browser != null) browser.onHostPause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", currentTab);
        if (browser != null) state.putString("browser_url", browser.currentUrl());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (browser != null) browser.destroy();
        if (controller != null) controller.removeListener(playerListener);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }

    private void loadStations() {
        try { stations = store.load(); readable = true; }
        catch (IllegalStateException failure) { readable = false; toast(failure.getMessage()); }
    }

    private void showTab(int tab) {
        if (browser != null && currentTab == R.id.nav_browser && tab != R.id.nav_browser) browser.onHostPause();
        currentTab = tab;
        content.removeAllViews();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        navigation.setVisibility(landscape && tab == R.id.nav_browser ? View.GONE : View.VISIBLE);
        findViewById(R.id.mini_player).setVisibility(tab == R.id.nav_browser ? View.GONE : View.VISIBLE);
        if (tab == R.id.nav_browser) {
            ensureBrowser();
            content.addView(browser);
            browser.openYouTube();
        } else if (tab == R.id.nav_home) {
            getLayoutInflater().inflate(R.layout.view_home, content, true);
            content.findViewById(R.id.home_listen).setOnClickListener(view -> navigation.setSelectedItemId(R.id.nav_radios));
            content.findViewById(R.id.quick_radio).setOnClickListener(view -> navigation.setSelectedItemId(R.id.nav_radios));
            content.findViewById(R.id.quick_youtube).setOnClickListener(view -> navigation.setSelectedItemId(R.id.nav_browser));
            content.findViewById(R.id.quick_maps).setOnClickListener(view -> showMaps());
            content.findViewById(R.id.quick_music).setOnClickListener(view -> showMusic());
            content.findViewById(R.id.home_help).setOnClickListener(view -> showHelp());
        } else {
            renderLibrary(tab == R.id.nav_favorites);
        }
    }

    private void ensureBrowser() {
        if (browser == null) browser = new BrowserPane(this, () -> { if (controller != null) controller.pause(); });
    }

    private void renderLibrary(boolean favoritesOnly) {
        getLayoutInflater().inflate(R.layout.view_library, content, true);
        ((TextView) content.findViewById(R.id.library_heading)).setText(favoritesOnly ? R.string.favorites : R.string.radios);
        ((TextView) content.findViewById(R.id.library_description)).setText(favoritesOnly ? R.string.favorites_subtitle : R.string.library_subtitle);
        content.findViewById(R.id.add_radio).setEnabled(readable);
        content.findViewById(R.id.add_radio).setOnClickListener(view -> editStation(null));
        LinearLayout rows = content.findViewById(R.id.station_rows);
        if (!readable) {
            rows.addView(label("Não foi possível ler o catálogo. Seus dados foram preservados.", 16, R.color.error));
            MaterialButton retry = new MaterialButton(this);
            retry.setText(R.string.load_retry);
            retry.setOnClickListener(view -> { loadStations(); showTab(currentTab); });
            rows.addView(retry);
            return;
        }
        int count = 0;
        for (Station station : stations) {
            if (!favoritesOnly || station.favorite) { rows.addView(stationCard(station)); count++; }
        }
        if (count == 0) {
            TextView title = label(getString(favoritesOnly ? R.string.empty_favorites : R.string.empty_radios), 22, R.color.text_primary);
            title.setPadding(0, dp(32), 0, dp(10));
            rows.addView(title);
            rows.addView(label(getString(favoritesOnly ? R.string.empty_favorites_body : R.string.empty_radios_body), 16, R.color.text_secondary));
        }
    }

    private MaterialCardView stationCard(Station station) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardElevation(0);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.outline));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(16), dp(18), dp(12));
        column.addView(label(station.name, 20, R.color.text_primary));
        TextView host = label(Uri.parse(station.url).getHost(), 13, R.color.text_secondary);
        host.setPadding(0, dp(4), 0, dp(12));
        column.addView(host);
        LinearLayout actions = new LinearLayout(this);
        MaterialButton play = new MaterialButton(this);
        play.setText(R.string.listen_radio);
        play.setIconResource(R.drawable.ic_play);
        play.setMinHeight(dp(48));
        play.setOnClickListener(view -> playStation(station));
        actions.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton favorite = iconButton(station.favorite ? R.drawable.ic_star : R.drawable.ic_star_outline,
                getString(station.favorite ? R.string.unfavorite_action : R.string.favorite_action) + ": " + station.name);
        favorite.setOnClickListener(view -> {
            List<Station> updated = new ArrayList<>(stations);
            int index = updated.indexOf(station);
            if (index >= 0) updated.set(index, new Station(station.id, station.name, station.url, !station.favorite));
            if (persist(updated)) showTab(currentTab);
        });
        actions.addView(favorite, new LinearLayout.LayoutParams(dp(48), dp(56)));
        MaterialButton edit = iconButton(R.drawable.ic_edit, getString(R.string.edit_radio) + ": " + station.name);
        edit.setOnClickListener(view -> editStation(station));
        actions.addView(edit, new LinearLayout.LayoutParams(dp(48), dp(56)));
        column.addView(actions);
        card.addView(column);
        return card;
    }

    private void editStation(Station existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_station, null);
        TextInputEditText name = form.findViewById(R.id.name_input);
        TextInputEditText url = form.findViewById(R.id.url_input);
        TextInputLayout nameField = form.findViewById(R.id.name_field);
        TextInputLayout urlField = form.findViewById(R.id.url_field);
        if (existing != null) { name.setText(existing.name); url.setText(existing.url); }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.add_radio : R.string.edit_radio)
                .setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null);
        if (existing != null) builder.setNeutralButton(R.string.delete, (dialog, which) -> confirmDelete(existing));
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            nameField.setError(null); urlField.setError(null);
            String title = String.valueOf(name.getText()).trim();
            if (title.isEmpty()) { nameField.setError("Dê um nome para a rádio."); return; }
            String address;
            try { address = UrlPolicy.requireStreamUrl(String.valueOf(url.getText())); }
            catch (IllegalArgumentException failure) { urlField.setError(failure.getMessage()); return; }
            List<Station> updated = new ArrayList<>(stations);
            Station replacement = new Station(existing == null ? UUID.randomUUID().toString() : existing.id,
                    title, address, existing != null && existing.favorite);
            if (existing == null) updated.add(replacement);
            else {
                int index = updated.indexOf(existing);
                if (index < 0) { toast("A rádio mudou. Abra o editor novamente."); dialog.dismiss(); return; }
                updated.set(index, replacement);
            }
            if (persist(updated)) { dialog.dismiss(); showTab(currentTab); }
        }));
        dialog.show();
    }

    private void confirmDelete(Station station) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete)
                .setMessage(getString(R.string.delete_confirm, station.name))
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete, (dialog, which) -> {
                    List<Station> updated = new ArrayList<>(stations);
                    updated.remove(station);
                    if (persist(updated)) showTab(currentTab);
                }).show();
    }

    private boolean persist(List<Station> updated) {
        if (!readable) return false;
        try { store.save(updated); stations = updated; return true; }
        catch (IllegalStateException | IllegalArgumentException failure) { toast(failure.getMessage()); return false; }
    }

    private void playStation(Station station) {
        if (controller == null) { toast(getString(controllerFailed ? R.string.player_unavailable : R.string.player_connecting)); return; }
        controller.setMediaItem(new MediaItem.Builder().setMediaId(station.id).build());
        controller.prepare();
        controller.play();
    }

    private void togglePlayback() {
        if (controller == null) { toast(getString(controllerFailed ? R.string.player_unavailable : R.string.player_connecting)); return; }
        if (controller.getCurrentMediaItem() == null) { navigation.setSelectedItemId(R.id.nav_radios); return; }
        if (controller.getPlayWhenReady()) controller.pause();
        else { if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare(); controller.play(); }
    }

    private void renderPlayer() {
        TextView title = findViewById(R.id.player_title);
        TextView state = findViewById(R.id.player_state);
        MaterialButton play = findViewById(R.id.play_pause);
        findViewById(R.id.stop_audio).setEnabled(controller != null && controller.getCurrentMediaItem() != null);
        play.setIconResource(controller != null && controller.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        title.setText(item == null || item.mediaMetadata.title == null ? getString(R.string.choose_radio) : item.mediaMetadata.title);
        if (controller == null) state.setText(controllerFailed ? R.string.player_unavailable : R.string.player_connecting);
        else if (controller.getPlayerError() != null) state.setText(R.string.audio_error);
        else if (item == null) state.setText(R.string.player_hint);
        else if (controller.getPlaybackState() == Player.STATE_BUFFERING) state.setText(R.string.connecting_audio);
        else if (controller.isPlaying()) state.setText(R.string.playing);
        else state.setText(controller.getPlaybackState() == Player.STATE_IDLE ? R.string.stopped : R.string.paused);
    }

    private void showMaps() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.maps)
                .setItems(new String[]{"Google Maps", "Waze"}, (dialog, choice) -> {
                    String pkg = choice == 0 ? "com.google.android.apps.maps" : "com.waze";
                    if (!launchPackage(pkg)) openLink(choice == 0 ? "https://maps.google.com/" : "https://www.waze.com/live-map/");
                }).show();
    }

    private void showMusic() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.music)
                .setItems(new String[]{"Spotify", "YouTube Music"}, (dialog, choice) -> {
                    if (!launchPackage(choice == 0 ? "com.spotify.music" : "com.google.android.apps.youtube.music"))
                        toast("Instale o aplicativo de música escolhido no telefone.");
                }).show();
    }

    private boolean launchPackage(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException | SecurityException unavailable) { toast(getString(R.string.no_external_app)); }
    }

    private void showHelp() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.android_auto)
                .setMessage(R.string.connection_instructions).setPositiveButton("Entendi", null).show();
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextSize(size); label.setTextColor(ContextCompat.getColor(this, color));
        label.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    private MaterialButton iconButton(int drawable, String description) {
        MaterialButton button = new MaterialButton(this);
        button.setIconResource(drawable);
        button.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(dp(48)); button.setMinHeight(dp(48));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setContentDescription(description);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
