package com.fongmi.android.tv.player.karaoke;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.PermissionUtil;

import java.util.List;

public class KaraokeController implements KaraokeMicRecorder.Listener {

    private final KaraokeTrackRepository repository = new KaraokeTrackRepository();
    private final KaraokeMicRecorder mic = new KaraokeMicRecorder();
    private KaraokePitchSample sample = new KaraokePitchSample(0, 0, 0, 0);
    private KaraokeScoreSnapshot snapshot = new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
    private KaraokeTrack track;
    private KaraokeScorer scorer;
    private KaraokeFreeSingScorer freeScorer;
    private String signature;
    private boolean active;
    private boolean micUnavailable;
    private int sequence;

    public interface Listener {
        void onKaraokeUpdate(KaraokeStatus status, KaraokeTrack track, KaraokePitchSample sample, KaraokeScoreSnapshot snapshot);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public KaraokeTrack getTrack() {
        return track;
    }

    public KaraokePitchSample getSample() {
        return sample;
    }

    public KaraokeScoreSnapshot getSnapshot() {
        return snapshot;
    }

    public KaraokeResult getResult() {
        return KaraokeResult.from(track, snapshot);
    }

    public void refresh(FragmentActivity activity, PlayerManager player, boolean available) {
        boolean enabled = available && PlayerSetting.isKaraokeMode() && player != null && !player.isEmpty();
        if (!enabled) {
            clear();
            return;
        }
        active = true;
        micUnavailable = false;
        ensureMic(activity);
        String nextSignature = signatureOf(player);
        if (nextSignature.equals(signature)) return;
        signature = nextSignature;
        int current = ++sequence;
        track = null;
        scorer = null;
        freeScorer = new KaraokeFreeSingScorer(scoringConfig());
        snapshot = new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
        notifyUpdate();
        repository.load(player, result -> {
            if (current != sequence || !active) return;
            track = result;
            if (result != null && result.hasScoredNotes()) {
                scorer = new KaraokeScorer(result, scoringConfig());
                freeScorer = null;
            } else {
                scorer = null;
                freeScorer = new KaraokeFreeSingScorer(scoringConfig());
            }
            notifyUpdate();
        });
    }

    public void update(PlayerManager player) {
        update(player, null);
    }

    public void update(PlayerManager player, List<LyricsLine> lyrics) {
        if (!active || player == null || player.isEmpty()) return;
        if (scorer != null) snapshot = scorer.update(player.getPosition(), sample.getFrequencyHz(), sample.getVolume(), sample.getConfidence());
        else if (freeScorer != null) snapshot = freeScorer.update(adjustLyricsPosition(player), sample, lyrics);
        notifyUpdate();
    }

    public void clear() {
        sequence++;
        active = false;
        signature = null;
        track = null;
        scorer = null;
        freeScorer = null;
        micUnavailable = false;
        sample = new KaraokePitchSample(0, 0, 0, 0);
        snapshot = new KaraokeScoreSnapshot(0, 0, null, Double.NaN, Double.NaN, false, false);
        mic.stop();
        notifyUpdate();
    }

    public void release() {
        clear();
        listener = null;
    }

    @Override
    public void onPitch(KaraokePitchSample sample) {
        this.sample = sample == null ? new KaraokePitchSample(0, 0, 0, 0) : sample;
        micUnavailable = false;
        notifyUpdate();
    }

    @Override
    public void onError(Throwable error) {
        micUnavailable = true;
        notifyUpdate();
    }

    private void ensureMic(FragmentActivity activity) {
        if (mic.isRunning()) return;
        if (mic.hasPermission()) {
            micUnavailable = !mic.start(this);
            notifyUpdate();
            return;
        }
        if (activity == null) return;
        notifyUpdate();
        PermissionUtil.requestAudio(activity, granted -> {
            if (!active) return;
            if (granted) micUnavailable = !mic.start(this);
            notifyUpdate();
        });
    }

    private KaraokeScoringConfig scoringConfig() {
        return new KaraokeScoringConfig.Builder()
                .toleranceSemitones(PlayerSetting.getKaraokeToleranceSemitones())
                .inputLatencyMs(PlayerSetting.getKaraokeMicDelayMs())
                .build();
    }

    private void notifyUpdate() {
        if (listener != null) listener.onKaraokeUpdate(getStatus(), track, sample, snapshot);
    }

    private KaraokeStatus getStatus() {
        if (!active) return KaraokeStatus.INACTIVE;
        if (!mic.hasPermission()) return KaraokeStatus.NEED_PERMISSION;
        if (micUnavailable || !mic.isRunning()) return KaraokeStatus.MIC_UNAVAILABLE;
        return track != null && track.hasScoredNotes() ? KaraokeStatus.SCORING : KaraokeStatus.FREE_SING;
    }

    private static String signatureOf(PlayerManager player) {
        return safe(player.getKey()) + "|" + safe(player.getUrl()) + "|" + player.getDuration();
    }

    private static long adjustLyricsPosition(PlayerManager player) {
        return Math.max(0, player.getPosition() + PlayerSetting.getLyricsTimeOffsetMs());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
