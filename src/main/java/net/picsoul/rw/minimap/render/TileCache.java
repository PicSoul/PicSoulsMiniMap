package net.picsoul.rw.minimap.render;

import java.util.LinkedHashMap;
import java.util.Map;

import net.risingworld.api.World;
import net.risingworld.api.objects.world.Chunk;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Caches rendered chunk tiles keyed by chunk coordinate, with access-order LRU
 * eviction. Tiles are world-global, so a single cache is shared across all
 * players. Unavailable chunks are not cached (so they get retried once loaded).
 *
 * <p>Contour lines are a per-player preference (see
 * {@code PlayerPreferences#contourEnabled}), but everything else about a
 * chunk's render is shared/global — so each chunk entry holds up to TWO
 * rendered variants (contour-on / contour-off), and a viewer's own preference
 * picks which one they get. Only the variant(s) actually requested by some
 * connected viewer are ever rendered; if every player shares one preference,
 * the other variant is simply never built, so this costs nothing extra in the
 * common case. See {@link Variants}.
 *
 * <p>Not thread-safe: accessed only from the plugin's main-thread update loop.
 */
public final class TileCache {

    private static final int MAX_TILES = 2048;

    private final MinimapConfig config;

    /** The two possible rendered variants of one chunk's tile, each with its own
     *  independent dirty flag (see {@link #invalidate}) - an edit marks BOTH
     *  variants stale, but each is only actually rebuilt (and its own dirty
     *  flag cleared) once some viewer with that specific preference requests
     *  this chunk again. Either array may be null if that variant hasn't been
     *  requested/rendered yet. */
    private static final class Variants {
        int[] contourOn;
        int[] contourOff;
        boolean dirtyOn;
        boolean dirtyOff;

        int[] get(boolean contourOn) {
            return contourOn ? this.contourOn : this.contourOff;
        }

        void set(boolean contourOn, int[] tile) {
            if (contourOn) this.contourOn = tile; else this.contourOff = tile;
        }

        boolean isDirty(boolean contourOn) {
            return contourOn ? dirtyOn : dirtyOff;
        }

        void markBothDirty() {
            dirtyOn = true;
            dirtyOff = true;
        }

        void clearDirty(boolean contourOn) {
            if (contourOn) dirtyOn = false; else dirtyOff = false;
        }
    }

    private final LinkedHashMap<Long, Variants> tiles =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Variants> eldest) {
                    return size() > MAX_TILES;
                }
            };

    /** Chunks that were asked for but weren't loaded/ready yet, with the time of
     *  the last attempt. Used to avoid re-blocking on the same not-yet-generated
     *  chunk every frame (the open-ocean stall). Chunk-level (not per-variant):
     *  "not loaded" is a fact about the chunk, independent of contour. */
    private final Map<Long, Long> misses = new java.util.HashMap<>();

    /** Fixed-window rate limiter for real {@code World.getChunk}/{@code Chunk}
     *  calls - see {@link MinimapConfig#maxChunkRendersPerWindow}. */
    private long rateWindowStartNs = System.nanoTime();
    private int rendersThisWindow = 0;

    private boolean allowRealChunkRender(long nowNs) {
        long windowNs = (long) (Math.max(1f, config.chunkRenderRateWindowSeconds) * 1_000_000_000.0);
        if (nowNs - rateWindowStartNs > windowNs) {
            rateWindowStartNs = nowNs;
            rendersThisWindow = 0;
        }
        return rendersThisWindow < Math.max(1, config.maxChunkRendersPerWindow);
    }

    /** Diagnostic (v2.76): lifetime count of actual tile renders performed this
     *  session (never reset, unlike the interval-based /mm perf stats) - added
     *  while investigating a native, uncatchable crash (no exception, no crash
     *  dump) that reproduces reliably with the plugin enabled and not at all
     *  with it disabled, across very different play styles (fast flying, slow
     *  walking, standing still gardening), with crash timing clustering around
     *  8-12 minutes into the session regardless of activity - suggesting a
     *  cumulative resource (most likely how many distinct chunks have been
     *  rendered, since {@code TileRenderer.render} does a bulk voxel read per
     *  chunk for cave detection) rather than instantaneous movement speed.
     *  Logged periodically from PicSoulsMiniMap.tick() so a crash's last log
     *  line shows a concrete number to correlate across sessions. */
    private long lifetimeRenders = 0;

    public long lifetimeRenders() {
        return lifetimeRenders;
    }

    public TileCache(MinimapConfig config) {
        this.config = config;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Cache-only lookup: returns the requested variant's tile if already
     *  rendered (even if stale and pending a rebuild — see {@link #isDirty}),
     *  else null. Never touches the world (no chunk load), so it's always cheap. */
    public int[] peek(int cx, int cz, boolean contourOn) {
        Variants v = tiles.get(key(cx, cz));
        return v == null ? null : v.get(contourOn);
    }

    /** True if this chunk's requested variant has a pending rebuild (its tile,
     *  if any, is stale). */
    public boolean isDirty(int cx, int cz, boolean contourOn) {
        Variants v = tiles.get(key(cx, cz));
        return v != null && v.isDirty(contourOn);
    }

    /**
     * Returns the rendered tile for a chunk's requested contour variant,
     * rendering on demand; null only if that variant has never been rendered
     * AND the chunk isn't loaded yet. A chunk found not-ready is put on a short
     * cooldown so we don't repeatedly call (and block on) {@code World.getChunk}
     * for a chunk the game hasn't generated yet — this is what kept the map
     * from stalling over open ocean.
     *
     * <p>If a rebuild is due (dirty, or never cached) but can't happen right
     * now (still on that chunk's retry cooldown), the last-known-good tile is
     * returned instead of null when one exists, so a chunk that's mid-rebuild
     * keeps showing its last real appearance instead of flashing to the
     * "unexplored" placeholder for a frame or two.
     */
    public int[] get(int cx, int cz, boolean contourOn) {
        long k = key(cx, cz);
        Variants v = tiles.get(k);
        int[] cached = v == null ? null : v.get(contourOn);
        if (cached != null && !v.isDirty(contourOn)) return cached;

        long now = System.nanoTime();
        long cooldownNs = (long) (config.chunkRetryCooldown * 1_000_000_000.0);
        Long lastMiss = misses.get(k);
        if (lastMiss != null && (now - lastMiss) < cooldownNs) {
            return cached; // still cooling down -- serve the stale tile if we have one
        }

        int[] tile;
        if (config.diagFakeChunkData) {
            // Diagnostic (v2.81): synthesize a same-size tile with zero
            // World/Chunk API calls, while leaving everything downstream (this
            // cache's real growth/eviction, MapRenderer's real async encode,
            // MinimapHud's real full-size TextureAsset creation) exercised
            // exactly as normal - see MinimapConfig#diagFakeChunkData.
            tile = fakeTile(cx, cz);
        } else {
            if (!allowRealChunkRender(now)) {
                // Rate-limited (see MinimapConfig#maxChunkRendersPerWindow) -
                // treat like a "not ready" miss so this retries once the
                // window has room again, rather than blocking outright.
                misses.put(k, now);
                return cached;
            }
            Chunk chunk;
            try {
                chunk = World.getChunk(cx, cz);
            } catch (Exception e) {
                misses.put(k, now);
                return cached;
            }
            tile = TileRenderer.render(chunk, config, contourOn);
            if (tile != null) rendersThisWindow++;
        }
        if (tile != null) {
            lifetimeRenders++;
            if (v == null) {
                v = new Variants();
                tiles.put(k, v);
            }
            v.set(contourOn, tile);
            v.clearDirty(contourOn);
            misses.remove(k);
            return tile;
        }
        misses.put(k, now);
        return cached;
    }

    /** Diagnostic (v2.81): a same-size ({@code TileRenderer.SIZE}^2) synthetic
     *  tile, deterministic per chunk coordinate but touching no World/Chunk
     *  API at all - see {@link MinimapConfig#diagFakeChunkData}. */
    private static int[] fakeTile(int cx, int cz) {
        int size = TileRenderer.SIZE;
        int[] tile = new int[size * size];
        java.util.Arrays.fill(tile, 0xFF3A6B35); // fixed solid color - content is irrelevant to this test
        return tile;
    }

    /**
     * Mark both of a chunk's tile variants stale so they get rebuilt, without
     * discarding either last-known-good tile: the point of an "unexplored"-
     * colored placeholder is for terrain that's never been seen, not terrain
     * whose real look we already know and are just about to refresh. A tree
     * edit (or any terrain edit) invalidates a 3x3 block of chunks at once —
     * with all of them evicted outright, a rebuild pass that can't finish all 9
     * within its time budget used to show the unfinished ones as blank/dark
     * until a follow-up pass caught up, a visible flash right where the player
     * was just looking. Keeping the stale tile in place means a pending
     * rebuild is invisible until it actually completes. An edit affects the
     * underlying terrain regardless of contour, so both variants are marked -
     * each is independently rebuilt (and its own dirty flag cleared) only once
     * some viewer with that specific preference requests this chunk again.
     */
    public void invalidate(int cx, int cz) {
        long k = key(cx, cz);
        Variants v = tiles.get(k);
        if (v != null) v.markBothDirty();
        misses.remove(k); // don't let a stale "not loaded" cooldown block retrying
    }

    public void clear() {
        tiles.clear();
        misses.clear();
    }

    public int size() {
        return tiles.size();
    }

    /** The config this cache renders with (used by MapRenderer for its budget). */
    public MinimapConfig config() {
        return config;
    }
}
