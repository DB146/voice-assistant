package com.voiceassist;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import java.util.ArrayList;

public class ListenActivity extends Activity implements RecognitionListener {

    private static final String[] SMARTTUBE = {
            "com.teamsmart.videomanager.tv",      // stable
            "com.liskovsoft.smarttubetv.beta"     // beta
    };

    private SpeechRecognizer recognizer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
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
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizer.startListening(i);
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) handle(list.get(0));
        finish();
    }

    private void handle(String sentence) {
        String text = sentence.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return;

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

    @Override
    public void onError(int error) {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    @Override public void onReadyForSpeech(Bundle p) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float v) {}
    @Override public void onBufferReceived(byte[] b) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onPartialResults(Bundle b) {}
    @Override public void onEvent(int t, Bundle b) {}
}
