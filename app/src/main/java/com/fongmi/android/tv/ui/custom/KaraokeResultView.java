package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.karaoke.KaraokeResult;
import com.google.android.material.textview.MaterialTextView;

public class KaraokeResultView extends LinearLayout {

    public KaraokeResultView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(4), dp(4), dp(4), 0);
    }

    public KaraokeResultView setResult(KaraokeResult result) {
        removeAllViews();
        if (result == null) return this;
        addHeader(result);
        addProgress(metricLabel(result), result.getHitPercent(), colorForScore(result.getHitPercent()));
        addProgress(getResources().getString(R.string.player_karaoke_result_voice_coverage), result.getVoicedPercent(), 0xFF38BDF8);
        if (result.getScoredLineCount() > 0) addProgress(getResources().getString(R.string.player_karaoke_result_line_average), result.getAverageLineScorePercent(), colorForScore(result.getAverageLineScorePercent()));
        addMeta(result);
        if (!result.getTrackLabel().isEmpty()) addText(getResources().getString(R.string.player_karaoke_result_track, result.getTrackLabel()), 13, false, 0xCCFFFFFF, Gravity.START, dp(10));
        return this;
    }

    private void addHeader(KaraokeResult result) {
        String score = getResources().getString(R.string.player_karaoke_result_score_value, result.getScorePercent(), result.getGrade());
        MaterialTextView title = addText(score, 30, true, Color.WHITE, Gravity.CENTER, 0);
        title.setIncludeFontPadding(false);
        addText(getResources().getString(result.isScoring() ? R.string.player_karaoke_result_scoring : R.string.player_karaoke_result_free), 13, false, 0xCCFFFFFF, Gravity.CENTER, dp(3));
    }

    private void addProgress(String label, int progress, int color) {
        MaterialTextView text = addText(getResources().getString(R.string.player_karaoke_result_progress, label, progress), 13, false, Color.WHITE, Gravity.START, dp(12));
        text.setSingleLine(true);
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, progress)));
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x2EFFFFFF));
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        params.topMargin = dp(5);
        addView(bar, params);
    }

    private void addMeta(KaraokeResult result) {
        String active = getResources().getString(R.string.player_karaoke_result_active_time, result.getTotalSeconds());
        String combo = getResources().getString(R.string.player_karaoke_result_best_combo, result.getBestComboSeconds());
        String lines = getResources().getString(R.string.player_karaoke_result_line_summary, result.getScoredLineCount(), result.getBestLineScorePercent());
        addText(active + "  ·  " + combo, 13, false, 0xDDFFFFFF, Gravity.START, dp(14));
        if (result.getScoredLineCount() > 0) addText(lines, 13, false, 0xDDFFFFFF, Gravity.START, dp(5));
    }

    private String metricLabel(KaraokeResult result) {
        return getResources().getString(result.isScoring() ? R.string.player_karaoke_result_metric_hit : R.string.player_karaoke_result_metric_participation);
    }

    private MaterialTextView addText(String text, int sizeSp, boolean bold, int color, int gravity, int topMargin) {
        MaterialTextView view = new MaterialTextView(getContext());
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        addView(view, params);
        return view;
    }

    private int colorForScore(int score) {
        if (score >= 80) return 0xFF34D399;
        if (score >= 60) return 0xFF2DD4BF;
        if (score >= 40) return 0xFFFBBF24;
        return 0xFF38BDF8;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
