package com.roadconnect.app.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.roadconnect.app.R;
import com.roadconnect.app.data.UrlPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.ByteArrayInputStream;
import java.util.Collections;

public final class BrowserPane extends FrameLayout {
    private static final String HOME = "https://m.youtube.com/";
    private final Runnable pauseAudio;
    private final FrameLayout webContainer;
    private final TextView status;
    private final ProgressBar progress;
    private WebView web;
    private FrameLayout fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String savedUrl = HOME;
    private boolean active;
    private boolean failed;

    public BrowserPane(Context context, Runnable pauseAudio) {
        super(context);
        this.pauseAudio = pauseAudio;
        setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        LayoutInflater.from(context).inflate(R.layout.view_browser, this, true);
        webContainer = findViewById(R.id.web_container);
        status = findViewById(R.id.browser_status);
        progress = findViewById(R.id.browser_progress);
        findViewById(R.id.browser_back).setOnClickListener(view -> handleBack());
        findViewById(R.id.browser_forward).setOnClickListener(view -> { if (web != null && web.canGoForward()) web.goForward(); });
        findViewById(R.id.browser_home).setOnClickListener(view -> { savedUrl = HOME; loadCurrent(); });
        findViewById(R.id.browser_reload).setOnClickListener(view -> loadCurrent());
        findViewById(R.id.browser_external).setOnClickListener(view -> openExternal(savedUrl));
    }

    public void restoreUrl(String url) { if (UrlPolicy.isInternalYouTubeUrl(url)) savedUrl = url; }
    public String currentUrl() { return savedUrl; }

    public void openYouTube() {
        pauseAudio.run();
        active = true;
        if (web == null) loadCurrent();
    }

    public void onHostResume() { openYouTube(); }

    public void onHostPause() {
        active = false;
        hideFullscreen();
        // Destroy the document, not only its timers: hidden HTML media must stop.
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.onPause();
            webContainer.removeView(web);
            web.destroy();
            web = null;
        }
    }

    public void destroy() { onHostPause(); removeAllViews(); }

    public boolean handleBack() {
        if (fullscreen != null) { hideFullscreen(); return true; }
        if (web != null && web.canGoBack()) { web.goBack(); return true; }
        return false;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private boolean createWebView() {
        try {
            web = new WebView(getContext());
            web.setBackgroundColor(Color.WHITE);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSafeBrowsingEnabled(true);
            settings.setSupportMultipleWindows(false);
            web.setWebViewClient(new BrowserClient());
            web.setWebChromeClient(new ChromeClient());
            web.setDownloadListener((url, agent, disposition, mime, size) -> openExternal(url));
            webContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));
            return true;
        } catch (RuntimeException unavailable) {
            if (web != null) { webContainer.removeView(web); web.destroy(); web = null; }
            error("O navegador do sistema não está disponível. Atualize o Android System WebView ou abra externamente.");
            return false;
        }
    }

    private void loadCurrent() {
        if (!active) return;
        if (web == null && !createWebView()) return;
        status.setText(R.string.browser_loading);
        status.setVisibility(View.VISIBLE);
        failed = false;
        web.loadUrl(savedUrl);
    }

    private final class BrowserClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // This callback also covers POST navigations omitted by shouldOverrideUrlLoading.
            if (request.isForMainFrame() && !UrlPolicy.isInternalYouTubeUrl(url) && !"about:blank".equals(url)) {
                post(() -> { if (active) openExternal(url); });
                return new WebResourceResponse("text/plain", "UTF-8", 403, "External navigation",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (UrlPolicy.isInternalYouTubeUrl(url)) return false;
            openExternal(url);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            if (!active) return;
            if (!UrlPolicy.isInternalYouTubeUrl(url)) {
                view.stopLoading();
                error(getContext().getString(R.string.unsafe_link));
                return;
            }
            savedUrl = url;
            failed = false;
            status.setText(R.string.browser_loading);
            status.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!active) return;
            progress.setVisibility(View.GONE);
            if (!failed) status.setVisibility(View.GONE);
            updateArrows();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
            if (active && request.isForMainFrame()) error(getContext().getString(R.string.browser_error));
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (active && request.isForMainFrame()) error(getContext().getString(R.string.browser_error));
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError failure) {
            handler.cancel();
            if (active) error("A conexão não pôde ser verificada. O certificado foi recusado.");
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int percent) { if (active) progress.setProgress(percent); }
        @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (!active || fullscreen != null) { callback.onCustomViewHidden(); return; }
            Activity activity = (Activity) getContext();
            fullscreen = new FrameLayout(getContext());
            fullscreen.setBackgroundColor(Color.BLACK);
            fullscreen.setKeepScreenOn(true);
            fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
            MaterialButton exit = new MaterialButton(getContext());
            exit.setIconResource(R.drawable.ic_close);
            exit.setIconPadding(0);
            exit.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            exit.setContentDescription(getContext().getString(R.string.leave_fullscreen));
            exit.setPadding(0, 0, 0, 0);
            int size = Math.round(56 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            position.topMargin = size / 2;
            position.rightMargin = size / 4;
            fullscreen.addView(exit, position);
            exit.setOnClickListener(ignored -> hideFullscreen());
            fullscreenCallback = callback;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(fullscreen, new ViewGroup.LayoutParams(-1, -1));
            WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(activity.getWindow(), decor);
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
        }
        @Override public void onHideCustomView() { hideFullscreen(); }
    }

    private void hideFullscreen() {
        if (fullscreen == null) return;
        FrameLayout old = fullscreen;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreen = null;
        fullscreenCallback = null;
        ((ViewGroup) old.getParent()).removeView(old);
        old.removeAllViews();
        Activity activity = (Activity) getContext();
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .show(WindowInsetsCompat.Type.systemBars());
        if (callback != null) callback.onCustomViewHidden();
    }

    private void updateArrows() {
        findViewById(R.id.browser_back).setEnabled(web != null && web.canGoBack());
        findViewById(R.id.browser_forward).setEnabled(web != null && web.canGoForward());
    }

    private void error(String message) {
        failed = true;
        progress.setVisibility(View.GONE);
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void openExternal(String raw) {
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                Toast.makeText(getContext(), R.string.unsafe_link, Toast.LENGTH_LONG).show();
                return;
            }
            getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(getContext(), R.string.no_external_app, Toast.LENGTH_LONG).show();
        } catch (URISyntaxException | NullPointerException invalid) {
            Toast.makeText(getContext(), R.string.unsafe_link, Toast.LENGTH_LONG).show();
        }
    }
}
