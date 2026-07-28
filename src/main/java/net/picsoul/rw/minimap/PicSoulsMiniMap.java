package net.picsoul.rw.minimap;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.Timer;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.Threading;
import net.risingworld.api.events.player.PlayerChangeEquippedItemEvent;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.events.player.PlayerCraftItemEvent;
import net.risingworld.api.events.player.PlayerKeyEvent;
import net.risingworld.api.events.player.ui.PlayerUIElementClickEvent;
import net.risingworld.api.utils.Key;
import net.risingworld.api.events.player.PlayerDisconnectEvent;
import net.risingworld.api.events.player.PlayerRespawnEvent;
import net.risingworld.api.events.player.PlayerSetSpawnPointEvent;
import net.risingworld.api.events.player.PlayerSpawnEvent;
import net.risingworld.api.events.player.inventory.PlayerInventoryAddItemEvent;
import net.risingworld.api.events.player.world.PlayerCreativeTerrainEditEvent;
import net.risingworld.api.events.player.world.PlayerDestroyConstructionEvent;
import net.risingworld.api.events.player.world.PlayerDestroyTerrainEvent;
import net.risingworld.api.events.player.world.PlayerPlaceBlueprintEvent;
import net.risingworld.api.events.player.world.PlayerPlaceConstructionEvent;
import net.risingworld.api.events.player.world.PlayerPlaceGrassEvent;
import net.risingworld.api.events.player.world.PlayerPlaceTerrainEvent;
import net.risingworld.api.events.player.world.PlayerRemoveConstructionEvent;
import net.risingworld.api.events.player.world.PlayerRemoveGrassEvent;
import net.risingworld.api.events.player.world.PlayerCreativePlaceVegetationEvent;
import net.risingworld.api.events.player.world.PlayerCreativeRemoveVegetationEvent;
import net.risingworld.api.events.player.world.PlayerDestroyVegetationEvent;
import net.risingworld.api.events.player.world.PlayerPlaceVegetationEvent;
import net.risingworld.api.events.player.world.PlayerRemoveVegetationEvent;
import net.risingworld.api.definitions.Terrain;
import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.world.Chunk;
import net.risingworld.api.objects.world.ConstructionElement;
import net.risingworld.api.utils.Vector3i;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.picsoul.rw.minimap.config.MinimapConfig;
import net.picsoul.rw.minimap.config.PlayerPreferences;
import net.picsoul.rw.minimap.render.MapRenderer;
import net.picsoul.rw.minimap.render.TileCache;
import net.picsoul.rw.minimap.capability.CapabilityService;
import net.picsoul.rw.minimap.session.PlayerSession;
import net.picsoul.rw.minimap.session.SessionRegistry;
import net.picsoul.rw.minimap.ui.SettingsPanel;
import net.picsoul.rw.minimap.waypoint.WaypointService;

public class PicSoulsMiniMap extends Plugin implements Listener {

    public static final String PLUGIN_VERSION = "2.76";
    private static final String TAG = "[PicSoulsMiniMap]";
    /** Item type id of the vanilla map (confirmed from the game log: "map (59)"). */
    private static final short VANILLA_MAP_TYPE_ID = 59;

    private final SessionRegistry sessions = new SessionRegistry();
    private MinimapConfig config;
    private CapabilityService capabilityService;
    private TileCache tileCache;
    private MapRenderer mapRenderer;
    private WaypointService waypointService;
    private ExecutorService renderWorker;
    private Timer updateTimer;

    private boolean mapDirty = false;
    private float lastDirtyRefresh = 0f;
    private static final float DIRTY_REFRESH_INTERVAL = 0.15f;

    private boolean perfLogging = false;
    private float lastPerfLog = 0f;
    private int ticksSincePerf = 0;
    private static final float PERF_LOG_INTERVAL = 5f;

    /** Diagnostic (v2.76): always-on (no /mm perf toggle needed) periodic log of
     *  cumulative session totals - added while investigating a native, uncatchable
     *  crash (no exception, no crash dump) confirmed via a clean A/B test to be
     *  caused by this plugin, reproducing across very different play styles
     *  (fast flying, slow walking, standing still) with crash timing clustering
     *  around 8-12 minutes into the session regardless of activity. See
     *  TileCache#lifetimeRenders for the leading theory. */
    private float lastDiagLog = 0f;
    private static final float DIAG_LOG_INTERVAL = 20f;

    private float lastCapsRefresh = 0f;
    private static final float CAPS_REFRESH_INTERVAL = 1f;

    /** False until the world-load grace window elapses after (re)enable; while false
     *  we don't read chunk data or the Maps.db, avoiding world-switch crashes. */
    private boolean renderReady = false;

    /** False until it is safe to attach the HUD. On a fresh game start this is true
     *  immediately; after a main-menu world switch it stays false for
     *  {@code config.hudGraceSeconds}, because attaching UI elements while the
     *  second world of a session loads crashes the game natively. */
    private boolean hudReady = false;
    /** True when this enable is a re-enable inside an already-running game (a world
     *  switch) rather than a fresh game start. */
    private boolean worldSwitch = false;

    /** Players awaiting session setup, queued by events and drained on the tick, so
     *  no UI is ever built or attached from inside a game event callback. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Player> pendingSetup =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Set by events when tiers may have changed; acted on by the next tick. */
    private volatile boolean capsDirty = false;

    /** True when the HUD is being held back for the whole session because this is a
     *  world switch and safe mode is on. */
    private boolean safeModeActive = false;
    /** Player UIDs already told why the minimap is not showing (notify once). */
    private final java.util.HashSet<String> safeModeNotified = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        config = MinimapConfig.defaults();
        loadDiagnostics(); // persisted dev diagnostic toggles (survive world switches)
        // Player-facing settings are per-player now (see PlayerPreferences) - each
        // player's own file is loaded in setupPlayer(), not here.
        // Detect whether this is a fresh game start or a world switch. The OS
        // process start time is identical across a world switch (same game process)
        // but changes when the game is restarted, so comparing it with the value we
        // stored last time tells the two apart even though our classloader — and
        // therefore all static state — is thrown away on every world switch.
        worldSwitch = detectWorldSwitch();
        // Fresh start: attach immediately, exactly as before. After a world switch:
        // hold the HUD back for the grace window, or — in safe mode — permanently,
        // because attaching it in a second world reliably crashes the game.
        hudReady = !worldSwitch;
        safeModeActive = worldSwitch && config.worldSwitchSafeMode;
        capabilityService = new CapabilityService(this, config);
        tileCache = new TileCache(config);
        renderWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PicSoulsMiniMap-Render");
            t.setDaemon(true);
            return t;
        });
        mapRenderer = new MapRenderer(tileCache, this, renderWorker);
        waypointService = new WaypointService(this, config);
        registerEventListener(this);
        ensureRadarIconsFolder();

        for (Player player : Server.getAllPlayers()) {
            if (player.isSpawned()) {
                setupPlayer(player);
            }
        }

        updateTimer = new Timer(config.hudRefreshInterval, 0f, -1, this::tick);
        updateTimer.start();

        System.out.println(TAG + " Enabled (v" + PLUGIN_VERSION + "). Tiers: map/compass/watch/calendar"
                + (config.devAllTiers ? " [DEV: all on]" : "")
                + (config.terrainRendering ? "" : " [DIAG: terrain OFF]")
                + (config.mapDbReads ? "" : " [DIAG: map/DB reads OFF]")
                + (config.useTextures ? "" : " [DIAG: textures OFF]")
                + (config.hudEnabled ? "" : " [DIAG: HUD OFF]")
                + (worldSwitch
                        ? (config.worldSwitchSafeMode
                                ? " [world switch: SAFE MODE — minimap stays off this session]"
                                : " [world switch detected: HUD deferred " + config.hudGraceSeconds + "s]")
                        : " [fresh game start: HUD immediate]"));
    }

    @Override
    public void onDisable() {
        if (updateTimer != null) {
            updateTimer.kill();
            updateTimer = null;
        }
        // Stop the render worker BEFORE disposing session textures, so a render
        // that is finishing can't marshal back and touch a freed texture handle.
        if (renderWorker != null) {
            renderWorker.shutdownNow();
            renderWorker = null;
        }
        sessions.forEach(PlayerSession::destroy);
        sessions.clear();
        // Verifiable: after a correct teardown the game should log
        // "Reset PluginUIManager (0 elements)" a few lines below this.
        try {
            for (Player p : Server.getAllPlayers()) {
                net.risingworld.api.ui.UIElement[] left = p.getAllUIElements(false);
                int n = (left == null) ? 0 : left.length;
                System.out.println(TAG + " UI elements still registered for "
                        + p.getName() + " after teardown: " + n);
                if (left != null) {
                    for (net.risingworld.api.ui.UIElement e : left) {
                        try { p.removeUIElement(e); } catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + " teardown sweep failed: " + t.getMessage());
        }
        if (waypointService != null) {
            waypointService.close();
            waypointService = null;
        }
        System.out.println(TAG + " Disabled.");
    }

    private void tick() {
        ticksSincePerf++;
        // World-load grace: don't touch chunks or Maps.db until the world has had a
        // few seconds to finish streaming in after a (re)enable / world switch.
        if (!renderReady && getRunningTime() > config.renderGraceSeconds) {
            renderReady = true;
            boolean terrainOn = config.terrainRendering;
            for (PlayerSession session : sessions.all()) {
                session.setRenderingEnabled(terrainOn);
            }
            System.out.println(TAG + (terrainOn
                    ? " terrain rendering enabled (world-load grace elapsed)."
                    : " world-load grace elapsed; terrain rendering held OFF (/mm terrain)."));
        }
        // World-switch grace for the HUD itself. Attaching UI elements while the
        // second world of a session is still loading crashes the game natively, so
        // after a switch we hold off until the world has settled, then let the
        // sessions attach.
        if (!hudReady && !safeModeActive && getRunningTime() > config.hudGraceSeconds) {
            hudReady = true;
            for (PlayerSession session : sessions.all()) {
                session.setHudAllowed(true);
            }
            System.out.println(TAG + " HUD enabled (post-world-switch grace elapsed).");
        }
        // Safe mode: explain once, to whoever would otherwise have a minimap.
        if (safeModeActive) {
            for (PlayerSession session : sessions.all()) {
                try {
                    Player p = session.getPlayer();
                    if (!session.getCapabilities().map()) continue;
                    if (!safeModeNotified.add(p.getUID())) continue;
                    p.sendTextMessage(TAG + " minimap is off in this world: showing it after a"
                            + " world switch crashes the game. Restart the game to use it here.");
                    p.sendTextMessage(TAG + " (\"/mm safemode off\" attaches it anyway — expect a crash.)");
                } catch (Exception ignored) {
                }
            }
        }
        if (renderReady && config.mapDbReads && waypointService != null) {
            waypointService.maybeRefresh();
        }
        // Drain deferred session setups queued by events (never build UI in a
        // game event callback — see recompute()).
        Player queued;
        while ((queued = pendingSetup.poll()) != null) {
            try {
                if (queued.isSpawned()) setupPlayer(queued);
            } catch (Exception ignored) {
            }
        }

        // Map guard: while the player holds the vanilla map, keep our HUD off
        // screen so it never coexists with the game's own map UI. This is where
        // every recent crash happened, and the only stable run was the one with our
        // HUD absent. Evaluated here on the tick, never from the equip event.
        if (config.mapGuard) {
            for (PlayerSession session : sessions.all()) {
                try {
                    session.setHudSuppressed(isHoldingVanillaMap(session.getPlayer()));
                } catch (Exception ignored) {
                }
            }
        }

        // Apply any tier recompute requested by events since the last tick.
        if (capsDirty) {
            capsDirty = false;
            for (PlayerSession session : sessions.all()) {
                try {
                    session.recomputeCapabilities();
                } catch (Exception ignored) {
                }
            }
        }

        // Safety-net recompute of tiers, so equipment changes are always reflected
        // even if no specific equip/inventory event fired for the slot.
        float capsNow = getRunningTime();
        if (capsNow - lastCapsRefresh > CAPS_REFRESH_INTERVAL) {
            lastCapsRefresh = capsNow;
            for (PlayerSession session : sessions.all()) {
                try {
                    session.recomputeCapabilities();
                } catch (Exception ignored) {
                }
            }
        }
        if (perfLogging) {
            float now = getRunningTime();
            float dt = now - lastPerfLog;
            if (dt >= PERF_LOG_INTERVAL) {
                float hz = (dt > 0) ? ticksSincePerf / dt : 0f;
                System.out.println(String.format(TAG + "[perf] update rate=%.0f Hz, %s, tilesCached=%d",
                        hz, mapRenderer.intervalStatsAndReset(), tileCache.size()));
                lastPerfLog = now;
                ticksSincePerf = 0;
            }
        }
        // Always-on (no toggle needed) so a crash's last log line shows these
        // cumulative totals - see the field doc on lastDiagLog for why.
        {
            float now = getRunningTime();
            if (now - lastDiagLog >= DIAG_LOG_INTERVAL) {
                lastDiagLog = now;
                System.out.println(String.format(
                        TAG + "[diag] session totals: uptimeSec=%.0f tilesCached=%d lifetimeRenders=%d",
                        now, tileCache.size(), tileCache.lifetimeRenders()));
            }
        }
        if (mapDirty) {
            float now = getRunningTime();
            if (now - lastDirtyRefresh > DIRTY_REFRESH_INTERVAL) {
                for (PlayerSession session : sessions.all()) {
                    session.invalidateMap();
                }
                mapDirty = false;
                lastDirtyRefresh = now;
            }
        }
        long t0 = System.nanoTime();
        for (PlayerSession session : sessions.all()) {
            try {
                session.tick();
            } catch (Exception ex) {
                System.out.println(TAG + " tick error: " + ex.getMessage());
            }
        }
        double tickMs = (System.nanoTime() - t0) / 1_000_000.0;
        if (tickMs > 5.0) {
            System.out.println(String.format(TAG + "[perf] SLOW TICK %.1fms (HUD update: pan/marker/label)", tickMs));
        }
    }

    private void onWorldEdit(Player player) {
        if (player == null) return;
        Vector3i cp = player.getChunkPosition();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                tileCache.invalidate(cp.x + dx, cp.z + dz);
            }
        }
        mapDirty = true;
    }

    private void setupPlayer(Player player) {
        if (sessions.get(player) != null) return;
        PlayerPreferences prefs = loadPlayerPrefs(player);
        PlayerSession session = new PlayerSession(this, player, config, prefs, mapRenderer, waypointService,
                capabilityService, sessions);
        session.setRenderingEnabled(renderReady && config.terrainRendering); // defer during grace; off if /mm terrain off
        session.setHudAllowed(hudReady); // no UI attach until the world-switch grace has passed
        sessions.put(player, session);
        session.recomputeCapabilities();
        registerHotkeys(player); // so PageUp/PageDown (or the rebound keys) and Ctrl+M reach us
    }

    // ---- Zoom + settings-panel hotkey: key input + rebinding ----

    /** Register the current zoom keys, plus the settings-panel hotkey
     *  ({@code settingsKeyName}), for a player so their presses reach us. */
    private void registerHotkeys(Player player) {
        try {
            PlayerSession s = sessions.get(player);
            PlayerPreferences prefs = s != null ? s.getPrefs() : PlayerPreferences.defaults();
            player.registerKeys(parseKey(prefs.zoomInKeyName), parseKey(prefs.zoomOutKeyName),
                    parseKey(config.settingsKeyName));
            // Registering keys alone is not enough: the client only forwards key
            // input (and PlayerKeyEvent only fires) once this plugin has also
            // opted in via setListenForKeyInput. Without this call every key press
            // — these AND the settings-panel rebind capture, which registers
            // Key.values() — is silently dropped by the client.
            player.setListenForKeyInput(true);
        } catch (Throwable ignored) {
        }
    }

    /** Clear every registered key, then re-register just the hotkeys (used
     *  after a rebind capture, which temporarily registers all keys). */
    private void restoreZoomKeyRegistration(Player player) {
        try {
            player.unregisterKeys(Key.values());
        } catch (Throwable ignored) {
        }
        registerHotkeys(player);
    }

    @EventMethod(Threading.Sync)
    public void onKey(PlayerKeyEvent event) {
        if (!event.isPressed()) return;
        Player player = event.getPlayer();
        PlayerSession s = sessions.get(player);
        if (s == null) return;
        Key k = event.getKey();

        int cap = s.getCaptureWhich();
        if (cap != 0) { // rebinding: the next key becomes the binding
            s.setCaptureWhich(0);
            boolean cancelled = (k == Key.Escape || k == Key.None);
            if (!cancelled) {
                PlayerPreferences prefs = s.getPrefs();
                if (cap == 1) prefs.zoomInKeyName = k.name();
                else prefs.zoomOutKeyName = k.name();
                savePlayerPrefs(player, prefs);
            }
            restoreZoomKeyRegistration(player);
            SettingsPanel panel = s.getSettingsPanel();
            if (panel.isOpen()) panel.setCapturing(0);
            player.sendTextMessage(TAG + (cancelled ? " rebind cancelled."
                    : " zoom-" + (cap == 1 ? "in" : "out") + " key set to " + k.name()));
            return;
        }

        // settingsKeyName (default F1) toggles the settings panel, mirroring
        // "/mm settings" - works regardless of whether the minimap itself is
        // currently shown, same as the chat command. Was originally Ctrl+M,
        // but plain M is the base game's own "open map" key and
        // PlayerKeyEvent has no way to suppress that (no setCancelled()), so
        // both fired together no matter what modifier we required on our
        // side - the game reacts to the raw key regardless. Then F4, which
        // turned out to be bound to something in creative mode.
        if (k == parseKey(config.settingsKeyName)) {
            s.getSettingsPanel().toggle();
            return;
        }

        if (!s.isVisible()) return; // zoom keys only act when the minimap is shown
        if (k == parseKey(s.getPrefs().zoomInKeyName)) applyZoomStep(s, -1);
        else if (k == parseKey(s.getPrefs().zoomOutKeyName)) applyZoomStep(s, +1);
    }

    @EventMethod(Threading.Sync)
    public void onUiClick(PlayerUIElementClickEvent event) {
        PlayerSession s = sessions.get(event.getPlayer());
        if (s == null) return;
        SettingsPanel panel = s.getSettingsPanel();
        switch (panel.actionFor(event.getUIElement())) {
            case CHANGE_IN -> beginCapture(event.getPlayer(), s, 1);
            case CHANGE_OUT -> beginCapture(event.getPlayer(), s, 2);
            case ICON_SIZE_TRACK -> panel.applyIconSizeFromRelativeX(event.getRelativeMousePositionX());
            case MAP_SIZE_TRACK -> {
                panel.applyMapSizeFromRelativeX(event.getRelativeMousePositionX());
                s.getHud().applyLayoutChange();
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
            }
            case CORNER_CYCLE -> {
                panel.cycleCorner();
                s.getHud().applyLayoutChange();
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
            }
            case SET_ROTATE_ON -> {
                s.getPrefs().rotate = true;
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
                panel.refresh();
            }
            case SET_ROTATE_OFF -> {
                s.getPrefs().rotate = false;
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
                panel.refresh();
            }
            case SET_CONTOUR_ON -> {
                PlayerPreferences p = s.getPrefs();
                if (!p.contourEnabled) {
                    p.contourEnabled = true;
                    s.invalidateMap();
                    savePlayerPrefs(event.getPlayer(), p);
                }
                panel.refresh();
            }
            case SET_CONTOUR_OFF -> {
                PlayerPreferences p = s.getPrefs();
                if (p.contourEnabled) {
                    p.contourEnabled = false;
                    s.invalidateMap();
                    savePlayerPrefs(event.getPlayer(), p);
                }
                panel.refresh();
            }
            case SET_HIDDEN_ON -> {
                s.getPrefs().hiddenFromOthers = true;
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
                panel.refresh();
            }
            case SET_HIDDEN_OFF -> {
                s.getPrefs().hiddenFromOthers = false;
                savePlayerPrefs(event.getPlayer(), s.getPrefs());
                panel.refresh();
            }
            case RESET_DEFAULTS -> {
                // Resets only what's actually on this panel (zoom keys, entity
                // icon size, map size/corner, rotate, contour, hidden) - NOT
                // the /mm diagreset diagnostic switches (terrain/mapdb/
                // textures/hud/mapguard/safemode/uilite/minimal/teardown),
                // which are a separate, unrelated set of crash-diagnosis dev
                // toggles, not player-facing settings. Per-player prefs reset
                // for THIS player only; entity icon size is still a shared
                // config value (not yet exposed as per-player), so it resets
                // for everyone, same as always.
                PlayerPreferences d = PlayerPreferences.defaults();
                PlayerPreferences p = s.getPrefs();
                p.zoomInKeyName = d.zoomInKeyName;
                p.zoomOutKeyName = d.zoomOutKeyName;
                p.minimapSizePx = d.minimapSizePx;
                p.corner = d.corner;
                p.rotate = d.rotate;
                p.contourEnabled = d.contourEnabled;
                p.hiddenFromOthers = d.hiddenFromOthers;
                config.radarIconPx = MinimapConfig.defaults().radarIconPx;
                restoreZoomKeyRegistration(event.getPlayer());
                s.invalidateMap();
                s.getHud().applyLayoutChange();
                savePlayerPrefs(event.getPlayer(), p);
                panel.refresh();
                event.getPlayer().sendTextMessage(TAG + " settings reset to defaults.");
            }
            case CLOSE -> {
                if (s.getCaptureWhich() != 0) {
                    s.setCaptureWhich(0);
                    restoreZoomKeyRegistration(event.getPlayer());
                }
                panel.close();
            }
            case NONE -> { }
        }
    }

    /** Enter "press a key" mode for a zoom binding (which: 1=in, 2=out). */
    private void beginCapture(Player player, PlayerSession s, int which) {
        s.setCaptureWhich(which);
        try {
            player.registerKeys(Key.values()); // capture whatever key is pressed next
        } catch (Throwable ignored) {
        }
        SettingsPanel panel = s.getSettingsPanel();
        if (panel.isOpen()) panel.setCapturing(which);
        player.sendTextMessage(TAG + " press a key to bind zoom-" + (which == 1 ? "in" : "out")
                + " (Esc cancels).");
    }

    /** Step the zoom by one level (dir: -1 = in/closer, +1 = out/wider). */
    private void applyZoomStep(PlayerSession s, int dir) {
        setSessionZoom(s, steppedZoom(s.getHud().getZoom(), dir));
    }

    /** Nearest zoom step to {@code current}, moved by dir and clamped. */
    private int steppedZoom(int current, int dir) {
        int[] steps = config.zoomSteps;
        if (steps == null || steps.length == 0) return current;
        int idx = 0, best = Integer.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            int dd = Math.abs(steps[i] - current);
            if (dd < best) { best = dd; idx = i; }
        }
        idx = Math.max(0, Math.min(steps.length - 1, idx + dir));
        return steps[idx];
    }

    /** Apply a zoom to a session's HUD and persist it as the current level. */
    private void setSessionZoom(PlayerSession s, int cells) {
        cells = Math.max(16, Math.min(1024, cells));
        s.getPrefs().defaultZoomCells = cells; // so a rebuilt HUD builds at this zoom
        s.getHud().setZoom(cells);             // live-updates if already built
        savePlayerPrefs(s.getPlayer(), s.getPrefs());
    }

    /** Resolve a Key enum name (tolerant of case/spaces + a few aliases). */
    private static Key parseKey(String name) {
        if (name == null) return Key.None;
        String n = name.trim().replace(" ", "").replace("_", "");
        for (Key k : Key.values()) {
            if (k.name().equalsIgnoreCase(n)) return k;
        }
        switch (n.toLowerCase()) {
            case "pgup": return Key.PageUp;
            case "pgdn": case "pgdown": return Key.PageDown;
            case "plus": return Key.Equals;
            case "minus": case "dash": return Key.Minus;
            default: return Key.None;
        }
    }

    @EventMethod(Threading.Sync)
    public void onSpawn(PlayerSpawnEvent event) {
        // Deferred: never build/attach UI from inside a game event callback (v2.23).
        pendingSetup.add(event.getPlayer());
    }

    @EventMethod(Threading.Sync)
    public void onDisconnect(PlayerDisconnectEvent event) {
        PlayerSession session = sessions.remove(event.getPlayer());
        if (session != null) {
            savePlayerPrefs(event.getPlayer(), session.getPrefs()); // safety net; every mutation already saves
            session.destroy();
        }
    }

    @EventMethod(Threading.Sync)
    public void onSetSpawnPoint(PlayerSetSpawnPointEvent event) {
        PlayerSession session = sessions.get(event.getPlayer());
        if (session != null) {
            session.setCurrentSpawn(event.getNewSpawnPosition());
        }
    }

    @EventMethod(Threading.Sync)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerSession session = sessions.get(event.getPlayer());
        if (session != null) {
            session.setCurrentSpawn(event.getSpawnPosition());
        }
    }

    @EventMethod(Threading.Sync)
    public void onCommand(PlayerCommandEvent event) {
        String raw = event.getCommand();
        if (raw == null) return;

        String[] parts = raw.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        if (!cmd.equals("minimap") && !cmd.equals("mm")) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        PlayerSession session = sessions.get(player);
        if (session == null) {
            setupPlayer(player);
            session = sessions.get(player);
        }

        String sub = parts.length > 1 ? parts[1].toLowerCase() : "toggle";
        switch (sub) {
            case "toggle" -> {
                boolean visible = session.toggleMinimap();
                player.sendTextMessage(TAG + " " + (visible ? "shown" : "hidden"));
            }
            case "status", "caps" -> {
                var c = session.getCapabilities();
                player.sendTextMessage(TAG + " tiers: map=" + c.map() + " compass=" + c.compass()
                        + " watch=" + c.watch() + " calendar=" + c.calendar() + " radar=" + c.radar()
                        + " | visible=" + session.isVisible() + (config.devAllTiers ? " (DEV)" : ""));
                player.sendTextMessage(TAG + " time: " + timeDebug());
                player.sendTextMessage(TAG + " diag: terrain=" + (config.terrainRendering ? "ON" : "OFF")
                        + " mapdb=" + (config.mapDbReads ? "ON" : "OFF")
                        + " textures=" + (config.useTextures ? "ON" : "OFF")
                        + " hud=" + (config.hudEnabled ? "ON" : "OFF")
                        + " mapguard=" + (config.mapGuard ? "ON" : "OFF")
                        + " safemode=" + (config.worldSwitchSafeMode ? "ON" : "OFF")
                        + " safeModeActive=" + safeModeActive
                        + " uilite=" + (config.uiLite ? "ON" : "OFF")
                        + " minimal=" + (config.minimalUi ? "ON" : "OFF")
                        + " teardown=" + config.teardownMode
                        + " | worldSwitch=" + worldSwitch + " hudReady=" + hudReady
                        + " hudgrace=" + config.hudGraceSeconds + "s");
                player.sendTextMessage(TAG + " caves=" + (config.caveDetectionEnabled ? "ON" : "OFF")
                        + " cavemode=" + (config.caveModeEnabled ? "ON" : "OFF")
                        + " | currently in cave view=" + session.getHud().isCaveMode());
                player.sendTextMessage(TAG + " trees=" + (config.showVegetation ? "ON" : "OFF")
                        + " radar=" + (config.showRadar ? "ON" : "OFF")
                        + " players=" + (session.getPrefs().showOtherPlayers ? "ON" : "OFF")
                        + " hidden=" + (session.getPrefs().hiddenFromOthers ? "ON" : "OFF"));
            }
            case "dev" -> {
                config.devAllTiers = !config.devAllTiers;
                for (PlayerSession s : sessions.all()) s.recomputeCapabilities();
                player.sendTextMessage(TAG + " dev-all-tiers " + (config.devAllTiers ? "ON" : "OFF"));
            }
            case "ids" -> {
                dumpSurfaceIds(player);
                String slots = capabilityService.describeSlots(player);
                System.out.println(TAG + "[ids] slots: " + slots);
                player.sendTextMessage(TAG + " " + slots);
            }
            case "npcs", "entities" -> {
                var defs = net.risingworld.api.definitions.Definitions.getAllNpcDefinitions();
                if (defs == null || defs.length == 0) {
                    player.sendTextMessage(TAG + " no npc definitions registered.");
                    break;
                }
                // getAllNpcDefinitions() can contain null entries (seen in-game:
                // an NPE sorting hit a null element), so filter those out first —
                // and null-safe the type/name compare too, since a real entry's
                // type or name field could plausibly be unset the same way.
                var sorted = new java.util.ArrayList<net.risingworld.api.definitions.Npcs.NpcDefinition>();
                for (var d : defs) if (d != null) sorted.add(d);
                sorted.sort((a, b) -> {
                    String at = a.type != null ? a.type.name() : "";
                    String bt = b.type != null ? b.type.name() : "";
                    int c = at.compareTo(bt);
                    if (c != 0) return c;
                    String an = a.name != null ? a.name : "";
                    String bn = b.name != null ? b.name : "";
                    return an.compareToIgnoreCase(bn);
                });
                StringBuilder sb = new StringBuilder();
                net.risingworld.api.definitions.Npcs.Type lastType = null;
                for (var d : sorted) {
                    if (d.type != lastType) {
                        sb.append("\n-- ").append(d.type).append(" --");
                        lastType = d.type;
                    }
                    sb.append("\n  id=").append(d.id).append(" name='").append(d.name).append('\'');
                }
                System.out.println(TAG + "[npcs] " + sorted.size() + " npc definitions registered ("
                        + (defs.length - sorted.size()) + " null entries skipped), by type:" + sb);
                player.sendTextMessage(TAG + " logged " + sorted.size()
                        + " registered npc definitions (grouped by type) to server console.");
            }
            case "perf" -> {
                perfLogging = !perfLogging;
                lastPerfLog = getRunningTime();
                mapRenderer.intervalStatsAndReset();
                player.sendTextMessage(TAG + " performance logging " + (perfLogging ? "ON" : "OFF")
                        + " — just play; it writes to the game console every " + (int) PERF_LOG_INTERVAL + "s.");
                System.out.println(TAG + "[perf] continuous logging " + (perfLogging ? "ENABLED" : "DISABLED")
                        + ". cumulative " + mapRenderer.statsLine());
            }
            case "contour" -> {
                PlayerPreferences p = session.getPrefs();
                p.contourEnabled = !p.contourEnabled;
                session.invalidateMap();
                savePlayerPrefs(player, p);
                player.sendTextMessage(TAG + " contour lines " + (p.contourEnabled ? "ON" : "OFF"));
            }
            case "blur" -> {
                if (parts.length > 2) {
                    try {
                        config.terrainBlurRadius = Math.max(0, Math.min(2, Integer.parseInt(parts[2])));
                    } catch (NumberFormatException e) {
                        player.sendTextMessage(TAG + " usage: /mm blur [0|1|2]");
                        break;
                    }
                } else {
                    config.terrainBlurRadius = (config.terrainBlurRadius + 1) % 3;
                }
                tileCache.clear();
                session.invalidateMap();
                player.sendTextMessage(TAG + " terrain color blur radius = " + config.terrainBlurRadius
                        + " (0=off, 1=3x3, 2=5x5 — construction blocks always stay sharp)");
            }
            case "caves" -> {
                config.caveDetectionEnabled = parseOnOff(parts, config.caveDetectionEnabled);
                tileCache.clear();
                session.invalidateMap();
                player.sendTextMessage(TAG + " cave-opening detection " + (config.caveDetectionEnabled ? "ON" : "OFF")
                        + " — when on, a real hole in the ground (sinkholes, cave mouths) looks down"
                        + " through it to the real floor instead of drawing fictitious surface.");
            }
            case "cavemode" -> {
                config.caveModeEnabled = parseOnOff(parts, config.caveModeEnabled);
                if (!config.caveModeEnabled) session.getHud().setCaveMode(false);
                player.sendTextMessage(TAG + " cave mode " + (config.caveModeEnabled ? "ON" : "OFF")
                        + " — while ON, the minimap automatically switches to a local view of the real"
                        + " terrain around you (instead of the meaningless surface map) whenever the game"
                        + " reports you're underground.");
            }
            case "trees", "vegetation" -> {
                config.showVegetation = parseOnOff(parts, config.showVegetation);
                tileCache.clear();
                session.invalidateMap();
                player.sendTextMessage(TAG + " trees " + (config.showVegetation ? "ON" : "OFF")
                        + " — trees/fruit trees tinted into the terrain tile as canopy blobs,"
                        + " shaped/sized/colored by tree type.");
            }
            case "radar" -> {
                config.showRadar = parseOnOff(parts, config.showRadar);
                player.sendTextMessage(TAG + " entity radar " + (config.showRadar ? "ON" : "OFF")
                        + " — requires the upgraded compass (compassmodern) equipped;"
                        + " compassold does not unlock it. Shows nearby animals/npcs as colored"
                        + " blips (red=hostile, amber=defensive, green=animal, blue=human, tan=mount).");
            }
            case "players" -> {
                PlayerPreferences p = session.getPrefs();
                p.showOtherPlayers = parseOnOff(parts, p.showOtherPlayers);
                savePlayerPrefs(player, p);
                player.sendTextMessage(TAG + " other-player markers " + (p.showOtherPlayers ? "ON" : "OFF")
                        + " — shows every other connected player's live position/name on the map,"
                        + " always visible (clamped to the rim like the spawn marker when out of range).");
            }
            case "hidden" -> {
                PlayerPreferences p = session.getPrefs();
                p.hiddenFromOthers = parseOnOff(parts, p.hiddenFromOthers);
                savePlayerPrefs(player, p);
                player.sendTextMessage(TAG + " hide-me-from-others " + (p.hiddenFromOthers ? "ON" : "OFF")
                        + (p.hiddenFromOthers
                                ? " — your position no longer appears on other players' minimaps."
                                : " — your position is visible on other players' minimaps again."));
            }
            case "fruitdebug" -> {
                config.debugFruitLogging = parseOnOff(parts, config.debugFruitLogging);
                tileCache.clear();
                session.invalidateMap();
                player.sendTextMessage(TAG + " fruit debug logging " + (config.debugFruitLogging ? "ON" : "OFF")
                        + " — every FruitTree the renderer processes near you will print to the"
                        + " server console (name, pickupitem, hasFruit, radius). Forced an immediate"
                        + " re-render so it logs right away; stand near a fruiting tree and check the console.");
            }
            case "smooth" -> {
                config.smoothPanning = !config.smoothPanning;
                player.sendTextMessage(TAG + " smooth panning " + (config.smoothPanning ? "ON" : "OFF"));
            }
            case "rotate" -> {
                PlayerPreferences p = session.getPrefs();
                p.rotate = !p.rotate;
                savePlayerPrefs(player, p);
                player.sendTextMessage(TAG + " map rotation " + (p.rotate ? "ON" : "OFF"));
            }
            case "waypoints", "wp" -> {
                if (parts.length > 2 && parts[2].equalsIgnoreCase("refresh")) {
                    if (waypointService != null) waypointService.refreshNow();
                    int n = (waypointService != null) ? waypointService.getMarkers().size() : 0;
                    player.sendTextMessage(TAG + " waypoints reloaded (" + n + " markers)");
                } else {
                    config.showWaypoints = !config.showWaypoints;
                    player.sendTextMessage(TAG + " waypoints " + (config.showWaypoints ? "ON" : "OFF"));
                }
            }
            case "wpprivacy", "privacy" -> {
                PlayerPreferences p = session.getPrefs();
                p.waypointPrivacy = parseOnOff(parts, p.waypointPrivacy);
                savePlayerPrefs(player, p);
                player.sendTextMessage(TAG + " waypoint privacy " + (p.waypointPrivacy ? "ON" : "OFF")
                        + (p.waypointPrivacy
                                ? " — your own markers all show; other players' show only if Global."
                                : " — every marker shows regardless of owner."));
            }
            case "spawn" -> {
                config.showSpawnLine = !config.showSpawnLine;
                player.sendTextMessage(TAG + " spawn line " + (config.showSpawnLine ? "ON" : "OFF"));
            }
            case "zoom" -> {
                String arg = parts.length > 1 ? parts[1].toLowerCase() : "";
                if (arg.equals("in")) applyZoomStep(session, -1);
                else if (arg.equals("out")) applyZoomStep(session, +1);
                else if (arg.equals("reset")) setSessionZoom(session, 96);
                else if (!arg.isEmpty()) {
                    try { setSessionZoom(session, Integer.parseInt(arg)); }
                    catch (NumberFormatException e) {
                        player.sendTextMessage(TAG + " usage: /mm zoom [in|out|reset|<cells>]");
                    }
                }
                player.sendTextMessage(TAG + " zoom: " + session.getHud().getZoom()
                        + " cells across (keys: " + session.getPrefs().zoomInKeyName + " in / "
                        + session.getPrefs().zoomOutKeyName + " out)");
            }
            case "zoomkey" -> {
                if (parts.length < 3) {
                    player.sendTextMessage(TAG + " usage: /mm zoomkey in|out <KeyName>  (e.g. PageUp)");
                    break;
                }
                String dir = parts[1].toLowerCase();
                Key k = parseKey(parts[2]);
                if (k == Key.None) {
                    player.sendTextMessage(TAG + " unknown key '" + parts[2] + "'. Use a name like"
                            + " PageUp, PageDown, LeftBracket, Equals, NumpadPlus, Home, End…");
                    break;
                }
                PlayerPreferences p = session.getPrefs();
                if (dir.equals("in")) p.zoomInKeyName = k.name();
                else if (dir.equals("out")) p.zoomOutKeyName = k.name();
                else {
                    player.sendTextMessage(TAG + " usage: /mm zoomkey in|out <KeyName>");
                    break;
                }
                savePlayerPrefs(player, p);
                restoreZoomKeyRegistration(player); // this player's own binding only - not everyone's
                if (session.getSettingsPanel().isOpen()) session.getSettingsPanel().refresh();
                player.sendTextMessage(TAG + " zoom-" + dir + " key set to " + k.name());
            }
            case "settings", "menu", "ui" -> {
                SettingsPanel panel = session.getSettingsPanel();
                if (panel.isOpen() && session.getCaptureWhich() != 0) {
                    session.setCaptureWhich(0);
                    restoreZoomKeyRegistration(player);
                }
                panel.toggle();
                player.sendTextMessage(TAG + " settings " + (panel.isOpen() ? "opened" : "closed"));
            }
            case "terrain" -> {
                config.terrainRendering = parseOnOff(parts, config.terrainRendering);
                saveDiagnostics();
                boolean apply = config.terrainRendering && renderReady;
                for (PlayerSession s : sessions.all()) s.setRenderingEnabled(apply);
                player.sendTextMessage(TAG + " terrain rendering " + (config.terrainRendering ? "ON" : "OFF")
                        + " (persists across world switches — diagnostic)");
            }
            case "minimal" -> {
                config.minimalUi = parseOnOff(parts, config.minimalUi);
                saveDiagnostics();
                for (PlayerSession s : sessions.all()) s.requestRebuild();
                player.sendTextMessage(TAG + " minimal UI " + (config.minimalUi
                        ? "ON — only a coordinates label (2 UI elements), nothing else"
                        : "OFF — full minimap")
                        + "; applied now. Persists.");
            }
            case "uilite" -> {
                config.uiLite = parseOnOff(parts, config.uiLite);
                saveDiagnostics();
                for (PlayerSession s : sessions.all()) s.requestRebuild();
                player.sendTextMessage(TAG + " UI-lite " + (config.uiLite ? "ON (~40 UI elements)"
                        : "OFF (~190 UI elements)")
                        + " — applied now; persists. Diagnostic: tests whether the"
                        + " number of UI elements is what kills a switched world.");
            }
            case "teardown" -> {
                if (parts.length > 2) {
                    String m = parts[2].toLowerCase();
                    if (m.equals("full") || m.equals("roots") || m.equals("none")) {
                        config.teardownMode = m;
                        saveDiagnostics();
                    } else {
                        player.sendTextMessage(TAG + " usage: /mm teardown full|roots|none");
                        break;
                    }
                }
                player.sendTextMessage(TAG + " UI teardown mode = " + config.teardownMode
                        + " (full=purge everything, roots=only the 2 containers,"
                        + " none=leave it all to the game). Persists.");
            }
            case "safemode" -> {
                config.worldSwitchSafeMode = parseOnOff(parts, config.worldSwitchSafeMode);
                saveDiagnostics();
                if (!config.worldSwitchSafeMode && safeModeActive) {
                    // Opt in to attaching anyway, without needing a restart.
                    safeModeActive = false;
                    hudReady = true;
                    for (PlayerSession s : sessions.all()) s.setHudAllowed(true);
                    player.sendTextMessage(TAG + " safe mode OFF — attaching the minimap now."
                            + " The game is likely to crash; this is for diagnosis only.");
                } else {
                    player.sendTextMessage(TAG + " world-switch safe mode "
                            + (config.worldSwitchSafeMode ? "ON (minimap stays off after a world"
                                    + " switch; restart the game to use it)" : "OFF")
                            + " (persists)");
                }
            }
            case "mapguard" -> {
                config.mapGuard = parseOnOff(parts, config.mapGuard);
                saveDiagnostics();
                if (!config.mapGuard) {
                    for (PlayerSession s : sessions.all()) s.setHudSuppressed(false);
                }
                player.sendTextMessage(TAG + " map guard " + (config.mapGuard ? "ON" : "OFF")
                        + " — hides the minimap while you hold the vanilla map (persists)");
            }
            case "hudgrace" -> {
                if (parts.length > 2) {
                    try {
                        config.hudGraceSeconds = Math.max(0f, Float.parseFloat(parts[2]));
                        saveDiagnostics();
                    } catch (NumberFormatException e) {
                        player.sendTextMessage(TAG + " usage: /mm hudgrace <seconds>");
                        break;
                    }
                }
                player.sendTextMessage(TAG + " HUD world-switch delay = " + config.hudGraceSeconds
                        + "s (raise it if switching worlds still crashes; persists)");
            }
            case "diagreset" -> {
                config.terrainRendering = true;
                config.mapDbReads = true;
                config.useTextures = true;
                config.hudEnabled = true;
                config.mapGuard = true;
                config.worldSwitchSafeMode = false;
                config.uiLite = false;      // full-size element pools
                config.minimalUi = false;   // full minimap, not just a coords label
                config.hudGraceSeconds = 15f;
                config.teardownMode = "none";
                saveDiagnostics();
                // These change the shape of the element tree, so rebuild it now
                // rather than making the player rejoin the world.
                for (PlayerSession s : sessions.all()) {
                    s.setRenderingEnabled(renderReady && config.terrainRendering);
                    s.setHudSuppressed(false);
                    s.requestRebuild();
                }
                player.sendTextMessage(TAG + " defaults restored"
                        + " (terrain/mapdb/textures/hud/mapguard ON, safemode OFF,"
                        + " uilite+minimal OFF, teardown=none) and the minimap was rebuilt.");
            }
            case "notex" -> {
                // Argument is "is texture creation OFF", so invert into useTextures.
                boolean off = parseOnOff(parts, !config.useTextures);
                config.useTextures = !off;
                saveDiagnostics();
                for (PlayerSession s : sessions.all()) s.requestRebuild();
                player.sendTextMessage(TAG + " texture creation " + (config.useTextures ? "ON" : "OFF")
                        + " — applied now; persists across world switches");
            }
            case "hud" -> {
                config.hudEnabled = parseOnOff(parts, config.hudEnabled);
                saveDiagnostics();
                if (!config.hudEnabled) {
                    for (PlayerSession s : sessions.all()) s.hideMinimap();
                } else {
                    for (PlayerSession s : sessions.all()) s.recomputeCapabilities();
                }
                player.sendTextMessage(TAG + " HUD " + (config.hudEnabled ? "ON" : "OFF")
                        + " (persists across world switches — diagnostic)");
            }
            case "mapdb" -> {
                config.mapDbReads = parseOnOff(parts, config.mapDbReads);
                saveDiagnostics();
                player.sendTextMessage(TAG + " map/DB reads " + (config.mapDbReads ? "ON" : "OFF")
                        + " (Maps.db waypoints + spawn lookup; persists across world switches — diagnostic)");
            }
            case "version" -> {
                player.sendTextMessage(TAG + " version " + PLUGIN_VERSION);
            }
            default -> player.sendTextMessage(TAG + " usage: /mm [toggle|status|caps|dev|ids|perf|contour|blur|caves|cavemode|trees|fruitdebug|smooth|rotate|zoom|zoomkey|settings|waypoints|wpprivacy|spawn|players|hidden|terrain|mapdb|notex|hud|mapguard|safemode|uilite|minimal|teardown|hudgrace|diagreset|version]");
        }
    }

    @EventMethod(Threading.Sync)
    public void onDestroyTerrain(PlayerDestroyTerrainEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onPlaceTerrain(PlayerPlaceTerrainEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onCreativeTerrain(PlayerCreativeTerrainEditEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onRemoveGrass(PlayerRemoveGrassEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onPlaceGrass(PlayerPlaceGrassEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onPlaceConstruction(PlayerPlaceConstructionEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onRemoveConstruction(PlayerRemoveConstructionEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onDestroyConstruction(PlayerDestroyConstructionEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onPlaceBlueprint(PlayerPlaceBlueprintEvent e) { onWorldEdit(e.getPlayer()); }

    // ---- Vegetation: trees are pixel-baked into the terrain tile (v2.39+) ----
    // Trees are painted straight into the terrain tile (see
    // TileRenderer.overlayVegetation), so any change to them needs the same
    // cache invalidation a terrain edit gets.

    @EventMethod(Threading.Sync)
    public void onDestroyVegetation(PlayerDestroyVegetationEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onRemoveVegetation(PlayerRemoveVegetationEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onCreativeRemoveVegetation(PlayerCreativeRemoveVegetationEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onPlaceVegetation(PlayerPlaceVegetationEvent e) { onWorldEdit(e.getPlayer()); }

    @EventMethod(Threading.Sync)
    public void onCreativePlaceVegetation(PlayerCreativePlaceVegetationEvent e) { onWorldEdit(e.getPlayer()); }

    /** Create the plugin's radar-icons folder (next to the jar) if it doesn't
     *  exist yet, and log its absolute path once so it's easy to find - this is
     *  where custom per-species radar icon PNGs go (see /mm npcs for names). */
    private void ensureRadarIconsFolder() {
        try {
            String dir = getPath();
            if (dir == null || dir.isEmpty()) return;
            java.io.File folder = new java.io.File(dir, config.radarIconsSubfolder);
            if (!folder.exists()) {
                folder.mkdirs();
                System.out.println(TAG + "[radar] created icons folder: " + folder.getAbsolutePath());
            }
            System.out.println(TAG + "[radar] custom per-species icons go in: " + folder.getAbsolutePath()
                    + "  (<npc name from /mm npcs>.png, e.g. wolf.png, wolf_hostile.png, horse_saddled.png)");
        } catch (Throwable t) {
            System.out.println(TAG + "[radar] could not prepare icons folder: " + t.getMessage());
        }
    }

    private void dumpSurfaceIds(Player player) {
        Chunk c = player.getChunk();
        if (c == null || !c.isValid()) {
            player.sendTextMessage(TAG + " no valid chunk here.");
            return;
        }
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (int z = 0; z < Chunk.SIZE_Z; z++) {
            for (int x = 0; x < Chunk.SIZE_X; x++) {
                ids.add(c.getLODSurfaceTexture(x, z) & 0xFF);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            Terrain t = null;
            try {
                t = Terrain.get(id);
            } catch (Exception ignored) {
            }
            sb.append(id).append('=').append(t).append("  ");
        }
        System.out.println(TAG + "[ids] chunk (" + c.getChunkPositionX() + "," + c.getChunkPositionZ()
                + ") surface ids: " + sb);

        ConstructionElement[] cons = c.getAllConstructionElements();
        if (cons != null && cons.length > 0) {
            StringBuilder cb = new StringBuilder();
            int shown = 0;
            for (ConstructionElement e : cons) {
                if (e == null || !e.isValid()) continue;
                cb.append("type=").append(e.getTypeID())
                  .append(" tex=").append(e.getTexture())
                  .append(" color=").append(Integer.toHexString(e.getColor()))
                  .append("  ");
                if (++shown >= 10) break;
            }
            System.out.println(TAG + "[ids] " + cons.length + " construction elements, sample: " + cb);
        }

        // v2.44: nearby TREE species, dumping every field that might plausibly
        // signal "this fruit tree currently has pickable fruit" — the v2.43
        // guess (PlantDefinition.pickupitem) was wrong (user confirmed: no
        // accent dots ever appeared on a visibly fruiting apple tree).
        // Deduplicated by definition id, NOT name: if a fruiting vs. barren
        // apple tree turns out to be two different PlantDefinitions that
        // happen to share the display name "Apple tree" (plausible — that's
        // exactly the pattern growth stages already use, a differently-IDed
        // definition swapped in via nextgrowthstage), deduping by name would
        // silently collapse them into one logged entry and hide the very
        // field we're trying to find. Only Tree/FruitTree are logged —
        // nothing else is rendered on the minimap as of v2.42.
        net.risingworld.api.objects.world.Plant[] plants = c.getAllPlants();
        if (plants != null && plants.length > 0) {
            java.util.TreeMap<Short, String> byId = new java.util.TreeMap<>();
            int treeCount = 0;
            for (var p : plants) {
                if (p == null || !p.isValid()) continue;
                var def = p.getDefinition();
                if (def == null || def.name == null) continue;
                if (def.type != net.risingworld.api.definitions.Plants.Type.Tree
                        && def.type != net.risingworld.api.definitions.Plants.Type.FruitTree) {
                    continue;
                }
                treeCount++;
                byId.putIfAbsent(def.id, def.name + " type=" + def.type
                        + " windparam=" + def.windparam + " extent=" + def.extent + " stage=" + def.stage
                        + " cangrow=" + def.cangrow + " nextgrowthstage=" + def.nextgrowthstage
                        + " pickupitem=" + def.pickupitem + " pickupitemcount=" + def.pickupitemcount
                        + " pickuprestplant=" + def.pickuprestplant
                        + " harvestable=" + def.harvestable + " harvestitem=" + def.harvestitem
                        + " harvestitemcount=" + def.harvestitemcount
                        + " harvestrestplant=" + def.harvestrestplant
                        + " destroyitem=" + def.destroyitem + " destroyitemcount=" + def.destroyitemcount
                        + " destroyrestplant=" + def.destroyrestplant
                        + " assetpath=" + def.assetpath);
            }
            StringBuilder pb = new StringBuilder();
            for (var e : byId.entrySet()) {
                pb.append("\n  id=").append(e.getKey()).append(' ').append(e.getValue());
            }
            System.out.println(TAG + "[ids] " + treeCount + " trees, " + byId.size()
                    + " unique definitions:" + pb);
        }
        // v2.50: nearby NPCS (animals/humans/monsters), for calibrating the radar
        // feature's hostile/animal/human/mount color classification and its
        // getViewDirection()-derived facing angle — both are a first guess from
        // reading the SDK javadoc, not yet confirmed against real gameplay.
        try {
            net.risingworld.api.objects.Npc[] npcs = net.risingworld.api.World.getAllNpcsInRange(
                    player.getPosition(), config.radarRangeM);
            if (npcs != null && npcs.length > 0) {
                StringBuilder nb = new StringBuilder();
                int shown = 0;
                for (var n : npcs) {
                    if (n == null) continue;
                    var def = n.getDefinition();
                    net.risingworld.api.definitions.Npcs.Behaviour behaviour = null;
                    try {
                        behaviour = n.getBehaviour();
                    } catch (Throwable ignored) {
                    }
                    var view = n.getViewDirection();
                    boolean saddled = false;
                    try {
                        var clothes = n.getClothes();
                        saddled = clothes != null
                                && clothes.hasSpecialGear(net.risingworld.api.definitions.Clothing.Function.Saddle);
                    } catch (Throwable ignored) {
                    }
                    nb.append("\n  name='").append(n.getName()).append('\'')
                      .append(" defName=").append(def != null ? def.name : "?")
                      .append(" type=").append(def != null ? def.type : "?")
                      .append(" behaviour=").append(behaviour)
                      .append(" dead=").append(n.isDead())
                      .append(" invisible=").append(n.isInvisible())
                      .append(" saddled=").append(saddled)
                      .append(" viewDir=").append(view);
                    if (++shown >= 15) break;
                }
                System.out.println(TAG + "[ids] " + npcs.length + " npcs within " + config.radarRangeM
                        + "m:" + nb);
            } else {
                System.out.println(TAG + "[ids] no npcs within " + config.radarRangeM + "m.");
            }
        } catch (Throwable t) {
            System.out.println(TAG + "[ids] npc scan failed: " + t.getMessage());
        }
        player.sendTextMessage(TAG + " logged surface + construction + tree + npc ids to server console.");
    }

    @EventMethod(Threading.Sync)
    public void onCraft(PlayerCraftItemEvent event) {
        try {
            Item item = event.getItem();
            String recipeName = null;
            try {
                var recipe = event.getRecipe();
                if (recipe != null) recipeName = recipe.name;
            } catch (Throwable ignored) {
            }
            System.out.println(TAG + "[craft] player=" + event.getPlayer().getName()
                    + " recipe='" + recipeName + "' item='" + (item != null ? item.getName() : "?")
                    + "' typeId=" + (item != null ? item.getTypeID() : (short) -1));
            capabilityService.onItemObtained(event.getPlayer(), item);   // carried calendar object
            capabilityService.onRecipeCrafted(event.getPlayer(), recipeName); // recipe == "calendar"
            recompute(event.getPlayer());
        } catch (Exception ex) {
            System.out.println(TAG + "[craft] error: " + ex.getMessage());
        }
    }

    @EventMethod(Threading.Sync)
    public void onEquipChange(PlayerChangeEquippedItemEvent event) {
        recompute(event.getPlayer());
    }

    @EventMethod(Threading.Sync)
    public void onInventoryAdd(PlayerInventoryAddItemEvent event) {
        Player player = event.getPlayer();
        capabilityService.onItemObtained(player, event.getItem()); // calendar via loot/pickup
        recompute(player);
    }

    /**
     * Request a tier recompute for a player. Deliberately does NO work here: this
     * is called from game event callbacks (equip / inventory / craft), and
     * recomputing touches the HUD. Every crash captured since v2.20 happened at the
     * instant the player switched to the map item — i.e. while the game was opening
     * its own map UI and our equip handler was mutating UI elements in the same
     * breath. All UI work now happens on our own tick instead (v2.23).
     */
    private void recompute(Player player) {
        if (player == null) return;
        pendingSetup.add(player); // tick creates the session if needed
        capsDirty = true;
    }

    /**
     * @return true while the player is holding the vanilla map item (which is what
     *         opens the game's own map screen). Read-only; safe to call per tick.
     */
    private boolean isHoldingVanillaMap(Player player) {
        try {
            Item held = player.getEquippedItem();
            if (held == null) return false;
            if (held.getTypeID() == VANILLA_MAP_TYPE_ID) return true;
            String n = held.getName();
            return n != null && n.equalsIgnoreCase("map");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Parse an on/off argument at parts[2]; if absent or unrecognized, toggle
     *  the current value. Accepts on/off, true/false, 1/0, enable/disable. */
    private static boolean parseOnOff(String[] parts, boolean current) {
        if (parts.length > 2) {
            String a = parts[2].toLowerCase();
            if (a.equals("on") || a.equals("true") || a.equals("1") || a.equals("enable")) return true;
            if (a.equals("off") || a.equals("false") || a.equals("0") || a.equals("disable")) return false;
        }
        return !current;
    }

    /**
     * Detect whether the plugin is re-enabling inside an already-running game (a
     * main-menu world switch) rather than starting fresh.
     *
     * <p>A world switch throws away the plugin classloader, so no static or
     * in-memory flag can survive it — but the OS process is the same. We therefore
     * compare the process start time with the value written on the previous enable:
     * equal means same game process (world switch), different or absent means the
     * game was restarted (fresh start). Falls back to assuming a world switch,
     * because that is the safe direction (it only delays the HUD).
     */
    private boolean detectWorldSwitch() {
        long start;
        try {
            start = ProcessHandle.current().info().startInstant()
                    .map(java.time.Instant::toEpochMilli).orElse(-1L);
        } catch (Throwable t) {
            return true; // can't tell -> be safe
        }
        if (start < 0) return true;
        boolean sameProcess = false;
        Path f = sessionMarkerFile();
        try {
            if (Files.exists(f)) {
                String s = Files.readString(f).trim();
                sameProcess = !s.isEmpty() && Long.parseLong(s) == start;
            }
        } catch (Throwable ignored) {
            // unreadable/corrupt marker -> treat as fresh start, then rewrite it
        }
        try {
            Files.writeString(f, Long.toString(start));
        } catch (Throwable ignored) {
        }
        return sameProcess;
    }

    /** Records the game process's start time, to tell a world switch from a restart. */
    private Path sessionMarkerFile() {
        try {
            String dir = getPath();
            if (dir != null && !dir.isEmpty()) return Paths.get(dir, "session.txt");
        } catch (Throwable ignored) {
        }
        return Paths.get("session.txt");
    }

    /** The diagnostics (crash-diagnosis dev toggles) file in the plugin folder
     *  (falls back to CWD). See {@link #settingsFile()} for the separate,
     *  player-facing settings file — these two used to be one file
     *  ("diagnostics.txt") holding both kinds of value, which read oddly for
     *  the settings half; split in v2.72. */
    private Path diagnosticsFile() {
        try {
            String dir = getPath();
            if (dir != null && !dir.isEmpty()) return Paths.get(dir, "diagnostics.txt");
        } catch (Throwable ignored) {
        }
        return Paths.get("diagnostics.txt");
    }

    /** The legacy, pre-per-player shared settings file (falls back to CWD).
     *  No longer written to - kept only as a one-time migration seed for a
     *  player's first-ever per-player prefs file, see {@link #loadPlayerPrefs}. */
    private Path legacySettingsFile() {
        try {
            String dir = getPath();
            if (dir != null && !dir.isEmpty()) return Paths.get(dir, "settings.txt");
        } catch (Throwable ignored) {
        }
        return Paths.get("settings.txt");
    }

    /** Folder holding one preferences file per player, keyed by their stable
     *  {@code Player.getUID()} (not {@code getID()}, which changes every
     *  reconnect). */
    private Path playerPrefsDir() {
        try {
            String dir = getPath();
            if (dir != null && !dir.isEmpty()) return Paths.get(dir, "players");
        } catch (Throwable ignored) {
        }
        return Paths.get("players");
    }

    private Path playerPrefsFile(String safeUid) {
        return playerPrefsDir().resolve(safeUid + ".txt");
    }

    /** {@code Player.getUID()}, sanitized for use as a filename - the UID format
     *  isn't guaranteed filename-safe on every platform. */
    private static String safeUid(Player player) {
        String uid;
        try {
            uid = player.getUID();
        } catch (Throwable t) {
            uid = null;
        }
        if (uid == null || uid.isEmpty()) uid = "unknown";
        return uid.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Load one player's own preferences. If they have never connected before
     * (no {@code players/<uid>.txt} yet) but the old shared
     * {@link #legacySettingsFile()} exists, seed from it instead of hardcoded
     * defaults - preserving whatever tuning existed before this became
     * per-player, rather than silently resetting it on upgrade. Uses the same
     * key=value parser either way ({@link PlayerPreferences#load}); the legacy
     * file just doesn't have the newer keys (rotate/players/contour/hidden),
     * which fall back to defaults exactly as a brand-new file would.
     */
    private PlayerPreferences loadPlayerPrefs(Player player) {
        Path own = playerPrefsFile(safeUid(player));
        if (Files.exists(own)) return PlayerPreferences.load(own);
        Path legacy = legacySettingsFile();
        if (Files.exists(legacy)) return PlayerPreferences.load(legacy);
        return PlayerPreferences.defaults();
    }

    /** Persist one player's own preferences to their own file. */
    private void savePlayerPrefs(Player player, PlayerPreferences prefs) {
        prefs.save(playerPrefsFile(safeUid(player)));
    }

    /** Load the persisted diagnostic kill-switches so a setting survives a world
     *  switch (the plugin re-enables and rebuilds config from defaults each time). */
    private void loadDiagnostics() {
        try {
            Path f = diagnosticsFile();
            if (f == null || !Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String s = line.trim().toLowerCase();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String key = s.substring(0, eq).trim();
                String val = s.substring(eq + 1).trim();
                boolean on = val.equals("on") || val.equals("true") || val.equals("1");
                switch (key) {
                    case "terrain" -> config.terrainRendering = on;
                    case "mapdb" -> config.mapDbReads = on;
                    case "textures" -> config.useTextures = on;
                    case "hud" -> config.hudEnabled = on;
                    case "mapguard" -> config.mapGuard = on;
                    case "safemode" -> config.worldSwitchSafeMode = on;
                    case "uilite" -> config.uiLite = on;
                    case "minimal" -> config.minimalUi = on;
                    case "teardown" -> {
                        if (val.equals("full") || val.equals("roots") || val.equals("none")) {
                            config.teardownMode = val;
                        }
                    }
                    case "hudgrace" -> {
                        try {
                            config.hudGraceSeconds = Math.max(0f, Float.parseFloat(val));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    default -> { }
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + " could not load diagnostics.txt: " + t.getMessage());
        }
    }

    /** Persist the diagnostic kill-switches. */
    private void saveDiagnostics() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("# PicSoulsMiniMap diagnostic switches (see /mm terrain, /mm mapdb) -"
                    + " crash-diagnosis dev toggles; see settings.txt for player-facing settings.");
            lines.add("terrain=" + (config.terrainRendering ? "on" : "off"));
            lines.add("mapdb=" + (config.mapDbReads ? "on" : "off"));
            lines.add("textures=" + (config.useTextures ? "on" : "off"));
            lines.add("hud=" + (config.hudEnabled ? "on" : "off"));
            lines.add("hudgrace=" + config.hudGraceSeconds);
            lines.add("mapguard=" + (config.mapGuard ? "on" : "off"));
            lines.add("safemode=" + (config.worldSwitchSafeMode ? "on" : "off"));
            lines.add("uilite=" + (config.uiLite ? "on" : "off"));
            lines.add("minimal=" + (config.minimalUi ? "on" : "off"));
            lines.add("teardown=" + config.teardownMode);
            Files.write(diagnosticsFile(), lines);
        } catch (Throwable t) {
            System.out.println(TAG + " could not save diagnostics.txt: " + t.getMessage());
        }
    }

    /** Raw in-game time/date values for /mm status (diagnostics). */
    @SuppressWarnings("deprecation")
    private String timeDebug() {
        try {
            var t = Server.getGameTime();
            return "dayOfYear=" + t.getDay() + "/124  " + t.getHours() + ":"
                    + String.format("%02d", t.getMinutes()) + "  year=" + t.getYear();
        } catch (Throwable e) {
            return "error: " + e.getMessage();
        }
    }
}
