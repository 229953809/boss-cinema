package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.karaoke.KaraokeNote;
import com.fongmi.android.tv.player.karaoke.KaraokePitch;
import com.fongmi.android.tv.player.karaoke.KaraokePitchSample;
import com.fongmi.android.tv.player.karaoke.KaraokeScoreSnapshot;
import com.fongmi.android.tv.player.karaoke.KaraokeStatus;
import com.fongmi.android.tv.player.karaoke.KaraokeTrack;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

public class KaraokeStatusView extends LinearLayout {

    private final MaterialTextView title;
    private final MaterialTextView detail;
    private final NoteTimelineView timeline;
    private final VolumeMeterView volume;

    public KaraokeStatusView(Context context) {
        this(context, null);
    }

    public KaraokeStatusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(0, dp(2), 0, dp(2));
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);

        title = textView(context, 13, true);
        detail = textView(context, 12, false);
        detail.setMinHeight(dp(14));
        timeline = new NoteTimelineView(context);
        volume = new VolumeMeterView(context);
        int meterWidth = meterWidth();
        addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(detail, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams timelineParams = new LayoutParams(meterWidth, timelineHeight());
        timelineParams.topMargin = dp(7);
        addView(timeline, timelineParams);
        LayoutParams params = new LayoutParams(Math.max(dp(120), Math.round(meterWidth * 0.88f)), dp(12));
        params.topMargin = dp(6);
        addView(volume, params);
    }

    public void setState(KaraokeStatus status, KaraokeTrack track, KaraokePitchSample sample, KaraokeScoreSnapshot snapshot) {
        if (status == null || status == KaraokeStatus.INACTIVE) {
            setVisibility(PlayerSetting.isKaraokeMode() ? INVISIBLE : GONE);
            return;
        }
        setVisibility(VISIBLE);
        title.setText(getTitle(status, track, snapshot));
        String text = getDetail(status, sample, snapshot, track);
        detail.setText(text.isEmpty() ? " " : text);
        detail.setVisibility(text.isEmpty() ? INVISIBLE : VISIBLE);
        timeline.setState(track, snapshot);
        timeline.setVisibility(showTimeline(status, track, snapshot) ? VISIBLE : GONE);
        volume.setLevel(getVolumeLevel(status, sample));
        volume.setVisibility(showVolume(status) ? VISIBLE : GONE);
    }

    private String getTitle(KaraokeStatus status, KaraokeTrack track, KaraokeScoreSnapshot snapshot) {
        if (status == KaraokeStatus.SCORING) {
            if (track != null && !track.hasPitchRequiredNotes()) {
                if (snapshot != null && snapshot.getTotalWeightMs() > 0) return getResources().getString(R.string.player_karaoke_status_rhythm_score_grade, snapshot.getScorePercent(), snapshot.getGrade());
                return getResources().getString(R.string.player_karaoke_status_rhythm_score, 0);
            }
            if (snapshot != null && snapshot.getTotalWeightMs() > 0) return getResources().getString(R.string.player_karaoke_status_score_grade, snapshot.getScorePercent(), snapshot.getGrade());
            return getResources().getString(R.string.player_karaoke_status_score, 0);
        }
        if (status == KaraokeStatus.FREE_SING && snapshot != null && snapshot.getTotalWeightMs() > 0) return getResources().getString(R.string.player_karaoke_status_free_score_grade, snapshot.getScorePercent(), snapshot.getGrade());
        return getResources().getString(R.string.player_karaoke_status_free);
    }

    private String getDetail(KaraokeStatus status, KaraokePitchSample sample, KaraokeScoreSnapshot snapshot, KaraokeTrack track) {
        if (status == KaraokeStatus.NEED_PERMISSION) return getResources().getString(R.string.player_karaoke_need_permission);
        if (status == KaraokeStatus.MIC_UNAVAILABLE) return getResources().getString(R.string.player_karaoke_mic_unavailable);
        if (sample == null || sample.getTimestampMs() <= 0) return getResources().getString(R.string.player_karaoke_no_voice);
        if (!sample.isVoiced()) return "";
        int midi = KaraokePitch.frequencyToNearestMidi(sample.getFrequencyHz());
        String pitch = KaraokePitch.midiToName(midi);
        if (status == KaraokeStatus.SCORING && track != null && snapshot != null && snapshot.getTargetNote() != null) {
            if (!track.hasPitchRequiredNotes()) return appendStats(getResources().getString(R.string.player_karaoke_rhythm_detail, snapshot.getScorePercent()), snapshot);
            String state = snapshot.isHit()
                    ? getResources().getString(R.string.player_karaoke_pitch_hit)
                    : getResources().getString(R.string.player_karaoke_pitch_miss, Math.abs(snapshot.getDistanceSemitones()));
            return appendStats(getResources().getString(R.string.player_karaoke_pitch_score, pitch, state), snapshot);
        }
        if (status == KaraokeStatus.FREE_SING && snapshot != null && snapshot.getTotalWeightMs() > 0) {
            return appendStats(getResources().getString(R.string.player_karaoke_free_detail, pitch, snapshot.getScorePercent()), snapshot);
        }
        return getResources().getString(R.string.player_karaoke_pitch, pitch);
    }

    private String appendStats(String text, KaraokeScoreSnapshot snapshot) {
        if (snapshot == null) return text;
        String value = text;
        if (snapshot.isPerfect()) value += " · " + getResources().getString(R.string.player_karaoke_pitch_perfect);
        if (snapshot.hasVibrato()) value += " · " + getResources().getString(R.string.player_karaoke_pitch_vibrato);
        if (snapshot.getCurrentLineScorePercent() > 0) value += " · " + getResources().getString(R.string.player_karaoke_line_score, snapshot.getCurrentLineScorePercent());
        if (snapshot.getCurrentComboSeconds() >= 2) value += " · " + getResources().getString(R.string.player_karaoke_combo, snapshot.getCurrentComboSeconds());
        return value;
    }

    private boolean showVolume(KaraokeStatus status) {
        return status == KaraokeStatus.FREE_SING || status == KaraokeStatus.SCORING;
    }

    private boolean showTimeline(KaraokeStatus status, KaraokeTrack track, KaraokeScoreSnapshot snapshot) {
        return status == KaraokeStatus.SCORING
                && track != null
                && track.hasScoredNotes()
                && snapshot != null;
    }

    private float getVolumeLevel(KaraokeStatus status, KaraokePitchSample sample) {
        if (!showVolume(status) || sample == null || sample.getTimestampMs() <= 0) return 0;
        return (float) Math.max(0, Math.min(1, Math.sqrt(sample.getVolume()) * 1.6f));
    }

    private MaterialTextView textView(Context context, int sizeSp, boolean bold) {
        MaterialTextView view = new MaterialTextView(context);
        view.setSingleLine(true);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int meterWidth() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 720) return dp(340);
        if (widthDp >= 540 || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return dp(280);
        return dp(300);
    }

    private int timelineHeight() {
        return dp(isWideLayout() ? 86 : 74);
    }

    private boolean isWideLayout() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return widthDp >= 540 || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private class NoteTimelineView extends View {

        private static final long WINDOW_BEFORE_MS = 2200;
        private static final long WINDOW_AFTER_MS = 4200;
        private static final int HISTORY_COUNT = 72;
        private static final float MIN_PITCH_SPAN = 14f;
        private static final float PITCH_PADDING = 2f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final long[] historyTime = new long[HISTORY_COUNT];
        private final float[] historyPitch = new float[HISTORY_COUNT];
        private KaraokeTrack track;
        private KaraokeScoreSnapshot snapshot;
        private PitchScale pitchScale = defaultPitchScale();
        private int historySize;
        private long lastHistoryPosition = -1;
        private float lastHistoryPitch = Float.NaN;
        private long smoothBasePosition = -1;
        private long smoothBaseRealtime;

        private NoteTimelineView(Context context) {
            super(context);
        }

        private void setState(KaraokeTrack track, KaraokeScoreSnapshot snapshot) {
            if (this.track != track) {
                clearHistory();
                pitchScale = pitchScaleFrom(track);
            }
            this.track = track;
            this.snapshot = snapshot;
            updateSmoothBase(snapshot);
            appendHistory(snapshot);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (track == null || snapshot == null || track.isEmpty()) return;
            float left = dp(2);
            float right = getWidth() - dp(2);
            float top = dp(3);
            float bottom = getHeight() - dp(3);
            long position = drawPosition();
            long start = Math.max(0, position - WINDOW_BEFORE_MS);
            long end = position + WINDOW_AFTER_MS;
            boolean pitchTrack = track.hasPitchRequiredNotes();
            drawBackground(canvas, left, right, top, bottom);
            drawNotes(canvas, left, right, top, bottom, start, end);
            if (pitchTrack) drawHistory(canvas, left, right, top, bottom, start, end);
            drawCursor(canvas, left, right, top, bottom);
            if (pitchTrack) drawSungMarker(canvas, left, right, top, bottom);
            if (pitchTrack) drawHitEffects(canvas, left, right, top, bottom);
            postInvalidateOnAnimation();
        }

        private void drawBackground(Canvas canvas, float left, float right, float top, float bottom) {
            rect.set(left, top, right, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x18FFFFFF);
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x20FFFFFF);
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        }

        private void drawNotes(Canvas canvas, float left, float right, float top, float bottom, long start, long end) {
            List<KaraokeNote> notes = track.getNotes();
            long position = drawPosition();
            drawTargetGuide(canvas, notes, left, right, top, bottom, start, end);
            for (KaraokeNote note : notes) {
                if (!note.isScored() || note.getEndMs() < start || note.getStartMs() > end) continue;
                float x1 = xOf(note.getStartMs(), start, end, left, right);
                float x2 = xOf(note.getEndMs(), start, end, left, right);
                float y = yOf(note.isPitchRequired() ? note.getPitch() : pitchScale.center(), top, bottom);
                drawNote(canvas, note, position, Math.max(left, x1), Math.min(right, Math.max(x2, x1 + dp(2))), y);
            }
        }

        private void drawTargetGuide(Canvas canvas, List<KaraokeNote> notes, float left, float right, float top, float bottom, long start, long end) {
            if (!track.hasPitchRequiredNotes()) return;
            path.reset();
            boolean started = false;
            KaraokeNote last = null;
            for (KaraokeNote note : notes) {
                if (!note.isScored() || !note.isPitchRequired() || note.getEndMs() < start || note.getStartMs() > end) continue;
                float x1 = xOf(note.getStartMs(), start, end, left, right);
                float x2 = xOf(note.getEndMs(), start, end, left, right);
                float y = yOf(note.getPitch(), top, bottom);
                boolean join = last != null && Math.abs(last.getPitch() - note.getPitch()) <= 1 && note.getStartMs() - last.getEndMs() <= 220;
                if (!started || !join) {
                    path.moveTo(Math.max(left, x1), y);
                    started = true;
                } else {
                    path.lineTo(Math.max(left, x1), y);
                }
                path.lineTo(Math.min(right, Math.max(x2, x1 + dp(2))), y);
                last = note;
            }
            if (path.isEmpty()) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(2.4f));
            paint.setColor(0x42FFFFFF);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawNote(Canvas canvas, KaraokeNote note, long position, float x1, float x2, float y) {
            boolean current = note.contains(position);
            boolean scoredCurrent = note == snapshot.getTargetNote();
            boolean golden = isGolden(note);
            float h = note.isPitchRequired() ? dp(isWideLayout() ? 5 : 4) : dp(isWideLayout() ? 7 : 6);
            float radius = h / 2f;
            if (golden && current) drawGoldenGlow(canvas, x1, x2, y, h);
            if (current) drawCurrentGlow(canvas, x1, x2, y, h, scoredCurrent);
            rect.set(x1, y - h / 2f, x2, y + h / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(noteColor(note, current, position));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (current) drawCurrentStroke(canvas, x1, x2, y, h, scoredCurrent);
        }

        private void drawGoldenGlow(Canvas canvas, float x1, float x2, float y, float h) {
            float inset = dp(2);
            rect.set(x1 - inset, y - h / 2f - inset, x2 + inset, y + h / 2f + inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33FBBF24);
            canvas.drawRoundRect(rect, h, h, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xAAFBBF24);
            canvas.drawRoundRect(rect, h, h, paint);
        }

        private void drawCurrentGlow(Canvas canvas, float x1, float x2, float y, float h, boolean scoredCurrent) {
            float inset = dp(3);
            rect.set(x1 - inset, y - h / 2f - inset, x2 + inset, y + h / 2f + inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(activeColor(scoredCurrent, 0x2634D399, 0x26FBBF24, 0x20FFFFFF));
            canvas.drawRoundRect(rect, h, h, paint);
        }

        private void drawCurrentStroke(Canvas canvas, float x1, float x2, float y, float h, boolean scoredCurrent) {
            float inset = dp(1);
            rect.set(x1 - inset, y - h / 2f - inset, x2 + inset, y + h / 2f + inset);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(activeColor(scoredCurrent, 0xFF7EE7D6, 0xFFFBBF24, 0xD9FFFFFF));
            canvas.drawRoundRect(rect, h, h, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawHistory(Canvas canvas, float left, float right, float top, float bottom, long start, long end) {
            path.reset();
            boolean started = false;
            for (int i = 0; i < historySize; i++) {
                long time = historyTime[i];
                if (time < start || time > end) {
                    started = false;
                    continue;
                }
                float x = xOf(time, start, end, left, right);
                float y = yOf(historyPitch[i], top, bottom);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            if (path.isEmpty()) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xEE7EE7D6);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawCursor(Canvas canvas, float left, float right, float top, float bottom) {
            float x = left + (right - left) * WINDOW_BEFORE_MS / (float) (WINDOW_BEFORE_MS + WINDOW_AFTER_MS);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99FFFFFF);
            canvas.drawRect(x - dp(0.7f), top, x + dp(0.7f), bottom, paint);
        }

        private void drawSungMarker(Canvas canvas, float left, float right, float top, float bottom) {
            if (!snapshot.isVoiced() || Double.isNaN(snapshot.getSungMidi())) return;
            double midi = snapshot.getSungMidi();
            if (snapshot.getTargetNote() != null && !Double.isNaN(snapshot.getDistanceSemitones())) midi = snapshot.getTargetNote().getPitch() + snapshot.getDistanceSemitones();
            float x = left + (right - left) * WINDOW_BEFORE_MS / (float) (WINDOW_BEFORE_MS + WINDOW_AFTER_MS);
            float y = yOf((float) midi, top, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(snapshot.isHit() ? 0xFF7EE7D6 : 0xFFFBBF24);
            canvas.drawCircle(x, y, dp(3.6f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xD9000000);
            canvas.drawCircle(x, y, dp(3.6f), paint);
        }

        private void drawHitEffects(Canvas canvas, float left, float right, float top, float bottom) {
            KaraokeNote note = snapshot.getTargetNote();
            if (note == null || (!snapshot.isHit() && !snapshot.isPerfect() && !snapshot.hasVibrato())) return;
            float x = left + (right - left) * WINDOW_BEFORE_MS / (float) (WINDOW_BEFORE_MS + WINDOW_AFTER_MS);
            float y = yOf(note.isPitchRequired() ? note.getPitch() : pitchScale.center(), top, bottom);
            long now = SystemClock.elapsedRealtime();
            float phase = (now % 700) / 700f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.1f));
            paint.setColor(snapshot.isPerfect() ? 0xBFFBBF24 : 0x9934D399);
            canvas.drawCircle(x, y, dp(5.5f + phase * 5.5f), paint);
            if (snapshot.isPerfect() || snapshot.hasVibrato()) drawSparkles(canvas, x, y, now, snapshot.hasVibrato());
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSparkles(Canvas canvas, float x, float y, long now, boolean vibrato) {
            int count = vibrato ? 7 : 5;
            float phase = (now % 900) / 900f;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2 * (i / (double) count + phase * (vibrato ? 0.7 : 0.35));
                float radius = dp(vibrato ? 14 : 12) + dp(3) * (float) Math.sin(phase * Math.PI * 2 + i);
                float sx = x + (float) Math.cos(angle) * radius;
                float sy = y + (float) Math.sin(angle) * radius * 0.56f;
                paint.setColor(vibrato && i % 2 == 0 ? 0xCC2DD4BF : 0xDDFBBF24);
                canvas.drawCircle(sx, sy, dp(i % 3 == 0 ? 1.8f : 1.25f), paint);
            }
        }

        private void appendHistory(KaraokeScoreSnapshot snapshot) {
            if (snapshot == null) {
                clearHistory();
                return;
            }
            long position = snapshot.getPositionMs();
            if (lastHistoryPosition >= 0 && Math.abs(position - lastHistoryPosition) > 2_000) clearHistory();
            if (!snapshot.isVoiced() || Double.isNaN(snapshot.getSungMidi())) {
                lastHistoryPosition = position;
                return;
            }
            if (lastHistoryPosition >= 0 && Math.abs(position - lastHistoryPosition) < 70) return;
            float pitch = (float) snapshot.getSungMidi();
            if (snapshot.getTargetNote() != null && !Double.isNaN(snapshot.getDistanceSemitones())) pitch = (float) (snapshot.getTargetNote().getPitch() + snapshot.getDistanceSemitones());
            if (historySize == HISTORY_COUNT) {
                System.arraycopy(historyTime, 1, historyTime, 0, HISTORY_COUNT - 1);
                System.arraycopy(historyPitch, 1, historyPitch, 0, HISTORY_COUNT - 1);
                historySize--;
            }
            historyTime[historySize] = position;
            historyPitch[historySize] = smoothHistoryPitch(pitch);
            historySize++;
            lastHistoryPosition = position;
        }

        private float smoothHistoryPitch(float pitch) {
            if (Float.isNaN(lastHistoryPitch) || Math.abs(pitch - lastHistoryPitch) > 5f) {
                lastHistoryPitch = pitch;
            } else {
                lastHistoryPitch = lastHistoryPitch * 0.72f + pitch * 0.28f;
            }
            return lastHistoryPitch;
        }

        private void clearHistory() {
            historySize = 0;
            lastHistoryPosition = -1;
            lastHistoryPitch = Float.NaN;
            smoothBasePosition = -1;
            smoothBaseRealtime = 0;
        }

        private void updateSmoothBase(KaraokeScoreSnapshot snapshot) {
            if (snapshot == null) {
                smoothBasePosition = -1;
                smoothBaseRealtime = 0;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long position = snapshot.getPositionMs();
            long current = drawPosition(now);
            if (smoothBasePosition < 0 || Math.abs(position - current) > 1500) {
                smoothBasePosition = position;
            } else {
                smoothBasePosition = Math.max(position, current);
            }
            smoothBaseRealtime = now;
        }

        private long drawPosition() {
            return drawPosition(SystemClock.elapsedRealtime());
        }

        private long drawPosition(long now) {
            if (snapshot == null) return 0;
            if (smoothBasePosition < 0) return snapshot.getPositionMs();
            long elapsed = Math.max(0, Math.min(260, now - smoothBaseRealtime));
            return smoothBasePosition + elapsed;
        }

        private float xOf(long timeMs, long start, long end, float left, float right) {
            float value = (timeMs - start) / (float) Math.max(1, end - start);
            return left + (right - left) * Math.max(0, Math.min(1, value));
        }

        private float yOf(float pitch, float top, float bottom) {
            float innerTop = top + dp(5);
            float innerBottom = bottom - dp(5);
            float value = (pitch - pitchScale.min) / Math.max(1f, pitchScale.max - pitchScale.min);
            value = Math.max(0f, Math.min(1f, value));
            return innerBottom - value * Math.max(1f, innerBottom - innerTop);
        }

        private int noteColor(KaraokeNote note, boolean current, long position) {
            if (note.getEndMs() < position) return 0x338E9BAA;
            if (!note.isPitchRequired()) return 0x6638BDF8;
            if (current) return 0xE6FFFFFF;
            return 0x72FFFFFF;
        }

        private int activeColor(boolean scoredCurrent, int hit, int miss, int pending) {
            if (!scoredCurrent || !snapshot.isVoiced()) return pending;
            return snapshot.isHit() ? hit : miss;
        }

        private boolean isGolden(KaraokeNote note) {
            return note.getType().getScoreWeight() > 1.0;
        }

        private PitchScale defaultPitchScale() {
            return new PitchScale(60f - MIN_PITCH_SPAN / 2f, 60f + MIN_PITCH_SPAN / 2f);
        }

        private PitchScale pitchScaleFrom(KaraokeTrack track) {
            if (track == null || !track.hasPitchRequiredNotes()) return defaultPitchScale();
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (KaraokeNote note : track.getNotes()) {
                if (!note.isScored() || !note.isPitchRequired()) continue;
                min = Math.min(min, note.getPitch());
                max = Math.max(max, note.getPitch());
            }
            if (min == Integer.MAX_VALUE || max == Integer.MIN_VALUE) return defaultPitchScale();
            float low = min - PITCH_PADDING;
            float high = max + PITCH_PADDING;
            float span = high - low;
            if (span < MIN_PITCH_SPAN) {
                float center = (low + high) / 2f;
                low = center - MIN_PITCH_SPAN / 2f;
                high = center + MIN_PITCH_SPAN / 2f;
            }
            return new PitchScale(low, high);
        }

        private class PitchScale {

            private final float min;
            private final float max;

            private PitchScale(float min, float max) {
                this.min = min;
                this.max = Math.max(min + 1f, max);
            }

            private float center() {
                return (min + max) / 2f;
            }
        }
    }

    private static class VolumeMeterView extends View {

        private static final int BAR_COUNT = 18;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] bars = new float[BAR_COUNT];
        private float level;

        private VolumeMeterView(Context context) {
            super(context);
        }

        private void setLevel(float level) {
            float next = Math.max(0, Math.min(1, level));
            this.level = this.level * 0.55f + next * 0.45f;
            System.arraycopy(bars, 1, bars, 0, bars.length - 1);
            bars[bars.length - 1] = this.level;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float gap = Math.max(1f, getWidth() / 90f);
            float barWidth = (getWidth() - gap * (BAR_COUNT - 1)) / BAR_COUNT;
            float radius = Math.max(1f, barWidth / 2f);
            for (int i = 0; i < BAR_COUNT; i++) drawBar(canvas, i, barWidth, gap, radius);
        }

        private void drawBar(Canvas canvas, int index, float barWidth, float gap, float radius) {
            float value = bars[index];
            float idle = 0.08f + (index % 4) * 0.015f;
            float left = index * (barWidth + gap);
            float idleHeight = getHeight() * idle;
            rect.set(left, getHeight() - idleHeight, left + barWidth, getHeight());
            paint.setColor(0x2EFFFFFF);
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (value <= 0.02f) return;
            float height = getHeight() * Math.max(idle, value);
            float top = getHeight() - height;
            rect.set(left, top, left + barWidth, getHeight());
            paint.setColor(getActiveColor(value));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (value <= 0.72f) return;
            float capHeight = Math.min(height * 0.28f, getHeight() * 0.22f);
            rect.set(left, top, left + barWidth, top + capHeight);
            paint.setColor(value > 0.9f ? 0xFFFBBF24 : 0xCCECFEFF);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        private int getActiveColor(float value) {
            if (value > 0.82f) return 0xFF34D399;
            if (value > 0.45f) return 0xFF2DD4BF;
            return 0xFF38BDF8;
        }
    }
}
