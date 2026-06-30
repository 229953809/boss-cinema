package com.fongmi.android.tv.player.karaoke;

public class KaraokeScoreSnapshot {

    private final double totalWeightMs;
    private final double hitWeightMs;
    private final double voicedWeightMs;
    private final double currentComboMs;
    private final double bestComboMs;
    private final KaraokeNote targetNote;
    private final double sungMidi;
    private final double distanceSemitones;
    private final boolean voiced;
    private final boolean hit;

    public KaraokeScoreSnapshot(double totalWeightMs, double hitWeightMs, KaraokeNote targetNote, double sungMidi, double distanceSemitones, boolean voiced, boolean hit) {
        this(totalWeightMs, hitWeightMs, voiced ? totalWeightMs : 0, 0, 0, targetNote, sungMidi, distanceSemitones, voiced, hit);
    }

    public KaraokeScoreSnapshot(double totalWeightMs, double hitWeightMs, double voicedWeightMs, double currentComboMs, double bestComboMs, KaraokeNote targetNote, double sungMidi, double distanceSemitones, boolean voiced, boolean hit) {
        this.totalWeightMs = Math.max(0, totalWeightMs);
        this.hitWeightMs = Math.max(0, hitWeightMs);
        this.voicedWeightMs = Math.max(0, voicedWeightMs);
        this.currentComboMs = Math.max(0, currentComboMs);
        this.bestComboMs = Math.max(0, bestComboMs);
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

    public double getVoicedWeightMs() {
        return voicedWeightMs;
    }

    public int getVoicedPercent() {
        if (totalWeightMs <= 0) return 0;
        return (int) Math.round(Math.max(0, Math.min(100, voicedWeightMs * 100.0 / totalWeightMs)));
    }

    public int getCurrentComboSeconds() {
        return (int) Math.round(currentComboMs / 1000.0);
    }

    public int getBestComboSeconds() {
        return (int) Math.round(bestComboMs / 1000.0);
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

    public String getGrade() {
        return KaraokeGrade.fromScore(getScorePercent());
    }
}
