package com.aircast.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * The app's glass material.
 *
 * Glass is read from its edges, never from its body: a translucent slab, a
 * bright specular band under the top edge, a faint shade along the bottom inner
 * edge, and a hairline rim lit from the upper left. Every effect fades out
 * inside the shape, because anything painted across the middle of the slab (an
 * earlier revision had a radial bloom) reads as a rectangular patch of paint
 * rather than light on glass.
 *
 * Three details finish the material: an inner refraction band that follows the
 * rim (the edge of a thick pane bends light), a catch light in the top-left and
 * bottom-right corners, and a noise grain faint enough to be felt rather than
 * seen -- a perfectly smooth fill is what makes a translucent panel look like
 * flat paint.
 */
public final class GlassDrawable extends Drawable {
    private static final int NOISE_ALPHA = 8;

    /** Shared, because the grain is the same for every pane in the app. */
    private static Bitmap noise;

    private final int fillTop;
    private final int fillBottom;
    private final int rimTop;
    private final int rimBottom;
    private final int specular;
    private final int innerShade;
    private final float radiusTopPx;
    private final float radiusBottomPx;
    private final float rimWidthPx;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refractPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint catchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();
    private final RectF refractRect = new RectF();
    private final RectF catchRect = new RectF();
    private final Path shapePath = new Path();
    private final Path rimPath = new Path();
    private final Path refractPath = new Path();

    public GlassDrawable(int fillTop, int fillBottom, int rimTop, int rimBottom,
                         int specular, int innerShade, float radiusPx, float rimWidthPx) {
        this(fillTop, fillBottom, rimTop, rimBottom, specular, innerShade,
                radiusPx, radiusPx, rimWidthPx);
    }

    /**
     * @param radiusBottomPx 0 for a panel that meets the bottom of the screen,
     *                       like a sheet; every other corner keeps the material's
     *                       normal radius.
     */
    public GlassDrawable(int fillTop, int fillBottom, int rimTop, int rimBottom,
                         int specular, int innerShade,
                         float radiusTopPx, float radiusBottomPx, float rimWidthPx) {
        this.fillTop = fillTop;
        this.fillBottom = fillBottom;
        this.rimTop = rimTop;
        this.rimBottom = rimBottom;
        this.specular = specular;
        this.innerShade = innerShade;
        this.radiusTopPx = radiusTopPx;
        this.radiusBottomPx = Math.max(0f, radiusBottomPx);
        this.rimWidthPx = Math.max(1f, rimWidthPx);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(this.rimWidthPx);
        // The refraction band sits just inside the rim, twice as wide, and is
        // only ever lit along the top edge.
        refractPaint.setStyle(Paint.Style.STROKE);
        refractPaint.setStrokeWidth(this.rimWidthPx * 2.2f);
        catchPaint.setStyle(Paint.Style.STROKE);
        catchPaint.setStrokeWidth(this.rimWidthPx * 1.3f);
    }

    public static GlassDrawable pill(int fillTop, int fillBottom, int rimTop, int rimBottom,
                                     int specular, int innerShade, float heightPx) {
        return new GlassDrawable(fillTop, fillBottom, rimTop, rimBottom, specular, innerShade,
                heightPx / 2f, 1.5f);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        float h = bounds.height();
        if (bounds.width() <= 0 || h <= 0) return;

        // Body: a vertical tint only. No diagonal wash -- across a wide card a
        // diagonal gradient reads as a colour block in one corner.
        fillPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                fillTop, fillBottom, Shader.TileMode.CLAMP));

        // Specular: brightest right under the top rim, gone by mid-height. The
        // band ends on a fully transparent stop, so it has no hard lower edge.
        specPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left,
                bounds.top + h * 0.55f,
                new int[]{specular, withAlpha(specular, 0.45f), withAlpha(specular, 0f)},
                new float[]{0f, 0.14f, 1f}, Shader.TileMode.CLAMP));

        // Inner shade: the slab's own thickness showing along the bottom edge.
        shadePaint.setShader(new LinearGradient(bounds.left, bounds.bottom - h * 0.30f,
                bounds.left, bounds.bottom,
                new int[]{withAlpha(innerShade, 0f), innerShade},
                null, Shader.TileMode.CLAMP));

        // Rim: lit from the upper left, so the top-left corner catches the light
        // and the bottom-right falls away.
        rimRect.set(bounds);
        rimRect.inset(rimWidthPx / 2f, rimWidthPx / 2f);
        rimPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                rimTop, rimBottom, Shader.TileMode.CLAMP));

        // The pane's own edge bending the light: same shape, pulled inwards.
        refractRect.set(rimRect);
        refractRect.inset(rimWidthPx * 1.1f, rimWidthPx * 1.1f);
        refractPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left,
                bounds.top + h * 0.60f,
                new int[]{withAlpha(rimTop, 0.42f), withAlpha(rimTop, 0.10f), withAlpha(rimTop, 0f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        buildPaths();
        invalidateSelf();
    }

    /** One shape for the fill, one for the rim, one for the refraction band. */
    private void buildPaths() {
        float rTop = Math.min(radiusTopPx,
                Math.min(rect.width(), rect.height()) / 2f);
        float rBottom = Math.min(radiusBottomPx,
                Math.min(rect.width(), rect.height()) / 2f);
        float[] radii = {rTop, rTop, rTop, rTop, rBottom, rBottom, rBottom, rBottom};

        shapePath.reset();
        shapePath.addRoundRect(rect, radii, Path.Direction.CW);

        float rIn = Math.max(0f, rTop - rimWidthPx / 2f);
        float rInBottom = Math.max(0f, rBottom - rimWidthPx / 2f);
        float[] inRadii = {rIn, rIn, rIn, rIn, rInBottom, rInBottom, rInBottom, rInBottom};
        rimPath.reset();
        rimPath.addRoundRect(rimRect, inRadii, Path.Direction.CW);

        refractPath.reset();
        refractPath.addRoundRect(refractRect, inRadii, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(shapePath, fillPaint);
        if (specPaint.getShader() != null) canvas.drawPath(shapePath, specPaint);
        if (shadePaint.getShader() != null) canvas.drawPath(shapePath, shadePaint);
        drawGrain(canvas);
        if (refractPaint.getShader() != null) canvas.drawPath(refractPath, refractPaint);
        canvas.drawPath(rimPath, rimPaint);
        drawCatchLights(canvas);
    }

    /**
     * A grain over the whole pane. Static noise that does not scroll with the
     * backdrop is what separates "frosted glass" from "translucent fill"; the
     * alpha is low enough to be invisible in a screenshot and just enough to
     * break up the banding of the gradient behind it.
     */
    private void drawGrain(Canvas canvas) {
        Bitmap n = noiseBitmap();
        if (n == null) return;
        if (noisePaint.getShader() == null) {
            noisePaint.setShader(new BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        canvas.drawPath(shapePath, noisePaint);
    }

    private static Bitmap noiseBitmap() {
        if (noise != null) return noise;
        final int size = 64;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Random rnd = new Random(7);
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            int a = rnd.nextInt(NOISE_ALPHA + 1);
            // Mid grey either way: lighten and darken in equal measure, so the
            // grain does not shift the pane's overall tone.
            int grey = (i & 1) == 0 ? 0xFF : 0x00;
            pixels[i] = (a << 24) | (grey << 16) | (grey << 8) | grey;
        }
        b.setPixels(pixels, 0, size, 0, 0, size, size);
        noise = b;
        return b;
    }

    /**
     * The two bright spots where a corner turns the light. Only the corners, and
     * only a quarter arc each: a full bright outline would read as a stroke, not
     * as light catching a bevel.
     */
    private void drawCatchLights(Canvas canvas) {
        float rTop = Math.min(radiusTopPx, Math.min(rect.width(), rect.height()) / 2f);
        float rBottom = Math.min(radiusBottomPx, Math.min(rect.width(), rect.height()) / 2f);
        if (rTop > 2f) {
            catchRect.set(rect.left, rect.top, rect.left + 2f * rTop, rect.top + 2f * rTop);
            catchPaint.setColor(withAlpha(rimTop, 0.95f));
            canvas.drawArc(catchRect, 180f, 90f, false, catchPaint);
        }
        if (rBottom > 2f) {
            catchRect.set(rect.right - 2f * rBottom, rect.bottom - 2f * rBottom,
                    rect.right, rect.bottom);
            catchPaint.setColor(withAlpha(rimTop, 0.30f));
            canvas.drawArc(catchRect, 0f, 90f, false, catchPaint);
        }
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * factor);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        specPaint.setAlpha(alpha);
        shadePaint.setAlpha(alpha);
        noisePaint.setAlpha(alpha);
        refractPaint.setAlpha(alpha);
        rimPaint.setAlpha(alpha);
        catchPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        specPaint.setColorFilter(colorFilter);
        shadePaint.setColorFilter(colorFilter);
        noisePaint.setColorFilter(colorFilter);
        refractPaint.setColorFilter(colorFilter);
        rimPaint.setColorFilter(colorFilter);
        catchPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
