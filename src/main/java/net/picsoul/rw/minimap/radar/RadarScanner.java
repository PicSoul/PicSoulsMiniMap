package net.picsoul.rw.minimap.radar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.risingworld.api.World;
import net.risingworld.api.definitions.Clothing;
import net.risingworld.api.definitions.Npcs;
import net.risingworld.api.objects.Clothes;
import net.risingworld.api.objects.Npc;
import net.risingworld.api.objects.Player;
import net.risingworld.api.utils.Vector3f;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Scans nearby npcs (animals, humans, monsters — see {@link Npcs.Type}) around
 * a point and classifies each into a {@link RadarBlip}.
 *
 * <p>One instance per player, owned by that player's {@code MinimapHud}: unlike
 * the waypoint list (one world-shared table read via {@code WaypointService}),
 * an npc scan is inherently position-dependent, so it can't be shared across
 * players the same way. The actual {@code World.getAllNpcsInRange} query is
 * throttled (see {@link MinimapConfig#radarScanIntervalSeconds}) so a
 * stationary player doesn't re-query it every tick — but {@link #getBlips()}
 * is called every frame and dead-reckons each tracked npc's position forward
 * from its last two scanned samples using their implied velocity, projected to
 * the current instant (see {@link #getBlips()} for why this — not the simple
 * replay-old-motion interpolation used for the player's own high-frequency
 * marker — is what a low-frequency 0.4s sample rate actually needs).
 */
public final class RadarScanner {

    private final MinimapConfig config;
    private final Player viewer;
    private volatile Map<Long, Track> tracks = Collections.emptyMap();
    private long lastScanNs = 0L;

    /** Per-npc interpolation state: the last two scanned positions/times (so
     *  {@link #getBlips()} can extrapolate a smooth in-between position), plus
     *  the rest of the latest scan's classification (color/icon/facing/etc,
     *  which just snaps to the newest value — only position is smoothed). */
    private static final class Track {
        final RadarBlip latest;
        final double prevX, prevZ, curX, curZ;
        final long prevT, curT;

        Track(RadarBlip latest, double prevX, double prevZ, long prevT, double curX, double curZ, long curT) {
            this.latest = latest;
            this.prevX = prevX;
            this.prevZ = prevZ;
            this.prevT = prevT;
            this.curX = curX;
            this.curZ = curZ;
            this.curT = curT;
        }
    }

    public RadarScanner(MinimapConfig config, Player viewer) {
        this.config = config;
        this.viewer = viewer;
    }

    /**
     * The current blips, each dead-reckoned to right now from its last two
     * scanned samples.
     *
     * <p>The player's own marker ({@code MinimapHud.updateSmoothed}) samples a
     * fresh real position every tick and interpolates BETWEEN its previous and
     * current sample, paced to arrive at the current sample right as the next
     * one lands — with a sample every tick that's imperceptible, since the
     * "target" is never more than a tick stale. Reusing that exact approach
     * here (as an earlier version of this method did) instead replays a scan
     * that's up to a full {@code radarScanIntervalSeconds} (0.4s) old, paced
     * against wall-clock time — so on a steadily-moving npc it perpetually
     * lags about 0.4s of travel distance behind reality, visible as a mount
     * trailing behind the player while riding, snapping to line up only once
     * both stop moving (position deltas go to zero and prev==cur again).
     *
     * <p>What a low sample rate actually needs is dead reckoning: derive the
     * npc's velocity from the last two samples, then project its position
     * forward by however long it's actually been since the last sample,
     * rather than replaying the old motion on a delay. This converges on the
     * true position for anything moving roughly in a straight line — which
     * covers a ridden/galloping mount, the case this was built for — at the
     * cost of a small, self-correcting overshoot on a sharp turn (corrected at
     * the next scan). Extrapolation is capped at 1.5x the scan interval so a
     * delayed scan (or the npc stopping abruptly) can't run away indefinitely;
     * beyond that it just holds at the last known position.
     */
    public List<RadarBlip> getBlips() {
        Map<Long, Track> snap = tracks;
        if (snap.isEmpty()) return Collections.emptyList();
        long now = System.nanoTime();
        double maxExtrapolateS = Math.max(0.05f, config.radarScanIntervalSeconds) * 1.5;
        List<RadarBlip> out = new ArrayList<>(snap.size());
        for (Track t : snap.values()) {
            double dtSampleS = (t.curT - t.prevT) / 1_000_000_000.0;
            double vx = 0, vz = 0;
            if (dtSampleS > 1e-4) {
                vx = (t.curX - t.prevX) / dtSampleS;
                vz = (t.curZ - t.prevZ) / dtSampleS;
            }
            double dtNowS = (now - t.curT) / 1_000_000_000.0;
            if (dtNowS < 0) dtNowS = 0;
            else if (dtNowS > maxExtrapolateS) dtNowS = maxExtrapolateS;
            double x = t.curX + vx * dtNowS;
            double z = t.curZ + vz * dtNowS;

            RadarBlip b = t.latest;
            out.add(new RadarBlip(x, z, b.facingDeg(), b.hasFacing(), b.color(),
                    b.iconKey(), b.saddled(), b.hostile(), b.isChild()));
        }
        return out;
    }

    /**
     * Main-thread: rescan at most once per {@code radarScanIntervalSeconds}.
     * {@code visibleZoomCells} is the minimap's current zoom (world cells
     * spanning the visible circle - see {@code MinimapHud.getZoom()}), used
     * to keep the scan radius covering whatever's actually on screen: half of
     * that span, clamped to [{@code radarRangeM}, {@code radarRangeMaxM}].
     * Without this a fixed-radius scan meant npcs only showed up while zoomed
     * in tight enough that the visible circle was smaller than the scan
     * radius - any further out, and real, on-screen npcs simply weren't
     * scanned at all.
     */
    public void maybeScan(Vector3f center, int visibleZoomCells) {
        long now = System.nanoTime();
        long intervalNs = (long) (Math.max(0.1f, config.radarScanIntervalSeconds) * 1_000_000_000.0);
        if (lastScanNs != 0L && (now - lastScanNs) < intervalNs) return;
        lastScanNs = now;
        scanNow(center, effectiveRangeM(visibleZoomCells));
    }

    private float effectiveRangeM(int visibleZoomCells) {
        float visibleRadiusM = visibleZoomCells / 2f;
        float min = Math.max(1f, config.radarRangeM);
        float max = Math.max(min, config.radarRangeMaxM);
        return Math.min(max, Math.max(min, visibleRadiusM));
    }

    private void scanNow(Vector3f center, float rangeM) {
        if (center == null) return;
        try {
            Npc[] npcs = World.getAllNpcsInRange(center, rangeM);
            if (npcs == null || npcs.length == 0) {
                tracks = Collections.emptyMap();
                return;
            }
            // Nearest-first, so capping at radarMaxTracked keeps whichever npcs
            // are actually closest rather than an arbitrary subset.
            Arrays.sort(npcs, (a, b) -> Float.compare(dist2(a, center), dist2(b, center)));
            long now = System.nanoTime();
            Map<Long, Track> old = tracks;
            Map<Long, Track> fresh = new HashMap<>();
            int kept = 0;
            for (Npc npc : npcs) {
                if (kept >= config.radarMaxTracked) break;
                long id;
                try {
                    id = npc.getGlobalID();
                } catch (Throwable t) {
                    continue;
                }
                RadarBlip blip = classify(npc);
                if (blip == null) continue;
                Track prevTrack = old.get(id);
                double px, pz;
                long pt;
                if (prevTrack != null) {
                    px = prevTrack.curX;
                    pz = prevTrack.curZ;
                    pt = prevTrack.curT;
                } else {
                    // First time this npc is seen: start flat (no jump from origin).
                    px = blip.x();
                    pz = blip.z();
                    pt = now;
                }
                fresh.put(id, new Track(blip, px, pz, pt, blip.x(), blip.z(), now));
                kept++;
            }
            tracks = fresh;
        } catch (Throwable t) {
            // Keep the last good snapshot rather than disturbing the render loop.
        }
    }

    private static long safeId(Npc npc) {
        try {
            return npc.getGlobalID();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static float dist2(Npc npc, Vector3f center) {
        try {
            Vector3f p = npc.getPosition();
            float dx = p.x - center.x, dz = p.z - center.z;
            return dx * dx + dz * dz;
        } catch (Throwable t) {
            return Float.MAX_VALUE;
        }
    }

    private RadarBlip classify(Npc npc) {
        try {
            if (npc == null || npc.isDead() || npc.isInvisible()) return null;
            Vector3f pos = npc.getPosition();
            if (pos == null) return null;

            Npcs.NpcDefinition def = null;
            try {
                def = npc.getDefinition();
            } catch (Throwable t) {
                System.out.println("[PicSoulsMiniMap][radar] npc id=" + safeId(npc)
                        + " getDefinition() threw: " + t);
            }
            if (def == null) {
                System.out.println("[PicSoulsMiniMap][radar] npc id=" + safeId(npc)
                        + " getDefinition() returned null");
            } else if (def.name == null || def.name.isBlank()) {
                System.out.println("[PicSoulsMiniMap][radar] npc id=" + safeId(npc)
                        + " def.name is null/blank (def.id=" + def.id + " type=" + def.type + ")");
            }

            // The player's own marker already shows where they are; a mount
            // they're currently riding is redundant clutter directly under it
            // (and was where the pre-dead-reckoning lag was most obvious).
            // getRider() only applies to Mounts (returns null for anything else).
            if (config.radarHideRiddenMount && def != null && def.type == Npcs.Type.Mount) {
                try {
                    if (npc.getRider() == viewer) return null;
                } catch (Throwable ignored) {
                }
            }

            Npcs.Behaviour behaviour = null;
            try {
                behaviour = npc.getBehaviour();
            } catch (Throwable ignored) {
            }
            boolean hostile = behaviour == Npcs.Behaviour.Aggressive
                    || behaviour == Npcs.Behaviour.DefensiveAggressive;
            boolean saddled = def != null && def.type == Npcs.Type.Mount && isSaddled(npc);
            boolean isChild = def != null && def.ischild;
            String iconKey = (def != null && def.name != null && !def.name.isBlank())
                    ? def.name.trim().toLowerCase() : null;

            float facingDeg = 0f;
            boolean hasFacing = false;
            if (config.radarShowFacing) {
                try {
                    Vector3f view = npc.getViewDirection();
                    if (view != null && (Math.abs(view.x) > 1e-4f || Math.abs(view.z) > 1e-4f)) {
                        // Same clockwise-from-north bearing convention as the player
                        // heading used elsewhere (0 = +Z/north, 90 = +X/east).
                        facingDeg = (float) Math.toDegrees(Math.atan2(view.x, view.z));
                        if (facingDeg < 0f) facingDeg += 360f;
                        hasFacing = true;
                    }
                } catch (Throwable ignored) {
                }
            }

            int color = colorFor(behaviour, def, saddled);
            return new RadarBlip(pos.x, pos.z, facingDeg, hasFacing, color, iconKey, saddled, hostile, isChild);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fallback tint color, used only for a species with no custom icon file
     * (see {@code MarkerOverlay.resolveRadarIcon}): an actively
     * {@code Aggressive} npc is the most alarming (hostile red);
     * {@code DefensiveAggressive} (attacks only if provoked) is a step down
     * (caution amber) so the two read as different threat levels at a glance.
     * Otherwise falls back to the npc's {@link Npcs.Type}, with a
     * {@code Mount} further split by saddled state. Not confirmed against real
     * gameplay yet — wants in-game calibration with {@code /mm ids} next to a
     * few different live npcs.
     */
    private int colorFor(Npcs.Behaviour behaviour, Npcs.NpcDefinition def, boolean saddled) {
        if (behaviour == Npcs.Behaviour.Aggressive) return config.radarColorHostile;
        if (behaviour == Npcs.Behaviour.DefensiveAggressive) return config.radarColorCaution;
        if (def != null) {
            if (def.type == Npcs.Type.Animal) return config.radarColorAnimal;
            if (def.type == Npcs.Type.Human) return config.radarColorHuman;
            if (def.type == Npcs.Type.Mount) {
                return saddled ? config.radarColorMountSaddled : config.radarColorMount;
            }
        }
        return config.radarColorDefault;
    }

    /** Whether this npc currently has a saddle equipped — the game tracks tack
     *  (saddle/saddlebag) the same way as clothing, just restricted to what a
     *  mount can wear (see {@code Npc.getClothes()}), with {@link Clothing.Function#Saddle}
     *  as the specific "gear" flag to check. */
    private static boolean isSaddled(Npc npc) {
        try {
            Clothes clothes = npc.getClothes();
            return clothes != null && clothes.hasSpecialGear(Clothing.Function.Saddle);
        } catch (Throwable t) {
            return false;
        }
    }
}
