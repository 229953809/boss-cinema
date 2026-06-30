package com.fongmi.android.tv.player.karaoke;

public class KaraokeScorer {

    private final KaraokeTrack track;
    private final KaraokeScoringConfig config;
    private long lastPositionMs = -1;
    private double totalWeightMs;
    private double hitWeightMs;
    private KaraokeScoreSnapshot snapshot;

    public KaraokeScorer(KaraokeTrack track) {
        this(track, KaraokeScoringConfig.DEFAULT);
    }

    public KaraokeScorer(KaraokeTrack track, KaraokeScoringConfig config) {
        this.track = track == null ? new KaraokeTrack("", "", 0, 0, null) : track;
        this.config = config == null ? KaraokeScoringConfig.DEFAULT : config;
        this.snapshot = new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
    }

    public KaraokeScoreSnapshot update(long positionMs, double frequencyHz, double volume, double confidence) {
        long adjustedPositionMs = Math.max(0, positionMs - config.getInputLatencyMs());
        Sample sample = sample(adjustedPositionMs, frequencyHz, volume, confidence);
        long sliceMs = nextSlice(adjustedPositionMs);
        if (sliceMs > 0 && sample.note != null && sample.note.isScored()) {
            double weight = sliceMs * sample.note.getType().getScoreWeight();
            totalWeightMs += weight;
            if (sample.hit) hitWeightMs += weight;
        }
        lastPositionMs = adjustedPositionMs;
        return snapshot = new KaraokeScoreSnapshot(totalWeightMs, hitWeightMs, sample.note, sample.sungMidi, sample.distanceSemitones, sample.voiced, sample.hit);
    }

    public KaraokeScoreSnapshot evaluate(long positionMs, double frequencyHz, double volume, double confidence) {
        long adjustedPositionMs = Math.max(0, positionMs - config.getInputLatencyMs());
        Sample sample = sample(adjustedPositionMs, frequencyHz, volume, confidence);
        return new KaraokeScoreSnapshot(totalWeightMs, hitWeightMs, sample.note, sample.sungMidi, sample.distanceSemitones, sample.voiced, sample.hit);
    }

    public KaraokeScoreSnapshot getSnapshot() {
        return snapshot;
    }

    public void reset() {
        lastPositionMs = -1;
        totalWeightMs = 0;
        hitWeightMs = 0;
        snapshot = new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
    }

    private long nextSlice(long positionMs) {
        if (lastPositionMs < 0) return 0;
        long delta = positionMs - lastPositionMs;
        if (delta <= 0 || delta > 2_000) return 0;
        return Math.min(delta, config.getMaxSliceMs());
    }

    private Sample sample(long positionMs, double frequencyHz, double volume, double confidence) {
        KaraokeNote note = track.findScoredNote(positionMs);
        boolean voiced = frequencyHz > 0 && volume >= config.getMinVolume() && confidence >= config.getMinConfidence();
        double sungMidi = voiced ? KaraokePitch.frequencyToMidi(frequencyHz) : Double.NaN;
        double distance = note != null && voiced ? KaraokePitch.semitoneDistance(sungMidi, note.getPitch(), config.isIgnoreOctave()) : Double.NaN;
        boolean hit = note != null && voiced && (!note.isPitchRequired() || Math.abs(distance) <= config.getToleranceSemitones());
        return new Sample(note, sungMidi, distance, voiced, hit);
    }

    private static class Sample {

        private final KaraokeNote note;
        private final double sungMidi;
        private final double distanceSemitones;
        private final boolean voiced;
        private final boolean hit;

        private Sample(KaraokeNote note, double sungMidi, double distanceSemitones, boolean voiced, boolean hit) {
            this.note = note;
            this.sungMidi = sungMidi;
            this.distanceSemitones = distanceSemitones;
            this.voiced = voiced;
            this.hit = hit;
        }
    }
}
