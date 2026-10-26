package com.aircast.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The single material used by every surface in the app: a translucent rounded
 * slab with a vertical light-to-dark fill, a bright rim that fades toward the
 * bottom, and a soft specular bloom in the upper-left corner.
 *
 * Drawing it by hand (instead of stacking GradientDrawables) is what gives the
 * cards their frosted-glass read: the rim and the bloom sit *inside* the shape,
 * so the material looks lit from above rather than outlined.
 */
public final class GlassDrawable extends Drawable {
    private final int fillTop;
    private final int fillBottom;
    private final int rimTop;
    private final int rimBottom;
    private final float radiusPx;
    private final float rimWidthPx;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();

    public GlassDrawable(int fillTop, int fillBottom, int rimTop, int rimBottom,
                         float radiusPx, float rimWidthPx) {
        this.fillTop = fillTop;
        this.fillBottom = fillBottom;
        this.rimTop = rimTop;
        this.rimBottom = rimBottom;
        this.radiusPx = radiusPx;
        this.rimWidthPx = Math.max(1f, rimWidthPx);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(this.rimWidthPx);
    }

    public static GlassDrawable pill(int fillTop, int fillBottom, int rimTop, int rimBottom,
                                     float heightPx) {
        return new GlassDrawable(fillTop, fillBottom, rimTop, rimBottom, heightPx / 2f, 1.5f);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        float w = bounds.width();
        float h = bounds.height();
        if (w <= 0 || h <= 0) return;
        float r = Math.min(radiusPx, Math.min(w, h) / 2f);

        // A diagonal-ish fill makes the slab read as a thick piece of glass
        // catching light from the top-left instead of a flat tint.
        fillPaint.setShader(new LinearGradient(bounds.left, bounds.top,
                bounds.left + w * 0.35f, bounds.bottom,
                fillTop, fillBottom, Shader.TileMode.CLAMP));

        rimRect.set(bounds);
        rimRect.inset(rimWidthPx / 2f, rimWidthPx / 2f);
        rimPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                rimTop, rimBottom, Shader.TileMode.CLAMP));

        bloomPaint.setShader(new RadialGradient(
                bounds.left + w * 0.26f, bounds.top + h * 0.08f,
                Math.max(w, h) * 0.85f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        float r = Math.min(radiusPx, Math.min(rect.width(), rect.height()) / 2f);
        canvas.drawRoundRect(rect, r, r, fillPaint);
        canvas.drawRoundRect(rect, r, r, bloomPaint);
        canvas.drawRoundRect(rimRect, r, r, rimPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        rimPaint.setAlpha(alpha);
        bloomPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        rimPaint.setColorFilter(colorFilter);
        bloomPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
