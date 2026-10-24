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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice launcher: listens for one sentence, then either
 *  - searches YouTube (SmartTube) when the sentence contains "youtube",
 *  - opens an FPT Play channel when the sentence is a channel name,
 *  - otherwise searches the whole sentence on YouTube.
 *
 * File layout:
 *  1. Constants and channel data
 *  2. ListenActivity (UI, permission, speech lifecycle)
 *  3. Command routing (handle)
 *  4. Nested helpers: Text, ChannelMatcher, AppLauncher, SimpleRecognitionListener
 */
public class ListenActivity extends Activity {

    // ---------------------------------------------------------------- constants

    private static final long SILENCE_MS = 1200;
    private static final long ERROR_CLOSE_MS = 1500;
    private static final int REQUEST_AUDIO = 1;
    private static final String LANGUAGE = "vi-VN";

    private static final Pattern YOUTUBE = Pattern.compile("(?i)\\byou\\s?tube\\b");

    // UI text
    private static final String TXT_STARTING = "Đang khởi động…";
    private static final String TXT_LISTENING = "Đang nghe…";
    private static final String TXT_HEARD = "Đã nghe…";
    private static final String TXT_NOT_UNDERSTOOD = "Không nghe rõ";
    private static final String TXT_CANCEL_HINT = "Nhấn Back để hủy";
    private static final String TXT_NO_RECOGNITION = "Nhận dạng giọng nói không khả dụng";

    // UI sizes
    private static final int PAD_H = 96, PAD_V = 48;
    private static final int SIZE_STATUS = 24, SIZE_HEARD = 40, SIZE_HINT = 20;

    // SmartTube package names
    private static final String[] SMARTTUBE = {
            "com.teamsmart.videomanager.tv",      // bản chính thức
            "com.liskovsoft.smarttubetv.beta"     // bản beta
    };
    private static final String FPT = "com.fptplay.launcher";

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
            "thai-nguyen", "thanh-hoa", "vinh-long-5", "tuyen-quang", "da-vinci-learning"
    };

    // ------------------------------------------------------------------- state

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable executeRunnable = this::executeNow;
    private final ChannelMatcher channelMatcher = new ChannelMatcher(CHANNELS);
    private final AppLauncher launcher = new AppLauncher(this);

    private SpeechRecognizer recognizer;
    private TextView statusView, heardView;
    private String latestTranscript = "";
    private boolean done;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        buildUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        done = true;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(PAD_H, PAD_V, PAD_H, PAD_V);

        statusView = createText(Color.LTGRAY, SIZE_STATUS);
        heardView = createText(Color.WHITE, SIZE_HEARD);
        heardView.setPadding(0, PAD_V, 0, PAD_V);
        TextView hint = createText(Color.GRAY, SIZE_HINT);
        hint.setText(TXT_CANCEL_HINT);

        root.addView(statusView);
        root.addView(heardView);
        root.addView(hint);
        setContentView(root);
        statusView.setText(TXT_STARTING);
    }

    private TextView createText(int color, int size) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ------------------------------------------------------------------ speech

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, TXT_NO_RECOGNITION, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(speechListener);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    private final RecognitionListener speechListener = new SimpleRecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            statusView.setText(TXT_LISTENING);
        }

        @Override
        public void onPartialResults(Bundle partial) {
            onTranscript(partial);
        }

        @Override
        public void onResults(Bundle results) {
            onTranscript(results);
            // Final result: run the command now; the silence timer is only a fallback.
            if (!latestTranscript.isEmpty()) executeNow();
        }

        @Override
        public void onError(int error) {
            if (done) return;
            if (latestTranscript.isEmpty()) {
                statusView.setText(TXT_NOT_UNDERSTOOD);
                handler.postDelayed(ListenActivity.this::finish, ERROR_CLOSE_MS);
            }
            // If something was already heard, the silence timer will run the command.
        }
    };

    /** Stores the best transcript and (re)starts the silence timer. */
    private void onTranscript(Bundle results) {
        if (done || results == null) return;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) return;

        latestTranscript = list.get(0).trim();
        heardView.setText("“" + latestTranscript + "”");
        statusView.setText(TXT_HEARD);
        handler.removeCallbacks(executeRunnable);
        handler.postDelayed(executeRunnable, SILENCE_MS);
    }

    private void executeNow() {
        if (done) return;
        done = true;
        handler.removeCallbacks(executeRunnable);
        if (recognizer != null) recognizer.cancel();
        handle(latestTranscript);
        finish();
    }

    // ----------------------------------------------------------------- routing

    private void handle(String sentence) {
        String text = Text.clean(sentence);
        if (text.isEmpty()) return;

        // 1) Contains "youtube": ignore everything before it, the rest is the search query.
        Matcher youtube = YOUTUBE.matcher(text);
        if (youtube.find()) {
            String query = text.substring(youtube.end()).trim();
            if (query.isEmpty()) launcher.openSmartTube();
            else launcher.searchYoutube(query);
            return;
        }

        // 2) Whole sentence is a channel name: open that channel.
        String channel = channelMatcher.find(text);
        if (channel != null) {
            launcher.openChannel(channel);
            return;
        }

        // 3) Fallback: search the whole sentence on YouTube.
        launcher.searchYoutube(text);
    }

    // ================================================================ helpers

    /** String helpers. */
    private static final class Text {
        private static final Pattern DIGITS = Pattern.compile("\\d+");

        /** Strips symbols and collapses whitespace, keeping letters (with accents) and digits. */
        static String clean(String s) {
            return s.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        }

        /** Lowercase, no accents, no symbols; joins "vtv 1" -> "vtv1". */
        static String norm(String s) {
            s = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replace('đ', 'd');
            s = s.replaceAll("[^a-z0-9]+", " ").trim();
            return s.replaceAll("([a-z])\\s+(\\d)", "$1$2");
        }

        static int firstNumber(String s) {
            Matcher m = DIGITS.matcher(s);
            return m.find() ? Integer.parseInt(m.group()) : Integer.MAX_VALUE;
        }
    }

    /** Maps a spoken sentence to an FPT Play channel id. */
    private static final class ChannelMatcher {
        /** Leading words dropped before matching: "mở kênh htv5" -> "htv5". */
        private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
                "mo", "bat", "xem", "kenh", "chuyen", "sang", "den", "qua",
                "len", "cho", "toi", "truyen", "hinh", "dai", "di", "nhe", "giup"));

        private final Map<String, String> keyToId = new LinkedHashMap<>();  // "vtv3" -> "vtv3-hd"

        ChannelMatcher(String[] ids) {
            for (String id : ids) {
                keyToId.putIfAbsent(keyOf(id), id);
            }
        }

        /**
         * Returns the channel id if the WHOLE sentence (minus leading filler) is a channel name,
         * either complete or the start of one ("ha noi" -> hanoitv-1). Shorter names win,
         * then the smaller channel number.
         */
        String find(String sentence) {
            String p = stripFiller(Text.norm(sentence));
            if (p.isEmpty()) return null;

            String best = null;
            for (String key : keyToId.keySet()) {
                if (startsAtWordBoundary(key, p) && (best == null || isBetter(key, best))) {
                    best = key;
                }
            }
            return best == null ? null : keyToId.get(best);
        }

        private static String keyOf(String id) {
            return Text.norm(id.replace("hanoitv-", "ha-noi-").replace("-hd", ""));
        }

        private static String stripFiller(String normalized) {
            String[] words = normalized.split(" ");
            int i = 0;
            while (i < words.length && FILLER.contains(words[i])) i++;
            return String.join(" ", Arrays.copyOfRange(words, i, words.length));
        }

        /** key starts with p, and p ends at a word or digit boundary of key. */
        private static boolean startsAtWordBoundary(String key, String p) {
            if (!key.startsWith(p)) return false;
            if (key.length() == p.length()) return true;
            char next = key.charAt(p.length());
            return next == ' ' || Character.isDigit(next);
        }

        private static boolean isBetter(String a, String b) {
            if (a.length() != b.length()) return a.length() < b.length();
            return Text.firstNumber(a) < Text.firstNumber(b);
        }
    }

    /** Starts SmartTube and FPT Play. */
    private static final class AppLauncher {
        private static final String TXT_NO_SMARTTUBE = "Chưa cài SmartTube";
        private static final String TXT_NO_FPT = "Chưa cài FPT Play";

        private final Activity activity;

        AppLauncher(Activity activity) {
            this.activity = activity;
        }

        void openSmartTube() {
            for (String pkg : SMARTTUBE) {
                Intent launch = launchIntentFor(pkg);
                if (launch != null) {
                    activity.startActivity(launch);
                    return;
                }
            }
            toast(TXT_NO_SMARTTUBE);
        }

        void searchYoutube(String query) {
            Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
            for (String pkg : SMARTTUBE) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // try the next SmartTube build
                }
            }
            toast(TXT_NO_SMARTTUBE);
        }

        void openChannel(String id) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("content://" + FPT + "/SWITCH_CHANNEL/" + id)).setPackage(FPT));
            } catch (ActivityNotFoundException | SecurityException e) {
                toast(TXT_NO_FPT);
            }
        }

        private Intent launchIntentFor(String pkg) {
            Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            return launch != null ? launch : activity.getPackageManager().getLaunchIntentForPackage(pkg);
        }

        private void toast(String message) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }

    /** RecognitionListener with empty defaults, so only the callbacks we need are overridden. */
    private abstract static class SimpleRecognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onError(int error) {}
        @Override public void onResults(Bundle results) {}
        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }
}
