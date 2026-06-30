package com.fongmi.android.tv.player.karaoke;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.List;

public class KaraokeFreeSingScorer {

    private static final long WARMUP_MS = 3000;
    private static final long MAX_LINE_MS = 8000;
    private static final long WORD_MARGIN_MS = 250;

    private final KaraokeScoringConfig config;
    private long lastPositionMs = -1;
    private long warmupUntilMs = WARMUP_MS;
    private long totalWeightMs;
    private double hitWeightMs;
    private int lineIndex = -1;
    private long lineActiveMs;
    private long lineVoicedMs;
    private double lastMidi = Double.NaN;
    private KaraokeScoreSnapshot snapshot;

    public KaraokeFreeSingScorer() {
        this(KaraokeScoringConfig.DEFAULT);
    }

    public KaraokeFreeSingScorer(KaraokeScoringConfig config) {
        this.config = config == null ? KaraokeScoringConfig.DEFAULT : config;
        this.snapshot = empty();
    }

    public KaraokeScoreSnapshot update(long positionMs, KaraokePitchSample sample, List<LyricsLine> lines) {
        long adjustedPositionMs = Math.max(0, positionMs - config.getInputLatencyMs());
        Window window = findWindow(lines, adjustedPositionMs);
        Sample current = sample(sample);
        long sliceMs = nextSlice(adjustedPositionMs);
        if (sliceMs > 0 && adjustedPositionMs >= warmupUntilMs && window.active) score(sliceMs, window, current);
        lastPositionMs = adjustedPositionMs;
        snapshot = new KaraokeScoreSnapshot(totalWeightMs, hitWeightMs, null, current.sungMidi, Double.NaN, current.voiced, current.voiced);
        return snapshot;
    }

    public KaraokeScoreSnapshot getSnapshot() {
        return snapshot;
    }

    public void reset() {
        lastPositionMs = -1;
        warmupUntilMs = WARMUP_MS;
        totalWeightMs = 0;
        hitWeightMs = 0;
        lineIndex = -1;
        lineActiveMs = 0;
        lineVoicedMs = 0;
        lastMidi = Double.NaN;
        snapshot = empty();
    }

    private void score(long sliceMs, Window window, Sample sample) {
        if (lineIndex != window.index) {
            lineIndex = window.index;
            lineActiveMs = 0;
            lineVoicedMs = 0;
            lastMidi = Double.NaN;
        }
        lineActiveMs += sliceMs;
        if (sample.voiced) lineVoicedMs += sliceMs;
        double score = freeScore(sample);
        totalWeightMs += sliceMs;
        hitWeightMs += sliceMs * score;
        if (sample.voiced) lastMidi = sample.sungMidi;
    }

    private double freeScore(Sample sample) {
        if (!sample.voiced) return 0;
        double coverage = 1.0;
        double rhythm = 1.0;
        double volume = volumeScore(sample.volume);
        double stability = stabilityScore(sample.sungMidi);
        double phrase = lineActiveMs <= 0 ? 0.5 : clamp01(lineVoicedMs / (double) lineActiveMs);
        return coverage * 0.35 + rhythm * 0.25 + volume * 0.20 + stability * 0.15 + phrase * 0.05;
    }

    private double volumeScore(double volume) {
        if (volume <= config.getMinVolume()) return 0;
        double score = volume / 0.16;
        if (volume > 0.86) score -= (volume - 0.86) * 1.5;
        return clamp01(score);
    }

    private double stabilityScore(double sungMidi) {
        if (Double.isNaN(lastMidi)) return 0.72;
        double diff = Math.abs(sungMidi - lastMidi);
        if (diff <= 0.75) return 1.0;
        if (diff <= 2.5) return 0.82;
        if (diff <= 5.0) return 0.55;
        return 0.28;
    }

    private long nextSlice(long positionMs) {
        if (lastPositionMs < 0) {
            warmupUntilMs = positionMs + WARMUP_MS;
            return 0;
        }
        long delta = positionMs - lastPositionMs;
        if (delta <= 0) return 0;
        if (delta > 2_000) {
            warmupUntilMs = positionMs + WARMUP_MS;
            lineIndex = -1;
            lineActiveMs = 0;
            lineVoicedMs = 0;
            lastMidi = Double.NaN;
            return 0;
        }
        return Math.min(delta, config.getMaxSliceMs());
    }

    private Sample sample(KaraokePitchSample sample) {
        boolean voiced = sample != null
                && sample.getFrequencyHz() > 0
                && sample.getVolume() >= config.getMinVolume()
                && sample.getConfidence() >= config.getMinConfidence();
        double sungMidi = voiced ? KaraokePitch.frequencyToMidi(sample.getFrequencyHz()) : Double.NaN;
        double volume = sample == null ? 0 : sample.getVolume();
        return new Sample(voiced, sungMidi, volume);
    }

    private Window findWindow(List<LyricsLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return Window.INACTIVE;
        int index = LyricsParser.findLine(lines, positionMs);
        if (index < 0 || index >= lines.size()) return Window.INACTIVE;
        LyricsLine line = lines.get(index);
        long lineStart = line.getTimeMs();
        long nextStart = index + 1 < lines.size() ? lines.get(index + 1).getTimeMs() : lineStart + MAX_LINE_MS;
        long start = lineStart;
        long end = nextStart;
        if (line.hasWords()) {
            List<LyricsWord> words = line.getWords();
            start = lineStart + Math.max(0, words.get(0).getStartOffsetMs() - WORD_MARGIN_MS);
            long wordEnd = 0;
            for (LyricsWord word : words) wordEnd = Math.max(wordEnd, word.getEndOffsetMs());
            end = lineStart + wordEnd + WORD_MARGIN_MS;
            if (nextStart > lineStart) end = Math.min(end, nextStart);
        } else {
            end = Math.min(end, lineStart + MAX_LINE_MS);
        }
        boolean active = positionMs >= start && positionMs < end && end > start + 300;
        return new Window(index, active);
    }

    private KaraokeScoreSnapshot empty() {
        return new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static class Sample {

        private final boolean voiced;
        private final double sungMidi;
        private final double volume;

        private Sample(boolean voiced, double sungMidi, double volume) {
            this.voiced = voiced;
            this.sungMidi = sungMidi;
            this.volume = volume;
        }
    }

    private static class Window {

        private static final Window INACTIVE = new Window(-1, false);

        private final int index;
        private final boolean active;

        private Window(int index, boolean active) {
            this.index = index;
            this.active = active;
        }
    }
}
