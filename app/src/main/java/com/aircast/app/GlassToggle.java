package com.aircast.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * An iOS-shaped toggle: a rounded track that cross-fades between two tints and
 * a white thumb that glides across it. The platform Switch keeps its Material
 * proportions no matter how it is tinted, which is why this exists.
 */
public final class GlassToggle extends View {
    public interface Listener {
        void onCheckedChanged(boolean checked);
    }

    private final float trackHeight;
    private final float thumbRadius;
    private final float inset;
    private final int onColor;
    private final int offColor;
    private final int rimColor;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final ArgbEvaluator blend = new ArgbEvaluator();

    private boolean checked;
    private float fraction;
    private ValueAnimator animator;
    private Listener listener;
    private Shader thumbShader;

    public GlassToggle(Context ctx, int onColor, int offColor, int rimColor) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        trackHeight = 31 * density;
        thumbRadius = 13.5f * density;
        inset = 3.5f * density;
        this.onColor = onColor;
        this.offColor = offColor;
        this.rimColor = rimColor;
        setClickable(true);
        setFocusable(true);
        thumbShade.setColor(0x33000000);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        setChecked(value, false);
    }

    public void setChecked(boolean value, boolean animate) {
        checked = value;
        float target = value ? 1f : 0f;
        if (animator != null) animator.cancel();
        if (!animate) {
            fraction = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        setChecked(!checked, true);
        if (listener != null) listener.onCheckedChanged(checked);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        thumbShader = new LinearGradient(0, 0, 0, h,
                0xFFFFFFFF, 0xFFE9EEF6, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = (int) (trackHeight * 52f / 31f);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize((int) trackHeight, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;

        trackRect.set(0, 0, w, h);
        int color = (int) blend.evaluate(fraction, offColor, onColor);
        trackPaint.setShader(null);
        trackPaint.setColor(color);
        canvas.drawRoundRect(trackRect, r, r, trackPaint);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        trackPaint.setColor(rimColor);
        trackRect.inset(0.5f, 0.5f);
        canvas.drawRoundRect(trackRect, r, r, trackPaint);
        trackPaint.setStyle(Paint.Style.FILL);

        float cx = inset + thumbRadius + fraction * (w - 2 * (inset + thumbRadius));
        float cy = h / 2f;
        canvas.drawCircle(cx, cy + 1.2f * getResources().getDisplayMetrics().density, thumbRadius, thumbShade);
        thumbPaint.setShader(thumbShader);
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);
    }
}
