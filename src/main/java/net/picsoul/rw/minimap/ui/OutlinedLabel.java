package net.picsoul.rw.minimap.ui;

import net.risingworld.api.ui.UIElement;
import net.risingworld.api.ui.UILabel;
import net.risingworld.api.ui.style.FontStyle;
import net.risingworld.api.ui.style.Pivot;
import net.risingworld.api.ui.style.Position;
import net.risingworld.api.ui.style.TextAnchor;

/**
 * A readable HUD text label: bold white text with a genuine OUTWARD black
 * outline, plus an optional subtle dark rounded backing chip.
 *
 * <p>Why not a single label with {@code style.textOutlineWidth}? Unity UI
 * Toolkit's text outline is centered on the glyph edge, so on small text its
 * inward half eats the letter and the whole glyph turns black (the exact problem
 * we hit). Here two stacked labels give a true outward outline:
 * <ul>
 *   <li>a rear <b>black</b> copy of the text, itself carrying a black outline —
 *       this draws a solid black silhouette slightly larger than the glyph; and</li>
 *   <li>a front <b>white</b> copy with no outline, which fills the silhouette.</li>
 * </ul>
 * Only the outward rim of the black shows, so the white text keeps its full
 * shape. Both layers share the same center pivot, centered alignment, font,
 * size, weight, padding and position, so their glyphs coincide exactly
 * regardless of the backing chip's padding.
 */
public final class OutlinedLabel {

    private final UILabel back;   // black silhouette (+ optional backing chip)
    private final UILabel front;  // white fill, drawn on top

    public OutlinedLabel(String text, float fontSize, float outlineWidth,
                         float backingAlpha, float paddingPx) {
        back = makeLabel(text, fontSize);
        back.setFontColor(0f, 0f, 0f, 1f);
        back.style.textOutlineColor.set(0f, 0f, 0f, 1f);
        back.style.textOutlineWidth.set(Math.max(0f, outlineWidth));
        if (backingAlpha > 0f) {
            back.setBackgroundColor(0f, 0f, 0f, backingAlpha);
            back.setBorderEdgeRadius(45f, true);
        }
        applyPadding(back, paddingPx);

        front = makeLabel(text, fontSize);
        front.setFontColor(1f, 1f, 1f, 1f);
        // No outline on the front layer at all: the rear black silhouette already
        // supplies the outward rim, so the white glyph is never eaten into.
        applyPadding(front, paddingPx);
    }

    private static UILabel makeLabel(String text, float fontSize) {
        UILabel l = new UILabel(text);
        l.setPivot(Pivot.MiddleCenter);
        l.setFontSize(fontSize);
        l.setTextAlign(TextAnchor.MiddleCenter);
        l.style.position.set(Position.Absolute);
        l.style.fontStyleAndWeight.set(FontStyle.Bold);
        return l;
    }

    private static void applyPadding(UILabel l, float padPx) {
        if (padPx <= 0f) return;
        l.style.paddingLeft.set(padPx);
        l.style.paddingRight.set(padPx);
        l.style.paddingTop.set(padPx * 0.4f);
        l.style.paddingBottom.set(padPx * 0.4f);
    }

    /** Add both layers to a parent, rear first so the white fill sits on top. */
    public void addTo(UIElement parent) {
        parent.addChild(back);
        parent.addChild(front);
    }

    public void setText(String text) {
        back.setText(text);
        front.setText(text);
    }

    /** Position both layers identically (percent or pixel, matching UIElement). */
    public void setPosition(float x, float y, boolean percent) {
        back.setPosition(x, y, percent);
        front.setPosition(x, y, percent);
    }

    public void updateStyle() {
        back.updateStyle();
        front.updateStyle();
    }

    public void setVisible(boolean visible) {
        back.setVisible(visible);
        front.setVisible(visible);
    }

    /** Fade both layers together (0..1) — used for the waypoint proximity fade. */
    public void setOpacity(float opacity) {
        back.setOpacity(opacity);
        front.setOpacity(opacity);
    }
}
