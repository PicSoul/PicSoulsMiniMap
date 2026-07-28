package net.picsoul.rw.minimap.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.risingworld.api.Plugin;
import net.risingworld.api.assets.TextureAsset;
import net.risingworld.api.ui.UIElement;
import net.risingworld.api.ui.style.Overflow;
import net.risingworld.api.ui.style.Pivot;
import net.risingworld.api.ui.style.Position;
import net.risingworld.api.ui.style.ScaleMode;

import net.picsoul.rw.minimap.config.MinimapConfig;
import net.picsoul.rw.minimap.config.PlayerPreferences;
import net.picsoul.rw.minimap.radar.RadarBlip;
import net.picsoul.rw.minimap.render.MarkerTexture;
import net.picsoul.rw.minimap.waypoint.MapMarker;

/**
 * Draws the waypoint icons, the spawn marker, and the dashed spawn line over the
 * map circle using pooled <b>child UI elements</b> (textured sprites + colored
 * segment elements), repositioned each frame.
 *
 * <p>Earlier versions drew these on a {@link net.risingworld.api.ui.UIPainter2D}
 * that was cleared and re-tessellated every frame, which flickered (the mesh
 * regenerates each frame; overlapping shapes made it worse). Retained child
 * elements — the same mechanism as the stable player marker and map image — only
 * move/tint/fade, so there is nothing to re-tessellate and no flicker.
 *
 * <p>Icons are single white+dark-halo textures ({@link MarkerTextures}) tinted to
 * each marker's color via {@code backgroundImageTintColor}, and faded via element
 * opacity. Off-map markers clamp to just inside the rim; names show (with
 * hysteresis) only when a marker is clearly inside the map.
 */
public final class MarkerOverlay {

    /** Pool sizes, resolved per HUD from the config (see MinimapConfig.uiLite). */
    private final int maxIcons;
    private final int maxDashes;

    private final MinimapConfig config;
    private final PlayerPreferences prefs;
    private final Plugin plugin;
    private final UIElement box;          // circular clip container
    private final UIElement labelParent;  // non-clipped layer for name labels
    /** Not final: a live map-size change (see {@link #updateGeometry}) updates
     *  these in place instead of rebuilding this overlay from scratch — every
     *  pooled element's on-screen position is already recomputed every frame
     *  from these via {@link #project}, so nothing else needs to change. */
    private float labelOffset;
    private float center;
    private float radius;

    private final TextureAsset[] iconTex = new TextureAsset[MarkerTextures.COUNT];
    private final TextureAsset spawnTex;
    private final TextureAsset radarTex;

    private final UIElement[] iconEls;
    private final int[] iconSlotShape;
    private final UIElement[] dashEls;
    private final UIElement spawnGlyph;
    /** Radar blip pool — a custom per-species icon when the user has dropped one
     *  in {@code MinimapConfig#radarIconsSubfolder} (see {@link #resolveRadarIcon}),
     *  else the same teardrop shape as the player marker (see
     *  {@link MarkerTexture#teardrop(int)}) tinted per npc classification —
     *  rotated to face direction when known either way. */
    private final int maxRadar;
    private final UIElement[] radarEls;
    private final TextureAsset[] radarSlotTex;
    /** Lazily-loaded, cached by lowercase file key (e.g. "wolf_hostile"), so a
     *  successfully-loaded icon isn't re-loaded from disk every frame. A
     *  load failure (missing file, or the load call not succeeding this early
     *  after world load) is NOT cached forever — see {@link #radarIconMissNs}
     *  — it's retried after a cooldown, since there was nothing wrong with the
     *  file itself in that case and it shouldn't need the npc to leave and
     *  re-enter radar range just to reload it. */
    private final Map<String, TextureAsset> radarIconCache = new HashMap<>();
    private final Map<String, Long> radarIconMissNs = new HashMap<>();

    /** Other-connected-player marker pool: same teardrop shape, a fixed
     *  distinguishing tint, always visible (no fade) with a name label that's
     *  never hysteresis-gated — see {@link #drawOtherPlayers}. */
    private final int maxOtherPlayers;
    private final UIElement[] otherPlayerEls;
    private final List<OutlinedLabel> otherPlayerLabels = new ArrayList<>();

    private final List<OutlinedLabel> labelPool = new ArrayList<>();
    private int labelsUsed = 0;

    private final HashMap<Long, Boolean> labelShown = new HashMap<>();
    private boolean spawnLabelShown = false;

    public MarkerOverlay(MinimapConfig config, PlayerPreferences prefs, int minimapSizePx, int mapAreaSize,
                         UIElement labelParent, Plugin plugin) {
        this.config = config;
        this.prefs = prefs;
        this.plugin = plugin;
        this.maxIcons = Math.max(1, config.uiLite ? config.uiLiteIconPool : config.markerIconPool);
        this.maxDashes = Math.max(1, config.uiLite ? config.uiLiteDashPool : config.markerDashPool);
        this.iconEls = new UIElement[maxIcons];
        this.iconSlotShape = new int[maxIcons];
        this.dashEls = new UIElement[maxDashes];
        this.maxRadar = Math.max(1, config.radarMaxTracked);
        this.radarEls = new UIElement[maxRadar];
        this.radarSlotTex = new TextureAsset[maxRadar];
        this.maxOtherPlayers = Math.max(1, config.maxOtherPlayersTracked);
        this.otherPlayerEls = new UIElement[maxOtherPlayers];
        this.labelParent = labelParent;
        this.center = minimapSizePx / 2f;
        this.radius = minimapSizePx / 2f;
        this.labelOffset = (mapAreaSize - minimapSizePx) / 2f;

        box = new UIElement();
        box.setPivot(Pivot.MiddleCenter);
        box.setPosition(50, 50, true);
        box.setSize(minimapSizePx, minimapSizePx, false);
        box.setBackgroundColor(0f, 0f, 0f, 0f);
        box.style.overflow.set(Overflow.Hidden);
        box.setBorderEdgeRadius(50f, true);

        // /mm notex: skip ALL texture creation; markers fall back to colored dots.
        if (config.useTextures) {
            for (int i = 0; i < MarkerTextures.COUNT; i++) {
                byte[] png = MarkerTextures.icon(i);
                iconTex[i] = (png != null) ? TextureAsset.load(AssetSalt.unique(png)) : null;
            }
            byte[] sp = MarkerTextures.spawnDiamond();
            spawnTex = (sp != null) ? TextureAsset.load(AssetSalt.unique(sp)) : null;
            byte[] rp = MarkerTexture.teardrop(64);
            radarTex = (rp != null) ? TextureAsset.load(AssetSalt.unique(rp)) : null;
        } else {
            spawnTex = null;
            radarTex = null;
        }

        // Dashed spawn line: pool of thin colored segment elements.
        for (int i = 0; i < maxDashes; i++) {
            UIElement e = new UIElement();
            e.style.position.set(Position.Absolute);
            e.setPivot(Pivot.MiddleCenter);
            e.setVisible(false);
            box.addChild(e);
            dashEls[i] = e;
        }

        // Spawn glyph sprite.
        spawnGlyph = new UIElement();
        spawnGlyph.style.position.set(Position.Absolute);
        spawnGlyph.setPivot(Pivot.MiddleCenter);
        spawnGlyph.setSize(config.spawnGlyphPx, config.spawnGlyphPx, false);
        if (spawnTex != null) {
            spawnGlyph.style.backgroundImage.set(spawnTex);
            spawnGlyph.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
        } else {
            spawnGlyph.setBorderEdgeRadius(50f, true); // texture-free fallback (/mm notex)
        }
        spawnGlyph.setVisible(false);
        box.addChild(spawnGlyph);

        // Waypoint icon sprites.
        for (int i = 0; i < maxIcons; i++) {
            UIElement e = new UIElement();
            e.style.position.set(Position.Absolute);
            e.setPivot(Pivot.MiddleCenter);
            e.setSize(config.waypointIconPx, config.waypointIconPx, false);
            e.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
            e.setVisible(false);
            box.addChild(e);
            iconEls[i] = e;
            iconSlotShape[i] = -1;
        }

        // Radar blip sprites: same teardrop shape as the player marker, tinted
        // per npc and rotated to its facing when known.
        for (int i = 0; i < maxRadar; i++) {
            UIElement e = new UIElement();
            e.style.position.set(Position.Absolute);
            e.setPivot(Pivot.MiddleCenter);
            e.setSize(config.radarIconPx, config.radarIconPx, false);
            if (radarTex != null) {
                e.style.backgroundImage.set(radarTex);
                e.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
            } else {
                e.setBorderEdgeRadius(50f, true); // texture-free fallback (/mm notex)
            }
            e.setVisible(false);
            box.addChild(e);
            radarEls[i] = e;
        }

        // Other-player marker sprites: same teardrop shape, fixed tint (set once
        // here — unlike radar blips this never changes per-instance, so no
        // per-frame backgroundImageTintColor churn is needed).
        float[] opc = argb(config.otherPlayerColor);
        for (int i = 0; i < maxOtherPlayers; i++) {
            UIElement e = new UIElement();
            e.style.position.set(Position.Absolute);
            e.setPivot(Pivot.MiddleCenter);
            e.setSize(config.otherPlayerIconPx, config.otherPlayerIconPx, false);
            if (radarTex != null) {
                e.style.backgroundImage.set(radarTex);
                e.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
                e.style.backgroundImageTintColor.set(opc[0], opc[1], opc[2], opc[3]);
            } else {
                e.setBackgroundColor(opc[0], opc[1], opc[2], opc[3]);
                e.setBorderEdgeRadius(50f, true); // texture-free fallback (/mm notex)
            }
            e.setVisible(false);
            box.addChild(e);
            otherPlayerEls[i] = e;
        }
        for (int i = 0; i < maxOtherPlayers; i++) {
            OutlinedLabel lbl = new OutlinedLabel("", 12f, config.textOutlineWidth, 0f, 0f);
            lbl.addTo(labelParent);
            lbl.setVisible(false);
            otherPlayerLabels.add(lbl);
        }

        for (int i = 0; i < maxIcons; i++) {
            OutlinedLabel lbl = new OutlinedLabel("", 12f, config.textOutlineWidth, 0f, 0f);
            lbl.addTo(labelParent);
            lbl.setVisible(false);
            labelPool.add(lbl);
        }

        if (config.useTextures) {
            preloadRadarIcons();
        }
    }

    /**
     * Eagerly load every {@code .png} already sitting in the icons folder,
     * right now at HUD-build time — before any npc has ever been drawn —
     * instead of the previous lazy-on-first-sighting approach.
     *
     * <p>The log-confirmed root cause of "my icon doesn't show until I leave
     * and come back": the load itself was never slow or failing (a species
     * with a real file loaded successfully on its very first lazy attempt,
     * logged as effectively instant, right at world load) — but "lazy on
     * first sighting" means that first attempt happens inside the very same
     * {@code drawRadar} call that's trying to render it, so a species already
     * in range at world load could still render one fallback-teardrop frame
     * before its real icon exists. Preloading here removes that window
     * entirely for anything already on disk: by the time the first npc is
     * ever drawn, every existing icon file is already a cache hit.
     */
    private void preloadRadarIcons() {
        if (plugin == null) return;
        try {
            String dir = plugin.getPath();
            if (dir == null || dir.isEmpty()) return;
            File folder = new File(dir, config.radarIconsSubfolder);
            File[] files = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));
            if (files == null || files.length == 0) return;
            int ok = 0;
            for (File f : files) {
                String name = f.getName();
                String key = name.substring(0, name.length() - 4).toLowerCase(); // strip ".png"
                if (iconFor(key) != null) ok++;
            }
            System.out.println("[PicSoulsMiniMap][radar] preloaded " + ok + "/" + files.length
                    + " icon file(s) from " + folder.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("[PicSoulsMiniMap][radar] icon preload failed: " + t);
        }
    }

    public UIElement root() {
        return box;
    }

    /**
     * Live-resize this overlay in place for a new map size, without
     * recreating any pooled child element — used by
     * {@code MinimapHud.applyLayoutChange()} when the settings panel's map-
     * size slider changes {@code prefs.minimapSizePx}. Every pooled
     * element's actual screen position is recomputed every frame from
     * {@code center}/{@code radius}/{@code labelOffset} via {@link #project},
     * so updating just those three fields (plus resizing the clip box itself)
     * is all a resize needs — no element churn at all.
     */
    public void updateGeometry(int minimapSizePx, int mapAreaSize) {
        this.center = minimapSizePx / 2f;
        this.radius = minimapSizePx / 2f;
        this.labelOffset = (mapAreaSize - minimapSizePx) / 2f;
        box.setSize(minimapSizePx, minimapSizePx, false);
        box.updateStyle();
    }

    /** Dispose the baked icon + spawn textures. Called from the HUD's dispose()
     *  on session end / world switch so no native asset handles are orphaned.
     *  The UI elements themselves are removed with the HUD container tree. */
    public void dispose() {
        for (int i = 0; i < iconTex.length; i++) {
            MinimapHud.disposeAsset(iconTex[i]);
            iconTex[i] = null;
        }
        MinimapHud.disposeAsset(spawnTex);
        MinimapHud.disposeAsset(radarTex);
        for (TextureAsset t : radarIconCache.values()) {
            MinimapHud.disposeAsset(t);
        }
        radarIconCache.clear();
        radarIconMissNs.clear();
    }

    /** Hide the whole overlay (used when the compass tier is off). Hides every
     *  pooled element explicitly — relying on the container's visibility to
     *  cascade to children is not reliable, which left waypoints on screen. */
    public void hide() {
        box.setVisible(false);
        spawnGlyph.setVisible(false);
        for (UIElement e : iconEls) if (e != null) e.setVisible(false);
        for (UIElement e : dashEls) if (e != null) e.setVisible(false);
        for (UIElement e : radarEls) if (e != null) e.setVisible(false);
        for (UIElement e : otherPlayerEls) if (e != null) e.setVisible(false);
        for (OutlinedLabel lbl : labelPool) lbl.setVisible(false);
        for (OutlinedLabel lbl : otherPlayerLabels) lbl.setVisible(false);
    }

    /** Hide just the radar blips (used when the radar tier is off but compass
     *  tier — waypoints/spawn — stays on). */
    public void hideRadar() {
        for (UIElement e : radarEls) if (e != null) e.setVisible(false);
    }

    /** Hide just the other-player markers (used when {@code showOtherPlayers}
     *  is off but the compass tier itself stays on). */
    public void hideOtherPlayers() {
        for (UIElement e : otherPlayerEls) if (e != null) e.setVisible(false);
        for (OutlinedLabel lbl : otherPlayerLabels) lbl.setVisible(false);
    }

    /**
     * Draw this frame's radar blips. Positions project the same way as
     * waypoints ({@link #project}); a blip's own rotation gets the same
     * -heading correction applied to its position in rotate mode, so an icon
     * drawn pointing world-north still visually points at the map's "up" (the
     * player's current heading) once the map itself has rotated.
     */
    public void drawRadar(double dispX, double dispZ, float headingDeg, float pxPerCell,
                          boolean rotate, List<RadarBlip> blips) {
        int used = 0;
        if (blips != null) {
            for (RadarBlip b : blips) {
                if (used >= radarEls.length) break;
                float[] p = project(b.x(), b.z(), dispX, dispZ, headingDeg, pxPerCell, rotate);
                float px = p[0], py = p[1];
                float sizePx = config.radarIconPx * (b.isChild() ? config.radarBabyScale : 1f);
                float d = (float) Math.hypot(px - center, py - center);
                float clampR = radius - sizePx * 0.5f - 2f;
                if (d > clampR && d > 0.001f) {
                    float k = clampR / d;
                    px = center + (px - center) * k;
                    py = center + (py - center) * k;
                }
                UIElement e = radarEls[used];
                if (!config.useTextures) {
                    e.setSize(sizePx, sizePx, false);
                    float[] c = argb(b.color());
                    e.setBackgroundColor(c[0], c[1], c[2], c[3]);
                } else {
                    TextureAsset tex = resolveRadarIcon(b);
                    if (radarSlotTex[used] != tex) {
                        // Neither a plain setVisible() toggle (v2.59) nor
                        // removeChild+addChild on the SAME element (v2.60)
                        // reliably forced this UI system to redraw an
                        // ALREADY-VISIBLE element with a newly-assigned
                        // image — both still left it stuck. What is known to
                        // work (the user's own test): re-equipping the map,
                        // which tears down and recreates elements, not just
                        // detaches/reattaches the existing ones. So stop
                        // trying to refresh the existing element at all —
                        // discard it and hand this slot a brand-new
                        // UIElement instead. A freshly-created element has
                        // never had a chance to get visually "stuck" in the
                        // first place, which every previous attempt here was
                        // still gambling on being fixable in place.
                        box.removeChild(e);
                        e = new UIElement();
                        e.style.position.set(Position.Absolute);
                        e.setPivot(Pivot.MiddleCenter);
                        e.style.backgroundImage.set(tex);
                        e.style.backgroundImageScaleMode.set(ScaleMode.ScaleToFit);
                        box.addChild(e);
                        radarEls[used] = e;
                        radarSlotTex[used] = tex;
                    }
                    e.setSize(sizePx, sizePx, false);
                    if (tex == radarTex) {
                        // No custom art found for this species: fall back to the
                        // shared teardrop, tinted by classification like before.
                        float[] c = argb(b.color());
                        e.style.backgroundImageTintColor.set(c[0], c[1], c[2], c[3]);
                    } else {
                        // Custom per-species art is full-color already (the user
                        // paints hostile/saddled state as a separate _hostile /
                        // _saddled icon file rather than a runtime tint) - draw it
                        // untinted.
                        e.style.backgroundImageTintColor.set(1f, 1f, 1f, 1f);
                    }
                    float screenRotate = b.hasFacing() ? (rotate ? b.facingDeg() - headingDeg : b.facingDeg()) : 0f;
                    e.style.rotate.set(screenRotate);
                }
                e.setPosition(px, py, false);
                e.setVisible(true);
                e.updateStyle();
                used++;
            }
        }
        for (int i = used; i < radarEls.length; i++) {
            radarEls[i].setVisible(false);
        }
    }

    /**
     * Which texture to show for this blip: tries, in order, a saddled variant
     * ({@code <name>_saddled.png}, mounts only), a hostile variant
     * ({@code <name>_hostile.png}), then the plain species icon
     * ({@code <name>.png}) — falling back to the shared teardrop
     * ({@link #radarTex}) if none of those files exist. Every lookup is cached
     * by {@link #iconFor}, so a missing file is only stat'd from disk once.
     */
    private TextureAsset resolveRadarIcon(RadarBlip b) {
        String key = b.iconKey();
        if (key == null || key.isBlank()) return radarTex;
        if (b.saddled()) {
            TextureAsset t = iconFor(key + "_saddled");
            if (t != null) return t;
        }
        if (b.hostile()) {
            TextureAsset t = iconFor(key + "_hostile");
            if (t != null) return t;
        }
        TextureAsset base = iconFor(key);
        return base != null ? base : radarTex;
    }

    private TextureAsset iconFor(String fileKey) {
        TextureAsset cached = radarIconCache.get(fileKey);
        if (cached != null) return cached;

        long now = System.nanoTime();
        long cooldownNs = (long) (Math.max(0.5f, config.radarIconRetryCooldownSeconds) * 1_000_000_000.0);
        Long lastMiss = radarIconMissNs.get(fileKey);
        if (lastMiss != null && (now - lastMiss) < cooldownNs) {
            return null; // still cooling down since the last failed attempt
        }

        TextureAsset loaded = loadRadarIcon(fileKey);
        if (loaded != null) {
            radarIconCache.put(fileKey, loaded);
            radarIconMissNs.remove(fileKey);
        } else {
            radarIconMissNs.put(fileKey, now);
        }
        return loaded;
    }

    /**
     * Loads {@code <pluginFolder>/<radarIconsSubfolder>/<fileKey>.png} from disk
     * (NOT the jar), so the user can drop in / replace art without a rebuild.
     * Returns null (cached as a miss, retried after a cooldown - see
     * {@link #iconFor}) if the file doesn't exist or plugin/path info isn't
     * available.
     *
     * <p>Every attempt is logged (not just failures): a species with no art
     * yet is an expected, harmless, one-time "does not exist" line, and the
     * retry cooldown means a real problem logs at most every few seconds - so
     * this stays cheap while making a "my icon never loads at all" report
     * fully diagnosable straight from the server log, the same way the v2.47/
     * v2.48 fruit-dot bug was root-caused, rather than guessed at again.
     */
    private TextureAsset loadRadarIcon(String fileKey) {
        try {
            if (plugin == null) {
                System.out.println("[PicSoulsMiniMap][radar] icon '" + fileKey + "': no plugin reference");
                return null;
            }
            String dir = plugin.getPath();
            if (dir == null || dir.isEmpty()) {
                System.out.println("[PicSoulsMiniMap][radar] icon '" + fileKey + "': plugin.getPath() is empty");
                return null;
            }
            File file = new File(dir, config.radarIconsSubfolder + File.separator + fileKey + ".png");
            boolean exists = file.isFile();
            if (!exists) {
                System.out.println("[PicSoulsMiniMap][radar] icon '" + fileKey + "': no file at "
                        + file.getAbsolutePath());
                return null;
            }
            TextureAsset t = TextureAsset.loadFromFile(file.getAbsolutePath());
            System.out.println("[PicSoulsMiniMap][radar] icon '" + fileKey + "': loaded from "
                    + file.getAbsolutePath() + " -> " + (t != null ? "OK" : "loadFromFile returned null"));
            return t;
        } catch (Throwable t) {
            System.out.println("[PicSoulsMiniMap][radar] icon '" + fileKey + "': load threw " + t);
            return null;
        }
    }

    /**
     * Draw this frame's other-player markers: same teardrop shape as the
     * player's own marker, always visible (no distance fade, unlike waypoints)
     * and clamped to just inside the rim when off-map — the same behavior as
     * the spawn marker, minus its dashed line. The name label is likewise
     * always shown, with no hysteresis gating.
     */
    public void drawOtherPlayers(double dispX, double dispZ, float headingDeg, float pxPerCell,
                                 boolean rotate, List<OtherPlayerBlip> others) {
        int used = 0;
        if (others != null) {
            for (OtherPlayerBlip o : others) {
                if (used >= otherPlayerEls.length) break;
                float[] p = project(o.x(), o.z(), dispX, dispZ, headingDeg, pxPerCell, rotate);
                float px = p[0], py = p[1];
                float sizePx = config.otherPlayerIconPx;
                float d = (float) Math.hypot(px - center, py - center);
                float clampR = radius - sizePx * 0.5f - 2f;
                if (d > clampR && d > 0.001f) {
                    float k = clampR / d;
                    px = center + (px - center) * k;
                    py = center + (py - center) * k;
                }
                UIElement e = otherPlayerEls[used];
                if (config.useTextures) {
                    float screenRotate = rotate ? o.headingDeg() - headingDeg : o.headingDeg();
                    e.style.rotate.set(screenRotate);
                }
                e.setPosition(px, py, false);
                e.setVisible(true);
                e.updateStyle();
                if (config.otherPlayerNames && used < otherPlayerLabels.size()) {
                    OutlinedLabel lbl = otherPlayerLabels.get(used);
                    lbl.setText(o.name());
                    lbl.setVisible(true);
                    lbl.setOpacity(1f);
                    lbl.setPosition(px + labelOffset, py - sizePx * 0.5f - 4f + labelOffset, false);
                    lbl.updateStyle();
                }
                used++;
            }
        }
        for (int i = used; i < otherPlayerEls.length; i++) {
            otherPlayerEls[i].setVisible(false);
        }
        for (int i = used; i < otherPlayerLabels.size(); i++) {
            otherPlayerLabels.get(i).setVisible(false);
        }
    }

    public void draw(double dispX, double dispZ, float headingDeg, float pxPerCell,
                     boolean rotate, List<MapMarker> markers,
                     boolean hasSpawn, double spawnX, double spawnZ,
                     double playerX, double playerZ, int viewerDbId) {
        box.setVisible(true);
        int iconsUsed = 0;
        int dashesUsed = 0;
        labelsUsed = 0;
        float rimR = radius - 2f;

        // --- spawn: dashed line + glyph + off-map distance label ---
        boolean spawnDrawn = false;
        if (config.showSpawnLine && hasSpawn) {
            float[] p = project(spawnX, spawnZ, dispX, dispZ, headingDeg, pxPerCell, rotate);
            float ex = p[0], ey = p[1];
            float d = (float) Math.hypot(ex - center, ey - center);
            float glyphClampR = Math.max(4f, radius - config.spawnGlyphPx * 0.5f - 3f);
            if (d > glyphClampR && d > 0.001f) {
                float k = glyphClampR / d;
                ex = center + (ex - center) * k;
                ey = center + (ey - center) * k;
            }
            dashesUsed = layoutDashes(center, center, ex, ey, config.spawnLineColor);
            placeSpawnGlyph(ex, ey, config.spawnGlyphColor);
            spawnDrawn = true;

            spawnLabelShown = spawnLabelShown ? (d > rimR - 6f) : (d > rimR + 6f);
            if (spawnLabelShown) {
                long wdist = Math.round(Math.hypot(spawnX - playerX, spawnZ - playerZ));
                placeLabel("Spawn " + wdist + "m", ex, ey - config.spawnGlyphPx - 2f, 1f);
            }
        }
        if (!spawnDrawn) spawnGlyph.setVisible(false);

        // --- waypoints ---
        if (config.showWaypoints && markers != null) {
            for (MapMarker m : markers) {
                if (iconsUsed >= iconEls.length) break;
                // Privacy: your own markers (default + global) always show; other
                // players' markers show only if they are global.
                if (prefs.waypointPrivacy && !m.isGlobal() && m.playerDbId() != viewerDbId) {
                    labelShown.remove(m.id());
                    continue;
                }
                double worldDist = Math.hypot(m.x() - playerX, m.z() - playerZ);
                float fade = fadeFor(worldDist);
                if (fade <= 0f) {
                    labelShown.remove(m.id());
                    continue;
                }

                float[] p = project(m.x(), m.z(), dispX, dispZ, headingDeg, pxPerCell, rotate);
                float px = p[0], py = p[1];
                float d = (float) Math.hypot(px - center, py - center);

                // Per-marker size from the game's own marker scale (DB scalex),
                // never below waypointMinPx so small markers stay legible.
                float dbScale = config.waypointUseDbScale ? m.scale() : 1f;
                float base = config.waypointIconPx;
                float minPx = config.waypointMinPx;
                // Clamp radius uses the EDGE size, since that is the size it would
                // take if pinned to the rim.
                float edgeSizePx = Math.max(minPx, base * clampf(dbScale, 0f, config.waypointScaleMaxEdge));
                float clampR = Math.max(4f, radius - edgeSizePx * 0.5f - 3f);
                boolean edge = d > clampR && d > 0.001f;
                float sizePx = edge ? edgeSizePx
                        : Math.max(minPx, base * clampf(dbScale, 0f, config.waypointScaleMaxInside));
                float iconRadius = sizePx * 0.5f;
                if (edge) {
                    float k = clampR / d;
                    px = center + (px - center) * k;
                    py = center + (py - center) * k;
                }
                // Opacity floor: remap the marker's alpha into [minOpacity, 1] so a
                // faint marker still shows on the small minimap.
                float dispAlpha = config.waypointMinOpacity + (1f - config.waypointMinOpacity) * m.a();
                if (dispAlpha > 1f) dispAlpha = 1f;
                placeIcon(iconsUsed, m.iconId(), px, py, sizePx, m.r(), m.g(), m.b(), dispAlpha, fade);
                iconsUsed++;

                boolean prev = Boolean.TRUE.equals(labelShown.get(m.id()));
                boolean show = prev ? (d < rimR - 4f) : (d < rimR - 16f);
                labelShown.put(m.id(), show);
                if (show && config.waypointLabels && !m.name().isEmpty()) {
                    placeLabel(m.name(), px, py - iconRadius - 4f, fade);
                }
            }
        }

        // hide unused pooled elements
        for (int i = iconsUsed; i < iconEls.length; i++) {
            if (iconEls[i] != null) iconEls[i].setVisible(false);
        }
        for (int i = dashesUsed; i < dashEls.length; i++) {
            dashEls[i].setVisible(false);
        }
        hideUnusedLabels();
    }

    /** World (x,z) -> box-pixel, matching the compass-label rotation convention. */
    private float[] project(double wx, double wz, double dispX, double dispZ,
                            float headingDeg, float pxPerCell, boolean rotate) {
        double sx = (wx - dispX) * pxPerCell;
        double sy = -(wz - dispZ) * pxPerCell;
        if (rotate) {
            double a = Math.toRadians(-headingDeg);
            double ca = Math.cos(a), sa = Math.sin(a);
            double rx = sx * ca - sy * sa;
            double ry = sx * sa + sy * ca;
            sx = rx;
            sy = ry;
        }
        return new float[]{(float) (center + sx), (float) (center + sy)};
    }

    private void placeIcon(int slot, int iconId, float px, float py, float sizePx,
                           float r, float g, float b, float a, float fade) {
        UIElement e = iconEls[slot];
        int idx = (iconId >= 0 && iconId < MarkerTextures.COUNT) ? iconId : 6;
        e.setSize(sizePx, sizePx, false); // per-marker size (from the map DB scale)
        if (!config.useTextures) {
            // Texture-free fallback: a plain colored dot (no icon shape).
            if (iconSlotShape[slot] != idx) {
                e.setBorderEdgeRadius(50f, true);
                iconSlotShape[slot] = idx;
            }
            e.setBackgroundColor(r, g, b, a);
            e.setOpacity(fade);
            e.setPosition(px, py, false);
            e.setVisible(true);
            e.updateStyle();
            return;
        }
        if (iconSlotShape[slot] != idx) {
            if (iconTex[idx] != null) e.style.backgroundImage.set(iconTex[idx]);
            iconSlotShape[slot] = idx;
        }
        e.style.backgroundImageTintColor.set(r, g, b, a);
        e.setOpacity(fade);
        e.setPosition(px, py, false);
        e.setVisible(true);
        e.updateStyle();
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void placeSpawnGlyph(float px, float py, int argb) {
        float[] c = argb(argb);
        if (!config.useTextures) {
            spawnGlyph.setBackgroundColor(c[0], c[1], c[2], c[3]);
            spawnGlyph.setPosition(px, py, false);
            spawnGlyph.setVisible(true);
            spawnGlyph.updateStyle();
            return;
        }
        spawnGlyph.style.backgroundImageTintColor.set(c[0], c[1], c[2], c[3]);
        spawnGlyph.setPosition(px, py, false);
        spawnGlyph.setVisible(true);
        spawnGlyph.updateStyle();
    }

    /** Lay out the dashed line as thin rotated segment elements; returns count used. */
    private int layoutDashes(float x0, float y0, float x1, float y1, int argb) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return 0;
        float ux = dx / len, uy = dy / len;
        float dash = Math.max(1f, config.spawnLineDashPx);
        float gap = Math.max(1f, config.spawnLineGapPx);
        float thick = Math.max(1f, config.spawnLineThicknessPx);
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        float[] c = argb(argb);
        int used = 0;
        float pos = 0f;
        while (pos < len && used < dashEls.length) {
            float seg = Math.min(dash, len - pos);
            float mx = x0 + ux * (pos + seg / 2f);
            float my = y0 + uy * (pos + seg / 2f);
            UIElement e = dashEls[used];
            e.setSize(seg, thick, false);
            e.setBackgroundColor(c[0], c[1], c[2], c[3]);
            e.setPosition(mx, my, false);
            e.style.rotate.set(angle);
            e.setVisible(true);
            e.updateStyle();
            used++;
            pos += dash + gap;
        }
        return used;
    }

    private float fadeFor(double distM) {
        float start = config.waypointFadeStartM;
        float end = config.waypointFadeEndM;
        if (start <= 0f || start <= end) return 1f;
        if (distM >= start) return 1f;
        if (distM <= end) return 0f;
        return (float) ((distM - end) / (start - end));
    }

    private void placeLabel(String text, float px, float py, float alpha) {
        if (labelsUsed >= labelPool.size()) return;
        OutlinedLabel lbl = labelPool.get(labelsUsed);
        labelsUsed++;
        lbl.setText(text);
        lbl.setVisible(true);
        lbl.setOpacity(alpha);
        lbl.setPosition(px + labelOffset, py + labelOffset, false);
        lbl.updateStyle();
    }

    private void hideUnusedLabels() {
        for (int i = labelsUsed; i < labelPool.size(); i++) {
            labelPool.get(i).setVisible(false);
        }
    }

    private static float[] argb(int c) {
        float a = ((c >>> 24) & 0xFF) / 255f;
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        return new float[]{r, g, b, a};
    }
}
