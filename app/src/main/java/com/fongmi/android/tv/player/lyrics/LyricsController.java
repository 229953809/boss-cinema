package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;

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
        repository.load(request, result -> {
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

    private static boolean hasWordTiming(List<LyricsLine> lines) {
        for (LyricsLine line : lines) if (line.hasWords()) return true;
        return false;
    }

    private static long adjust(long positionMs) {
        return Math.max(0, positionMs + PlayerSetting.getLyricsTimeOffsetMs());
    }
}
