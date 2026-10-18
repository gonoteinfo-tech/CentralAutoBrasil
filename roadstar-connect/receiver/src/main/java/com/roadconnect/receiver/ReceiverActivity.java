package com.roadconnect.receiver;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Visible, local-only receiver. Leaving this Activity always ends reception. */
public final class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final int BG = Color.rgb(11, 16, 22);
    private static final int MINT = Color.rgb(80, 227, 194);
    private SurfaceView video;
    private TextView status;
    private TextView addresses;
    private TextView fingerprint;
    private TextView codeView;
    private Button control;
    private LinearLayout content;
    private View connectionPanel;
    private Button connectionToggle;
    private boolean fullscreen;
    private int insetLeft, insetTop, insetRight, insetBottom;
    private ReceiverServer server;
    private boolean started;
    private boolean enabled = true;
    private String code;
    private int serverGeneration;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        code = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        LinearLayout root = new LinearLayout(this);
        content = root;
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            applyPadding();
            return insets;
        });
        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.BLACK);
        video = new AspectSurface(this);
        video.setId(R.id.receiver_video);
        video.getHolder().addCallback(this);
        canvas.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(canvas, new LinearLayout.LayoutParams(0, -1, 1));
        ScrollView scroll = new ScrollView(this);
        connectionPanel = scroll;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(8), dp(8));
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(dp(280), -1));
        addText(panel, "ROADSTAR", 23, MINT);
        addText(panel, "Receptor de tela • Wi-Fi", 14, Color.WHITE);
        status = addText(panel, "Preparando receptor…", 16, Color.WHITE);
        status.setId(R.id.receiver_status);
        addresses = addText(panel, "", 14, Color.LTGRAY);
        addText(panel, "Código desta sessão", 13, Color.LTGRAY);
        codeView = addText(panel, code, 30, MINT);
        codeView.setId(R.id.receiver_code);
        addText(panel, "Confira esta impressão SHA-256 no telefone antes de autorizar:", 12, Color.LTGRAY);
        fingerprint = addText(panel, "Gerando certificado…", 11, Color.WHITE);
        fingerprint.setId(R.id.receiver_fingerprint);
        fingerprint.setTextIsSelectable(true);
        addText(panel, "Abra Espelhamento no telefone e informe o IP e o código. Ambos precisam estar na mesma rede Wi-Fi.", 13, Color.LTGRAY);
        addText(panel, "Use com o veículo estacionado. Áudio depende da permissão de captura de cada aplicativo. A tela não controla o telefone.", 12, Color.LTGRAY);
        control = new Button(this);
        control.setText("Parar receptor");
        control.setTextColor(BG);
        control.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MINT));
        control.setOnClickListener(view -> {
            enabled = !enabled;
            if (enabled) startReceiver(); else stopReceiver("Receptor parado");
            control.setText(enabled ? "Parar receptor" : "Iniciar receptor");
        });
        panel.addView(control, new LinearLayout.LayoutParams(-1, -2));
        Button fullscreenButton = new Button(this);
        fullscreenButton.setText("Tela cheia");
        fullscreenButton.setOnClickListener(view -> setFullscreen(true));
        panel.addView(fullscreenButton, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.BLACK);
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        connectionToggle = new Button(this);
        connectionToggle.setText("Conexão");
        connectionToggle.setTextSize(12);
        connectionToggle.setTextColor(MINT);
        connectionToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(210, 21, 29, 40)));
        connectionToggle.setVisibility(View.GONE);
        connectionToggle.setOnClickListener(view -> setFullscreen(false));
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        toggleParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        shell.addView(connectionToggle, toggleParams);
        setContentView(shell);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        updateAddresses();
        startReceiver();
    }

    @Override protected void onStop() {
        started = false;
        stopReceiver("Receptor parado. Volte a esta tela para conectar.");
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ReceiverServer current = server;
        if (!hasFocus && current != null) {
            // Stop captured sound when another window obscures the receiver.
            current.disconnectSender();
        }
    }

    private void startReceiver() {
        if (!started || !enabled || server != null || !video.getHolder().getSurface().isValid()) return;
        int generation = ++serverGeneration;
        // Each explicit restart, return to this screen or new surface is a new pairing session.
        // A probe or a bad code keeps the current displayed code stable.
        String previous = code;
        do { code = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000)); }
        while (code.equals(previous));
        codeView.setText(code);
        server = new ReceiverServer(getApplicationContext(), video.getHolder().getSurface(), code,
                new ReceiverServer.Listener() {
                    @Override public void onStatus(String message) {
                        runOnUiThread(() -> {
                            if (serverGeneration == generation && started) {
                                status.setText(message);
                                setFullscreen(message.startsWith("Espelhando"));
                            }
                        });
                    }
                    @Override public void onCertificate(String sha256) {
                        runOnUiThread(() -> {
                            if (serverGeneration == generation && started) fingerprint.setText(groupFingerprint(sha256));
                        });
                    }
                });
        server.start();
        status.setText("Abrindo conexão segura…");
    }

    private void stopReceiver(String message) {
        ++serverGeneration;
        ReceiverServer old = server;
        server = null;
        if (old != null) old.close();
        setFullscreen(false);
        status.setText(message);
    }

    private void setFullscreen(boolean value) {
        fullscreen = value;
        connectionPanel.setVisibility(value ? View.GONE : View.VISIBLE);
        connectionToggle.setVisibility(value ? View.VISIBLE : View.GONE);
        int flags = value ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        applyPadding();
        // Resize the existing surface; never remove/recreate it to enter fullscreen.
        video.requestLayout();
    }

    private void applyPadding() {
        if (fullscreen) content.setPadding(0, 0, 0, 0);
        else content.setPadding(dp(12) + insetLeft, dp(12) + insetTop,
                dp(12) + insetRight, dp(12) + insetBottom);
    }

    private void updateAddresses() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) ips.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // Connection details remain usable after returning to the Activity.
        }
        addresses.setText(ips.isEmpty() ? "Sem IP local. Conecte à mesma rede Wi-Fi do telefone."
                : "IP: " + android.text.TextUtils.join("\nIP: ", ips) + "\nPorta: " + ReceiverServer.PORT);
    }

    private TextView addText(LinearLayout parent, String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private static String groupFingerprint(String value) {
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < value.length(); i += 4) {
            if (i > 0) display.append(i % 16 == 0 ? '\n' : ' ');
            display.append(value, i, Math.min(i + 4, value.length()));
        }
        return display.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void surfaceCreated(SurfaceHolder holder) { startReceiver(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopReceiver("Tela indisponível"); }

    private static final class AspectSurface extends SurfaceView {
        AspectSurface(android.content.Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            int height = View.MeasureSpec.getSize(heightSpec);
            if (width * 9L > height * 16L) width = height * 16 / 9;
            else height = width * 9 / 16;
            setMeasuredDimension(width, height);
        }
    }
}
