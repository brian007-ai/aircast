package com.aircast.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The wall of colour the glass sits on. Three soft radial blooms drift over a
 * vertical base gradient; without something moving behind them the translucent
 * cards would read as flat panels, which is the usual failure mode of a
 * glassmorphism skin.
 *
 * The blooms are pre-rendered into a single soft sprite and drawn tinted, so the
 * animation costs one scaled bitmap blit per blob instead of rebuilding three
 * radial shaders every frame.
 */
public final class AuroraView extends View {
    private static final long LOOP_MS = 26000;

    private final Ui ui;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect spriteSrc = new Rect();
    private final RectF blobDst = new RectF();

    private Bitmap sprite;
    private PorterDuffColorFilter tintA;
    private PorterDuffColorFilter tintB;
    private PorterDuffColorFilter tintC;
    private float phase;
    private ValueAnimator animator;

    public AuroraView(Context ctx, Ui ui) {
        super(ctx);
        this.ui = ui;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private Bitmap buildSprite() {
        int size = 256;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        spriteSrc.set(0, 0, size, size);
        return bmp;
    }

    private void ensureResources() {
        if (sprite == null) sprite = buildSprite();
        if (tintA == null) tintA = new PorterDuffColorFilter(ui.blobA, PorterDuff.Mode.SRC_IN);
        if (tintB == null) tintB = new PorterDuffColorFilter(ui.blobB, PorterDuff.Mode.SRC_IN);
        if (tintC == null) tintC = new PorterDuffColorFilter(ui.blobC, PorterDuff.Mode.SRC_IN);
        if (basePaint.getShader() == null && getWidth() > 0 && getHeight() > 0) {
            basePaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                    new int[]{ui.bgTop, ui.bgMid, ui.bgBottom},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        basePaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{ui.bgTop, ui.bgMid, ui.bgBottom},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ensureResources();
        canvas.drawRect(0, 0, getWidth(), getHeight(), basePaint);

        double t = phase * Math.PI * 2.0;
        blobPaint.setColorFilter(tintA);
        drawBlob(canvas, 0.26f + 0.16f * (float) Math.sin(t),
                0.16f + 0.10f * (float) Math.cos(t * 0.8), 1.15f);

        blobPaint.setColorFilter(tintB);
        drawBlob(canvas, 0.82f + 0.14f * (float) Math.cos(t * 0.7 + 1.2),
                0.34f + 0.12f * (float) Math.sin(t * 0.9 + 0.4), 1.05f);

        blobPaint.setColorFilter(tintC);
        drawBlob(canvas, 0.52f + 0.22f * (float) Math.sin(t * 0.6 + 2.1),
                0.92f + 0.10f * (float) Math.cos(t * 0.75 + 0.9), 1.0f);
    }

    private void drawBlob(Canvas canvas, float cx, float cy, float scale) {
        float size = Math.max(getWidth(), getHeight()) * scale;
        float left = getWidth() * cx - size / 2f;
        float top = getHeight() * cy - size / 2f;
        blobDst.set(left, top, left + size, top + size);
        canvas.drawBitmap(sprite, spriteSrc, blobDst, blobPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
