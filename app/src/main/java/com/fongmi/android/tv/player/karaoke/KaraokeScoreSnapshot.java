package com.fongmi.android.tv.player.karaoke;

public class KaraokeScoreSnapshot {

    private final double totalWeightMs;
    private final double hitWeightMs;
    private final KaraokeNote targetNote;
    private final double sungMidi;
    private final double distanceSemitones;
    private final boolean voiced;
    private final boolean hit;

    public KaraokeScoreSnapshot(double totalWeightMs, double hitWeightMs, KaraokeNote targetNote, double sungMidi, double distanceSemitones, boolean voiced, boolean hit) {
        this.totalWeightMs = Math.max(0, totalWeightMs);
        this.hitWeightMs = Math.max(0, hitWeightMs);
        this.targetNote = targetNote;
        this.sungMidi = sungMidi;
        this.distanceSemitones = distanceSemitones;
        this.voiced = voiced;
        this.hit = hit;
    }

    public double getTotalWeightMs() {
        return totalWeightMs;
    }

    public double getHitWeightMs() {
        return hitWeightMs;
    }

    public KaraokeNote getTargetNote() {
        return targetNote;
    }

    public double getSungMidi() {
        return sungMidi;
    }

    public int getNearestSungMidi() {
        return Double.isNaN(sungMidi) ? -1 : (int) Math.round(sungMidi);
    }

    public double getDistanceSemitones() {
        return distanceSemitones;
    }

    public boolean isVoiced() {
        return voiced;
    }

    public boolean isHit() {
        return hit;
    }

    public int getScorePercent() {
        if (totalWeightMs <= 0) return 0;
        return (int) Math.round(Math.max(0, Math.min(100, hitWeightMs * 100.0 / totalWeightMs)));
    }
}
