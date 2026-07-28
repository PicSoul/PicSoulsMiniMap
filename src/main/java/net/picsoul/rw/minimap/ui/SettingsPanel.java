package net.picsoul.rw.minimap.ui;

import net.risingworld.api.objects.Player;
import net.risingworld.api.ui.UIElement;
import net.risingworld.api.ui.UILabel;
import net.risingworld.api.ui.UITarget;
import net.risingworld.api.ui.style.Pivot;
import net.risingworld.api.ui.style.Position;
import net.risingworld.api.ui.style.TextAnchor;

import net.picsoul.rw.minimap.config.MinimapConfig;
import net.picsoul.rw.minimap.config.MinimapConfig.Corner;
import net.picsoul.rw.minimap.config.PlayerPreferences;

/**
 * A small, self-contained settings window the player opens with {@code /mm settings}.
 * Styled to loosely match the game's own settings screen (dark panel, gold section
 * headers with a thin separator, two-state ON/OFF segmented toggles) within what this
 * SDK's plain {@link UIElement}/{@link UILabel} primitives can actually do — there's no
 * texture-backed panel decoration, icon font, or dropdown widget available here, so this
 * is the same visual language (not a pixel clone). Built lazily and attached/detached on
 * demand.
 *
 * <p><b>Crash-safety:</b> like the HUD, this panel is only ever detached during
 * normal play (when the player closes it) — never from {@code onDisable}. Removing
 * plugin UI elements while the plugin is being unloaded is what crashed the game on
 * a world switch (see the v2.30 fix); on a world switch the panel's elements are
 * simply left for the game's own {@code Reset PluginUIManager}.
 *
 * <p>The action buttons are clickable {@link UILabel}s; the plugin routes
 * {@code PlayerUIElementClickEvent}s here via {@link #actionFor(UIElement)}.
 */
public final class SettingsPanel {

    /** Result of a click hit-test on the panel. */
    public enum Action {
        NONE, CHANGE_IN, CHANGE_OUT, ICON_SIZE_TRACK, MAP_SIZE_TRACK, CORNER_CYCLE,
        SET_ROTATE_ON, SET_ROTATE_OFF, SET_CONTOUR_ON, SET_CONTOUR_OFF,
        SET_HIDDEN_ON, SET_HIDDEN_OFF, RESET_DEFAULTS, CLOSE
    }

    private static final int PANEL_W = 320;
    /** Left label column width; the control column starts right after it. */
    private static final float LABEL_X = 18f, LABEL_W = 168f;
    private static final float CTRL_X = 194f, CTRL_W = 108f;
    private static final float SLIDER_TRACK_W = PANEL_W - 2 * LABEL_X;

    // Gold section-header / accent-value tone, matching the game's own settings screen.
    private static final float[] GOLD = {0.95f, 0.78f, 0.32f};
    private static final float[] LABEL_COLOR = {0.85f, 0.87f, 0.9f};
    // Segmented ON/OFF toggle tones: active = blue accent, inactive = dim gray - the same
    // pairing the game's own VSync row uses (its Dithering row's red is specific to that
    // one "risky" setting, not a general convention there).
    private static final float[] SEG_ACTIVE = {0.16f, 0.50f, 0.80f};
    private static final float[] SEG_INACTIVE = {0.17f, 0.19f, 0.22f};

    private final Player player;
    private final MinimapConfig config;
    private final PlayerPreferences prefs;

    private UIElement root;
    private UILabel inValue, outValue, hint;
    private UILabel changeInBtn, changeOutBtn, closeBtn, resetBtn;
    /** Entity-icon-size slider: a clickable track the player clicks anywhere
     *  along to set the value (there's no drag event in this SDK, only click -
     *  see {@link net.picsoul.rw.minimap.PicSoulsMiniMap#onUiClick}), a fill
     *  bar showing the current value as a proportion of the track, and a
     *  numeric readout. */
    private UIElement iconSizeTrack, iconSizeFill;
    private UILabel iconSizeValueLabel;
    /** Map-size slider: same click-to-set track/fill/label shape as the icon
     *  size slider above. */
    private UIElement mapSizeTrack, mapSizeFill;
    private UILabel mapSizeValueLabel;
    /** Screen-corner cycle button (click advances to the next corner). */
    private UILabel cornerBtn;
    /** Rotate-with-heading / contour-lines two-state segmented toggles: an ON
     *  half and an OFF half, whichever matches the current value highlighted -
     *  clicking a half explicitly SETS that value (not a blind flip), same as
     *  the game's own ON/OFF rows. */
    private UILabel rotateOnBtn, rotateOffBtn, contourOnBtn, contourOffBtn;
    /** Hide-me-from-others two-state segmented toggle, same shape as rotate/contour. */
    private UILabel hiddenOnBtn, hiddenOffBtn;
    private boolean built = false;
    private boolean open = false;

    public SettingsPanel(Player player, MinimapConfig config, PlayerPreferences prefs) {
        this.player = player;
        this.config = config;
        this.prefs = prefs;
    }

    public boolean isOpen() {
        return open;
    }

    private void build() {
        if (built) return;
        int w = PANEL_W, h = 542;

        root = new UIElement();
        root.setPivot(Pivot.MiddleCenter);
        root.setPosition(50, 50, true);
        root.setSize(w, h, false);
        root.setBackgroundColor(0.09f, 0.10f, 0.12f, 0.96f);
        root.setBorder(2f);
        root.setBorderColor(1f, 1f, 1f, 0.35f);

        addText("PicSouls MiniMap — Settings", 0, 12, w, 22, 16f, TextAnchor.UpperCenter,
                1f, 1f, 1f, 1f);

        float y = 42f;
        y = addSectionHeader("ZOOM", y);
        addText("Zoom in", LABEL_X, y, 90, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        inValue = addText("", 96, y, 96, 20, 13f, TextAnchor.MiddleCenter, 1f, 0.9f, 0.4f, 1f);
        changeInBtn = addButton("Change", CTRL_X, y - 2, CTRL_W, 24);
        y += 30f;
        addText("Zoom out", LABEL_X, y, 90, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        outValue = addText("", 96, y, 96, 20, 13f, TextAnchor.MiddleCenter, 1f, 0.9f, 0.4f, 1f);
        changeOutBtn = addButton("Change", CTRL_X, y - 2, CTRL_W, 24);
        y += 38f;

        y = addSectionHeader("MAP DISPLAY", y);
        addText("Entity icon size", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        iconSizeValueLabel = addText("", CTRL_X, y, CTRL_W, 20, 13f, TextAnchor.MiddleCenter, 1f, 0.9f, 0.4f, 1f);
        y += 24f;
        iconSizeTrack = addSliderTrack(y);
        iconSizeFill = addSliderFill(iconSizeTrack);
        y += 30f;
        addText("Map size", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        mapSizeValueLabel = addText("", CTRL_X, y, CTRL_W, 20, 13f, TextAnchor.MiddleCenter, 1f, 0.9f, 0.4f, 1f);
        y += 24f;
        mapSizeTrack = addSliderTrack(y);
        mapSizeFill = addSliderFill(mapSizeTrack);
        y += 30f;
        addText("Map corner", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        cornerBtn = addButton("", CTRL_X, y - 2, CTRL_W, 24);
        y += 38f;

        y = addSectionHeader("MAP BEHAVIOR", y);
        addText("Rotate with heading", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        float segW = (CTRL_W - 4f) / 2f;
        rotateOnBtn = addButton("ON", CTRL_X, y - 2, segW, 24);
        rotateOffBtn = addButton("OFF", CTRL_X + segW + 4f, y - 2, segW, 24);
        y += 30f;
        addText("Contour lines", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        contourOnBtn = addButton("ON", CTRL_X, y - 2, segW, 24);
        contourOffBtn = addButton("OFF", CTRL_X + segW + 4f, y - 2, segW, 24);
        y += 30f;
        addText("Hide me from others", LABEL_X, y, LABEL_W, 20, 13f, TextAnchor.MiddleLeft, LABEL_COLOR[0], LABEL_COLOR[1], LABEL_COLOR[2], 1f);
        hiddenOnBtn = addButton("ON", CTRL_X, y - 2, segW, 24);
        hiddenOffBtn = addButton("OFF", CTRL_X + segW + 4f, y - 2, segW, 24);
        y += 40f;

        hint = addText("", 12, y, w - 24, 44, 11f, TextAnchor.UpperCenter, 0.68f, 0.72f, 0.78f, 1f);
        hint.setTextWrap(true);
        y += 50f;

        resetBtn = addButton("Reset to Defaults", w - 218, y, 110, 26);
        closeBtn = addButton("Close", w - 100, y, 82, 26);

        built = true;
        refresh();
    }

    /** A gold, uppercase section header with a thin separator line beneath it,
     *  matching the game's own settings screen. Returns the Y the next row
     *  should start at. */
    private float addSectionHeader(String text, float y) {
        addText(text, LABEL_X, y, PANEL_W - 2 * LABEL_X, 16, 12.5f, TextAnchor.MiddleLeft,
                GOLD[0], GOLD[1], GOLD[2], 1f);
        UIElement line = new UIElement();
        line.style.position.set(Position.Absolute);
        line.style.left.set(LABEL_X);
        line.style.top.set(y + 19f);
        line.setSize(PANEL_W - 2 * LABEL_X, 1f, false);
        line.setBackgroundColor(1f, 1f, 1f, 0.14f);
        root.addChild(line);
        return y + 30f;
    }

    /** A click-to-set slider track (background bar) at a given vertical offset,
     *  spanning {@link #SLIDER_TRACK_W} starting at the same left margin as
     *  every other row. */
    private UIElement addSliderTrack(float y) {
        UIElement track = new UIElement();
        track.style.position.set(Position.Absolute);
        track.style.left.set(LABEL_X);
        track.style.top.set(y);
        track.setSize(SLIDER_TRACK_W, 16, false);
        track.setBackgroundColor(0.16f, 0.18f, 0.21f, 1f);
        track.setBorder(1f);
        track.setBorderColor(1f, 1f, 1f, 0.35f);
        track.setClickable(true);
        root.addChild(track);
        return track;
    }

    /** The fill bar (proportional to the current value) inside a slider track. */
    private UIElement addSliderFill(UIElement track) {
        UIElement fill = new UIElement();
        fill.style.position.set(Position.Absolute);
        fill.style.left.set(0f);
        fill.style.top.set(0f);
        fill.setSize(0, 16, false);
        fill.setBackgroundColor(0.95f, 0.75f, 0.25f, 0.9f);
        track.addChild(fill);
        return fill;
    }

    private UILabel addText(String text, float x, float y, float w, float h, float font,
                            TextAnchor align, float r, float g, float b, float a) {
        UILabel l = new UILabel(text);
        l.style.position.set(Position.Absolute);
        l.style.left.set(x);
        l.style.top.set(y);
        l.setSize(w, h, false);
        l.setFontSize(font);
        l.setFontColor(r, g, b, a);
        l.setTextAlign(align);
        root.addChild(l);
        return l;
    }

    private UILabel addButton(String text, float x, float y, float w, float h) {
        UILabel b = addText(text, x, y, w, h, 13f, TextAnchor.MiddleCenter, 1f, 1f, 1f, 1f);
        b.setBackgroundColor(0.22f, 0.25f, 0.30f, 1f);
        b.setBorder(1f);
        b.setBorderColor(1f, 1f, 1f, 0.4f);
        b.setClickable(true);
        return b;
    }

    /** Update every displayed value from the current config + capture state. */
    public void refresh() {
        if (!built) return;
        inValue.setText("[ " + prefs.zoomInKeyName + " ]");
        inValue.updateStyle();
        outValue.setText("[ " + prefs.zoomOutKeyName + " ]");
        outValue.updateStyle();
        refreshIconSizeVisual();
        refreshMapSizeVisual();
        cornerBtn.setText(cornerLabel(prefs.corner));
        cornerBtn.updateStyle();
        setSegmented(rotateOnBtn, rotateOffBtn, prefs.rotate);
        setSegmented(contourOnBtn, contourOffBtn, prefs.contourEnabled);
        setSegmented(hiddenOnBtn, hiddenOffBtn, prefs.hiddenFromOthers);
        hint.setText("Click Change then press a key to rebind zoom (Esc cancels). Click a"
                + " slider bar to set it. Reset to Defaults resets everything on this page.");
        hint.updateStyle();
    }

    /** Highlight whichever half of a segmented ON/OFF toggle matches {@code on}. */
    private static void setSegmented(UILabel onBtn, UILabel offBtn, boolean on) {
        onBtn.setBackgroundColor(on ? SEG_ACTIVE[0] : SEG_INACTIVE[0],
                on ? SEG_ACTIVE[1] : SEG_INACTIVE[1], on ? SEG_ACTIVE[2] : SEG_INACTIVE[2], 1f);
        offBtn.setBackgroundColor(!on ? SEG_ACTIVE[0] : SEG_INACTIVE[0],
                !on ? SEG_ACTIVE[1] : SEG_INACTIVE[1], !on ? SEG_ACTIVE[2] : SEG_INACTIVE[2], 1f);
        onBtn.updateStyle();
        offBtn.updateStyle();
    }

    /** Sync the icon-size fill width + numeric label to the current config value. */
    private void refreshIconSizeVisual() {
        if (iconSizeFill == null) return;
        float min = config.radarIconSizeMinPx, max = config.radarIconSizeMaxPx;
        float span = Math.max(0.0001f, max - min);
        float frac = (clamp(config.radarIconPx, min, max) - min) / span;
        iconSizeFill.setSize(SLIDER_TRACK_W * frac, 16, false);
        iconSizeFill.updateStyle();
        iconSizeValueLabel.setText(Math.round(config.radarIconPx) + " px");
        iconSizeValueLabel.updateStyle();
    }

    /**
     * Apply a click on the icon-size track: {@code relativeXPercent} is the
     * click's position within the track (0-100, from
     * {@code PlayerUIElementClickEvent.getRelativeMousePositionX()} — this SDK
     * has no drag event, so the slider is click-to-set rather than click-and-drag).
     */
    public void applyIconSizeFromRelativeX(float relativeXPercent) {
        if (!built) return;
        float min = config.radarIconSizeMinPx, max = config.radarIconSizeMaxPx;
        float frac = clamp(relativeXPercent / 100f, 0f, 1f);
        config.radarIconPx = Math.round(min + frac * (max - min));
        refreshIconSizeVisual();
    }

    /** Sync the map-size fill width + numeric label to this player's own preference. */
    private void refreshMapSizeVisual() {
        if (mapSizeFill == null) return;
        float min = config.minimapSizeMinPx, max = config.minimapSizeMaxPx;
        float span = Math.max(0.0001f, max - min);
        float frac = (clamp(prefs.minimapSizePx, min, max) - min) / span;
        mapSizeFill.setSize(SLIDER_TRACK_W * frac, 16, false);
        mapSizeFill.updateStyle();
        mapSizeValueLabel.setText(prefs.minimapSizePx + " px");
        mapSizeValueLabel.updateStyle();
    }

    /**
     * Apply a click on the map-size track. The caller (PicSoulsMiniMap
     * .onUiClick) is responsible for applying the resulting size to the live
     * HUD; this only updates this player's own preference + this panel's own
     * display.
     */
    public void applyMapSizeFromRelativeX(float relativeXPercent) {
        if (!built) return;
        float min = config.minimapSizeMinPx, max = config.minimapSizeMaxPx;
        float frac = clamp(relativeXPercent / 100f, 0f, 1f);
        prefs.minimapSizePx = Math.round(min + frac * (max - min));
        refreshMapSizeVisual();
    }

    /**
     * Advance this player's own corner preference to the next screen corner
     * (wrapping) and update this panel's display. Like map size, the caller is
     * responsible for applying it to the live HUD.
     */
    public void cycleCorner() {
        if (!built) return;
        Corner[] all = Corner.values();
        int next = (prefs.corner.ordinal() + 1) % all.length;
        prefs.corner = all[next];
        cornerBtn.setText(cornerLabel(prefs.corner));
        cornerBtn.updateStyle();
    }

    private static String cornerLabel(Corner c) {
        return switch (c) {
            case TOP_LEFT -> "Top-Left";
            case TOP_RIGHT -> "Top-Right";
            case BOTTOM_LEFT -> "Bottom-Left";
            case BOTTOM_RIGHT -> "Bottom-Right";
        };
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Show the "press a key" prompt on the row being rebound. which: 1=in, 2=out. */
    public void setCapturing(int which) {
        if (!built) return;
        if (which == 1) {
            inValue.setText("press a key…");
            inValue.updateStyle();
        } else if (which == 2) {
            outValue.setText("press a key…");
            outValue.updateStyle();
        } else {
            refresh();
        }
        if (which == 1 || which == 2) {
            hint.setText("Press any key to bind it (Esc cancels).");
            hint.updateStyle();
        }
    }

    public void open() {
        if (open) return;
        build();
        player.addUIElement(root, UITarget.HUD);
        player.setMouseCursorVisible(true);
        open = true;
        refresh();
    }

    public void close() {
        if (!open) return;
        if (root != null) {
            player.removeUIElement(root); // safe: normal play, not plugin unload
        }
        player.setMouseCursorVisible(false);
        open = false;
    }

    public void toggle() {
        if (open) close();
        else open();
    }

    /** @return which action a clicked element maps to (NONE if it's not ours). */
    public Action actionFor(UIElement clicked) {
        if (clicked == null || !built) return Action.NONE;
        if (clicked == changeInBtn) return Action.CHANGE_IN;
        if (clicked == changeOutBtn) return Action.CHANGE_OUT;
        if (clicked == iconSizeTrack) return Action.ICON_SIZE_TRACK;
        if (clicked == mapSizeTrack) return Action.MAP_SIZE_TRACK;
        if (clicked == cornerBtn) return Action.CORNER_CYCLE;
        if (clicked == rotateOnBtn) return Action.SET_ROTATE_ON;
        if (clicked == rotateOffBtn) return Action.SET_ROTATE_OFF;
        if (clicked == contourOnBtn) return Action.SET_CONTOUR_ON;
        if (clicked == contourOffBtn) return Action.SET_CONTOUR_OFF;
        if (clicked == hiddenOnBtn) return Action.SET_HIDDEN_ON;
        if (clicked == hiddenOffBtn) return Action.SET_HIDDEN_OFF;
        if (clicked == resetBtn) return Action.RESET_DEFAULTS;
        if (clicked == closeBtn) return Action.CLOSE;
        return Action.NONE;
    }
}
