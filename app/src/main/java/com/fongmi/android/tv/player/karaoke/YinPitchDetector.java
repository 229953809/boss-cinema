package com.fongmi.android.tv.player.karaoke;

public class YinPitchDetector {

    public static final int DEFAULT_SAMPLE_SIZE = 2048;

    private final int sampleRate;
    private final double threshold;
    private final double minConfidence;
    private final float[] yinBuffer;

    public YinPitchDetector(int sampleRate) {
        this(sampleRate, 0.1, 0.1, DEFAULT_SAMPLE_SIZE);
    }

    public YinPitchDetector(int sampleRate, double threshold, double minConfidence, int sampleSize) {
        this.sampleRate = Math.max(1, sampleRate);
        this.threshold = Math.max(0.01, Math.min(0.9, threshold));
        this.minConfidence = Math.max(0, Math.min(1, minConfidence));
        this.yinBuffer = new float[Math.max(64, sampleSize / 2)];
    }

    public int getSampleSize() {
        return yinBuffer.length * 2;
    }

    public KaraokePitchSample detect(float[] input, int length, long timestampMs) {
        int bufferSize = Math.min(length, getSampleSize());
        if (input == null || bufferSize < 128) return new KaraokePitchSample(timestampMs, 0, 0, 0);
        double volume = rms(input, bufferSize);
        Detection detection = detectFrequency(input, bufferSize);
        if (detection.confidence < minConfidence) detection = Detection.EMPTY;
        return new KaraokePitchSample(timestampMs, detection.frequencyHz, volume, detection.confidence);
    }

    private Detection detectFrequency(float[] input, int bufferSize) {
        int yinLength = Math.min(yinBuffer.length, bufferSize / 2);
        difference(input, yinLength);
        cumulativeMeanNormalizedDifference(yinLength);
        int tau = absoluteThreshold(yinLength);
        if (tau < 0) return Detection.EMPTY;
        double betterTau = parabolicInterpolation(tau, yinLength);
        if (betterTau <= 0) return Detection.EMPTY;
        double confidence = 1.0 - yinBuffer[tau];
        return new Detection(sampleRate / betterTau, confidence);
    }

    private void difference(float[] input, int yinLength) {
        for (int tau = 0; tau < yinLength; tau++) yinBuffer[tau] = 0;
        for (int tau = 1; tau < yinLength; tau++) {
            float sum = 0;
            for (int index = 0; index < yinLength; index++) {
                float delta = input[index] - input[index + tau];
                sum += delta * delta;
            }
            yinBuffer[tau] = sum;
        }
    }

    private void cumulativeMeanNormalizedDifference(int yinLength) {
        yinBuffer[0] = 1;
        float runningSum = 0;
        for (int tau = 1; tau < yinLength; tau++) {
            runningSum += yinBuffer[tau];
            yinBuffer[tau] = runningSum <= 0 ? 1 : yinBuffer[tau] * tau / runningSum;
        }
    }

    private int absoluteThreshold(int yinLength) {
        for (int tau = 2; tau < yinLength; tau++) {
            if (yinBuffer[tau] >= threshold) continue;
            while (tau + 1 < yinLength && yinBuffer[tau + 1] < yinBuffer[tau]) tau++;
            return tau;
        }
        return -1;
    }

    private double parabolicInterpolation(int tau, int yinLength) {
        int x0 = tau < 1 ? tau : tau - 1;
        int x2 = tau + 1 < yinLength ? tau + 1 : tau;
        if (x0 == tau) return yinBuffer[tau] <= yinBuffer[x2] ? tau : x2;
        if (x2 == tau) return yinBuffer[tau] <= yinBuffer[x0] ? tau : x0;
        double s0 = yinBuffer[x0];
        double s1 = yinBuffer[tau];
        double s2 = yinBuffer[x2];
        double denominator = 2 * (2 * s1 - s2 - s0);
        return Math.abs(denominator) < 1e-9 ? tau : tau + (s2 - s0) / denominator;
    }

    private static double rms(float[] input, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) sum += input[i] * input[i];
        return Math.min(1, Math.sqrt(sum / Math.max(1, length)));
    }

    private static class Detection {

        private static final Detection EMPTY = new Detection(0, 0);

        private final double frequencyHz;
        private final double confidence;

        private Detection(double frequencyHz, double confidence) {
            this.frequencyHz = frequencyHz;
            this.confidence = Math.max(0, Math.min(1, confidence));
        }
    }
}
