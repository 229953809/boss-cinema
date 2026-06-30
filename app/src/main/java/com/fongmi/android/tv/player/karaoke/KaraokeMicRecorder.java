package com.fongmi.android.tv.player.karaoke;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.util.concurrent.atomic.AtomicBoolean;

public class KaraokeMicRecorder {

    private static final int SAMPLE_RATE = 44_100;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord recorder;
    private YinPitchDetector detector;
    private Listener listener;

    public interface Listener {
        void onPitch(KaraokePitchSample sample);

        default void onError(Throwable error) {
        }
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean start(Listener listener) {
        if (isRunning() || !hasPermission()) return false;
        this.listener = listener;
        try {
            detector = new YinPitchDetector(SAMPLE_RATE);
            recorder = createRecorder(detector.getSampleSize());
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                return false;
            }
            running.set(true);
            Task.execute(this::recordLoop);
            return true;
        } catch (Throwable e) {
            notifyError(e);
            stop();
            return false;
        }
    }

    public void stop() {
        running.set(false);
        releaseRecorder();
    }

    private void recordLoop() {
        short[] pcm = new short[detector.getSampleSize()];
        float[] buffer = new float[detector.getSampleSize()];
        try {
            recorder.startRecording();
            while (running.get()) {
                int read = recorder.read(pcm, 0, pcm.length);
                if (read <= 0) continue;
                for (int i = 0; i < read; i++) buffer[i] = pcm[i] / 32768f;
                KaraokePitchSample sample = detector.detect(buffer, read, System.currentTimeMillis());
                if (listener != null) App.post(() -> listener.onPitch(sample));
            }
        } catch (Throwable e) {
            notifyError(e);
        } finally {
            running.set(false);
            releaseRecorder();
        }
    }

    private AudioRecord createRecorder(int sampleSize) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(sampleSize * Short.BYTES * 2, minBuffer);
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT};
        for (int source : sources) {
            AudioRecord record = createRecorder(source, bufferSize);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) return record;
            if (record != null) record.release();
        }
        return null;
    }

    private AudioRecord createRecorder(int source, int bufferSize) {
        return new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();
    }

    private void releaseRecorder() {
        AudioRecord current = recorder;
        recorder = null;
        if (current == null) return;
        try {
            if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop();
        } catch (Exception ignored) {
        }
        current.release();
    }

    private void notifyError(Throwable error) {
        if (listener != null) App.post(() -> listener.onError(error));
    }
}
