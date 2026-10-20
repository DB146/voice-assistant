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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public class ListenActivity extends Activity implements RecognitionListener {

    private static final long SILENCE_MS = 1000;
    private static final String[] SMARTTUBE = {
            "com.teamsmart.videomanager.tv",      // bản chính thức
            "com.liskovsoft.smarttubetv.beta"     // bản beta
    };
    private static final String FPT = "com.fptplay.launcher";

    // Kênh thường: nói tên kênh (bỏ gạch, bỏ "-hd"), ví dụ "vtv3", "htv7", "arirang".
    private static final String[] CHANNELS = {
            "fpt-play", "vtv1-hd", "vtv2-hd", "vtv3-hd", "vtv4-hd", "vtv5-hd", "vtv6-hd",
            "vtv7-hd", "vtv8-hd", "vtv9-hd", "vtv10", "vtv5-tay-nam-bo", "htv7-hd", "htv9-hd",
            "thvl1", "vtv5-tay-nguyen", "asian-food-channel", "outdoor-channel",
            "da-vinci-learning", "fim360", "htvc-the-thao", "france-24", "arirang", "nhk-world-hd",
            "channel-newsasia", "tv5-monde", "dw", "kbs-world", "cnbc", "kix", "vietnam-today",
            "quoc-phong-vn", "antv", "htv5-bchannel", "mien-tay-thdt2", "htv1", "htv2", "htv3",
            "htvc-plus", "htvc-du-lich", "htvc-phu-nu", "htvc-gia-dinh", "htv4",
            "htvc-thuan-viet-hd", "htvc-phim"
    };

    // Kênh tỉnh/thành: phải nói "kênh" + tên + số nếu có, ví dụ "kênh hà nội 1", "kênh cần thơ 3".
    private static final String[] PROVINCES = {
            "hanoitv-1", "hanoitv-2", "vinh-long-2", "vinh-long-3", "vinh-long-4-hd", "an-giang",
            "an-giang-3", "bac-ninh-1", "lam-dong-2", "gia-lai-1", "ca-mau", "can-tho", "cao-bang",
            "da-nang-1", "da-nang-2", "dak-lak", "dien-bien", "dong-nai-1", "dong-nai-2",
            "dong-thap", "ha-tinh", "hai-phong", "hai-phong-3", "can-tho-2", "thua-thien-hue",
            "hung-yen", "khanh-hoa", "an-giang-1", "quang-ngai-2", "lai-chau", "lam-dong",
            "lang-son", "lao-cai", "tay-ninh", "nghe-an", "ninh-binh", "khanh-hoa-1", "phu-tho",
            "quang-ngai-1", "quang-ninh-1", "quang-ninh-3", "quang-tri", "can-tho-3", "son-la",
            "thai-nguyen", "thanh-hoa", "vinh-long-5", "tuyen-quang"
    };

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
        hint.setText("Tự chạy sau 1 giây im lặng · Nhấn Back để hủy");

        root.addView(status);
        root.addView(heard);
        root.addView(hint);
        setContentView(root);
        status.setText("Đang khởi động…");
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
            Toast.makeText(this, "Nhận dạng giọng nói không khả dụng", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(i);
    }

    private void update(Bundle results) {
        if (done || results == null) return;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) return;
        latest = list.get(0).trim();
        heard.setText("“" + latest + "”");
        status.setText("Đã nghe…");
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

    /** Bỏ dấu, chữ thường, bỏ ký tự lạ, ghép "vtv 1" -> "vtv1". */
    private static String norm(String s) {
        s = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd');
        s = s.replaceAll("[^a-z0-9]+", " ").trim();
        return s.replaceAll("([a-z])\\s+(\\d)", "$1$2");
    }

    /** Trả về ID kênh nếu câu nói chứa tên kênh (ưu tiên tên dài nhất). Kênh tỉnh cần có chữ "kênh". */
    private static String findChannel(String sentence) {
        String text = " " + norm(sentence) + " ";
        boolean hasKenh = text.contains(" kenh");
        ArrayList<String> ids = new ArrayList<>(Arrays.asList(CHANNELS));
        if (hasKenh) ids.addAll(Arrays.asList(PROVINCES));
        ids.sort(Comparator.comparingInt((String s) -> s.length()).reversed());
        for (String id : ids) {
            String key = norm(id.replace("hanoitv-", "ha-noi-"));
            if (text.contains(" " + key + " ")) return id;
            if (key.endsWith(" hd") && text.contains(" " + key.substring(0, key.length() - 3) + " ")) return id;
        }
        return null;
    }

    private void handle(String sentence) {
        String text = sentence.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return;

        String channel = findChannel(text);
        if (channel != null) {
            openChannel(channel);
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
            Toast.makeText(this, "Chưa cài SmartTube", Toast.LENGTH_LONG).show();
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
        Toast.makeText(this, "Chưa cài SmartTube", Toast.LENGTH_LONG).show();
    }

    private void openChannel(String id) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("content://" + FPT + "/SWITCH_CHANNEL/" + id)).setPackage(FPT));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Chưa cài FPT Play", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onReadyForSpeech(Bundle p) { status.setText("Đang nghe…"); }
    @Override public void onPartialResults(Bundle b) { update(b); }
    @Override public void onResults(Bundle b) { update(b); }

    @Override
    public void onError(int error) {
        if (done) return;
        if (latest.isEmpty()) {
            status.setText("Không nghe rõ");
            handler.postDelayed(this::finish, 1500);
        }
        // nếu đã nghe được gì đó, bộ đếm sẽ tự chạy lệnh
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
