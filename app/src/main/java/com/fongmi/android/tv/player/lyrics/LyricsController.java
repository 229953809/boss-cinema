package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.custom.LyricsOverlayView;
import com.github.catvod.utils.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class LyricsController {

    private static final List<String> AUDIO_EXTS = List.of(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".ape", ".wma", ".alac", ".amr", ".mka");

    private final LyricsRepository repository = new LyricsRepository();
    private final LyricsOverlayView view;
    private List<LyricsLine> lines = Collections.emptyList();
    private String loadingSignature;
    private String activeSignature;
    private String emptySignature;
    private int sequence;

    public interface Callback {
        void onResult(LyricsResult result);
    }

    public interface SearchCallback {
        void onResult(List<LyricsResult> results, boolean complete);
    }

    public LyricsController(LyricsOverlayView view) {
        this.view = view;
    }

    public void refresh(PlayerManager player) {
        refresh(player, isAudioOnly(player));
    }

    public void refresh(PlayerManager player, boolean audioOnly) {
        if (player == null || !audioOnly) {
            clear();
            return;
        }
        LyricsRequest request = LyricsRequest.from(player);
        if (!request.isValid()) {
            clear();
            return;
        }
        String signature = request.signature();
        if (signature.equals(activeSignature) || signature.equals(loadingSignature) || signature.equals(emptySignature)) return;
        loadingSignature = signature;
        activeSignature = null;
        lines = Collections.emptyList();
        view.clear();
        int current = ++sequence;
        repository.loadPreferWord(request, result -> {
            if (current != sequence) return;
            loadingSignature = null;
            if (result == null || !result.isValid()) {
                emptySignature = signature;
                view.clear();
                return;
            }
            ArrayList<LyricsLine> parsed = new ArrayList<>(result.getLines(request.getDurationMs()));
            if (parsed.isEmpty()) {
                emptySignature = signature;
                view.clear();
                return;
            }
            activeSignature = signature;
            lines = parsed;
            view.setLyrics(result, parsed);
            update(player);
        });
    }

    public boolean hasChoice(PlayerManager player) {
        if (player == null) return false;
        LyricsRequest request = LyricsRequest.from(player);
        return request.isValid() && repository.hasChoice(request);
    }

    public void reload(PlayerManager player, boolean audioOnly, String keyword, Callback callback) {
        if (player == null || !audioOnly) {
            clear();
            if (callback != null) callback.onResult(null);
            return;
        }
        LyricsRequest request = LyricsRequest.from(player).withKeyword(keyword);
        if (!request.isValid()) {
            clear();
            if (callback != null) callback.onResult(null);
            return;
        }
        String signature = request.signature();
        loadingSignature = signature;
        activeSignature = null;
        emptySignature = null;
        lines = Collections.emptyList();
        view.clear();
        int current = ++sequence;
        repository.loadPreferWord(request, true, result -> {
            if (current != sequence) return;
            loadingSignature = null;
            if (result == null || !result.isValid()) {
                emptySignature = signature;
                view.clear();
                if (callback != null) callback.onResult(null);
                return;
            }
            ArrayList<LyricsLine> parsed = new ArrayList<>(result.getLines(request.getDurationMs()));
            if (parsed.isEmpty()) {
                emptySignature = signature;
                view.clear();
                if (callback != null) callback.onResult(null);
                return;
            }
            activeSignature = signature;
            lines = parsed;
            view.setLyrics(result, parsed);
            update(player);
            if (callback != null) callback.onResult(result);
        });
    }

    public void search(PlayerManager player, boolean audioOnly, String keyword, SearchCallback callback) {
        if (player == null || !audioOnly) {
            if (callback != null) callback.onResult(Collections.emptyList(), true);
            return;
        }
        LyricsRequest request = LyricsRequest.from(player).withKeyword(keyword);
        if (!request.isValid()) {
            if (callback != null) callback.onResult(Collections.emptyList(), true);
            return;
        }
        repository.search(request, (results, complete) -> {
            if (callback != null) callback.onResult(results, complete);
        });
    }

    public void apply(PlayerManager player, LyricsResult result, boolean remember, Callback callback) {
        if (player == null || result == null || !result.isValid()) {
            if (callback != null) callback.onResult(null);
            return;
        }
        LyricsRequest request = LyricsRequest.from(player);
        ArrayList<LyricsLine> parsed = new ArrayList<>(result.getLines(request.getDurationMs()));
        if (parsed.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }
        int current = ++sequence;
        loadingSignature = null;
        emptySignature = null;
        activeSignature = request.signature();
        lines = parsed;
        view.setLyrics(result, parsed);
        update(player);
        if (remember) repository.remember(request, result);
        if (current == sequence && callback != null) callback.onResult(result);
    }

    public boolean setInlineLyrics(String signature, String title, String artist, String lyrics, long durationMs, long positionMs) {
        if (!hasTimedLyrics(lyrics)) return false;
        String key = Util.md5(TextUtils.join("|", new String[]{safe(signature), safe(title), safe(artist), String.valueOf(lyrics.hashCode())}));
        if (key.equals(activeSignature) && !lines.isEmpty()) {
            update(positionMs);
            return true;
        }
        int current = ++sequence;
        LyricsResult result = new LyricsResult("Detail", title, artist, "", lyrics, durationMs, true, 100);
        ArrayList<LyricsLine> parsed = new ArrayList<>(result.getLines(durationMs));
        if (current != sequence || parsed.isEmpty()) {
            clear();
            return false;
        }
        loadingSignature = null;
        emptySignature = null;
        activeSignature = key;
        lines = parsed;
        view.setLyrics(result, parsed);
        update(positionMs);
        if (!result.hasWordTiming()) upgradeInlineLyrics(current, key, signature, title, artist, durationMs, positionMs);
        return true;
    }

    private void upgradeInlineLyrics(int current, String inlineKey, String signature, String title, String artist, long durationMs, long positionMs) {
        LyricsRequest request = new LyricsRequest(signature, "", title, artist, "", durationMs);
        if (!request.isValid()) return;
        String requestSignature = request.signature();
        if (requestSignature.equals(loadingSignature) || requestSignature.equals(emptySignature)) return;
        loadingSignature = requestSignature;
        repository.loadPreferWord(request, result -> {
            if (current != sequence || !inlineKey.equals(activeSignature)) return;
            if (requestSignature.equals(loadingSignature)) loadingSignature = null;
            if (result == null || !result.isValid() || !result.hasWordTiming()) {
                emptySignature = requestSignature;
                return;
            }
            ArrayList<LyricsLine> parsed = new ArrayList<>(result.getLines(durationMs));
            if (parsed.isEmpty() || !hasWordTiming(parsed)) {
                emptySignature = requestSignature;
                return;
            }
            emptySignature = null;
            lines = parsed;
            view.setLyrics(result, parsed);
            update(positionMs);
        });
    }

    public void update(long positionMs) {
        if (lines.isEmpty()) return;
        view.update(adjust(positionMs));
    }

    public void update(PlayerManager player) {
        if (player == null || lines.isEmpty()) return;
        view.update(adjust(player.getPosition()), player.isPlaying());
    }

    public List<LyricsLine> getLines() {
        return lines;
    }

    public void clear() {
        sequence++;
        loadingSignature = null;
        activeSignature = null;
        emptySignature = null;
        lines = Collections.emptyList();
        view.clear();
    }

    public void release() {
        clear();
    }

    public static boolean isAudioOnly(PlayerManager player) {
        if (player == null || player.isEmpty()) return false;
        if (player.haveTrack(C.TRACK_TYPE_VIDEO)) return false;
        if (player.haveTrack(C.TRACK_TYPE_AUDIO)) return true;
        return isAudioUrl(player.getUrl());
    }

    public static boolean isAudioContent(PlayerManager player) {
        if (player == null || player.isEmpty()) return false;
        return isAudioOnly(player) || isAudioUrl(player.getUrl()) || isMusicLike(player);
    }

    public static boolean isMusicLike(PlayerManager player) {
        if (player == null) return false;
        StringBuilder builder = new StringBuilder();
        append(builder, player.getKey());
        append(builder, player.getUrl());
        MediaMetadata metadata = player.getMetadata();
        if (metadata != null) {
            append(builder, metadata.title);
            append(builder, metadata.artist);
            append(builder, metadata.albumTitle);
        }
        return isMusicLikeText(builder.toString());
    }

    public static boolean isMusicLikeText(String text) {
        String lower = safe(text).toLowerCase(Locale.ROOT);
        return lower.contains("音乐") || lower.contains("音樂")
                || lower.contains("music") || lower.contains("song")
                || lower.contains("歌曲") || lower.contains("歌单") || lower.contains("歌單")
                || lower.contains("聽歌") || lower.contains("听歌")
                || lower.contains("有声") || lower.contains("有聲")
                || lower.contains("听书") || lower.contains("聽書")
                || lower.contains("audiobook") || lower.contains("audio book")
                || lower.contains("podcast") || lower.contains("播客")
                || lower.contains("电台") || lower.contains("電台") || lower.contains("radio");
    }

    public static boolean hasTimedLyrics(String text) {
        return LyricsParser.hasTimedLine(text);
    }

    private static boolean isAudioUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String path = url;
        try {
            Uri uri = Uri.parse(url);
            if (!TextUtils.isEmpty(uri.getPath())) path = uri.getPath();
        } catch (Exception ignored) {
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTS) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static void append(StringBuilder builder, CharSequence text) {
        if (TextUtils.isEmpty(text)) return;
        if (builder.length() > 0) builder.append(' ');
        builder.append(text);
    }

    private static boolean hasWordTiming(List<LyricsLine> lines) {
        for (LyricsLine line : lines) if (line.hasWords()) return true;
        return false;
    }

    private static long adjust(long positionMs) {
        return Math.max(0, positionMs + PlayerSetting.getLyricsTimeOffsetMs());
    }
}
