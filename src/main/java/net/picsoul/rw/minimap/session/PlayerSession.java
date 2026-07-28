package net.picsoul.rw.minimap.session;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.database.WorldDatabase;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Vehicle;
import net.risingworld.api.utils.Vector3f;

import net.picsoul.rw.minimap.capability.Capabilities;
import net.picsoul.rw.minimap.capability.CapabilityService;
import net.picsoul.rw.minimap.config.MinimapConfig;
import net.picsoul.rw.minimap.render.MapRenderer;
import net.picsoul.rw.minimap.ui.MinimapHud;
import net.picsoul.rw.minimap.ui.SettingsPanel;
import net.picsoul.rw.minimap.waypoint.WaypointService;

/**
 * Per-player state: the HUD, its visibility, the tracked spawn point, and the
 * player's minimap tiers (which upgrade with equipped/owned items).
 */
public class PlayerSession {

    private static final String TAG = "[PicSoulsMiniMap]";

    private final Plugin plugin;
    private final Player player;
    private final MinimapConfig config;
    private final MinimapHud hud;
    private final CapabilityService capabilities;

    private boolean visible = false;
    /** The settings window (lazily created). */
    private SettingsPanel settingsPanel;
    /** Zoom-key rebind capture: 0 = none, 1 = zoom-in, 2 = zoom-out. */
    private int captureWhich = 0;
    /** False during the post-world-switch grace window; no UI may be attached. */
    private boolean hudAllowed = true;
    /** True while the player is holding the vanilla map, so our HUD stands down and
     *  never coexists with the game's own map UI (see mapGuard). */
    private boolean hudSuppressed = false;
    private Capabilities caps = new Capabilities(false, false, false, false, false);

    /** Raw {@code Player.isInCave()} reading and when it last changed, and the
     *  debounced value actually applied to the HUD — so standing right at a
     *  cave mouth doesn't flicker the map between surface and cave view. */
    private boolean caveRaw = false;
    private boolean caveActive = false;
    private long caveRawChangedAtNs = System.nanoTime();

    /** The player's current spawn point in world space. Initialized from the save
     *  and kept live by the spawn events (see {@link #setCurrentSpawn}). */
    private Vector3f currentSpawn;

    public PlayerSession(Plugin plugin, Player player, MinimapConfig config, MapRenderer renderer,
                         WaypointService waypoints, CapabilityService capabilities) {
        this.plugin = plugin;
        this.player = player;
        this.config = config;
        this.capabilities = capabilities;
        this.hud = new MinimapHud(plugin, player, config, renderer, waypoints);
        this.currentSpawn = resolveInitialSpawn();
        hud.setSpawn(currentSpawn);
    }

    /**
     * Recompute the player's tiers from their equipped/owned items and apply them:
     * the basic minimap is shown only while the map is equipped; the compass, watch
     * and calendar tiers toggle their features. Called on connect/spawn and whenever
     * equipment or inventory changes.
     */
    public void recomputeCapabilities() {
        caps = capabilities.compute(player);
        if (caps.map() && !visible) {
            showMinimap();
        } else if (!caps.map() && visible) {
            hideMinimap();
        }
        hud.setTiers(caps.compass(), caps.watch(), caps.calendar(), caps.radar());
    }

    public Capabilities getCapabilities() {
        return caps;
    }

    /** Enable/disable terrain rendering (used for the world-load grace window). */
    public void setRenderingEnabled(boolean enabled) {
        hud.setRenderingEnabled(enabled);
    }

    /**
     * Allow/forbid attaching the HUD to the screen. Held false during the
     * post-world-switch grace window: attaching UI elements while a second world
     * is still loading crashes the game natively (see
     * {@link MinimapConfig#hudGraceSeconds}). When it flips true we re-evaluate,
     * so the minimap appears as soon as it is safe.
     */
    /**
     * Take the HUD off screen while the player holds the vanilla map, and restore
     * it afterwards. Every captured crash since v2.20 happened at the moment the
     * player switched to the map item in a second world, and the run with the HUD
     * disabled entirely was the only stable one — so our UI must not be attached
     * while the game opens its own map. Driven from the tick, never from an event.
     */
    public void setHudSuppressed(boolean suppressed) {
        if (this.hudSuppressed == suppressed) return;
        this.hudSuppressed = suppressed;
        if (suppressed) {
            hideMinimap();
        } else {
            recomputeCapabilities();
        }
    }

    public void setHudAllowed(boolean allowed) {
        if (this.hudAllowed == allowed) return;
        this.hudAllowed = allowed;
        if (allowed) {
            recomputeCapabilities();
        } else {
            hideMinimap();
        }
    }

    /** Update the tracked spawn (called from the spawn/respawn events). */
    public void setCurrentSpawn(Vector3f pos) {
        if (pos == null) return;
        currentSpawn = pos;
        hud.setSpawn(currentSpawn);
    }

    /**
     * Resolve the player's active spawn from the world save on session start.
     * The {@code player.lastspawn} column names the active spawn type
     * (0=world/default, 1=primary, 2=secondary, 3=tertiary); we decode that
     * type's position blob (byte type + 3 little-endian floats x,y,z). Falls
     * back to the world default spawn. Read through the game's own Players DB
     * connection ({@code getWorldDatabase}), so no file locking is involved.
     */
    private Vector3f resolveInitialSpawn() {
        if (config.mapDbReads) try {
            WorldDatabase db = plugin.getWorldDatabase(WorldDatabase.Target.Players);
            int dbid = player.getDbID();
            try (ResultSet rs = db.executeQuery(
                    "SELECT lastspawn, primaryspawn, secondaryspawn, tertiaryspawn"
                    + " FROM player WHERE id=" + dbid)) {
                if (rs.next()) {
                    int last = rs.getInt("lastspawn");
                    byte[] blob = switch (last) {
                        case 1 -> rs.getBytes("primaryspawn");
                        case 2 -> rs.getBytes("secondaryspawn");
                        case 3 -> rs.getBytes("tertiaryspawn");
                        default -> null;
                    };
                    Vector3f p = decodeSpawnBlob(blob);
                    if (p != null) return p;
                }
            }
        } catch (Exception e) {
            System.out.println(TAG + "[spawn] initial read failed: " + e.getMessage());
        }
        try {
            Vector3f w = Server.getDefaultSpawnPosition();
            if (w != null) return w;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Decode a spawn blob: 1 type byte then little-endian floats x, y, z. */
    private static Vector3f decodeSpawnBlob(byte[] b) {
        if (b == null || b.length < 13) return null;
        try {
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            bb.get(); // spawn type byte
            float x = bb.getFloat();
            float y = bb.getFloat();
            float z = bb.getFloat();
            return new Vector3f(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    public void showMinimap() {
        if (visible) return;
        if (!config.hudEnabled) return; // diagnostic: attach no UI elements at all (/mm hud off)
        if (!hudAllowed) return;        // world-switch grace: attaching now would crash the game
        if (hudSuppressed) return;      // vanilla map is open: stay off screen (mapGuard)
        hud.attach();
        visible = true;
        hud.setTiers(caps.compass(), caps.watch(), caps.calendar(), caps.radar());
        Vector3f p = trackingPosition();
        hud.updateView(p.x, p.z);
    }

    /**
     * The world position the map should follow. In a vehicle we use the vehicle's
     * position, because the player's own {@code getPosition()} only re-syncs on
     * input changes while seated (so it appears frozen when boating in a straight
     * line, and jumps when you steer). On foot / mounted the player position is
     * live and correct.
     */
    private Vector3f trackingPosition() {
        try {
            if (player.isInVehicle()) {
                Vehicle v = player.getVehicle();
                if (v != null) {
                    Vector3f vp = v.getPosition();
                    if (vp != null) return vp;
                }
            }
        } catch (Throwable ignored) {
            // fall through to player position
        }
        return player.getPosition();
    }

    public void hideMinimap() {
        if (!visible) return;
        hud.detach();
        visible = false;
    }

    /** @return the new visibility state. */
    public boolean toggleMinimap() {
        if (visible) {
            hideMinimap();
        } else {
            showMinimap();
        }
        return visible;
    }

    public boolean isVisible() {
        return visible;
    }

    /** Per-tick update: refresh the readout, follow the player smoothly,
     *  (debounced) switch the HUD in/out of cave mode, and perform a debounced
     *  HUD rebuild if one was requested (see {@link #requestRebuild()}). */
    public void tick() {
        applyPendingRebuild();
        if (!visible) return;
        if (config.caveModeEnabled) {
            updateCaveMode();
        }
        Vector3f p = trackingPosition();
        hud.updateInfo(p);
        hud.updateView(p.x, p.z);
    }

    private long lastRebuildNs = 0L;
    private boolean rebuildPending = false;

    /**
     * Request a debounced HUD rebuild instead of calling {@link #rebuildHud()}
     * directly — see {@link MinimapConfig#hudRebuildCooldownSeconds} for why
     * this exists (short version: rapid-fire repeated rebuilds, e.g. from
     * clicking across the settings panel's map-size slider, could crash the
     * game). Repeated requests just keep the config value (and the settings
     * panel's own slider visuals) updating immediately and cheaply; the
     * actual expensive rebuild is coalesced to at most once per cooldown
     * window, applied from {@link #tick()}.
     */
    public void requestRebuild() {
        rebuildPending = true;
    }

    private void applyPendingRebuild() {
        if (!rebuildPending) return;
        long now = System.nanoTime();
        long cooldownNs = (long) (Math.max(0f, config.hudRebuildCooldownSeconds) * 1_000_000_000.0);
        if (now - lastRebuildNs < cooldownNs) return;
        rebuildPending = false;
        lastRebuildNs = now;
        rebuildHud();
    }

    /** Debounce {@code Player.isInCave()} before applying it to the HUD: the raw
     *  reading must hold steady for {@code caveModeDelaySeconds} before the mode
     *  actually switches, so a moment spent right at a cave mouth doesn't flicker
     *  the map back and forth. */
    private void updateCaveMode() {
        boolean nowInCave;
        try {
            nowInCave = player.isInCave();
        } catch (Throwable ignored) {
            return;
        }
        long now = System.nanoTime();
        if (nowInCave != caveRaw) {
            caveRaw = nowInCave;
            caveRawChangedAtNs = now;
        }
        if (caveRaw != caveActive) {
            double elapsed = (now - caveRawChangedAtNs) / 1_000_000_000.0;
            if (elapsed >= config.caveModeDelaySeconds) {
                caveActive = caveRaw;
                hud.setCaveMode(caveActive);
            }
        }
    }

    /** Force a map texture re-render on the next tick (e.g. after a config change). */
    public void invalidateMap() {
        hud.invalidate();
    }

    /**
     * Tear the HUD down and rebuild it from the current config, without needing to
     * rejoin the world. Used by {@code /mm diagreset}, the structural switches
     * ({@code minimal}, {@code uilite}, {@code notex}), and the settings panel's
     * map-size/corner controls — anything whose change only takes effect when the
     * element tree is rebuilt.
     *
     * <p>Deliberately calls {@link MinimapHud#rebuildElements()}, NOT
     * {@link MinimapHud#dispose()}: {@code dispose()} only fully tears down the
     * element tree when {@code config.teardownMode == "full"}, and defaults to
     * {@code "none"} (a deliberate world-switch-crash workaround — see
     * {@code MinimapHud.dispose()}'s own doc) which leaves {@code built} stuck
     * true, so a fresh size/corner in config would silently never take visual
     * effect. This is a live, in-session rebuild — not plugin unload — so
     * there's no crash risk to protect against, and a real purge is what
     * "rebuild" actually needs to mean here.
     */
    public void rebuildHud() {
        boolean wasVisible = visible;
        hideMinimap();
        hud.rebuildElements();
        if (wasVisible) {
            recomputeCapabilities();
        }
    }

    public void destroy() {
        if ("none".equalsIgnoreCase(config.teardownMode)) {
            // Leave every UI element registered; the game resets them itself.
            visible = false;
            hud.disposeTexturesOnly();
            return;
        }
        hideMinimap();
        hud.dispose(); // free native textures so no asset handles leak into the next world
    }

    public Player getPlayer() {
        return player;
    }

    public MinimapHud getHud() {
        return hud;
    }

    /** The settings window for this player (created on first use). */
    public SettingsPanel getSettingsPanel() {
        if (settingsPanel == null) {
            settingsPanel = new SettingsPanel(player, config);
        }
        return settingsPanel;
    }

    public int getCaptureWhich() {
        return captureWhich;
    }

    public void setCaptureWhich(int which) {
        this.captureWhich = which;
    }
}
