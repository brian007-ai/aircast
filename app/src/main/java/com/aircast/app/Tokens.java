package com.aircast.app;

/**
 * The design tokens: every visual constant lives here.
 *
 * Nothing else in the app is allowed to invent a radius, a spacing value or a
 * text size. Keeping the ramp in one file is what stops the "26 / 999 / 18 /
 * 15.6 dp of corner radius" drift that makes a hand-drawn skin look cheap.
 */
public final class Tokens {
    private Tokens() {}

    // ── corner radius (dp) ────────────────────────────────────────────
    public static final float RADIUS_CARD = 26f;
    public static final float RADIUS_FIELD = 18f;
    /** Ripple mask for a list cell: a card's corner, softened. */
    public static final float RADIUS_ROW = RADIUS_CARD * 0.6f;
    public static final float RADIUS_PILL = 999f;
    /** Rim (edge highlight) thickness. */
    public static final float RIM = 1.2f;
    public static final float RIM_PRIMARY = 1.4f;

    // ── spacing (dp) ──────────────────────────────────────────────────
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 6;
    public static final int SPACE_3 = 9;
    public static final int SPACE_4 = 12;
    public static final int SPACE_5 = 14;
    public static final int SPACE_6 = 16;
    public static final int SPACE_7 = 18;
    public static final int SPACE_8 = 20;
    public static final int SPACE_9 = 22;
    /** Vertical padding inside an input field. */
    public static final int SPACE_FIELD_PAD = 13;
    /** Vertical padding inside a primary button. */
    public static final int SPACE_BTN_PAD = 17;

    // ── type ramp (sp) ────────────────────────────────────────────────
    public static final float TYPE_TITLE = 32;
    public static final float TYPE_DIALOG = 20;
    public static final float TYPE_HEADING = 17;
    public static final float TYPE_ROW = 16;
    public static final float TYPE_BODY = 15;
    public static final float TYPE_VALUE = 14;
    public static final float TYPE_CAPTION = 13;
    public static final float TYPE_SECTION = 12;
    public static final float TYPE_DIAG = 11;
    /** Glyph size for the › chevron and the round icon buttons. */
    public static final float TYPE_GLYPH = 18;
    public static final float TYPE_CHEVRON = 20;

    // ── motion (ms) ───────────────────────────────────────────────────
    public static final long MOTION_TAP = 150;
    public static final long MOTION_DIALOG = 220;
    public static final long MOTION_SHEET = 260;

    // ── metrics ───────────────────────────────────────────────────────
    /** Minimum touch target, as recommended by the platform docs. */
    public static final int TOUCH_MIN = 48;
}
