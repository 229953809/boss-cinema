package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.player.lyrics.LyricsWord;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.android.material.textview.MaterialTextView;

import java.util.Collections;
import java.util.List;

public class LyricsOverlayView extends FrameLayout {

    private static final int ROWS = 5;
    private static final long WORD_REFRESH_MS = 50;
    private static final int PRIMARY_COLOR = 0xFF7EE7D6;

    private final LinearLayout box;
    private final MaterialTextView[] rows = new MaterialTextView[ROWS];
    private final Runnable wordRefresh = this::refreshWordProgress;
    private List<LyricsLine> lines = Collections.emptyList();
    private boolean compact;
    private boolean desktopMode;
    private boolean audioStageMode;
    private boolean suppressed;
    private boolean playing;
    private int index = -1;
    private long basePositionMs;
    private long baseRealtimeMs;

    public LyricsOverlayView(Context context) {
        this(context, null);
    }

    public LyricsOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);

        box = new LinearLayout(context);
        box.setGravity(Gravity.CENTER);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(10));

        for (int i = 0; i < ROWS; i++) {
            rows[i] = textView(context);
            box.addView(rows[i], new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(box, params);
        applyStyle();
    }

    public void setDesktopMode(boolean desktopMode) {
        this.desktopMode = desktopMode;
        setClickable(desktopMode);
        box.setPadding(dp(desktopMode ? 16 : 18), dp(desktopMode ? 8 : 10), dp(desktopMode ? 16 : 18), dp(desktopMode ? 8 : 10));
        box.setBackground(desktopMode ? desktopBackground() : null);
        applyStyle();
    }

    public void setAudioStageMode(boolean audioStageMode) {
        if (this.audioStageMode == audioStageMode) return;
        this.audioStageMode = audioStageMode;
        LayoutParams params = (LayoutParams) box.getLayoutParams();
        params.gravity = audioStageMode ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        box.setLayoutParams(params);
        box.setPadding(dp(18), dp(10), dp(18), dp(10));
        applyStyle();
    }

    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        if (suppressed) setVisibility(GONE);
        else if (!lines.isEmpty()) setVisibility(VISIBLE);
    }

    public void setLyrics(LyricsResult result, List<LyricsLine> lines) {
        this.lines = lines == null ? Collections.emptyList() : lines;
        this.index = -1;
        setVisibility(this.lines.isEmpty() || suppressed ? GONE : VISIBLE);
        if (!this.lines.isEmpty()) update(0);
    }

    public void update(long positionMs) {
        update(positionMs, false);
    }

    public void update(long positionMs, boolean playing) {
        if (lines.isEmpty()) {
            clear();
            return;
        }
        if (suppressed) {
            setVisibility(GONE);
            return;
        }
        this.playing = playing;
        this.basePositionMs = Math.max(0, positionMs);
        this.baseRealtimeMs = SystemClock.elapsedRealtime();
        int nextIndex = LyricsParser.findLine(lines, positionMs);
        if (nextIndex == index) {
            renderPrimaryLine(positionMs);
            scheduleWordRefresh();
            return;
        }
        index = nextIndex;
        render(positionMs);
        scheduleWordRefresh();
        setVisibility(VISIBLE);
    }

    private void render(long positionMs) {
        if (desktopMode) {
            renderDesktop(positionMs);
            return;
        }
        int count = visibleRows();
        int center = count / 2;
        int offset = (ROWS - count) / 2;
        for (int i = 0; i < ROWS; i++) {
            MaterialTextView row = rows[i];
            if (i < offset || i >= offset + count) {
                row.setText("");
                row.setVisibility(GONE);
                continue;
            }
            int lineIndex = index + i - offset - center;
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                row.setText("");
                row.setVisibility(INVISIBLE);
                continue;
            }
            row.setVisibility(VISIBLE);
            style(row, Math.abs(i - offset - center));
            setRowText(row, lines.get(lineIndex), lineIndex == index, positionMs);
        }
    }

    private void renderDesktop(long positionMs) {
        int count = visibleRows();
        for (int i = 0; i < ROWS; i++) {
            MaterialTextView row = rows[i];
            if (i >= count) {
                row.setText("");
                row.setVisibility(GONE);
                continue;
            }
            int lineIndex = index + i;
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                row.setText("");
                row.setVisibility(i == 0 ? INVISIBLE : GONE);
                continue;
            }
            row.setVisibility(VISIBLE);
            style(row, i);
            setRowText(row, lines.get(lineIndex), i == 0, positionMs);
        }
    }

    public void clear() {
        stopWordRefresh();
        lines = Collections.emptyList();
        index = -1;
        for (MaterialTextView row : rows) row.setText("");
        setVisibility(GONE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean nextCompact = h > 0 && h < dp(300);
        if (compact == nextCompact && w == oldw && h == oldh) return;
        compact = nextCompact;
        applyStyle();
        if (!lines.isEmpty() && index >= 0) {
            int current = index;
            index = -1;
            update(lines.get(current).getTimeMs(), playing);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWordRefresh();
        super.onDetachedFromWindow();
    }

    private MaterialTextView textView(Context context) {
        MaterialTextView view = new MaterialTextView(context);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        view.setShadowLayer(4f, 0, 2f, 0xCC000000);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private void applyStyle() {
        if (desktopMode) {
            for (int i = 0; i < ROWS; i++) {
                MaterialTextView row = rows[i];
                row.setVisibility(i < visibleRows() ? row.getVisibility() : GONE);
                style(row, i);
            }
            return;
        }
        int count = visibleRows();
        int offset = (ROWS - count) / 2;
        int center = count / 2;
        for (int i = 0; i < ROWS; i++) {
            MaterialTextView row = rows[i];
            row.setVisibility(i < offset || i >= offset + count ? GONE : row.getVisibility());
            style(row, Math.abs(i - offset - center));
        }
    }

    private void style(MaterialTextView view, int distance) {
        boolean primary = distance == 0;
        int size = desktopMode ? primary ? 20 : 14 : compact ? primary ? 18 : 13 : primary ? 28 : distance == 1 ? 19 : 16;
        float scale = PlayerSetting.getLyricsTextSizeScale();
        int color = primary ? PRIMARY_COLOR : distance == 1 ? Color.WHITE : 0xB8FFFFFF;
        view.setTextSize(size * scale);
        view.setTextColor(color);
        view.setAlpha(primary ? 1f : desktopMode ? 0.72f : distance == 1 ? 0.82f : 0.58f);
        view.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        view.setMaxLines(desktopMode ? 1 : primary ? 2 : 1);
        view.setMinHeight(dp((desktopMode ? primary ? 32 : 24 : compact ? primary ? 44 : 24 : primary ? 62 : 34) * scale));
    }

    private void setRowText(MaterialTextView row, LyricsLine line, boolean primary, long positionMs) {
        if (!primary || !line.hasWords()) {
            row.setText(line.getText());
            return;
        }
        row.setTextColor(Color.WHITE);
        row.setText(buildKaraokeText(line, positionMs));
    }

    private SpannableString buildKaraokeText(LyricsLine line, long positionMs) {
        String text = line.getText();
        SpannableString span = new SpannableString(text);
        if (!text.isEmpty()) span.setSpan(new ForegroundColorSpan(Color.WHITE), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        applyKaraokeSpans(span, line, Math.max(0, positionMs - line.getTimeMs()));
        return span;
    }

    private void applyKaraokeSpans(SpannableString span, LyricsLine line, long relativeMs) {
        int cursor = 0;
        String text = line.getText();
        for (LyricsWord word : line.getWords()) {
            String value = word.getText();
            if (TextUtils.isEmpty(value)) continue;
            int start = text.indexOf(value, cursor);
            if (start < 0) start = cursor;
            int stop = Math.min(text.length(), start + value.length());
            if (stop <= start) continue;
            if (relativeMs >= word.getEndOffsetMs()) {
                span.setSpan(new ForegroundColorSpan(PRIMARY_COLOR), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (relativeMs >= word.getStartOffsetMs()) {
                float progress = word.getDurationMs() <= 0 ? 1f : Math.min(1f, Math.max(0f, (relativeMs - word.getStartOffsetMs()) / (float) word.getDurationMs()));
                span.setSpan(new KaraokeSpan(PRIMARY_COLOR, progress), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            } else {
                return;
            }
            cursor = stop;
        }
    }

    private void renderPrimaryLine(long positionMs) {
        if (index < 0 || index >= lines.size()) return;
        int primary = primaryRow();
        if (primary < 0 || primary >= ROWS || rows[primary].getVisibility() != VISIBLE) return;
        LyricsLine line = lines.get(index);
        if (!line.hasWords()) return;
        style(rows[primary], 0);
        setRowText(rows[primary], line, true, positionMs);
    }

    private void refreshWordProgress() {
        if (!playing || lines.isEmpty() || index < 0 || index >= lines.size()) return;
        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - baseRealtimeMs);
        long position = basePositionMs + elapsed;
        int nextIndex = LyricsParser.findLine(lines, position);
        if (nextIndex != index) {
            index = nextIndex;
            render(position);
            App.post(wordRefresh, WORD_REFRESH_MS);
            return;
        }
        if (!lines.get(index).hasWords()) return;
        renderPrimaryLine(position);
        App.post(wordRefresh, WORD_REFRESH_MS);
    }

    private void scheduleWordRefresh() {
        stopWordRefresh();
        if (!playing || index < 0 || index >= lines.size() || !lines.get(index).hasWords()) return;
        App.post(wordRefresh, WORD_REFRESH_MS);
    }

    private void stopWordRefresh() {
        App.removeCallbacks(wordRefresh);
    }

    private int primaryRow() {
        if (desktopMode) return 0;
        int count = visibleRows();
        return (ROWS - count) / 2 + count / 2;
    }

    private int visibleRows() {
        if (desktopMode) return 2;
        int rows = Math.min(PlayerSetting.getLyricsRows(), ROWS);
        return compact ? Math.min(rows, 3) : rows;
    }

    private GradientDrawable desktopBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xB320232A);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), 0x26FFFFFF);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class KaraokeSpan extends ReplacementSpan {

        private final int activeColor;
        private final float progress;

        private KaraokeSpan(int activeColor, float progress) {
            this.activeColor = activeColor;
            this.progress = Math.min(1f, Math.max(0f, progress));
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(paint.measureText(text, start, end));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            int color = paint.getColor();
            float width = paint.measureText(text, start, end);
            canvas.drawText(text, start, end, x, y, paint);
            if (progress > 0f && width > 0f) {
                int save = canvas.save();
                canvas.clipRect(x, top, x + width * progress, bottom);
                paint.setColor(activeColor);
                canvas.drawText(text, start, end, x, y, paint);
                canvas.restoreToCount(save);
            }
            paint.setColor(color);
        }
    }
}
