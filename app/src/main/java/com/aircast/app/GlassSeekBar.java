package com.aircast.app;

import android.content.Context;
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
import android.widget.SeekBar;

/**
 * A SeekBar wearing the glass material: a recessed track with an accent-filled
 * portion and a white, softly lit thumb. It stays a real SeekBar so the
 * existing progress/listener plumbing keeps working unchanged.
 */
public final class GlassSeekBar extends SeekBar {

    public GlassSeekBar(Context ctx, Ui ui) {
        super(ctx);
        int thumb = ui.dp(22);
        setSplitTrack(false);
        setThumb(new ThumbDrawable(ui));
        setProgressDrawable(new TrackDrawable(ui));
        // Must follow setThumb(): the platform resets the offset when it is set.
        setThumbOffset(0);
        // Padding of half a thumb on each side makes the track start and end
        // exactly under the thumb's centre at 0 % and 100 %.
        setPadding(thumb / 2, ui.dp(12), thumb / 2, ui.dp(12));
        setMinimumHeight(ui.dp(44));
    }

    /** Track + filled portion; SeekBar drives it through {@link #setLevel(int)}. */
    private static final class TrackDrawable extends Drawable {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Shader fillShader;

        TrackDrawable(Ui ui) {
            this.ui = ui;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            fillShader = new LinearGradient(bounds.left, 0, bounds.right, 0,
                    ui.trackFillStart, ui.trackFillEnd, Shader.TileMode.CLAMP);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float thickness = ui.dp(6);
            float cy = b.centerY();
            float radius = thickness / 2f;

            rect.set(b.left, cy - radius, b.right, cy + radius);
            paint.setShader(null);
            paint.setColor(ui.trackColor);
            canvas.drawRoundRect(rect, radius, radius, paint);

            float fraction = getLevel() / 10000f;
            if (fraction <= 0f) return;
            float end = b.left + b.width() * fraction;
            float start = b.left;
            // Keep the filled cap round even at tiny progress values.
            if (end - start < thickness) end = Math.min(b.right, start + thickness);
            rect.set(start, cy - radius, end, cy + radius);
            paint.setShader(fillShader);
            canvas.drawRoundRect(rect, radius, radius, paint);

            // A light catch along the top of the filled part: it separates the
            // accent segment from the recessed track even at low values, where
            // the colour difference alone is easy to miss.
            paint.setShader(null);
            paint.setColor(ui.dark ? 0x33FFFFFF : 0x40FFFFFF);
            rect.set(start, cy - radius, end, cy - radius * 0.35f);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) ui.dp(18);
        }
    }

    /** The white glass knob, with a rim and a soft contact shadow. */
    private static final class ThumbDrawable extends Drawable {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader body;

        ThumbDrawable(Ui ui) {
            this.ui = ui;
            shade.setColor(ui.dark ? 0x66000000 : 0x33000000);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(ui.dp(1));
            rim.setColor(ui.dark ? 0x40FFFFFF : 0x66000000);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            body = new RadialGradient(bounds.centerX() - bounds.width() * 0.18f,
                    bounds.centerY() - bounds.height() * 0.22f,
                    bounds.width() * 0.75f,
                    0xFFFFFFFF, 0xFFDFE6F1, Shader.TileMode.CLAMP);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f - ui.dp(1);
            float cx = b.centerX();
            float cy = b.centerY();
            canvas.drawCircle(cx, cy + ui.dp(1.5f), r, shade);
            paint.setShader(body);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawCircle(cx, cy, r, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) ui.dp(22);
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) ui.dp(22);
        }
    }
}
