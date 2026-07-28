package net.picsoul.rw.minimap.config;

import net.risingworld.api.ui.style.Pivot;

/**
 * Plugin configuration. For M0 these are in-code defaults; a config file
 * (config.yml in the plugin folder) is loaded in a later milestone (M5).
 */
public class MinimapConfig {

    /**
     * Screen corner the minimap anchors to. Each corner carries its UI pivot
     * and the anchor position as screen percentages. Top-left is the default
     * because the game renders its own tool/hotbar icons in the top-right.
     */
    public enum Corner {
        TOP_LEFT(Pivot.UpperLeft, 2f, 3f),
        TOP_RIGHT(Pivot.UpperRight, 98f, 3f),
        BOTTOM_LEFT(Pivot.LowerLeft, 2f, 97f),
        BOTTOM_RIGHT(Pivot.LowerRight, 98f, 97f);

        public final Pivot pivot;
        public final float xPercent;
        public final float yPercent;

        Corner(Pivot pivot, float xPercent, float yPercent) {
            this.pivot = pivot;
            this.xPercent = xPercent;
            this.yPercent = yPercent;
        }
    }

    // ---- Tiered capability gate ----
    // The minimap upgrades in tiers based on what the player has EQUIPPED (map,
    // compass, clock/pocket-watch) or has ever OWNED (calendar). Each value is a
    // comma-separated list of case-insensitive name tokens (substring match), so
    // one setting can cover item variants. Calibrate in-game with /mm ids.
    /** Equipped -> basic minimap (terrain + coordinates). */
    public String mapItemName = "map";
    /** Equipped -> adds cardinal directions + waypoints + spawn line. Covers compassold/compassmodern. */
    public String compassItemName = "compass";
    /** Equipped -> adds the in-game time readout. The in-game timepiece is a "clock"
     *  (clockold/clockmodern); tokens also cover watch/pocketwatch just in case. */
    public String watchItemName = "watch,clock,pocketwatch";
    /** Ever owned (craft/loot/carry) -> adds the in-game date readout (persistent). */
    public String calendarItemName = "calendar";
    /** Equipped -> adds the entity/radar tier (nearby animals/NPCs shown as blips).
     *  Stricter than compassItemName on purpose: the upgraded "compassmodern" grants
     *  it, but the plain "compassold" (still matched by compassItemName's "compass"
     *  token, for the base cardinal/waypoint tier) does not. */
    public String radarItemName = "compassmodern";
    /** Dev/testing: unlock every tier regardless of equipped/owned items. Toggle /mm dev. */
    public boolean devAllTiers = false;

    // ---- Time / date readout ----
    /** 24-hour clock (14:05) vs 12-hour with AM/PM (2:05 PM). */
    public boolean time24Hour = false;
    /** Include the season and year in the date readout. */
    public boolean dateShowSeason = true;
    public boolean dateShowYear = true;

    // ---- HUD layout ----
    /** Per-player now (see {@link net.picsoul.rw.minimap.config.PlayerPreferences#minimapSizePx});
     *  these remain here only as the shared slider bounds for the in-game map-size control. */
    public int minimapSizeMinPx = 120;
    public int minimapSizeMaxPx = 360;
    public boolean circular = false;
    /** On-screen size (px) of the player pointer marker. */
    public int markerSizePx = 16;

    // ---- Map rendering ----
    /** Discrete zoom levels the zoom-in/out keys step through (cells across). */
    public int[] zoomSteps = {48, 64, 96, 128, 192, 256};

    // ---- Settings-panel hotkey ----
    /** Name (from the game's {@code Key} enum) of the key that opens/closes the
     *  settings panel (see {@code onKey} in the main plugin class) - not yet
     *  rebindable in-game, only by editing this default. Was originally
     *  Ctrl+M, but plain M is the base game's own "open map" key and
     *  PlayerKeyEvent has no way to suppress that, so both fired together
     *  regardless of the Ctrl requirement (which was purely our own plugin's
     *  logic - the game reacts to the raw M press either way). Then F4, which
     *  turned out to be bound to something in creative mode. Now F1. */
    public String settingsKeyName = "F1";
    /** Extra cells rendered as a border around the visible area, so the map can be
     *  panned smoothly (by translating the image) before needing a re-render. */
    public int panPaddingCells = 32;
    /** Texture super-sampling: rendered pixels per world cell. Higher = crisper, heavier. */
    public int superSample = 2;

    // ---- Terrain color smoothing ----
    /** Blur radius (in cells) for the terrain's base material color, so type
     *  boundaries (e.g. grass into dirt) read as a soft gradient instead of a
     *  hard line. 0 disables it; 1 = 3x3, 2 = 5x5 (both weighted toward the
     *  center, not a flat box average). Player-built construction blocks are
     *  painted after this and are never blurred. Toggle with {@code /mm blur}. */
    public int terrainBlurRadius = 1;

    // ---- Cave openings on the surface map (v2.38) ----
    /** The surface map is a 2D heightmap ({@code getLODTerrain}), which cannot
     *  represent an actual hole (a sinkhole, cave mouth, dug shaft): the
     *  heightmap just reports whatever height it has for that column even
     *  when the real, voxel-accurate terrain there is open air. When this is
     *  on, each cell also checks the real {@code ChunkPart} voxel data near
     *  the reported surface height; if it's actually open there, the render
     *  looks down through the opening for the real floor (or renders a void
     *  color if none is found within {@link #caveScanDepth}) instead of
     *  drawing fictitious ground. Costs one extra bulk voxel read (a couple
     *  hundred KB) per chunk *render* (not per frame -- same amortization as
     *  the existing LOD read, since renders are cached). Toggle {@code /mm caves}. */
    public boolean caveDetectionEnabled = true;
    /** Max depth (world blocks) to look for a real floor under a detected
     *  opening before giving up and drawing {@link #caveVoidColor}. */
    public int caveScanDepth = 48;
    /** Safety cap on how many vertical ChunkParts (64 blocks each) a single
     *  chunk render may fetch for cave detection, so a chunk spanning a huge
     *  elevation range (a tall cliff) can't force an unbounded number of bulk
     *  voxel reads. Detection is silently skipped for that render if the
     *  chunk's height range would need more parts than this. */
    public int caveMaxChunkParts = 4;
    /** Color drawn where a hole is real but no floor was found within
     *  {@link #caveScanDepth} (a deep/bottomless-looking shaft). Shared with
     *  cave mode below. */
    public int caveVoidColor = 0xFF0D0D12;

    // ---- Cave mode (v2.38) ----
    /** While the player is underground ({@code Player.isInCave()}), swap the
     *  minimap to a local view of the real voxel terrain around the player's
     *  current altitude instead of the (meaningless, underground) surface
     *  heightmap. Toggle {@code /mm cavemode}. */
    public boolean caveModeEnabled = true;
    /** Seconds the raw {@code isInCave()} reading must hold steady before the
     *  HUD actually switches modes, so standing right at a cave mouth doesn't
     *  flicker between surface and cave view. */
    public float caveModeDelaySeconds = 1.5f;
    /** World cells across the map while in cave mode. Independent of the
     *  normal surface zoom -- underground you want a tight, close-in view. */
    public int caveZoomCells = 48;
    /** How far above/below the player's current Y each cave-mode column scans
     *  for an opening (the player's "current level") and then a floor below
     *  it. Up is small (ceiling height); down is generous (drop-offs, pits). */
    public int caveWindowUp = 4;
    public int caveWindowDown = 40;
    /** Re-render cave mode when the player's Y changes by more than this many
     *  blocks (in addition to the normal X/Z pan threshold), since climbing
     *  or dropping a level changes what's visible underground. */
    public float caveYMoveThreshold = 4f;
    /** Per-block darkening applied to a cave floor the deeper it is below the
     *  player, and the floor below which the factor cannot drop (so distant
     *  floors stay dimly visible rather than going black). */
    public float caveDepthDarken = 0.02f;
    public float caveDepthMin = 0.35f;
    /** Color for a column that is solid rock all the way through the scan
     *  window (no opening at the player's level) -- reads as a wall/mass. */
    public int caveWallColor = 0xFF2B2B2E;

    // ---- Cave-hole vignette (v2.43) ----
    // A detected hole/void used to be a flat color -- a "grey patch". This
    // makes it read as an actual hole: cells near the boundary of a hole
    // patch (adjacent to solid ground/wall) are lightened toward a bright rim
    // tone; cells deep inside the patch (far from any solid neighbor) fade
    // toward black. Applied to both the surface cave-opening detection
    // (render()) and cave mode's void/chasm cells (renderCave()).
    public boolean caveEdgeGlowEnabled = true;
    /** Bright highlight color blended in at the very rim of a hole. */
    public int caveRimColor = 0xFFE8E4D8;
    /** How strongly the rim blends toward {@link #caveRimColor} at distance 0
     *  (0 = no highlight, 1 = fully replaced by the rim color). */
    public float caveRimStrength = 0.55f;
    /** Brightness multiplier floor at/beyond the falloff distance -- how dark
     *  the deep interior of a hole gets (near-black, not pure black, so a
     *  faint hint of the underlying color still reads). */
    public float caveCenterDarkness = 0.06f;
    /** Distance (cells) over which the rim-to-center fade happens. */
    public float caveEdgeFalloff = 5f;
    /** Search bound (cells) when looking for the nearest solid/non-hole
     *  neighbor to measure that distance; also the fallback "fully dark"
     *  distance for a hole patch bigger than this. */
    public int caveEdgeSearchMax = 7;

    // ---- Elevation visualization ----
    public float hillshadeStrength = 0.32f;
    public float elevationBrightness = 0.011f;
    public float elevationTintMin = -0.42f;
    public float elevationTintMax = 0.52f;
    /** Cap on the per-cell slope relief term, so a single sharp edit (e.g. a
     *  shallow dug hole) doesn't render as an unnaturally dark, deep gash. */
    public float reliefClamp = 0.28f;
    public float shadeFactorMin = 0.40f;
    public float shadeFactorMax = 1.85f;

    // ---- Water edge blending ----
    /** Water depth (world units) at which the water becomes fully opaque blue.
     *  Shallower water blends toward the sea floor for a soft shoreline. */
    public float waterBlendDepth = 5f;
    /** Minimum water tint even at the shallowest edge (0..1), so water still reads
     *  as water right at the shore. */
    public float waterMinAlpha = 0.18f;

    // ---- Player-built structures ----
    public boolean showConstructions = true;

    // ---- Trees (v2.39+) ----
    // v2.42: scope narrowed to trees only, per user request — flowers, crops,
    // bushes and ore-rock markers were removed entirely (they either didn't
    // read well at minimap scale or, for ore, never reliably worked). See
    // render/TreeColors and TileRenderer.overlayVegetation.
    /** Tint trees/fruit trees into the terrain tile at their exact cell, the
     *  same technique already used for construction blocks — cheap (a small
     *  stamped blob per tree, no UI elements) and lives entirely inside the
     *  existing per-chunk render, so it updates for free whenever that
     *  chunk's tile is rebuilt. Toggle {@code /mm trees}. */
    public boolean showVegetation = true;
    /** How much darker a tree canopy blob gets toward its rim, 0 = flat fill,
     *  higher = a more pronounced "lit dome" look. Falloff is quadratic
     *  (stays bright near the center, darkens near the edge). */
    public float vegetationEdgeDarken = 0.45f;
    /** Canopy radius multiplier for a sapling / still-growing tree
     *  ({@code Plants.Stage}), so a young tree reads as visibly smaller than a
     *  mature one instead of the same size blob. */
    public float vegetationSaplingScale = 0.35f;
    public float vegetationGrowingScale = 0.65f;
    /** Also scan the 8 neighboring chunks' trees when painting this tile, so a
     *  tree near a chunk edge gets its full canopy (the overlapping half into
     *  the next tile would otherwise just be missing — a tree only exists in
     *  its own chunk's {@code getAllPlants()}, so without this its canopy
     *  looked "cut off" exactly at the invisible chunk boundary). Costs up to
     *  9x the tree-scanning work per chunk render (only on cache miss/rebuild,
     *  not per frame); turn off to trade the edge-overlap fix back for less
     *  work if that ever matters on slower hardware. */
    public boolean vegetationScanNeighbors = true;
    /** Diagnostic (v2.47): log every FruitTree instance TileRenderer actually
     *  processes — name, pickupitem, the resulting hasFruit/radius — straight
     *  from the real render path, not a side-channel command. Three rounds of
     *  fixes to the fruit-accent logic itself produced no visible change, so
     *  the next step is confirming what the renderer itself sees rather than
     *  guessing a fourth time. Toggle {@code /mm fruitdebug} (also forces an
     *  immediate re-render so the very next render logs). */
    public boolean debugFruitLogging = false;

    // ---- Topographic contour overlay ----
    /** Per-player now (see {@link PlayerPreferences#contourEnabled}) - each
     *  viewer's own preference picks which of the two rendered variants
     *  {@link net.picsoul.rw.minimap.render.TileCache} serves for a chunk.
     *  These remain here only as the shared visual tuning for whichever
     *  variant gets rendered. */
    public float contourInterval = 4f;
    public int contourMajorEvery = 5;
    public float contourMinorDarken = 0.78f;
    public float contourMajorDarken = 0.58f;

    // ---- Chunk-fetch budgeting ----
    /** Max wall-clock time (ms) the plugin thread may spend building *new* tiles
     *  per render. Chunks beyond the budget are deferred to later renders, so the
     *  minimap never freezes when many new chunks must load at once (e.g. boating
     *  into fresh open ocean). Cached tiles are free and don't count. */
    public float snapshotBudgetMs = 3f;
    /** After a chunk is found not-yet-loaded, wait this long (seconds) before
     *  asking the game for it again. Prevents repeatedly blocking on chunks the
     *  game hasn't generated yet (the open-ocean stall). */
    public float chunkRetryCooldown = 0.75f;

    // ---- Smooth panning ----
    /** Interpolate the map's follow position between raw position samples, so the
     *  map glides even when the underlying player position updates slower than the
     *  HUD refresh rate (the cause of jitter when moving fast, e.g. on horseback).
     *  Self-adapting: when positions already change every frame it adds no
     *  perceptible lag; when they arrive in coarse steps it fills the gaps. */
    public boolean smoothPanning = true;
    /** If the player position jumps more than this many cells between two samples
     *  (teleport, respawn, fast-travel), snap instead of gliding across the gap. */
    public float interpSnapCells = 48f;

    // ---- Refresh ----
    /** Seconds between HUD updates. ~60 Hz for smooth panning at high speed
     *  (e.g. on horseback). Effectively capped at the server tick rate. */
    public float hudRefreshInterval = 0.016f;
    /** Minimum gap enforced between two live, in-session HUD element-tree
     *  rebuilds (PlayerSession.requestRebuild/rebuildHud - full teardown and
     *  recreation of every UI element, hundreds of them). A single settings
     *  panel click firing one rebuild is fine; a burst of rapid-fire clicks
     *  (e.g. clicking repeatedly along the map-size slider) each triggering
     *  its own immediate full rebuild was observed, via the live log, to
     *  spiral into a dozen-plus rebuilds within a couple of seconds (UI
     *  element ids climbing into the high-2000s - far more than a single
     *  HUD needs) and intermittently crash the game - the same general class
     *  of UI-churn fragility as the world-switch crash this project hit
     *  before, just triggered live instead of at plugin unload. */
    public float hudRebuildCooldownSeconds = 0.4f;

    // ---- World-load safety ----
    /** After the plugin (re)enables — which happens on every world load and world
     *  switch — wait this many seconds before rendering the terrain (reading chunk
     *  LOD data + creating textures). Reading chunks while a freshly-loaded world is
     *  still streaming in can crash the game natively (uncatchable from Java). The
     *  map frame, marker and coordinates still show during this grace window; only
     *  the terrain image is deferred. Increase if world-switch crashes persist. */
    public float renderGraceSeconds = 6f;

    /**
     * Seconds to wait after the plugin re-enables <b>in an already-running game</b>
     * (i.e. after a main-menu world switch) before attaching the HUD to the screen.
     *
     * <p>This is the world-switch crash fix (v2.22). Attaching UI elements during
     * the world-load window of a <i>second</i> world in the same game session
     * crashes the game natively. Proven by bisect: {@code /mm hud off} (never
     * attach) stopped the crash, while {@code /mm notex on} (no textures at all)
     * did not — and re-enabling the HUD manually later in that same second world
     * was completely safe. So it is not UI creation as such, only UI creation too
     * early after a switch.
     *
     * <p>Only applied when a world switch is detected (the plugin re-enabling
     * inside the same OS process). On a fresh game start the HUD attaches
     * immediately as before, so there is no cost to normal play. Raise this with
     * {@code /mm hudgrace <seconds>} if a slower machine still crashes.
     */
    public float hudGraceSeconds = 15f;

    // ---- Diagnostics: world-switch crash isolation (v2.17) ----
    // Two master kill-switches to pinpoint what triggers the native crash that
    // can happen shortly after switching worlds from the menu. Both default ON
    // (normal operation). They are PERSISTED to diagnostics.txt in the plugin
    // folder so a setting made in one world survives the switch into the next
    // (the crash happens within seconds of the switch, before a command could be
    // typed in the new world). Toggle with /mm terrain and /mm mapdb.
    /** Master switch for terrain-image rendering (all chunk LOD reads). Turn OFF
     *  to test whether reading chunk terrain after a world switch is the cause.
     *  The map frame, player marker, coordinates, time/date and waypoints still
     *  show; only the terrain picture is skipped. */
    public boolean terrainRendering = true;
    /** Master switch for SQLite/world-DB reads: the Maps.db waypoint reads AND
     *  the initial spawn-point lookup from the players DB. Turn OFF to test
     *  whether the DB access is the cause. Spawn falls back to the world default
     *  spawn while off. */
    public boolean mapDbReads = true;
    /** Master switch for ALL texture creation (`TextureAsset.load`). When false the
     *  plugin never creates a single texture: the player marker, waypoint icons and
     *  spawn glyph are drawn as plain colored UI shapes, and the terrain image is
     *  skipped entirely. Everything else (frame, coordinates, cardinals, time/date,
     *  waypoint positions, dashed spawn line, labels) still works. Toggle /mm notex. */
    public boolean useTextures = true;
    /** Master switch for the HUD itself. When false the plugin never attaches a
     *  single UI element to the screen — it stays loaded and responds to commands
     *  but is completely invisible. Toggle /mm hud. */
    public boolean hudEnabled = true;
    /** Hide the minimap while the player is holding the vanilla map, so our UI never
     *  coexists with the game's own map screen. Every crash captured from v2.20 on
     *  happened exactly when the player switched to the map item in a second world,
     *  and the only stable run was the one with our HUD absent. Toggle /mm mapguard. */
    public boolean mapGuard = true;

    /**
     * Safe mode: after a main-menu world switch, never attach the HUD at all for
     * the rest of the game session.
     *
     * <p><b>No longer needed as of v2.30</b> — the world-switch crash is fixed by
     * {@link #teardownMode} {@code = "none"}, so this defaults to off and the
     * minimap works normally in every world. Kept as a fallback.
     *
     * <p>Historical note: this was the stability guarantee while the crash was
     * unsolved, though it never actually prevented it (the trigger was the
     * <i>previous</i> world's UI, not the new one's). Established by testing: with the HUD attached in a <i>second</i>
     * world the game reliably crashes, and no amount of delaying
     * ({@code hudGraceSeconds}), removing textures ({@code useTextures}), avoiding
     * the vanilla map ({@code mapGuard}) or moving UI work off the event callbacks
     * changed that — while {@code /mm hud off} was always stable. A fresh game
     * start is completely unaffected: the minimap works normally in the first world
     * of every session.
     *
     * <p>Turn off with {@code /mm safemode off} to attach anyway (expect a crash);
     * useful only for further diagnosis.
     */
    public boolean worldSwitchSafeMode = false;

    /**
     * Diagnostic: build the HUD with tiny element pools.
     *
     * <p>A full HUD allocates roughly 190 UI elements per player — mostly the marker
     * overlay's pools (48 icon sprites, 24 dash segments and 48 outlined labels,
     * each label being two elements). The live logs show the game dies shortly
     * after the HUD is attached in a second world, so the sheer number of UI
     * elements allocated on a plugin reload is the next suspect. With this on the
     * pools drop to 8/8/8, taking the HUD to roughly 40 elements.
     *
     * <p>If a switched world is stable with this on, element pressure is the cause
     * and the pools can simply be tuned — which would restore a working minimap
     * after a world switch instead of relying on safe mode. Test with
     * {@code /mm uilite on} plus {@code /mm safemode off}, then switch worlds.
     */
    public boolean uiLite = false;
    /**
     * Diagnostic: attach the smallest possible plugin UI — one container holding a
     * single plain label (two elements), showing only coordinates. Everything else
     * (map circle, marker, overlay, textures) is skipped. This answers the last
     * open question: whether attaching <i>any</i> UI at all after a plugin reload
     * is fatal, or whether a small enough UI survives. Also serves as the minimal
     * reproduction for an upstream bug report. Toggle /mm minimal.
     */
    public boolean minimalUi = false;

    /**
     * What the plugin does with its UI elements when it is unloaded (world switch).
     *
     * <p><b>Resolved:</b> calling {@code removeUIElement} while the plugin is being
     * unloaded is what crashes the next world. Removing nothing is stable. Note
     * that removing UI during normal play is perfectly safe — the HUD is detached
     * and rebuilt in-world routinely without incident; it is specifically removal
     * during {@code onDisable} that does the damage.
     *
     * <p>Original reasoning. It is now established that the crash is caused by the
     * plugin having created UI in the <i>previous</i> world: with safe mode on, the
     * second world attaches no UI and creates no textures at all, and the game still
     * crashes — while suppressing the UI in <i>both</i> worlds is stable. So the
     * damage happens around world 1's UI, and the untested variable is whether it is
     * the creation itself or our removal of it during {@code onDisable}.
     *
     * <ul>
     *   <li>{@code full} (default) — purge the element tree depth-first, remove the
     *       roots, then sweep {@code getAllUIElements}. Game reports 0 elements.</li>
     *   <li>{@code roots} — remove only the two root containers (behaviour before
     *       v2.27; the game then reported 47 leftover elements).</li>
     *   <li>{@code none} — <b>(default, and the fix)</b> remove nothing; let the
     *       game's own {@code Reset PluginUIManager} handle it. Verified stable:
     *       with this the game survives world switches with the minimap fully
     *       active in the first world.</li>
     * </ul>
     * Set with {@code /mm teardown full|roots|none}; persisted.
     */
    public String teardownMode = "none";
    /** Pool sizes used when {@link #uiLite} is on / off. */
    public int markerIconPool = 48;
    public int markerDashPool = 24;
    public int uiLiteIconPool = 8;
    public int uiLiteDashPool = 8;

    // ---- Text readability (cardinal + coordinate labels) ----
    /** Width of the outward black outline on HUD text. It drives a rear black
     *  silhouette layer (see OutlinedLabel), so the outline only ever grows
     *  OUTWARD and never fills the white glyph. */
    public float textOutlineWidth = 1.6f;
    /** Alpha (0..1) of the subtle dark rounded backing chip behind HUD text.
     *  0 disables the backing (outline only). */
    public float textBackingAlpha = 0f;
    /** Horizontal padding (px) of the backing chip around the text; vertical
     *  padding is 40% of this. */
    public float textPaddingPx = 5f;

    // ---- Waypoints & spawn markers (v2.5) ----
    /** Show map waypoints read from the game's own map (Maps.db). Toggle /mm waypoints. */
    public boolean showWaypoints = true;
    /** Draw each waypoint's name beside its dot. */
    public boolean waypointLabels = true;
    /** Show the dashed line from the player to the spawn point. Toggle /mm spawn. */
    public boolean showSpawnLine = true;
    /** Seconds between reloads of the waypoint list from Maps.db. Slow on purpose:
     *  waypoints change rarely and the read is kept off the per-frame path. */
    public float waypointRefreshSeconds = 3f;
    /** On-screen size (px) of a waypoint icon at 100% marker scale (the shape
     *  matching its map iconid). Larger than the original 15 so waypoints read
     *  clearly; each marker is then multiplied by its own size from the map DB. */
    public float waypointIconPx = 20f;

    // ---- Waypoint size from the game's own marker scale (Maps.db scalex) ----
    /** Honor each marker's size as set in the in-game map (the DB {@code scalex}).
     *  On-screen size = {@code waypointIconPx} × the marker's scale, clamped below.
     *  Turn off to draw every waypoint at the base size. */
    public boolean waypointUseDbScale = true;
    /** Minimum on-screen icon size in px. The DB scale can make a marker tiny (a
     *  50% marker would be only 10px at the 20px base), so we never draw smaller
     *  than this — small-scale markers stay legible instead of shrinking away. */
    public float waypointMinPx = 14f;
    /** Max size multiplier for a waypoint drawn INSIDE the map (up to 500%). */
    public float waypointScaleMaxInside = 5.0f;
    /** Max size multiplier for a waypoint clamped to the map EDGE. Kept small so
     *  edge markers never dominate the minimap — at most twice the base size. */
    public float waypointScaleMaxEdge = 2.0f;
    /** Marker opacity floor. The stored marker alpha (from its color) is remapped
     *  to [waypointMinOpacity, 1], so a half-transparent marker still reads clearly
     *  on the small minimap. Set to 0 to use the raw alpha unchanged. */
    public float waypointMinOpacity = 0.6f;

    // ---- Waypoint privacy ----
    // Per-player now - see PlayerPreferences#waypointPrivacy. (Filter: show your
    // OWN markers (default + global), but only OTHER players' GLOBAL markers -
    // so a player's "default" markers stay private to them. Off shows every
    // marker regardless of owner. Toggle /mm wpprivacy.)
    /** Proximity fade: a waypoint is at full opacity beyond {@code waypointFadeStartM}
     *  metres, fades linearly as you approach, and is fully hidden within
     *  {@code waypointFadeEndM} metres — reduces clutter when you're at a base.
     *  Set start <= end (or start <= 0) to disable fading. Distance is horizontal. */
    public float waypointFadeStartM = 40f;
    public float waypointFadeEndM = 20f;
    /** On-screen size (px) of the spawn glyph (diamond). */
    public float spawnGlyphPx = 12f;
    /** Dashed spawn line dash + gap lengths, and line thickness (px). */
    public float spawnLineDashPx = 6f;
    public float spawnLineGapPx = 5f;
    public float spawnLineThicknessPx = 2f;
    /** Spawn line + glyph color (ARGB). Amber to match the player marker. */
    public int spawnLineColor = 0xFFFFD833;
    public int spawnGlyphColor = 0xFFFFD833;

    // ---- Entity radar (v2.50) ----
    /** Show nearby animals/NPCs as blips on the map. Requires the radar tier
     *  (compassmodern equipped — see radarItemName). Toggle /mm radar. */
    public boolean showRadar = true;
    /**
     * How far out (world units, horizontal) to scan for npcs, each scan.
     *
     * <p>This is a FLOOR, not the actual range used: the real per-scan range
     * tracks the minimap's current zoom (half of {@code zoomCells}, the
     * world-cell span actually visible on screen), clamped to
     * [{@code radarRangeM}, {@code radarRangeMaxM}]. Without this, a fixed
     * 60m scan radius meant npcs only ever showed up while zoomed in close
     * enough that the visible circle was smaller than 60m across — zoomed out
     * any further and real, on-screen-visible npcs beyond 60m were simply
     * never scanned, so the map looked empty of them even though they'd fit
     * on screen. radarRangeM itself still matters as the minimum: zoomed in
     * tight (a small visible circle), you still want a sensible scan radius
     * rather than shrinking to nearly nothing.
     */
    public float radarRangeM = 60f;
    /** Upper bound on the zoom-tracking scan radius above, so zooming all the
     *  way out doesn't turn every scan into an expensive, huge-radius
     *  World.getAllNpcsInRange query. */
    public float radarRangeMaxM = 150f;
    /** Seconds between npc scans. Kept off the per-frame path, like waypoints -
     *  a moving blip re-querying every tick is unnecessary and not free (native
     *  World.getAllNpcsInRange call). */
    public float radarScanIntervalSeconds = 0.4f;
    /** Cap on how many blips are drawn at once (nearest-first), so a large pack
     *  or herd can't exhaust the icon pool or clutter the map. */
    public int radarMaxTracked = 24;
    /** On-screen size (px) of a radar blip. Adjustable in-game via the settings
     *  panel slider (/mm settings), clamped to [radarIconSizeMinPx,
     *  radarIconSizeMaxPx]. 10px (the original default) read as "way too
     *  small" once real per-species art was in the picture; 20 is the new
     *  baseline. */
    public float radarIconPx = 20f;
    /** Slider bounds for the in-game icon-size control. */
    public float radarIconSizeMinPx = 8f;
    public float radarIconSizeMaxPx = 48f;
    /** Rotate each blip to face the same direction as the npc, using the same
     *  teardrop-arrow shape as the player marker. Off draws plain dots.
     *  Defaulted OFF ("locked north-up") per user feedback - v2.53's rotation
     *  didn't read well in practice. The detection code is untouched and still
     *  runs; flip this back on (or wire a toggle) to revisit later. */
    public boolean radarShowFacing = false;
    /** Blip colors (ARGB) by classification. Aggressive npcs (actively hostile)
     *  are red; "defensive aggressive" (attacks only if provoked) is amber, so
     *  the two read as different threat levels at a glance. Passive animals,
     *  human npcs and mounts get their own tone; anything unclassified falls
     *  back to radarColorDefault. See net.risingworld.api.definitions.Npcs.Type
     *  and Npcs.Behaviour - calibrate with /mm ids near a live npc. */
    public int radarColorHostile = 0xFFE53935;
    public int radarColorCaution = 0xFFFF9800;
    public int radarColorAnimal = 0xFF7CC576;
    public int radarColorHuman = 0xFF64B5F6;
    /** A wild/untamed mount (no saddle) vs. one that's been saddled - the game
     *  tracks a saddle as npc "clothing" (Clothing.Function.Saddle), so this is
     *  reliably detectable. Distinct colors so a tamed/rideable horse is
     *  visually obvious from a wild one at a glance, without needing to get
     *  close enough to check. */
    public int radarColorMount = 0xFFC8975B;
    public int radarColorMountSaddled = 0xFF9C6ADE;
    public int radarColorDefault = 0xFFE0E0E0;
    /** Hide a mount's own radar blip while the viewing player is the one riding
     *  it - the player marker already shows where you are, so a mount you're
     *  currently on is redundant clutter directly under it (and was visibly
     *  lagging behind during fast movement even after the dead-reckoning fix,
     *  which this sidesteps entirely for the ridden case). Uses
     *  {@code Npc.getRider()} (Mounts only). */
    public boolean radarHideRiddenMount = true;

    // ---- Radar per-species icons (v2.53) ----
    /** Subfolder (inside the plugin's own folder, next to the jar) to look for
     *  custom per-species radar icons: {@code <name>.png}, matching the lowercase
     *  name from {@code /mm npcs}. Loaded from disk with TextureAsset.loadFromFile
     *  (NOT baked into the jar), so dropping in / replacing a PNG takes effect on
     *  the next world load - no rebuild needed. A species with no file falls back
     *  to the shared teardrop shape, tinted by radarColor* above.
     *  <p>Two optional state variants are tried first, before the plain
     *  {@code <name>.png}: {@code <name>_saddled.png} for a currently-saddled
     *  mount, and {@code <name>_hostile.png} for a currently-aggressive/
     *  defensive-aggressive npc. Either or both can be skipped per-species; it
     *  just falls back to {@code <name>.png} if the variant file isn't there. */
    public String radarIconsSubfolder = "icons";
    /** If a species' icon file failed to load (or wasn't found), how long
     *  before trying again, rather than assuming it's permanently missing.
     *  A load can plausibly fail transiently very early after world load
     *  (before other engine systems are ready) with nothing wrong with the
     *  file itself, and re-encountering the same npc later shouldn't be
     *  needed just to get the art to show - it should show up on its own a
     *  few seconds after loading in. */
    public float radarIconRetryCooldownSeconds = 4f;
    /** Icons are full-color art (no auto-tint) once a file is found - only the
     *  no-file fallback teardrop gets tinted by radarColor*. */
    /** Size multiplier applied to a child/baby npc definition's icon (see
     *  Npcs.NpcDefinition.ischild), so e.g. a calf reads a bit smaller than its
     *  adult on the map. */
    public float radarBabyScale = 0.75f;

    // ---- Other players on the map (v2.53) ----
    // Whether to show them at all is per-player now (PlayerPreferences#
    // showOtherPlayers, toggle /mm players) - same teardrop-arrow shape as your
    // own marker (tinted otherPlayerColor below), always visible (no distance
    // fade), clamped to the map rim when out of zoom range like the spawn
    // marker. Gated behind the compass tier, not the radar tier.
    public int otherPlayerColor = 0xFF29B6F6;
    public float otherPlayerIconPx = 14f;
    /** Show each other player's name above their marker. Unlike waypoint labels
     *  this has no distance/hysteresis gating - always on while the marker is. */
    public boolean otherPlayerNames = true;
    public int maxOtherPlayersTracked = 32;

    public static MinimapConfig defaults() {
        return new MinimapConfig();
    }
}
