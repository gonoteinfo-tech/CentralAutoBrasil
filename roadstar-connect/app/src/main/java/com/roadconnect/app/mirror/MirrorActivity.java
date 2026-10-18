package com.roadconnect.app.mirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.roadconnect.mirror.MirrorWire;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocket;

/** Pairing and platform permission flow. A projection token is used once, by the service. */
public final class MirrorActivity extends AppCompatActivity {
    private static final int BACKGROUND = Color.rgb(11, 16, 22);
    private static final int SURFACE = Color.rgb(21, 29, 40);
    private static final int ACCENT = Color.rgb(80, 227, 194);
    private static final int WHITE = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(164, 178, 195);
    private final ExecutorService inspector = Executors.newSingleThreadExecutor();
    private volatile SSLSocket inspectionSocket;
    private SharedPreferences preferences;
    private TextInputEditText hostInput;
    private TextInputEditText codeInput;
    private TextInputLayout hostField;
    private TextInputLayout codeField;
    private RadioButton fullHd;
    private CheckBox audio;
    private TextView status;
    private MaterialButton connect;
    private MaterialButton stop;
    private boolean busy;
    private boolean destroyed;
    private int inspectionGeneration;
    private CaptureRequest pending;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> projectionLauncher;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener = (prefs, key) -> renderStatus();

    @Override protected void onCreate(Bundle savedState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        preferences = getSharedPreferences(MirrorService.PREFERENCES, MODE_PRIVATE);
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::permissionsResult);
        projectionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            CaptureRequest request = pending;
            pending = null;
            busy = false;
            if (request == null) { renderStatus(); return; }
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                status.setText("Captura cancelada. Nada está sendo transmitido.");
                renderControls();
                return;
            }
            Intent start = new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_START)
                    .putExtra(MirrorService.EXTRA_RESULT_CODE, result.getResultCode())
                    .putExtra(MirrorService.EXTRA_RESULT_DATA, result.getData())
                    .putExtra(MirrorService.EXTRA_HOST, request.host)
                    .putExtra(MirrorService.EXTRA_PORT, MirrorService.PORT)
                    .putExtra(MirrorService.EXTRA_PAIR_CODE, request.code)
                    .putExtra(MirrorService.EXTRA_FINGERPRINT, request.fingerprint)
                    .putExtra(MirrorService.EXTRA_WIDTH, request.width)
                    .putExtra(MirrorService.EXTRA_HEIGHT, request.height)
                    .putExtra(MirrorService.EXTRA_AUDIO, request.audio);
            try {
                ContextCompat.startForegroundService(this, start);
                status.setText("Preparando transmissão… Acompanhe também a notificação do Android.");
            } catch (RuntimeException denied) {
                status.setText("O Android não permitiu iniciar. Volte a esta tela e autorize uma nova sessão.");
            }
            renderControls();
        });
        buildScreen();
        if (savedState != null) {
            hostInput.setText(savedState.getString("host", ""));
            codeInput.setText(savedState.getString("code", ""));
            if (savedState.getBoolean("full_hd", true)) fullHd.setChecked(true);
            else ((RadioButton) ((RadioGroup) fullHd.getParent()).getChildAt(1)).setChecked(true);
            audio.setChecked(Build.VERSION.SDK_INT >= 29 && savedState.getBoolean("audio", true));
            if (savedState.containsKey("pending_host")) {
                pending = new CaptureRequest(savedState.getString("pending_host"), savedState.getString("pending_code"),
                        savedState.getString("pending_pin"), savedState.getInt("pending_width"),
                        savedState.getInt("pending_height"), savedState.getBoolean("pending_audio"));
                busy = true;
            }
        } else {
            hostInput.setText(preferences.getString("receiver_host", ""));
        }
        renderStatus();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(32));
        scroll.addView(page);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(scroll);
        MaterialButton back = new MaterialButton(this);
        back.setText("‹  Voltar");
        back.setTextColor(ACCENT);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        back.setOnClickListener(view -> finish());
        page.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView badge = label("ESPELHAMENTO • WI-FI LOCAL", 12, ACCENT);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(0, dp(18), 0, dp(12));
        page.addView(badge);
        page.addView(label("Sua tela, na central", 30, WHITE));
        TextView introduction = label("Transmita a tela do telefone e o áudio interno permitido. Use com o veículo estacionado.", 16, MUTED);
        introduction.setPadding(0, dp(12), 0, dp(24));
        page.addView(introduction);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardBackgroundColor(SURFACE);
        card.setCardElevation(0);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.addView(form);
        form.addView(label("Conectar à Roadstar", 21, WHITE));
        TextView directions = label("1. Instale e abra o APK Roadstar Receptor na central.\n2. Conecte os dois aparelhos à mesma rede Wi-Fi.\n3. Digite o IP e o código mostrados na central.", 14, MUTED);
        directions.setPadding(0, dp(12), 0, dp(20));
        form.addView(directions);
        hostField = field("IP da central (ex.: 192.168.1.20)");
        hostInput = new TextInputEditText(hostField.getContext());
        hostInput.setInputType(InputType.TYPE_CLASS_PHONE);
        hostInput.setSingleLine(true);
        hostInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        hostField.addView(hostInput);
        form.addView(hostField, fieldParams());
        codeField = field("Código de 6 dígitos");
        codeInput = new TextInputEditText(codeField.getContext());
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setSingleLine(true);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeField.addView(codeInput);
        form.addView(codeField, fieldParams());
        TextView qualityTitle = label("Qualidade de transmissão", 15, WHITE);
        qualityTitle.setPadding(0, dp(8), 0, dp(8));
        form.addView(qualityTitle);
        RadioGroup resolution = new RadioGroup(this);
        resolution.setOrientation(LinearLayout.VERTICAL);
        fullHd = new RadioButton(this);
        fullHd.setId(View.generateViewId());
        fullHd.setText("Full HD • 1920 × 1080 • 30 fps");
        fullHd.setTextColor(WHITE);
        resolution.addView(fullHd);
        RadioButton hd = new RadioButton(this);
        hd.setId(View.generateViewId());
        hd.setText("HD • 1280 × 720 • 30 fps");
        hd.setTextColor(WHITE);
        resolution.addView(hd);
        fullHd.setChecked(true);
        form.addView(resolution);
        audio = new CheckBox(this);
        audio.setText("Transmitir áudio interno permitido");
        audio.setTextColor(WHITE);
        audio.setChecked(Build.VERSION.SDK_INT >= 29);
        audio.setEnabled(Build.VERSION.SDK_INT >= 29);
        form.addView(audio);
        if (Build.VERSION.SDK_INT < 29) form.addView(label("Áudio interno requer Android 10 ou superior.", 13, MUTED));
        connect = new MaterialButton(this);
        connect.setText("Conferir central e conectar");
        connect.setMinHeight(dp(52));
        connect.setOnClickListener(view -> inspectReceiver());
        form.addView(connect, fieldParams());
        stop = new MaterialButton(this);
        stop.setText("Parar / cancelar conexão");
        stop.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(39, 51, 66)));
        stop.setTextColor(WHITE);
        stop.setOnClickListener(view -> stopCapture());
        form.addView(stop, fieldParams());
        status = label("Pronto para conectar.", 14, ACCENT);
        status.setPadding(0, dp(10), 0, 0);
        form.addView(status);
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView limitations = label("A central precisa permitir a instalação do receptor. A transmissão funciona fora do Android Auto.\n\n1080p depende dos codificadores dos aparelhos e não muda a resolução física da tela. A proporção é preservada com bordas. Aplicativos podem bloquear vídeo ou áudio protegido. Não há controle remoto por toque nem captura do microfone.", 13, MUTED);
        limitations.setPadding(0, dp(24), 0, 0);
        page.addView(limitations);
    }

    private void inspectReceiver() {
        if (busy || MirrorService.isRunning()) return;
        hostField.setError(null);
        codeField.setError(null);
        final String host;
        try { host = MirrorWire.requirePrivateIpv4(String.valueOf(hostInput.getText())); }
        catch (IllegalArgumentException invalid) { hostField.setError("Informe um IPv4 da rede local: 192.168.x.x, 10.x.x.x ou 172.16–31.x.x."); return; }
        String code = String.valueOf(codeInput.getText()).trim();
        if (!code.matches("[0-9]{6}")) { codeField.setError("Use os 6 dígitos exibidos pelo receptor."); return; }
        CaptureRequest candidate = new CaptureRequest(host, code, "", fullHd.isChecked() ? 1920 : 1280,
                fullHd.isChecked() ? 1080 : 720, audio.isChecked() && Build.VERSION.SDK_INT >= 29);
        preferences.edit().putString("receiver_host", host).apply();
        busy = true;
        renderControls();
        status.setText("Consultando certificado da central… Nenhuma tela ou código foi enviado.");
        final int generation = ++inspectionGeneration;
        inspector.execute(() -> {
            try {
                X509Certificate[] certificate = new X509Certificate[1];
                try (SSLSocket socket = MirrorTls.create(null, certificate)) {
                    inspectionSocket = socket;
                    MirrorTls.handshake(socket, host, MirrorService.PORT);
                    if (certificate[0] == null) throw new IllegalStateException("Certificado ausente.");
                    String fingerprint = MirrorWire.fingerprint(certificate[0].getEncoded());
                    runOnUiThread(() -> {
                        if (destroyed || generation != inspectionGeneration) return;
                        showCertificate(new CaptureRequest(candidate.host, candidate.code, fingerprint,
                                candidate.width, candidate.height, candidate.audio));
                    });
                } finally { inspectionSocket = null; }
            } catch (Exception unavailable) {
                runOnUiThread(() -> {
                    if (destroyed || generation != inspectionGeneration) return;
                    busy = false;
                    status.setText("Não foi possível consultar a central. Confira IP, Wi-Fi, horário dos aparelhos e se o receptor está aberto.");
                    renderControls();
                });
            }
        });
    }

    private void showCertificate(CaptureRequest candidate) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < candidate.fingerprint.length(); i += 8) {
            if (i != 0) formatted.append(i % 32 == 0 ? '\n' : ' ');
            formatted.append(candidate.fingerprint, i, Math.min(i + 8, candidate.fingerprint.length()));
        }
        TextView message = label("Compare todos os grupos SHA-256 abaixo com os exibidos no Roadstar Receptor. Autorize somente se forem iguais.\n\n" + formatted, 14, WHITE);
        message.setTypeface(Typeface.MONOSPACE);
        message.setPadding(dp(24), dp(12), dp(24), dp(12));
        message.setTextIsSelectable(true);
        new MaterialAlertDialogBuilder(this).setTitle("Esta é a sua central?").setView(message)
                .setNegativeButton("Cancelar", (dialog, which) -> cancelPending())
                .setPositiveButton("São iguais, autorizar", (dialog, which) -> {
                    pending = candidate;
                    requestPermissionsThenProjection();
                }).setOnCancelListener(dialog -> cancelPending()).show();
    }

    private void requestPermissionsThenProjection() {
        if (pending == null) return;
        ArrayList<String> needed = new ArrayList<>();
        if (pending.audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (needed.isEmpty()) requestProjection();
        else permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void permissionsResult(Map<String, Boolean> ignored) {
        if (pending == null) return;
        if (pending.audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            new MaterialAlertDialogBuilder(this).setTitle("Continuar só com vídeo?")
                    .setMessage("O Android exige a permissão de áudio para capturar áudio interno. Este app não usa o microfone.")
                    .setNegativeButton("Cancelar", (dialog, which) -> cancelPending())
                    .setPositiveButton("Somente vídeo", (dialog, which) -> {
                        pending.audio = false;
                        audio.setChecked(false);
                        requestProjection();
                    }).setOnCancelListener(dialog -> cancelPending()).show();
        } else requestProjection();
    }

    private void requestProjection() {
        if (pending == null) return;
        if (MirrorService.isRunning()) { cancelPending(); renderStatus(); return; }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            if (manager == null) throw new IllegalStateException("Captura indisponível.");
            Intent request = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            status.setText("Autorize o compartilhamento de tela na janela do Android.");
            projectionLauncher.launch(request);
        } catch (RuntimeException unavailable) {
            cancelPending();
            status.setText("Captura de tela indisponível neste aparelho.");
        }
    }

    private void stopCapture() {
        cancelPending();
        if (MirrorService.isRunning()) startService(new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
        status.setText("Transmissão encerrada. Para reconectar, autorize uma nova sessão.");
        renderControls();
    }

    private void cancelPending() {
        pending = null;
        busy = false;
        inspectionGeneration++;
        SSLSocket socket = inspectionSocket;
        if (socket != null) new Thread(() -> { try { socket.close(); } catch (Exception ignored) {} }, "mirror-inspect-cancel").start();
        renderControls();
    }

    private void renderStatus() {
        if (status == null) return;
        if (busy) status.setText("Conexão em preparação. Conclua a autorização do Android ou cancele.");
        else {
            String message = preferences.getString(MirrorService.STATUS_MESSAGE, "Pronto para conectar.");
            if (!MirrorService.isRunning() && (message.startsWith("Transmitindo") || message.startsWith("Conectando")))
                message = "Nenhuma transmissão ativa. Autorize uma nova sessão para conectar.";
            status.setText(message);
        }
        renderControls();
    }

    private void renderControls() {
        if (connect == null) return;
        boolean occupied = busy || MirrorService.isRunning();
        connect.setEnabled(!occupied);
        hostInput.setEnabled(!occupied);
        codeInput.setEnabled(!occupied);
        fullHd.setEnabled(!occupied);
        ((RadioGroup) fullHd.getParent()).getChildAt(1).setEnabled(!occupied);
        audio.setEnabled(!occupied && Build.VERSION.SDK_INT >= 29);
        stop.setEnabled(occupied);
    }

    @Override protected void onStart() {
        super.onStart();
        preferences.registerOnSharedPreferenceChangeListener(statusListener);
        renderStatus();
    }

    @Override protected void onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(statusListener);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("host", String.valueOf(hostInput.getText()));
        state.putString("code", String.valueOf(codeInput.getText()));
        state.putBoolean("full_hd", fullHd.isChecked());
        state.putBoolean("audio", audio.isChecked());
        if (pending != null) {
            state.putString("pending_host", pending.host);
            state.putString("pending_code", pending.code);
            state.putString("pending_pin", pending.fingerprint);
            state.putInt("pending_width", pending.width);
            state.putInt("pending_height", pending.height);
            state.putBoolean("pending_audio", pending.audio);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        inspectionGeneration++;
        SSLSocket socket = inspectionSocket;
        if (socket != null) new Thread(() -> { try { socket.close(); } catch (Exception ignored) {} }, "mirror-inspect-close").start();
        inspector.shutdownNow();
        super.onDestroy();
    }

    private TextInputLayout field(String hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        return layout;
    }
    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        return params;
    }
    private TextView label(String text, int size, int color) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setLineSpacing(dp(3), 1);
        return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class CaptureRequest {
        final String host;
        final String code;
        final String fingerprint;
        final int width;
        final int height;
        boolean audio;
        CaptureRequest(String host, String code, String fingerprint, int width, int height, boolean audio) {
            this.host = host; this.code = code; this.fingerprint = fingerprint;
            this.width = width; this.height = height; this.audio = audio;
        }
    }
}
