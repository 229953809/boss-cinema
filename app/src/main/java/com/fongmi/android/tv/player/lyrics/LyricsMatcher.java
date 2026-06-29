package com.fongmi.android.tv.player.lyrics;

import android.text.TextUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LyricsMatcher {

    private static final int MIN_SCORE = 55;

    public LyricsResult best(LyricsRequest request, List<LrcLibClient.Entry> entries) {
        List<LyricsResult> results = all(request, entries, 1);
        return results.isEmpty() ? null : results.get(0);
    }

    public List<LyricsResult> all(LyricsRequest request, List<LrcLibClient.Entry> entries, int limit) {
        ArrayList<LyricsResult> results = new ArrayList<>();
        ArrayList<ScoredEntry> scored = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (LrcLibClient.Entry entry : entries) {
            if (entry == null || !seen.add(entry.id)) continue;
            int score = score(request, entry);
            if (score < MIN_SCORE) continue;
            scored.add(new ScoredEntry(entry, score));
        }
        scored.sort(Comparator.comparingInt((ScoredEntry item) -> item.score).reversed());
        for (ScoredEntry item : scored) {
            LyricsResult result = toResult(item.entry, item.score);
            if (result == null) continue;
            results.add(result);
            if (results.size() >= Math.max(1, limit)) break;
        }
        return results;
    }

    private LyricsResult toResult(LrcLibClient.Entry entry, int score) {
        String synced = clean(entry.syncedLyrics);
        String plain = clean(entry.plainLyrics);
        boolean hasSynced = !TextUtils.isEmpty(synced) && LyricsParser.hasTimedLine(synced);
        String lyrics = hasSynced ? synced : plain;
        if (TextUtils.isEmpty(lyrics)) return null;
        return new LyricsResult("LRCLIB", clean(entry.trackName), clean(entry.artistName), clean(entry.albumName), lyrics, Math.round(entry.duration * 1000), hasSynced, score);
    }

    private int score(LyricsRequest request, LrcLibClient.Entry entry) {
        if (entry.instrumental) return Integer.MIN_VALUE;
        boolean hasSynced = !TextUtils.isEmpty(entry.syncedLyrics) && LyricsParser.hasTimedLine(entry.syncedLyrics);
        boolean hasPlain = !TextUtils.isEmpty(entry.plainLyrics);
        if (!hasSynced && !hasPlain) return Integer.MIN_VALUE;
        int score = hasSynced ? 18 : 4;
        score += textScore(request.getTitle(), entry.trackName, 55, 30, -45);
        if (!TextUtils.isEmpty(request.getArtist())) score += textScore(request.getArtist(), entry.artistName, 24, 12, -18);
        if (!TextUtils.isEmpty(request.getAlbum()) && !TextUtils.isEmpty(entry.albumName)) score += textScore(request.getAlbum(), entry.albumName, 10, 5, 0);
        score += durationScore(request.getDurationSec(), entry.duration);
        return score;
    }

    private int textScore(String wanted, String actual, int exact, int contains, int mismatch) {
        String a = normalize(wanted);
        String b = normalize(actual);
        if (TextUtils.isEmpty(a)) return 0;
        if (TextUtils.isEmpty(b)) return mismatch / 2;
        if (a.equals(b)) return exact;
        if (a.contains(b) || b.contains(a)) return contains;
        return mismatch;
    }

    private int durationScore(int wantedSec, double actualSec) {
        if (wantedSec <= 0 || actualSec <= 0) return 0;
        double delta = Math.abs(wantedSec - actualSec);
        if (delta <= 2) return 24;
        if (delta <= 5) return 20;
        if (delta <= 10) return 14;
        if (delta <= 20) return 4;
        if (delta <= 40) return -18;
        return -40;
    }

    public static String normalize(String text) {
        String value = clean(text).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return "";
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = value.replaceAll("\\.[a-z0-9]{2,5}$", "");
        value = value.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", "");
        value = value.replaceAll("(?i)official|music video|video|audio|lyrics|lyric|mv|flac|mp3|lossless|tv size|short ver\\.?|full ver\\.?|opening|ending|op|ed|feat\\.?|featuring", "");
        value = value.replaceAll("(?i)tvアニメ|テレビアニメ|アニメ|オープニング|エンディング|主題歌|挿入歌", "");
        value = value.replaceAll("[\\s_\\-.,:;!?/\\\\|+~`'\"#@$%^&*=<>，。！？、·：；“”‘’《》〈〉]+", "");
        return value.trim();
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static class ScoredEntry {
        private final LrcLibClient.Entry entry;
        private final int score;

        private ScoredEntry(LrcLibClient.Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
