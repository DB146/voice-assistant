package com.voiceassist;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ListenActivity extends Activity implements RecognitionListener {

    private static final long SILENCE_MS = 3000;
    private static final String[] SMARTTUBE = {
            "com.teamsmart.videomanager.tv",      // stable
            "com.liskovsoft.smarttubetv.beta"     // beta
    };

    private static final String FPT = "com.fptplay.launcher";
    // "vtv1" .. "vtv9" (also "VTV 3") -> vtv3-hd
    private static final String CHANNEL_URI = "content://com.fptplay.launcher/SWITCH_CHANNEL/vtv%s-hd";
    private static final Pattern CHANNEL = Pattern.compile("vtv\\s*([1-9])\\b", Pattern.CASE_INSENSITIVE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fire = this::execute;

    private SpeechRecognizer recognizer;
    private TextView status, heard;
    private String latest = "";
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(96, 48, 96, 48);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);

        heard = new TextView(this);
        heard.setTextColor(Color.WHITE);
        heard.setTextSize(40);
        heard.setGravity(Gravity.CENTER);
        heard.setPadding(0, 48, 0, 48);

        TextView hint = new TextView(this);
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(20);
        hint.setGravity(Gravity.CENTER);
        hint.setText("Runs after 3 seconds of silence · Press Back to cancel");

        root.addView(status);
        root.addView(heard);
        root.addView(hint);
        setContentView(root);
        status.setText("Starting…");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            listen();
        } else {
            finish();
        }
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(i);
    }

    private void update(Bundle results) {
        if (done || results == null) return;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) return;
        latest = list.get(0).trim();
        heard.setText("“" + latest + "”");
        status.setText("Heard…");
        handler.removeCallbacks(fire);
        handler.postDelayed(fire, SILENCE_MS);
    }

    private void execute() {
        if (done) return;
        done = true;
        if (recognizer != null) recognizer.cancel();
        handle(latest);
        finish();
    }

    private void handle(String sentence) {
        String text = sentence.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return;

        Matcher m = CHANNEL.matcher(text);
        if (m.find()) {
            openChannel(m.group(1));
            return;
        }

        if (text.equalsIgnoreCase("youtube")) {
            for (String pkg : SMARTTUBE) {
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    startActivity(launch);
                    return;
                }
            }
            Toast.makeText(this, "SmartTube not installed", Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(text));
        for (String pkg : SMARTTUBE) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Toast.makeText(this, "SmartTube not installed", Toast.LENGTH_LONG).show();
    }

    private void openChannel(String number) {
        if (!CHANNEL_URI.isEmpty()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(CHANNEL_URI, number)))
                        .setPackage(FPT));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(FPT);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(FPT);
        if (launch == null) {
            Toast.makeText(this, "FPT launcher not installed", Toast.LENGTH_LONG).show();
            return;
        }
        launch.putExtra("channel", number);
        startActivity(launch);
    }

    @Override public void onReadyForSpeech(Bundle p) { status.setText("Listening…"); }
    @Override public void onPartialResults(Bundle b) { update(b); }
    @Override public void onResults(Bundle b) { update(b); }

    @Override
    public void onError(int error) {
        if (done) return;
        if (latest.isEmpty()) {
            status.setText("Didn't catch that");
            handler.postDelayed(this::finish, 1500);
        }
        // otherwise the pending timer will run the action with what was heard
    }

    @Override
    protected void onDestroy() {
        done = true;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float v) {}
    @Override public void onBufferReceived(byte[] b) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int t, Bundle b) {}
}
