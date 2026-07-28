package net.picsoul.rw.minimap.ui;

import java.util.Collections;
import java.util.List;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.assets.TextureAsset;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Time;
import net.risingworld.api.ui.UIElement;
import net.risingworld.api.ui.UILabel;
import net.risingworld.api.ui.UITarget;
import net.risingworld.api.ui.style.Overflow;
import net.risingworld.api.ui.style.Pivot;
import net.risingworld.api.ui.style.Position;
import net.risingworld.api.ui.style.ScaleMode;
import net.risingworld.api.utils.Vector3f;

import net.picsoul.rw.minimap.config.MinimapConfig;
import net.picsoul.rw.minimap.config.PlayerPreferences;
import net.picsoul.rw.minimap.radar.RadarScanner;
import net.picsoul.rw.minimap.render.MapRenderer;
import net.picsoul.rw.minimap.render.MarkerTexture;
import net.picsoul.rw.minimap.session.PlayerSession;
import net.picsoul.rw.minimap.session.SessionRegistry;
import net.picsoul.rw.minimap.waypoint.MapMarker;
import net.picsoul.rw.minimap.waypoint.WaypointService;

public class MinimapHud {

    private final Player player;
    private final Plugin plugin;
    private final MinimapConfig config;
    private final PlayerPreferences prefs;
    private final MapRenderer renderer;
    private final WaypointService waypoints;
    private final SessionRegistry sessions;
    private final RadarScanner radar;

    private MarkerOverlay overlay;
    private Vector3f spawnPos;
    private UIElement mapContainer;
    private UIElement infoContainer;
    private UIElement mapBox;
    private final UIElement[] layers = new UIElement[2];
    private int active = 0;
    private UIElement marker;
    /** Native texture handles we own, tracked so they can be disposed on teardown
     *  (and so the per-frame map texture doesn't leak). Undisposed textures orphan
     *  native asset-registry handles on world switch, which crashes the game when
     *  its own asset-heavy systems (e.g. the default map) run in the next world. */
    private TextureAsset markerTex;
    private final TextureAsset[] layerTex = new TextureAsset[2];
    private OutlinedLabel coordsLabel, timeLabel, dateLabel;
    private OutlinedLabel northLabel, southLabel, eastLabel, westLabel;
    private boolean built = false;

    // Tier flags (driven by equipped/owned items via the session).
    private boolean tierCompass = false;
    private boolean tierWatch = false;
    private boolean tierCalendar = false;
    private boolean tierRadar = false;

    /** When false, the terrain image is not rendered (no chunk reads / textures).
     *  Held false during the post-(re)enable world-load grace window. */
    private boolean renderingEnabled = false;

    private float pxPerCell;
    private int renderCells;
    private float elemPx;
    private float baseOffset;
    private int outPx;
    private int zoomCells; // live zoom (world cells across the map); see setZoom

    private int renderCenterX;
    private int renderCenterZ;
    private boolean hasTexture = false;
    private boolean renderPending = false;
    private boolean incompleteRegion = false;
    private long nextFillRetryNs = 0;
    /** {@code renderer.lifetimeRenders()} as of the last fill-retry attempt -
     *  see the v2.83 fix note above {@code fillDue} in updateView(). */
    private long incompleteAtRenderCount = -1;
    private static final long FILL_RETRY_NS = 400_000_000L;
    private static final int REVEAL_DELAY_TICKS = 3;
    private int pendingRevealIn = 0;
    private int pendingBack, pendingCX, pendingCZ, pendingCY;

    /** True while showing the local cave view instead of the world surface (see
     *  {@link #setCaveMode}). Driven by the session from {@code Player.isInCave()}. */
    private boolean caveMode = false;
    /** The player's Y the current cave-mode texture was rendered at; MIN_VALUE
     *  when not yet rendered or not in cave mode (surface view ignores Y). */
    private int renderCenterY = Integer.MIN_VALUE;
    private float lastTx = Float.NaN;
    private float lastTz = Float.NaN;
    private float lastHeading = Float.NaN;

    private boolean interpInit = false;
    private double sPrevX, sPrevZ, sCurX, sCurZ;
    private long sPrevT, sCurT;
    private double dispX, dispZ;

    private boolean interpHeadingInit = false;
    private double sPrevH, sCurH;
    private long sPrevHT, sCurHT;
    private float dispH;

    public MinimapHud(Plugin plugin, Player player, MinimapConfig config, PlayerPreferences prefs,
                      MapRenderer renderer, WaypointService waypoints, SessionRegistry sessions) {
        this.plugin = plugin;
        this.player = player;
        this.config = config;
        this.prefs = prefs;
        this.renderer = renderer;
        this.waypoints = waypoints;
        this.sessions = sessions;
        this.radar = new RadarScanner(config, player);
        this.zoomCells = prefs.defaultZoomCells;
    }

    /** Minimal-UI mode: a single container holding one plain label. Two UI elements
     *  in total — the smallest plugin UI that still shows something. Used to find
     *  out whether attaching ANY UI after a plugin reload is fatal (/mm minimal). */
    private UILabel minimalLabel;

    private void buildMinimal() {
        int w = prefs.minimapSizePx + 32;
        infoContainer = new UIElement();
        infoContainer.setPivot(prefs.corner.pivot);
        infoContainer.setPosition(prefs.corner.xPercent, prefs.corner.yPercent, true);
        infoContainer.setSize(w, 24, false);
        infoContainer.setBackgroundColor(0f, 0f, 0f, 0f);
        minimalLabel = new UILabel("");
        minimalLabel.setFontSize(14f);
        minimalLabel.setFontColor(1f, 1f, 1f, 1f);
        minimalLabel.setPosition(4f, 4f, false);
        infoContainer.addChild(minimalLabel);
        built = true;
    }

    private void build() {
        if (built) return;
        if (config.minimalUi) { buildMinimal(); return; }
        int size = prefs.minimapSizePx;

        int mapAreaSize = size + 32;
        int infoAreaHeight = 56; // room for coords + optional time + optional date lines

        this.zoomCells = prefs.defaultZoomCells;
        recomputeZoomGeometry();

        // Main container for the map + cardinal labels
        mapContainer = new UIElement();
        mapContainer.setPivot(prefs.corner.pivot);
        mapContainer.setPosition(prefs.corner.xPercent, prefs.corner.yPercent, true);
        mapContainer.setSize(mapAreaSize, mapAreaSize, false);
        mapContainer.setBackgroundColor(0f, 0f, 0f, 0f);

        // Separate, isolated container for the info label
        infoContainer = new UIElement();
        infoContainer.setPivot(prefs.corner.pivot);
        float mapContainerHeightPercent = (float)mapAreaSize / 1080f * 100f;
        // Bottom corners: the info block (coords/time/date) needs to sit ABOVE
        // the map instead of below it, or it either runs off the bottom of the
        // screen or overlaps the map. The corner's pivot already anchors from
        // the bottom for BOTTOM_LEFT/BOTTOM_RIGHT (Pivot.Lower*, growing
        // upward from the given Y) same as it does for the map box itself, so
        // subtracting the map's height instead of adding it places the info
        // block's bottom edge exactly at the map's top edge - directly above,
        // not overlapping - with no other change needed.
        boolean bottomCorner = prefs.corner == MinimapConfig.Corner.BOTTOM_LEFT
                || prefs.corner == MinimapConfig.Corner.BOTTOM_RIGHT;
        float infoY = bottomCorner
                ? prefs.corner.yPercent - mapContainerHeightPercent
                : prefs.corner.yPercent + mapContainerHeightPercent;
        infoContainer.setPosition(prefs.corner.xPercent, infoY, true);
        infoContainer.setSize(mapAreaSize, infoAreaHeight, false);
        infoContainer.setBackgroundColor(0f, 0f, 0f, 0f);

        mapBox = new UIElement();
        mapBox.setPivot(Pivot.MiddleCenter);
        mapBox.setPosition(50, 50, true);
        mapBox.setSize(size, size, false);
        mapBox.setBackgroundColor(0.10f, 0.12f, 0.14f, 1f);
        mapBox.setBorder(2f);
        mapBox.setBorderColor(1f, 1f, 1f, 0.35f);
        mapBox.style.overflow.set(Overflow.Hidden);
        mapBox.setBorderEdgeRadius(50f, true); // Always circular
        mapContainer.addChild(mapBox);

        for (int i = 0; i < 2; i++) {
            UIElement img = new UIElement();
            img.style.position.set(Position.Absolute);
            img.setSize(elemPx, elemPx, false);
            img.style.left.set(baseOffset);
            img.style.top.set(baseOffset);
            img.style.backgroundImageScaleMode.set(ScaleMode.StretchToFill);
            mapBox.addChild(img);
            layers[i] = img;
        }
        layers[0].setOpacity(1f);
        layers[1].setOpacity(0f);

        marker = new UIElement();
        marker.style.position.set(Position.Absolute);
        marker.setSize(config.markerSizePx, config.markerSizePx, false);
        marker.setPivot(Pivot.MiddleCenter);
        marker.setPosition(50, 50, true);
        byte[] tearPng = config.useTextures ? MarkerTexture.teardrop(96) : null;
        if (tearPng != null) {
            markerTex = TextureAsset.load(AssetSalt.unique(tearPng));
            marker.style.backgroundImage.set(markerTex);
            marker.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
        } else {
            marker.setBackgroundColor(1f, 0.85f, 0.2f, 1f);
            marker.setBorderEdgeRadius(50f, true);
        }
        mapBox.addChild(marker);

        // Stacked readout lines under the map: coordinates (always), then time
        // (pocket-watch tier), then date (calendar tier).
        float infoCx = mapAreaSize / 2f;
        coordsLabel = new OutlinedLabel("", 14f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        coordsLabel.setPosition(infoCx, 10f, false);
        coordsLabel.addTo(infoContainer);

        timeLabel = new OutlinedLabel("", 13f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        timeLabel.setPosition(infoCx, 28f, false);
        timeLabel.setVisible(false);
        timeLabel.addTo(infoContainer);

        dateLabel = new OutlinedLabel("", 13f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        dateLabel.setPosition(infoCx, 45f, false);
        dateLabel.setVisible(false);
        dateLabel.addTo(infoContainer);

        northLabel = new OutlinedLabel("N", 15f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        southLabel = new OutlinedLabel("S", 15f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        eastLabel = new OutlinedLabel("E", 15f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        westLabel = new OutlinedLabel("W", 15f, config.textOutlineWidth,
                config.textBackingAlpha, config.textPaddingPx);
        for (OutlinedLabel label : new OutlinedLabel[]{northLabel, southLabel, eastLabel, westLabel}) {
            label.addTo(mapContainer);
        }

        overlay = new MarkerOverlay(config, prefs, size, mapAreaSize, mapContainer, plugin);
        mapContainer.addChild(overlay.root());

        built = true;
    }

    public void attach() {
        build();
        if (config.minimalUi) {
            player.addUIElement(infoContainer, UITarget.HUD);
            return;
        }
        player.addUIElement(mapContainer, UITarget.HUD);
        player.addUIElement(infoContainer, UITarget.HUD);
        updateInfo(player.getPosition());
    }

    public void detach() {
        if (config.minimalUi) {
            if (infoContainer != null) player.removeUIElement(infoContainer);
            return;
        }
        if (mapContainer != null) {
            player.removeUIElement(mapContainer);
        }
        if (infoContainer != null) {
            player.removeUIElement(infoContainer);
        }
    }

    // No longer needed, but kept in case of future features
    public void rebuild() {
        detach();
        built = false;
        attach();
    }

    /**
     * Apply the player's tier flags: compass (cardinals + waypoint/spawn overlay),
     * watch (time line), calendar (date line), radar (nearby animal/npc blips —
     * the upgraded compassmodern only; see {@link net.picsoul.rw.minimap.capability.Capabilities}).
     * Called by the session whenever the player's equipped/owned items change.
     */
    public void setTiers(boolean compass, boolean watch, boolean calendar, boolean radarTier) {
        this.tierCompass = compass;
        this.tierWatch = watch;
        this.tierCalendar = calendar;
        this.tierRadar = radarTier;
        if (!built || config.minimalUi) return;
        for (OutlinedLabel label : new OutlinedLabel[]{northLabel, southLabel, eastLabel, westLabel}) {
            label.setVisible(compass);
            label.updateStyle();
        }
        if (timeLabel != null) {
            timeLabel.setVisible(watch);
            timeLabel.updateStyle();
        }
        if (dateLabel != null) {
            dateLabel.setVisible(calendar);
            dateLabel.updateStyle();
        }
        if (!compass && overlay != null) overlay.hide();
        else if (!radarTier && overlay != null) overlay.hideRadar();
    }

    public void updateInfo(Vector3f pos) {
        if (config.minimalUi) {
            if (minimalLabel != null) {
                minimalLabel.setText(String.format("X %.0f  Y %.0f  Z %.0f", pos.x, pos.y, pos.z));
                minimalLabel.updateStyle();
            }
            return;
        }
        if (!built || coordsLabel == null) return;

        updateSmoothedHeading(player.getHeading());
        coordsLabel.setText(String.format("X %.0f  Y %.0f  Z %.0f", pos.x, pos.y, pos.z));

        if (tierWatch) {
            timeLabel.setText(formatTime());
            timeLabel.updateStyle();
        }
        if (tierCalendar) {
            dateLabel.setText(formatDate());
            dateLabel.updateStyle();
        }

        float heading = this.dispH;

        if (prefs.rotate) {
            mapBox.style.rotate.set(-heading);
            marker.style.rotate.set(heading);
        } else {
            mapBox.style.rotate.set(0);
            marker.style.rotate.set(heading);
        }
        mapBox.updateStyle();
        marker.updateStyle();
        if (tierCompass) {
            // v2.84 fix: this used to always swing the N/S/E/W ring by
            // -heading, even with "rotate with heading" OFF - so in the
            // default north-up mode (where the map image itself never
            // rotates, only the player marker does), the cardinal ring was
            // incorrectly spinning around anyway. Only rotate-with-heading
            // mode should move the ring (to track mapBox's own -heading
            // rotation, see above); north-up mode keeps it fixed (N always
            // at top), matching mapBox staying at rotate(0).
            updateCardinalLabels(prefs.rotate ? heading : 0f);
        }
        lastHeading = heading;
    }

    private static final String[] SEASONS = {"Spring", "Summer", "Autumn", "Winter"};

    /** Current in-game time, e.g. "2:05 PM" (12h) or "14:05" (24h). */
    @SuppressWarnings("deprecation")
    private String formatTime() {
        try {
            Time t = Server.getGameTime();
            int h = t.getHours();
            int m = t.getMinutes();
            if (config.time24Hour) {
                return String.format("%02d:%02d", h, m);
            }
            int h12 = h % 12;
            if (h12 == 0) h12 = 12;
            return String.format("%d:%02d %s", h12, m, h < 12 ? "AM" : "PM");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Current in-game date, e.g. "Spring, Day 8  Yr 0". Rising World has no months:
     * a year is 124 days = 4 seasons × 31 days, so we derive the season and the
     * day-within-season from {@code Time.getDay()} (day of year, 1-124).
     */
    @SuppressWarnings("deprecation")
    private String formatDate() {
        try {
            Time t = Server.getGameTime();
            int doy = t.getDay();               // day of year, 1-124
            if (doy < 1) doy = 1;
            int seasonIdx = Math.min(3, (doy - 1) / 31);
            int dayOfSeason = ((doy - 1) % 31) + 1;
            StringBuilder sb = new StringBuilder();
            if (config.dateShowSeason) {
                sb.append(SEASONS[seasonIdx]).append(", ");
            }
            sb.append("Day ").append(dayOfSeason);
            if (config.dateShowYear) {
                sb.append("  Yr ").append(t.getYear());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void updateSmoothedHeading(float rawH) {
        if (!config.smoothPanning) {
            dispH = rawH;
            return;
        }
        long now = System.nanoTime();
        if (!interpHeadingInit) {
            sPrevH = sCurH = dispH = rawH;
            sPrevHT = sCurHT = now;
            interpHeadingInit = true;
            return;
        }

        double h = rawH;
        double diffToCurrent = h - sCurH;
        if (diffToCurrent > 180) {
            h -= 360;
        } else if (diffToCurrent < -180) {
            h += 360;
        }
        
        if (Math.abs(h - sCurH) > 0.1) {
            sPrevH = sCurH;
            sPrevHT = sCurHT;
            sCurH = h;
            sCurHT = now;
        }

        long interval = sCurHT - sPrevHT;
        if (interval <= 0) {
            dispH = (float)sCurH;
            return;
        }
        
        double livePrevH = sPrevH;
        double diffPrevCurrent = sCurH - livePrevH;
        if(diffPrevCurrent > 180) {
            livePrevH += 360;
        } else if (diffPrevCurrent < -180) {
            livePrevH -= 360;
        }

        double alpha = (double) (now - sCurHT) / (double) interval;
        if (alpha < 0d) alpha = 0d;
        else if (alpha > 1d) alpha = 1d;
        
        double interpolated = livePrevH + (sCurH - livePrevH) * alpha;
        dispH = (float)interpolated;
    }

    private void updateCardinalLabels(float playerHeading) {
        float angleForNorth = -playerHeading;

        int size = prefs.minimapSizePx;
        int mapAreaSize = size + 32;
        float radius = size / 2f + 12f;
        float centerX = mapAreaSize / 2f;
        float centerY = mapAreaSize / 2f;

        double northAngle = Math.toRadians(angleForNorth);
        northLabel.setPosition((float)(centerX + radius * Math.sin(northAngle)), (float)(centerY - radius * Math.cos(northAngle)), false);
        northLabel.updateStyle();

        double southAngle = Math.toRadians(angleForNorth + 180);
        southLabel.setPosition((float)(centerX + radius * Math.sin(southAngle)), (float)(centerY - radius * Math.cos(southAngle)), false);
        southLabel.updateStyle();

        double eastAngle = Math.toRadians(angleForNorth + 90);
        eastLabel.setPosition((float)(centerX + radius * Math.sin(eastAngle)), (float)(centerY - radius * Math.cos(eastAngle)), false);
        eastLabel.updateStyle();
        
        double westAngle = Math.toRadians(angleForNorth - 90);
        westLabel.setPosition((float)(centerX + radius * Math.sin(westAngle)), (float)(centerY - radius * Math.cos(westAngle)), false);
        westLabel.updateStyle();
    }

    public void updateView(double worldX, double doubleZ) {
        if (!built || config.minimalUi) return;

        updateSmoothed(worldX, doubleZ);

        if (pendingRevealIn > 0) {
            float ptx = -(float) (dispX - pendingCX) * pxPerCell;
            float ptz = (float) (dispZ - pendingCZ) * pxPerCell;
            layers[pendingBack].style.translate.set(ptx, ptz);
            layers[pendingBack].updateStyle();
            pendingRevealIn--;
            if (pendingRevealIn == 0) {
                int old = active;
                renderCenterX = pendingCX;
                renderCenterZ = pendingCZ;
                renderCenterY = pendingCY;
                layers[pendingBack].setOpacity(1f);
                layers[pendingBack].updateStyle();
                if (old != pendingBack) {
                    layers[old].setOpacity(0f);
                    layers[old].updateStyle();
                }
                active = pendingBack;
                hasTexture = true;
                lastTx = ptx;
                lastTz = ptz;
            }
        }

        double threshold = config.panPaddingCells * 0.8;
        boolean movedFar = Math.abs(worldX - renderCenterX) > threshold
                || Math.abs(doubleZ - renderCenterZ) > threshold;
        // Cave mode's view also depends on the player's altitude (climbing or
        // dropping a level changes what's visible), which the surface view never
        // needs to care about since it always looks straight down from the sky.
        int py = 0;
        if (caveMode) {
            py = (int) Math.round(player.getPosition().y);
            if (renderCenterY != Integer.MIN_VALUE
                    && Math.abs(py - renderCenterY) > config.caveYMoveThreshold) {
                movedFar = true;
            }
        }
        // v2.20: progressive fill-in RESTORED (it was removed in v2.19 on the theory
        // that texture churn caused the world-switch crash — disproven: the crash
        // happened on the FIRST map texture of the new world, with no churn at all.
        // The real cause was duplicate asset names; see AssetSalt.) Without this,
        // chunks that stream in after you stop are missing from the map until you
        // move, which looked bad.
        //
        // v2.83: this used to fire unconditionally every FILL_RETRY_NS (400ms)
        // whenever incomplete, re-encoding and creating a brand-new texture
        // even when NOTHING new had actually become available since the last
        // attempt — under the v2.82 chunk-render rate limiter (which
        // deliberately holds back new tiles), regions stay incomplete far
        // more of the time, so this was firing near-continuously: one live
        // session logged 915 texture creates in ~7 minutes, only 140 of
        // which were real chunk renders — the other 430+ were wasted,
        // near-blank re-encodes of unchanged data (visible as the map
        // looking blank a lot). Now only actually retries once
        // MapRenderer.lifetimeRenders() has moved since the last attempt;
        // otherwise it just pushes the retry window out again for free.
        boolean fillDue = incompleteRegion && System.nanoTime() >= nextFillRetryNs;
        // Cave mode never touches the surface TileCache (renderCaveAsync reads
        // fresh every call, no cache - see MapRenderer#renderCaveAsync's own
        // doc), so lifetimeRenders() would never move during cave viewing;
        // only apply the skip-if-unchanged check to the surface path.
        if (fillDue && !caveMode && renderer.lifetimeRenders() == incompleteAtRenderCount) {
            fillDue = false;
            nextFillRetryNs = System.nanoTime() + FILL_RETRY_NS;
        }
        boolean needRender = !hasTexture || movedFar || fillDue;

        if (renderingEnabled && config.useTextures && needRender && !renderPending && pendingRevealIn == 0) {
            if (config.diagFakeTextureChurn) {
                // Same "handled" bookkeeping a real render does (renderCenterX/Z,
                // hasTexture, incompleteRegion), so this recurs at the same
                // natural movedFar/fillDue cadence a real render would - the
                // only difference is fakeChurnTick() never touches World/Chunk.
                fakeChurnTick();
                renderCenterX = (int) Math.round(worldX);
                renderCenterZ = (int) Math.round(doubleZ);
                hasTexture = true;
                incompleteRegion = false;
            } else {
                renderPending = true;
                final int ncx = (int) Math.round(worldX);
                final int ncz = (int) Math.round(doubleZ);
                if (caveMode) {
                    final int ncy = py;
                    renderer.renderCaveAsync(ncx, ncy, ncz, renderCells, outPx,
                            (png, complete) -> onRenderDone(png, ncx, ncz, ncy, complete));
                } else {
                    renderer.renderAsync(ncx, ncz, renderCells, outPx, prefs.contourEnabled,
                            (png, complete) -> onRenderDone(png, ncx, ncz, Integer.MIN_VALUE, complete));
                }
            }
        }

        if (hasTexture) {
            float tx = -(float) (dispX - renderCenterX) * pxPerCell;
            float tz = (float) (dispZ - renderCenterZ) * pxPerCell;
            if (tx != lastTx || tz != lastTz) {
                layers[active].style.translate.set(tx, tz);
                layers[active].updateStyle();
                lastTx = tx;
                lastTz = tz;
            }
        }

        drawOverlay(worldX, doubleZ);
    }

    /** Recompute all map geometry derived from the current zoom — {@link #zoomCells}
     *  normally, or {@code config.caveZoomCells} while {@link #caveMode} is on. */
    private void recomputeZoomGeometry() {
        int size = prefs.minimapSizePx;
        int cells = caveMode ? config.caveZoomCells : zoomCells;
        pxPerCell = (float) size / cells;
        renderCells = cells + 2 * config.panPaddingCells;
        elemPx = renderCells * pxPerCell;
        baseOffset = (size - elemPx) / 2f;
        outPx = renderCells * config.superSample;
    }

    /** The current surface zoom (world cells across the minimap). Unaffected by
     *  cave mode, which has its own independent zoom ({@code config.caveZoomCells}). */
    public int getZoom() {
        return zoomCells;
    }

    /** Recompute geometry for the current zoom/mode, resize the two image layers
     *  to match, and force a clean re-render (drop any in-flight reveal). Shared
     *  by zoom changes and cave-mode toggling, since both change what a "cell" of
     *  render region covers. */
    private void applyGeometryChange() {
        recomputeZoomGeometry();
        for (UIElement layer : layers) {
            if (layer == null) continue;
            layer.setSize(elemPx, elemPx, false);
            layer.style.left.set(baseOffset);
            layer.style.top.set(baseOffset);
            layer.style.translate.set(0f, 0f);
            layer.updateStyle();
        }
        hasTexture = false;
        renderPending = false;
        pendingRevealIn = 0;
        lastTx = Float.NaN;
        lastTz = Float.NaN;
    }

    /**
     * Change the zoom live (world cells across the minimap). Recomputes the map
     * geometry, resizes the two image layers, and forces a fresh render. Safe to
     * call any time after {@link #build()}; a no-op in minimal-UI mode.
     */
    public void setZoom(int cells) {
        if (!built || config.minimalUi) return;
        int min = 16, max = 1024;
        cells = Math.max(min, Math.min(max, cells));
        if (cells == zoomCells) return;
        zoomCells = cells;
        if (!caveMode) applyGeometryChange();
    }

    /**
     * Apply a live {@code prefs.minimapSizePx} or {@code prefs.corner}
     * change by resizing/repositioning the existing top-level containers in
     * place — the same "mutate, don't recreate" approach {@link #setZoom}
     * already uses for live zoom changes — rather than a full element-tree
     * teardown and rebuild.
     *
     * <p>This replaced an earlier version that called
     * {@code PlayerSession.rebuildHud()} on every settings-panel map-size/
     * corner change: a full rebuild recreates every pooled UI element (the
     * waypoint/radar/other-player icon pools, name labels, dashes — several
     * hundred elements), and the live game log showed a burst of rapid clicks
     * along the size slider (or the corner button) driving UI element ids
     * into the high 2000s within one session before the game crashed — twice,
     * independently, in roughly the same id range, on two separate settings
     * (size, then corner). A resize/reposition never needed new elements at
     * all: {@link #recomputeZoomGeometry()} already reads
     * {@code prefs.minimapSizePx} fresh, {@link #applyGeometryChange()}
     * already resizes the two image layers in place, the cardinal labels
     * already recompute their position every frame in
     * {@link #updateCardinalLabels}, and {@link MarkerOverlay#updateGeometry}
     * (new) resizes its clip box and updates the three fields every pooled
     * element's position is computed from — none of that touches the element
     * COUNT, so nothing needs recreating.
     */
    public void applyLayoutChange() {
        if (!built || config.minimalUi) return;
        int size = prefs.minimapSizePx;
        int mapAreaSize = size + 32;
        int infoAreaHeight = 56;

        mapContainer.setPivot(prefs.corner.pivot);
        mapContainer.setPosition(prefs.corner.xPercent, prefs.corner.yPercent, true);
        mapContainer.setSize(mapAreaSize, mapAreaSize, false);
        mapContainer.updateStyle();

        float mapContainerHeightPercent = (float) mapAreaSize / 1080f * 100f;
        boolean bottomCorner = prefs.corner == MinimapConfig.Corner.BOTTOM_LEFT
                || prefs.corner == MinimapConfig.Corner.BOTTOM_RIGHT;
        float infoY = bottomCorner
                ? prefs.corner.yPercent - mapContainerHeightPercent
                : prefs.corner.yPercent + mapContainerHeightPercent;
        infoContainer.setPivot(prefs.corner.pivot);
        infoContainer.setPosition(prefs.corner.xPercent, infoY, true);
        infoContainer.setSize(mapAreaSize, infoAreaHeight, false);
        infoContainer.updateStyle();

        mapBox.setSize(size, size, false);
        mapBox.updateStyle();

        if (!caveMode) applyGeometryChange();
        if (overlay != null) overlay.updateGeometry(size, mapAreaSize);
    }

    /** True while showing the local cave view (see {@link #setCaveMode}). */
    public boolean isCaveMode() {
        return caveMode;
    }

    /**
     * Switch between the normal surface map and the local cave view. Driven by
     * the session from {@code Player.isInCave()} (with debounce — see
     * {@code PlayerSession.tick()} — so standing at a cave mouth doesn't flicker
     * between the two). Recomputes geometry for cave mode's own zoom, tints the
     * map border as a visual cue, and forces a fresh render since the data source
     * itself changed. A no-op in minimal-UI mode (no map circle to switch).
     */
    public void setCaveMode(boolean on) {
        if (!built || config.minimalUi) return;
        if (this.caveMode == on) return;
        this.caveMode = on;
        if (mapBox != null) {
            if (on) {
                mapBox.setBorderColor(0.95f, 0.55f, 0.15f, 0.75f);
            } else {
                mapBox.setBorderColor(1f, 1f, 1f, 0.35f);
            }
            mapBox.updateStyle();
        }
        applyGeometryChange();
        renderCenterY = Integer.MIN_VALUE;
    }

    /** Enable/disable terrain rendering (deferred during the world-load grace window). */
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
    }

    /** Set the world position of the player's current spawn point (from the
     *  session, which tracks it via the spawn events). Null hides the spawn line. */
    public void setSpawn(Vector3f sp) {
        this.spawnPos = sp;
    }

    /** Draw the waypoint + spawn overlay for this frame (compass tier only), and
     *  the radar blip overlay (radar tier only — see {@link #setTiers}). */
    private void drawOverlay(double playerX, double playerZ) {
        if (overlay == null) return;
        if (!tierCompass) {
            overlay.hide();
            return;
        }
        Vector3f sp = spawnPos;
        List<MapMarker> mk = (waypoints != null) ? waypoints.getMarkers() : Collections.emptyList();
        overlay.draw(dispX, dispZ, dispH, pxPerCell, prefs.rotate, mk,
                sp != null, sp != null ? sp.x : 0d, sp != null ? sp.z : 0d,
                playerX, playerZ, viewerDbId());

        if (tierRadar && config.showRadar) {
            float py = 0f;
            try {
                py = player.getPosition().y;
            } catch (Throwable ignored) {
            }
            int visibleZoomCells = caveMode ? config.caveZoomCells : zoomCells;
            radar.maybeScan(new Vector3f((float) playerX, py, (float) playerZ), visibleZoomCells);
            overlay.drawRadar(dispX, dispZ, dispH, pxPerCell, prefs.rotate, radar.getBlips());
        } else {
            overlay.hideRadar();
        }

        if (prefs.showOtherPlayers) {
            overlay.drawOtherPlayers(dispX, dispZ, dispH, pxPerCell, prefs.rotate, gatherOtherPlayers());
        } else {
            overlay.hideOtherPlayers();
        }
    }

    /** Every other currently-connected player, as a fixed name + live position/
     *  heading snapshot for this frame. Cheap (just accessors on already-connected
     *  Player objects, no world/DB IO), so unlike the npc radar this isn't
     *  throttled — capped at {@code maxOtherPlayersTracked}, nearest-first. */
    private List<OtherPlayerBlip> gatherOtherPlayers() {
        Player[] all;
        try {
            all = Server.getAllPlayers();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (all == null || all.length == 0) return Collections.emptyList();

        double px = dispX, pz = dispZ;
        java.util.ArrayList<Player> others = new java.util.ArrayList<>(all.length);
        for (Player p : all) {
            if (p == null || p == player) continue;
            PlayerSession ps = sessions.get(p);
            if (ps != null && ps.getPrefs().hiddenFromOthers) continue;
            others.add(p);
        }
        if (others.isEmpty()) return Collections.emptyList();
        others.sort((a, b) -> Double.compare(dist2To(a, px, pz), dist2To(b, px, pz)));

        int max = Math.max(1, config.maxOtherPlayersTracked);
        List<OtherPlayerBlip> out = new java.util.ArrayList<>(Math.min(others.size(), max));
        for (Player p : others) {
            if (out.size() >= max) break;
            try {
                Vector3f pos = p.getPosition();
                if (pos == null) continue;
                String name = p.getName();
                out.add(new OtherPlayerBlip(pos.x, pos.z, p.getHeading(), name != null ? name : "?"));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static double dist2To(Player p, double x, double z) {
        try {
            Vector3f pos = p.getPosition();
            double dx = pos.x - x, dz = pos.z - z;
            return dx * dx + dz * dz;
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }

    /** The viewing player's database id, cached (constant for the session). Used to
     *  decide which "default" (private) markers belong to this player. */
    private int cachedDbId = Integer.MIN_VALUE;
    private int viewerDbId() {
        if (cachedDbId == Integer.MIN_VALUE) {
            try {
                cachedDbId = player.getDbID();
            } catch (Throwable t) {
                return -1;
            }
        }
        return cachedDbId;
    }

    private void updateSmoothed(double rawX, double rawZ) {
        if (!config.smoothPanning) {
            dispX = rawX;
            dispZ = rawZ;
            return;
        }
        long now = System.nanoTime();
        if (!interpInit) {
            sPrevX = sCurX = dispX = rawX;
            sPrevZ = sCurZ = dispZ = rawZ;
            sPrevT = sCurT = now;
            interpInit = true;
            return;
        }
        double dx = rawX - sCurX, dz = rawZ - sCurZ;
        double snap = config.interpSnapCells;
        if (dx * dx + dz * dz > snap * snap) {
            sPrevX = sCurX = dispX = rawX;
            sPrevZ = sCurZ = dispZ = rawZ;
            sPrevT = sCurT = now;
            return;
        }
        if (Math.abs(dx) > 1e-4 || Math.abs(dz) > 1e-4) {
            sPrevX = sCurX;
            sPrevZ = sCurZ;
            sPrevT = sCurT;
            sCurX = rawX;
            sCurZ = rawZ;
            sCurT = now;
        }
        long interval = sCurT - sPrevT;
        if (interval <= 0) {
            dispX = sCurX;
            dispZ = sCurZ;
            return;
        }
        double alpha = (double) (now - sCurT) / (double) interval;
        if (alpha < 0d) alpha = 0d;
        else if (alpha > 1d) alpha = 1d;
        dispX = sPrevX + (sCurX - sPrevX) * alpha;
        dispZ = sPrevZ + (sCurZ - sPrevZ) * alpha;
    }

    /** Cached once so {@link #fakeChurnTick()} only pays the AssetSalt +
     *  TextureAsset cost per call, not PNG generation too. */
    private byte[] fakeChurnPng;

    /** Diagnostic (v2.79, {@code config.diagFakeTextureChurn}): creates and
     *  immediately disposes one native texture, at the same rate a real
     *  render would happen, with zero {@code World}/{@code Chunk} calls -
     *  isolates the render pipeline's texture-asset churn from its chunk
     *  reads. See the field doc on {@code MinimapConfig#diagFakeTextureChurn}. */
    private void fakeChurnTick() {
        if (fakeChurnPng == null) {
            fakeChurnPng = MarkerTexture.teardrop(8);
        }
        if (fakeChurnPng == null) return;
        TextureAsset t = TextureAsset.load(AssetSalt.unique(fakeChurnPng));
        disposeAsset(t);
    }

    private void onRenderDone(byte[] png, int ncx, int ncz, int ncy, boolean complete) {
        renderPending = false;
        incompleteRegion = !complete;
        if (!complete) {
            nextFillRetryNs = System.nanoTime() + FILL_RETRY_NS;
            incompleteAtRenderCount = renderer.lifetimeRenders();
        }
        if (!built || png == null) return;

        int back = 1 - active;
        UIElement target = layers[back];
        target.setOpacity(0f);
        // The back layer is the hidden one; free the texture it previously held
        // before assigning a new one, so we don't leak a texture every render.
        disposeAsset(layerTex[back]);
        TextureAsset tex = TextureAsset.load(AssetSalt.unique(png));
        layerTex[back] = tex;
        target.style.backgroundImage.set(tex);

        float tx = -(float) (dispX - ncx) * pxPerCell;
        float tz = (float) (dispZ - ncz) * pxPerCell;
        target.style.translate.set(tx, tz);
        target.updateStyle();

        pendingBack = back;
        pendingCX = ncx;
        pendingCZ = ncz;
        pendingCY = ncy;
        pendingRevealIn = REVEAL_DELAY_TICKS;
    }

    public void invalidate() {
        hasTexture = false;
    }

    /**
     * Full teardown: remove the UI from the player and dispose every native
     * texture we created. Called when the session ends — including on world
     * switch (onDisable) — so no orphaned asset handles survive into the next
     * world. Safe to call more than once.
     */
    /** Free only the native textures, leaving every UI element registered
     *  (teardown mode "none"). */
    public void disposeTexturesOnly() {
        if (overlay != null) overlay.dispose();
        disposeAsset(markerTex);
        markerTex = null;
        disposeAsset(layerTex[0]);
        disposeAsset(layerTex[1]);
        layerTex[0] = null;
        layerTex[1] = null;
        hasTexture = false;
    }

    public void dispose() {
        if ("none".equalsIgnoreCase(config.teardownMode)) {
            disposeTexturesOnly();
            return;
        }
        if ("roots".equalsIgnoreCase(config.teardownMode)) {
            try { detach(); } catch (Throwable ignored) { }
            disposeTexturesOnly();
            return;
        }
        // Full teardown (config.teardownMode == "full").
        rebuildElements();
    }

    /**
     * Unconditionally purges the whole element tree and resets {@code built}
     * to false, so the next {@link #attach()} calls {@link #build()} fresh —
     * regardless of {@code config.teardownMode}.
     *
     * <p>This is deliberately a DIFFERENT method from {@link #dispose()}:
     * {@code dispose()} is only ever called from {@code onDisable}/world-switch
     * teardown, where {@code teardownMode} defaults to {@code "none"} on
     * purpose (removing elements while the plugin unloads is what crashes the
     * *next* world — see the v2.30 fix). This method is for LIVE structural
     * rebuilds during normal play instead (the settings panel's map-size/corner
     * changes, {@code /mm uilite}/{@code minimal}/{@code notex}) — there's no
     * unload-timing crash risk mid-session, and skipping the real purge here
     * (as calling the teardown-mode-gated {@code dispose()} used to) left
     * {@code built} stuck true forever, so a new size/corner was written to
     * config but {@link #build()}'s {@code if (built) return;} guard silently
     * kept the OLD geometry — the settings panel's map-size slider and corner
     * button had no visible effect because of exactly this.
     */
    public void rebuildElements() {
        // Removing only the two root containers leaves every child element
        // registered with the game's PluginUIManager — recursively detach every
        // descendant, remove the roots, then sweep up anything still registered.
        try {
            purgeChildren(mapContainer);
            purgeChildren(infoContainer);
        } catch (Throwable ignored) {
        }
        try {
            detach();
        } catch (Throwable ignored) {
        }
        try {
            UIElement[] leftovers = player.getAllUIElements(false); // only this plugin's
            if (leftovers != null) {
                for (UIElement e : leftovers) {
                    try {
                        player.removeUIElement(e);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (overlay != null) {
            overlay.dispose();
        }
        disposeAsset(markerTex);
        markerTex = null;
        disposeAsset(layerTex[0]);
        disposeAsset(layerTex[1]);
        layerTex[0] = null;
        layerTex[1] = null;
        hasTexture = false;
        caveMode = false;
        renderCenterY = Integer.MIN_VALUE;
        // The element tree no longer exists; force a rebuild if we are shown again.
        mapContainer = null;
        infoContainer = null;
        mapBox = null;
        marker = null;
        minimalLabel = null;
        overlay = null;
        layers[0] = null;
        layers[1] = null;
        coordsLabel = null;
        timeLabel = null;
        dateLabel = null;
        northLabel = southLabel = eastLabel = westLabel = null;
        built = false;
    }

    /** Depth-first detach of every descendant, so no child is left registered with
     *  the game's PluginUIManager when the plugin unloads. */
    private static void purgeChildren(UIElement e) {
        if (e == null) return;
        try {
            java.util.List<UIElement> kids = e.getChilds();
            if (kids != null && !kids.isEmpty()) {
                for (UIElement k : new java.util.ArrayList<>(kids)) {
                    purgeChildren(k);
                }
            }
            e.removeAllChilds();
        } catch (Throwable ignored) {
        }
    }

    /** Dispose one texture asset defensively (null-safe, never throws). */
    static void disposeAsset(TextureAsset t) {
        if (t == null) return;
        try {
            if (!t.isDisposed()) t.dispose();
        } catch (Throwable ignored) {
        }
    }
}
