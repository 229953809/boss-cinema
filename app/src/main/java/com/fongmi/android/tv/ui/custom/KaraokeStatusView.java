package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.karaoke.KaraokePitch;
import com.fongmi.android.tv.player.karaoke.KaraokePitchSample;
import com.fongmi.android.tv.player.karaoke.KaraokeScoreSnapshot;
import com.fongmi.android.tv.player.karaoke.KaraokeStatus;
import com.fongmi.android.tv.player.karaoke.KaraokeTrack;
import com.google.android.material.textview.MaterialTextView;

public class KaraokeStatusView extends LinearLayout {

    private final MaterialTextView title;
    private final MaterialTextView detail;
    private final VolumeMeterView volume;

    public KaraokeStatusView(Context context) {
        this(context, null);
    }

    public KaraokeStatusView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setPadding(dp(12), dp(8), dp(12), dp(8));
        setBackground(background());
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);

        title = textView(context, 13, true);
        detail = textView(context, 12, false);
        volume = new VolumeMeterView(context);
        addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(detail, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams params = new LayoutParams(dp(112), dp(30));
        params.topMargin = dp(8);
        addView(volume, params);
    }

    public void setState(KaraokeStatus status, KaraokeTrack track, KaraokePitchSample sample, KaraokeScoreSnapshot snapshot) {
        if (status == null || status == KaraokeStatus.INACTIVE) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        title.setText(getTitle(status, snapshot));
        String text = getDetail(status, sample, snapshot, track);
        detail.setText(text);
        detail.setVisibility(text.isEmpty() ? GONE : VISIBLE);
        volume.setLevel(getVolumeLevel(status, sample));
        volume.setVisibility(showVolume(status) ? VISIBLE : GONE);
    }

    private String getTitle(KaraokeStatus status, KaraokeScoreSnapshot snapshot) {
        if (status == KaraokeStatus.SCORING) return getResources().getString(R.string.player_karaoke_status_score, snapshot == null ? 0 : snapshot.getScorePercent());
        if (status == KaraokeStatus.FREE_SING && snapshot != null && snapshot.getTotalWeightMs() > 0) return getResources().getString(R.string.player_karaoke_status_free_score, snapshot.getScorePercent());
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
            String state = snapshot.isHit()
                    ? getResources().getString(R.string.player_karaoke_pitch_hit)
                    : getResources().getString(R.string.player_karaoke_pitch_miss, Math.abs(snapshot.getDistanceSemitones()));
            return getResources().getString(R.string.player_karaoke_pitch_score, pitch, state);
        }
        if (status == KaraokeStatus.FREE_SING && snapshot != null && snapshot.getTotalWeightMs() > 0) {
            return getResources().getString(R.string.player_karaoke_free_detail, pitch, snapshot.getScorePercent());
        }
        return getResources().getString(R.string.player_karaoke_pitch, pitch);
    }

    private boolean showVolume(KaraokeStatus status) {
        return status == KaraokeStatus.FREE_SING || status == KaraokeStatus.SCORING;
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
        view.setGravity(Gravity.END);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable background() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xA820232A);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), 0x24FFFFFF);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
