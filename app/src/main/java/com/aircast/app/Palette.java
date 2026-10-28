package com.aircast.app;

/**
 * One resolved colour set. Built once per screen, treated as immutable.
 *
 * Fields are not final only so this can be built by a factory without a
 * twenty-six argument constructor; nothing outside this file writes them.
 */
public final class Palette {
    // backdrop
    public int bgTop, bgMid, bgBottom, blobA, blobB, blobC;
    // glass
    public int glassTop, glassBottom, glassSpec, glassShade, strokeTop, strokeBottom;
    // text
    public int text, dim, faint, accent, danger;
    // controls
    public int fieldFill, divider, track, trackFillStart, trackFillEnd;
    public int accentFillTop, accentFillBottom, stopFillTop, stopFillBottom;
    // pill material (status capsule, secondary button, icon button)
    public int pillTop, pillBottom, pillSpec, pillShade, pillRim;
    // ripple tints
    public int rippleRow, ripplePill;
    /** Ripple drawn over a saturated fill, where a tint has to be light. */
    public int rippleOnAccent;

    private Palette() {}

    public static Palette light() {
        Palette p = new Palette();
        p.bgTop = 0xFFF0F2F6;
        p.bgMid = 0xFFE9ECF2;
        p.bgBottom = 0xFFDFE4EB;
        p.blobA = 0x33718BA8;
        p.blobB = 0x2E8A7F9E;
        p.blobC = 0x26938F86;

        p.glassTop = 0xD9FFFFFF;
        p.glassBottom = 0xB8FFFFFF;
        p.glassSpec = 0x66FFFFFF;
        p.glassShade = 0x1C000000;
        p.strokeTop = 0xFFFFFFFF;
        p.strokeBottom = 0x24000000;

        p.text = 0xFF10131A;
        p.dim = 0xFF4A5261;
        p.faint = 0xFF6B7484;
        p.accent = 0xFF0A6BFF;
        p.danger = 0xFFD92D20;

        p.fieldFill = 0x12000000;
        p.divider = 0x14000000;
        p.track = 0x33000000;
        p.trackFillStart = 0xFF2F8FFF;
        p.trackFillEnd = 0xFF0A5FD0;
        p.accentFillTop = 0xFF3D9BFF;
        p.accentFillBottom = 0xFF0A5FD0;
        p.stopFillTop = 0xFFFF6B61;
        p.stopFillBottom = 0xFFE03A30;

        p.pillTop = 0xD9FFFFFF;
        p.pillBottom = 0xC4FFFFFF;
        p.pillSpec = 0x4DFFFFFF;
        p.pillShade = 0x0F000000;
        p.pillRim = 0x1A000000;

        p.rippleRow = 0x14000000;
        p.ripplePill = 0x26FFFFFF;
        p.rippleOnAccent = 0x40FFFFFF;
        return p;
    }

    public static Palette dark() {
        Palette p = new Palette();
        p.bgTop = 0xFF08090B;
        p.bgMid = 0xFF0D0F12;
        p.bgBottom = 0xFF15181D;
        p.blobA = 0x3D5A7BA8;
        p.blobB = 0x336A5C8C;
        p.blobC = 0x2E3C7A72;

        // Genuinely translucent: the aurora behind the card has to come
        // through, or the slab reads as painted plastic instead of glass.
        p.glassTop = 0x33FFFFFF;
        p.glassBottom = 0x1FFFFFFF;
        p.glassSpec = 0x4DFFFFFF;
        p.glassShade = 0x40000000;
        p.strokeTop = 0x8AFFFFFF;
        p.strokeBottom = 0x24FFFFFF;

        p.text = 0xFFF4F6F9;
        p.dim = 0xFFB3BAC6;
        p.faint = 0xFF8B93A1;
        p.accent = 0xFF64D2FF;
        p.danger = 0xFFFF453A;

        p.fieldFill = 0x2A000000;
        p.divider = 0x26FFFFFF;
        p.track = 0x3AFFFFFF;
        p.trackFillStart = 0xFF8ADCFF;
        p.trackFillEnd = 0xFF2F7CFF;
        p.accentFillTop = 0xFF63D2FF;
        p.accentFillBottom = 0xFF2A6FE0;
        p.stopFillTop = 0xFFFF6F66;
        p.stopFillBottom = 0xFFD62B22;

        p.pillTop = 0x26FFFFFF;
        p.pillBottom = 0x14FFFFFF;
        p.pillSpec = 0x3AFFFFFF;
        p.pillShade = 0x1A000000;
        p.pillRim = 0x3DFFFFFF;

        p.rippleRow = 0x24FFFFFF;
        p.ripplePill = 0x26FFFFFF;
        p.rippleOnAccent = 0x40FFFFFF;
        return p;
    }
}
