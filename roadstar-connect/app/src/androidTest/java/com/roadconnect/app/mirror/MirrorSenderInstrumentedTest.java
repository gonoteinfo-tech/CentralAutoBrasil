package com.roadconnect.app.mirror;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.google.android.material.textfield.TextInputLayout;
import com.roadconnect.mirror.MirrorWire;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses the production Activity, consent flow, foreground service, encoder and playback capture. */
@RunWith(AndroidJUnit4.class)
public final class MirrorSenderInstrumentedTest {
    @Test(timeout = 120000)
    public void consentedPhoneScreenAndInternalAudioReachPinnedLocalReceiver() throws Exception {
        assertTrue("Internal playback capture requires API 29+", Build.VERSION.SDK_INT >= 29);
        String expectedPin = InstrumentationRegistry.getArguments().getString("receiverPin");
        assertNotNull("Pass -e receiverPin with the CI receiver certificate SHA-256", expectedPin);
        expectedPin = MirrorWire.requireFingerprint(expectedPin);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = context.getPackageName();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS);
        }
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.pressMenu();
        SharedPreferences preferences = context.getSharedPreferences(MirrorService.PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        ActivityScenario<MirrorActivity> scenario = null;
        try {
            scenario = ActivityScenario.launch(MirrorActivity.class);
            scenario.onActivity(activity -> {
                List<View> views = descendants(activity.getWindow().getDecorView());
                boolean host = false, code = false, fullHd = false, audio = false, connect = false;
                for (View view : views) {
                    if (view instanceof TextInputLayout) {
                        TextInputLayout field = (TextInputLayout) view;
                        String hint = String.valueOf(field.getHint());
                        EditText input = field.getEditText();
                        if (hint.startsWith("IP da central")) {
                            assertNotNull(input); input.setText("10.0.2.2"); host = true;
                        } else if (hint.startsWith("Código de 6")) {
                            assertNotNull(input); input.setText("123456"); code = true;
                        }
                    }
                    if (view instanceof RadioButton && ((RadioButton) view).getText().toString().contains("1920")) {
                        ((RadioButton) view).setChecked(true); fullHd = true;
                    }
                    if (view instanceof CheckBox) {
                        CheckBox option = (CheckBox) view;
                        if (option.getText().toString().contains("áudio interno")) {
                            assertTrue("Audio option must be enabled", option.isEnabled());
                            option.setChecked(true); audio = true;
                        }
                    }
                }
                assertTrue("Production host/code/1080p/audio fields must be present", host && code && fullHd && audio);
                for (View view : views) {
                    if (view instanceof TextView && ((TextView) view).getText().toString()
                            .equals("Conferir central e conectar")) {
                        assertTrue("Connect click must be handled", view.performClick()); connect = true; break;
                    }
                }
                assertTrue("Production connect button must be present", connect);
            });

            UiObject2 certificate = device.wait(Until.findObject(By.textContains("Compare todos os grupos SHA-256")), 25000);
            assertNotNull("Certificate inspection must show its actual fingerprint. Status: " + status(preferences), certificate);
            Matcher fingerprint = Pattern.compile("(?i)(?:[0-9a-f]{8}[\\s:]*){8}").matcher(certificate.getText());
            assertTrue("Certificate dialog must contain all 64 hexadecimal digits", fingerprint.find());
            assertEquals("Approve only the expected local TLS certificate", expectedPin,
                    MirrorWire.requireFingerprint(fingerprint.group()));
            UiObject2 confirm = device.findObject(By.text("São iguais, autorizar"));
            assertNotNull("Production certificate approval button", confirm);
            confirm.click();
            approveAndroidProjection(device);
            waitForTransmission(preferences, 25000);

            // Runs on the instrumentation worker. The app's real AudioRecord captures this permitted playback.
            playCapturableTone();
            SystemClock.sleep(1500);
            assertTrue("Projection must remain active while sending. Status: " + status(preferences), MirrorService.isRunning());
            assertTrue("Internal audio must stay available: " + status(preferences),
                    status(preferences).contains("áudio interno permitido"));
            scenario.onActivity(activity -> activity.startService(
                    new Intent(activity, MirrorService.class).setAction(MirrorService.ACTION_STOP)));
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (MirrorService.isRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            assertFalse("Production stop action must release the session", MirrorService.isRunning());
            assertTrue("Stop must leave an ended status: " + status(preferences), status(preferences).contains("encerrada"));
        } finally {
            // stopService invokes production onDestroy cleanup even if a UI assertion or capture fails.
            context.stopService(new Intent(context, MirrorService.class));
            if (scenario != null) scenario.close();
        }
    }

    private static void approveAndroidProjection(UiDevice device) {
        UiObject2 start = device.wait(Until.findObject(By.res("android", "button1")
                .pkg("com.android.systemui")), 15000);
        if (start == null) {
            start = device.wait(Until.findObject(By.pkg("com.android.systemui")
                    .text(Pattern.compile("(?i)(start now|start recording|iniciar agora|iniciar|começar)"))), 5000);
        }
        assertNotNull("Actual Android MediaProjection consent dialog must appear", start);
        start.click();
    }

    private static void waitForTransmission(SharedPreferences preferences, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            String status = status(preferences);
            if (MirrorService.isRunning() && status.startsWith("Transmitindo")
                    && status.contains("áudio interno permitido")) return;
            if (status.contains("interrompida") || status.contains("indisponível") || status.contains("não codifica"))
                fail("Production capture reported failure: " + status);
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for production transmission: " + status(preferences));
    }

    private static String status(SharedPreferences preferences) {
        return preferences.getString(MirrorService.STATUS_MESSAGE, "<no service status>");
    }

    private static void playCapturableTone() {
        if (Build.VERSION.SDK_INT < 29) throw new AssertionError("AudioPlaybackCapture requires API 29");
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        assertTrue("Emulator must support PCM playback", minimum > 0);
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum * 2, 19200)).build();
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.getState());
            track.setVolume(1f);
            track.play();
            short[] samples = new short[4800];
            for (int i = 0; i < samples.length; i++)
                samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48000) * 12000);
            for (int batch = 0; batch < 50; batch++) {
                int written = 0;
                while (written < samples.length) {
                    int count = track.write(samples, written, samples.length - written, AudioTrack.WRITE_BLOCKING);
                    assertTrue("Actual tone playback must write PCM", count > 0);
                    written += count;
                }
            }
            SystemClock.sleep(500);
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    private static List<View> descendants(View root) {
        List<View> views = new ArrayList<>();
        views.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) views.addAll(descendants(group.getChildAt(i)));
        }
        return views;
    }
}
