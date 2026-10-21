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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ListenActivity extends Activity implements RecognitionListener {

    private static final long SILENCE_MS = 1200;
    private static final String[] SMARTTUBE = {
            "com.teamsmart.videomanager.tv",      // bản chính thức
            "com.liskovsoft.smarttubetv.beta"     // bản beta
    };
    private static final String FPT = "com.fptplay.launcher";
    private static final Pattern YOUTUBE = Pattern.compile("(?i)\\byou\\s?tube\\b");

    // ID kênh FPT Play. Nói tên kênh (bỏ gạch, bỏ "-hd"), ví dụ "vtv3", "htv7", "hà nội 1", "cần thơ 3".
    private static final String[] CHANNELS = {
            "fpt-play", "vtv1-hd", "vtv2-hd", "vtv3-hd", "vtv4-hd", "vtv5-hd", "vtv6-hd",
            "vtv7-hd", "vtv8-hd", "vtv9-hd", "vtv10", "vtv5-tay-nam-bo", "htv7-hd", "htv9-hd",
            "thvl1", "vtv5-tay-nguyen", "fim360", "htvc-the-thao", "vietnam-today",
            "quoc-phong-vn", "antv", "htv5-bchannel", "mien-tay-thdt2", "htv1", "htv2", "htv3",
            "htvc-plus", "htvc-du-lich", "htvc-phu-nu", "htvc-gia-dinh", "htv4",
            "htvc-thuan-viet-hd", "htvc-phim",
            "hanoitv-1", "hanoitv-2", "vinh-long-2", "vinh-long-3", "vinh-long-4-hd", "an-giang",
            "an-giang-3", "bac-ninh-1", "lam-dong-2", "gia-lai-1", "ca-mau", "can-tho", "cao-bang",
            "da-nang-1", "da-nang-2", "dak-lak", "dien-bien", "dong-nai-1", "dong-nai-2",
            "dong-thap", "ha-tinh", "hai-phong", "hai-phong-3", "can-tho-2", "thua-thien-hue",
            "hung-yen", "khanh-hoa", "an-giang-1", "quang-ngai-2", "lai-chau", "lam-dong",
            "lang-son", "lao-cai", "tay-ninh", "nghe-an", "ninh-binh", "khanh-hoa-1", "phu-tho",
            "quang-ngai-1", "quang-ninh-1", "quang-ninh-3", "quang-tri", "can-tho-3", "son-la",
            "thai-nguyen", "thanh-hoa", "vinh-long-5", "tuyen-quang","da-vinci-learning"
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
        hint.setText("Nhấn Back để hủy");

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
        // Từ đệm đứng đầu câu, bị bỏ khi so khớp: "mở kênh htv5" -> "htv5"
    private static final java.util.Set<String> FILLER = new java.util.HashSet<>(
            java.util.Arrays.asList("mo", "bat", "xem", "kenh", "chuyen", "sang", "den", "qua",
                    "len", "cho", "toi", "truyen", "hinh", "dai", "di", "nhe", "giup"));

    private static String channelKey(String id) {
        return norm(id.replace("hanoitv-", "ha-noi-").replace("-hd", ""));
    }

    private static int firstNumber(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : Integer.MAX_VALUE;
    }

/** Trả về ID kênh nếu CẢ CÂU (sau khi bỏ từ đệm đầu câu) là tên kênh, đủ hoặc một phần. */
    private static String findChannel(String sentence) {
        String[] words = norm(sentence).split(" ");
        int start = 0;
        while (start < words.length && FILLER.contains(words[start])) start++;
        if (start >= words.length) return null;
        String p = String.join(" ", java.util.Arrays.copyOfRange(words, start, words.length));

        // 1) Khớp đúng toàn bộ tên kênh
        for (String id : CHANNELS) {
            if (channelKey(id).equals(p)) return id;
        }

        // 2) Khớp một phần: p là phần đầu của tên kênh -> chọn tên ngắn nhất, rồi số nhỏ nhất
        String pick = null;
        int pickLen = Integer.MAX_VALUE, pickNum = Integer.MAX_VALUE;
        for (String id : CHANNELS) {
            String key = channelKey(id);
            if (!key.startsWith(p)) continue;
            if (key.length() > p.length()) {
                char next = key.charAt(p.length());
                if (next != ' ' && !Character.isDigit(next)) continue;
            }
            int num = firstNumber(key);
            if (key.length() < pickLen || (key.length() == pickLen && num < pickNum)) {
                pick = id;
                pickLen = key.length();
                pickNum = num;
            }
        }
        return pick;
    }

    private void handle(String sentence) {
        String text = sentence.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return;

        // Có chữ "youtube": bỏ mọi từ trước nó, phần sau là từ khóa tìm kiếm.
        Matcher m = YOUTUBE.matcher(text);
        if (m.find()) {
            String query = text.substring(m.end()).trim();
            if (query.isEmpty()) openSmartTube();
            else searchYoutube(query);
            return;
        }

        // Không có "youtube": nếu có tên kênh thì mở kênh đó.
        String channel = findChannel(text);
        if (channel != null) {
            openChannel(channel);
            return;
        }

        // Không khớp kênh nào: tìm kiếm cả câu trên YouTube.
        searchYoutube(text);
    }

    private void openSmartTube() {
        for (String pkg : SMARTTUBE) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        Toast.makeText(this, "Chưa cài SmartTube", Toast.LENGTH_LONG).show();
    }

    private void searchYoutube(String query) {
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
        for (String pkg : SMARTTUBE) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(this, "Chưa cài SmartTube", Toast.LENGTH_LONG).show();
    }

    private void openChannel(String id) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("content://" + FPT + "/SWITCH_CHANNEL/" + id)).setPackage(FPT));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Chưa cài FPT Play", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onReadyForSpeech(Bundle p) { status.setText("Đang nghe…"); }
    @Override public void onPartialResults(Bundle b) { update(b); }

    @Override
    public void onResults(Bundle b) {
        update(b);
        // Kết quả cuối cùng: chạy lệnh ngay, bộ đếm chỉ là dự phòng.
        if (!latest.isEmpty()) {
            handler.removeCallbacks(fire);
            execute();
        }
    }

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
