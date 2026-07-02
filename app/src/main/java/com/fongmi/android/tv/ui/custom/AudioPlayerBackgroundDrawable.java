package com.fongmi.android.tv.ui.custom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.PlayerSetting;

public class AudioPlayerBackgroundDrawable extends Drawable {

    private final Paint paint;
    private final Path path;
    private final int style;
    private final int artworkColor;
    private final boolean decorated;
    private int alpha;

    public AudioPlayerBackgroundDrawable(int style, int artworkColor) {
        this(style, artworkColor, true);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated) {
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.path = new Path();
        this.style = style;
        this.artworkColor = artworkColor;
        this.decorated = decorated;
        this.alpha = 255;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        canvas.translate(b.left, b.top);
        switch (style) {
            case PlayerSetting.AUDIO_BACKGROUND_DARK_NEON -> drawDarkNeon(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_BLACK_GOLD -> drawBlackGold(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_SUNSET -> drawSunset(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_MINT -> drawMint(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_CANDY -> drawCandy(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_SKY -> drawSky(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_ROSE -> drawRose(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_CYBER -> drawCyber(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_FOREST -> drawForest(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_LEMON -> drawLemon(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_DUSK -> drawDusk(canvas, w, h);
            default -> drawArtwork(canvas, w, h);
        }
        drawReadability(canvas, w, h);
        canvas.restore();
    }

    private void drawArtwork(Canvas canvas, int w, int h) {
        int c1 = vivid(artworkColor, 1.12f, 1.15f);
        int c2 = rotate(c1, 38f, 0.92f, 0.95f);
        int c3 = rotate(c1, 196f, 0.78f, 0.82f);
        fillLinear(canvas, w, h, c1, c2, c3, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.18f, h * 0.18f, Math.max(w, h) * 0.68f, withAlpha(Color.WHITE, 70), Color.TRANSPARENT);
        drawWave(canvas, w, h, h * 0.72f, h * 0.12f, withAlpha(c3, 130), withAlpha(c2, 30));
    }

    private void drawDarkNeon(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF070A18, 0xFF111B46, 0xFF070812, true);
        if (!decorated) return;
        drawRibbon(canvas, w, h, -0.08f, 0.24f, 0xCC19E6FF, 0x2219E6FF);
        drawRibbon(canvas, w, h, 0.28f, 0.56f, 0xCCFF3AE0, 0x22FF3AE0);
        drawGrid(canvas, w, h, 0x2248D8FF, Math.max(18, w / 18));
    }

    private void drawBlackGold(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF060608, 0xFF211808, 0xFF0A0704, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.78f, h * 0.18f, Math.max(w, h) * 0.62f, 0x99FFC857, Color.TRANSPARENT);
        drawVinyl(canvas, w * 0.26f, h * 0.3f, Math.min(w, h) * 0.5f, 0x44FFFFFF, 0x66FFC857);
        drawRibbon(canvas, w, h, 0.48f, 0.74f, 0xAAFFC857, 0x18FFC857);
    }

    private void drawSunset(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFFFF6A3D, 0xFFFFD36E, 0xFF7F63FF, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.72f, h * 0.28f, Math.min(w, h) * 0.28f, 0xDDFFF3A3, Color.TRANSPARENT);
        drawHorizon(canvas, w, h, 0x663A1D52, 0.62f, 0.1f);
        drawWave(canvas, w, h, h * 0.78f, h * 0.08f, 0x667B61FF, 0x11FFFFFF);
    }

    private void drawMint(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF21D4B4, 0xFFB7F66E, 0xFF35A7FF, true);
        if (!decorated) return;
        drawWave(canvas, w, h, h * 0.38f, h * 0.09f, 0x88FFFFFF, 0x2242E695);
        drawWave(canvas, w, h, h * 0.58f, h * 0.12f, 0x662DD4BF, 0x2235A7FF);
        drawFineLines(canvas, w, h, 0x33FFFFFF, false);
    }

    private void drawCandy(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFFFF4FA3, 0xFFAC5CFF, 0xFF25D8FF, false);
        if (!decorated) return;
        drawCandyStripes(canvas, w, h);
        fillRadial(canvas, w * 0.12f, h * 0.15f, Math.min(w, h) * 0.26f, 0x88FFFFFF, Color.TRANSPARENT);
    }

    private void drawSky(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF26B8FF, 0xFFFFCA45, 0xFFFF706A, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.22f, h * 0.2f, Math.min(w, h) * 0.26f, 0xDDFFF6B8, Color.TRANSPARENT);
        drawCloudBand(canvas, w, h, 0.34f, 0x70FFFFFF);
        drawHorizon(canvas, w, h, 0x55455DAA, 0.74f, 0.06f);
    }

    private void drawRose(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFFFF4D87, 0xFFFFB3C7, 0xFF817CFF, true);
        if (!decorated) return;
        drawWave(canvas, w, h, h * 0.34f, h * 0.16f, 0x88FFFFFF, 0x18FFFFFF);
        drawWave(canvas, w, h, h * 0.66f, h * 0.18f, 0x77D83378, 0x11817CFF);
        drawFineLines(canvas, w, h, 0x26FFFFFF, true);
    }

    private void drawCyber(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF00D4FF, 0xFFFF2FB3, 0xFFFFF25C, true);
        if (!decorated) return;
        drawGrid(canvas, w, h, 0x55FFFFFF, Math.max(22, w / 14));
        drawRibbon(canvas, w, h, 0.14f, 0.4f, 0xAA001B34, 0x22001B34);
        drawRibbon(canvas, w, h, 0.62f, 0.88f, 0x88FFFFFF, 0x18FFFFFF);
    }

    private void drawForest(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF1BA784, 0xFFFFD166, 0xFF3A86FF, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.2f, h * 0.16f, Math.min(w, h) * 0.24f, 0xCCFFF3A6, Color.TRANSPARENT);
        drawLeaf(canvas, w, h, 0.68f, 0.2f, 0x6642E695);
        drawLeaf(canvas, w, h, 0.82f, 0.48f, 0x5547C06B);
        drawHorizon(canvas, w, h, 0x77325F4A, 0.72f, 0.09f);
    }

    private void drawLemon(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFFF9F871, 0xFF42E695, 0xFF3BB2FF, false);
        if (!decorated) return;
        drawCitrus(canvas, w * 0.76f, h * 0.22f, Math.min(w, h) * 0.26f);
        drawCandyStripes(canvas, w, h);
        drawWave(canvas, w, h, h * 0.76f, h * 0.1f, 0x663BB2FF, 0x11FFFFFF);
    }

    private void drawDusk(Canvas canvas, int w, int h) {
        fillLinear(canvas, w, h, 0xFF6A5CFF, 0xFFFF7A59, 0xFFFFD166, false);
        if (!decorated) return;
        fillRadial(canvas, w * 0.68f, h * 0.22f, Math.min(w, h) * 0.22f, 0xAAFFE5A3, Color.TRANSPARENT);
        drawMountain(canvas, w, h, 0.62f, 0x77301954);
        drawMountain(canvas, w, h, 0.74f, 0x884A1841);
    }

    private void fillLinear(Canvas canvas, int w, int h, int start, int center, int end, boolean reverse) {
        paint.setShader(new LinearGradient(reverse ? w : 0, 0, reverse ? 0 : w, h, new int[]{start, center, end}, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void fillRadial(Canvas canvas, float x, float y, float radius, int start, int end) {
        paint.setShader(new RadialGradient(x, y, radius, start, end, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, radius, paint);
        paint.setShader(null);
    }

    private void drawRibbon(Canvas canvas, int w, int h, float startY, float endY, int color, int edgeColor) {
        path.reset();
        path.moveTo(-w * 0.18f, h * startY);
        path.cubicTo(w * 0.18f, h * (startY - 0.1f), w * 0.42f, h * (endY + 0.08f), w * 1.18f, h * endY);
        path.lineTo(w * 1.18f, h * (endY + 0.12f));
        path.cubicTo(w * 0.48f, h * (endY + 0.02f), w * 0.18f, h * (startY + 0.18f), -w * 0.18f, h * (startY + 0.11f));
        path.close();
        paint.setShader(new LinearGradient(0, h * startY, w, h * endY, color, edgeColor, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private void drawWave(Canvas canvas, int w, int h, float y, float amp, int color, int edgeColor) {
        path.reset();
        path.moveTo(0, y);
        path.cubicTo(w * 0.22f, y - amp, w * 0.36f, y + amp, w * 0.56f, y);
        path.cubicTo(w * 0.78f, y - amp, w * 0.9f, y + amp, w, y - amp * 0.2f);
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        paint.setShader(new LinearGradient(0, y - amp, 0, h, color, edgeColor, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private void drawGrid(Canvas canvas, int w, int h, int color, int step) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w / 420f));
        paint.setColor(color);
        for (int x = -w; x < w * 2; x += step) canvas.drawLine(x, 0, x + w / 2f, h, paint);
        for (int y = 0; y < h; y += step) canvas.drawLine(0, y, w, y + h * 0.08f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawVinyl(Canvas canvas, float cx, float cy, float r, int ringColor, int accentColor) {
        paint.setShader(new SweepGradient(cx, cy, new int[]{0x66000000, 0x22FFFFFF, 0x99000000, 0x22FFFFFF, 0x66000000}, null));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, r / 42f));
        paint.setColor(ringColor);
        for (int i = 1; i <= 5; i++) canvas.drawCircle(cx, cy, r * (0.28f + i * 0.11f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accentColor);
        canvas.drawCircle(cx, cy, r * 0.16f, paint);
    }

    private void drawHorizon(Canvas canvas, int w, int h, int color, float base, float variance) {
        path.reset();
        path.moveTo(0, h * base);
        path.lineTo(w * 0.22f, h * (base - variance));
        path.lineTo(w * 0.48f, h * (base + variance * 0.35f));
        path.lineTo(w * 0.72f, h * (base - variance * 0.6f));
        path.lineTo(w, h * (base + variance * 0.25f));
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        paint.setShader(null);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    private void drawCloudBand(Canvas canvas, int w, int h, float y, int color) {
        paint.setColor(color);
        paint.setShader(null);
        canvas.drawOval(w * -0.08f, h * (y + 0.02f), w * 0.35f, h * (y + 0.18f), paint);
        canvas.drawOval(w * 0.18f, h * y, w * 0.72f, h * (y + 0.18f), paint);
        canvas.drawOval(w * 0.55f, h * (y + 0.04f), w * 1.1f, h * (y + 0.2f), paint);
    }

    private void drawFineLines(Canvas canvas, int w, int h, int color, boolean vertical) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w / 520f));
        paint.setColor(color);
        int step = Math.max(18, w / 16);
        for (int i = -step; i < (vertical ? w : h) + step; i += step) {
            if (vertical) canvas.drawLine(i, 0, i + w * 0.18f, h, paint);
            else canvas.drawLine(0, i, w, i + h * 0.16f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCandyStripes(Canvas canvas, int w, int h) {
        paint.setShader(null);
        int step = Math.max(48, w / 7);
        for (int i = -w; i < w * 2; i += step) {
            path.reset();
            path.moveTo(i, 0);
            path.lineTo(i + step * 0.38f, 0);
            path.lineTo(i + w * 0.7f + step * 0.38f, h);
            path.lineTo(i + w * 0.7f, h);
            path.close();
            paint.setColor(i % (step * 2) == 0 ? 0x38FFFFFF : 0x22000000);
            canvas.drawPath(path, paint);
        }
    }

    private void drawLeaf(Canvas canvas, int w, int h, float x, float y, int color) {
        path.reset();
        float cx = w * x;
        float cy = h * y;
        float rw = w * 0.18f;
        float rh = h * 0.12f;
        path.moveTo(cx, cy - rh);
        path.cubicTo(cx + rw, cy - rh * 0.6f, cx + rw, cy + rh * 0.7f, cx, cy + rh);
        path.cubicTo(cx - rw, cy + rh * 0.4f, cx - rw, cy - rh * 0.8f, cx, cy - rh);
        paint.setColor(color);
        paint.setShader(null);
        canvas.drawPath(path, paint);
    }

    private void drawCitrus(Canvas canvas, float cx, float cy, float r) {
        paint.setShader(new SweepGradient(cx, cy, new int[]{0xCCFFFFFF, 0x66FFF45A, 0xCCFFFFFF, 0x66FFF45A, 0xCCFFFFFF}, null));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xAAFFFFFF);
        paint.setStrokeWidth(Math.max(2f, r / 20f));
        canvas.drawCircle(cx, cy, r * 0.96f, paint);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            canvas.drawLine(cx, cy, cx + (float) Math.cos(a) * r * 0.9f, cy + (float) Math.sin(a) * r * 0.9f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMountain(Canvas canvas, int w, int h, float base, int color) {
        path.reset();
        path.moveTo(0, h * base);
        path.lineTo(w * 0.18f, h * (base - 0.12f));
        path.lineTo(w * 0.34f, h * (base - 0.04f));
        path.lineTo(w * 0.52f, h * (base - 0.18f));
        path.lineTo(w * 0.74f, h * (base - 0.03f));
        path.lineTo(w, h * (base - 0.1f));
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        paint.setColor(color);
        paint.setShader(null);
        canvas.drawPath(path, paint);
    }

    private void drawReadability(Canvas canvas, int w, int h) {
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x5E000000, 0x1A000000, 0x8C000000}, new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private int vivid(int color, float satMul, float valMul) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = clamp(hsv[1] * satMul, 0.48f, 0.92f);
        hsv[2] = clamp(hsv[2] * valMul, 0.7f, 1f);
        return Color.HSVToColor(hsv);
    }

    private int rotate(int color, float hue, float satMul, float valMul) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hue) % 360f;
        hsv[1] = clamp(hsv[1] * satMul, 0.38f, 0.9f);
        hsv[2] = clamp(hsv[2] * valMul, 0.55f, 1f);
        return Color.HSVToColor(hsv);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
