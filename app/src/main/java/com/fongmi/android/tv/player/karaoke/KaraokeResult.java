package com.fongmi.android.tv.player.karaoke;

public class KaraokeResult {

    private final boolean scoring;
    private final int scorePercent;
    private final int hitPercent;
    private final long totalMs;
    private final String trackLabel;

    private KaraokeResult(boolean scoring, int scorePercent, int hitPercent, long totalMs, String trackLabel) {
        this.scoring = scoring;
        this.scorePercent = scorePercent;
        this.hitPercent = hitPercent;
        this.totalMs = totalMs;
        this.trackLabel = trackLabel == null ? "" : trackLabel.trim();
    }

    public static KaraokeResult from(KaraokeTrack track, KaraokeScoreSnapshot snapshot) {
        if (snapshot == null) return empty(track);
        boolean scoring = track != null && track.hasScoredNotes();
        int score = snapshot.getScorePercent();
        int hit = snapshot.getTotalWeightMs() <= 0 ? 0 : (int) Math.round(Math.max(0, Math.min(100, snapshot.getHitWeightMs() * 100.0 / snapshot.getTotalWeightMs())));
        return new KaraokeResult(scoring, score, hit, Math.round(snapshot.getTotalWeightMs()), label(track));
    }

    public static KaraokeResult empty(KaraokeTrack track) {
        return new KaraokeResult(track != null && track.hasScoredNotes(), 0, 0, 0, label(track));
    }

    public boolean isScoring() {
        return scoring;
    }

    public int getScorePercent() {
        return scorePercent;
    }

    public int getHitPercent() {
        return hitPercent;
    }

    public long getTotalSeconds() {
        return Math.max(0, Math.round(totalMs / 1000.0));
    }

    public String getTrackLabel() {
        return trackLabel;
    }

    private static String label(KaraokeTrack track) {
        if (track == null) return "";
        String artist = track.getArtist();
        String title = track.getTitle();
        if (!artist.isEmpty() && !title.isEmpty()) return artist + " - " + title;
        if (!title.isEmpty()) return title;
        return artist;
    }
}
