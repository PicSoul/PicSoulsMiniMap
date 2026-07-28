package net.picsoul.rw.minimap.render;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.risingworld.api.World;
import net.risingworld.api.objects.world.Chunk;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Caches rendered chunk tiles keyed by chunk coordinate, with access-order LRU
 * eviction. Tiles are world-global, so a single cache is shared across all
 * players. Unavailable chunks are not cached (so they get retried once loaded).
 *
 * <p>Not thread-safe: accessed only from the plugin's main-thread update loop.
 */
public final class TileCache {

    private static final int MAX_TILES = 2048;

    private final MinimapConfig config;

    private final LinkedHashMap<Long, int[]> tiles =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
                    return size() > MAX_TILES;
                }
            };

    /** Chunks that were asked for but weren't loaded/ready yet, with the time of
     *  the last attempt. Used to avoid re-blocking on the same not-yet-generated
     *  chunk every frame (the open-ocean stall). */
    private final Map<Long, Long> misses = new java.util.HashMap<>();

    /** Chunks whose cached tile is stale (an edit invalidated it) and needs a
     *  rebuild, but whose last-known-good tile is deliberately kept in
     *  {@link #tiles} rather than removed — see {@link #invalidate}. */
    private final Set<Long> dirty = new HashSet<>();

    public TileCache(MinimapConfig config) {
        this.config = config;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Cache-only lookup: returns the tile if already rendered (even if stale
     *  and pending a rebuild — see {@link #isDirty}), else null. Never touches
     *  the world (no chunk load), so it's always cheap. */
    public int[] peek(int cx, int cz) {
        return tiles.get(key(cx, cz));
    }

    /** True if this chunk has a pending rebuild (its tile, if any, is stale). */
    public boolean isDirty(int cx, int cz) {
        return dirty.contains(key(cx, cz));
    }

    /**
     * Returns the rendered tile for a chunk, rendering on demand; null only if
     * the chunk has never been rendered AND isn't loaded yet. A chunk found
     * not-ready is put on a short cooldown so we don't repeatedly call (and
     * block on) {@code World.getChunk} for a chunk the game hasn't generated
     * yet — this is what kept the map from stalling over open ocean.
     *
     * <p>If a rebuild is due (dirty, or never cached) but can't happen right
     * now (still on that chunk's retry cooldown), the last-known-good tile is
     * returned instead of null when one exists, so a chunk that's mid-rebuild
     * keeps showing its last real appearance instead of flashing to the
     * "unexplored" placeholder for a frame or two.
     */
    public int[] get(int cx, int cz) {
        long k = key(cx, cz);
        int[] cached = tiles.get(k);
        if (cached != null && !dirty.contains(k)) return cached;

        long now = System.nanoTime();
        long cooldownNs = (long) (config.chunkRetryCooldown * 1_000_000_000.0);
        Long lastMiss = misses.get(k);
        if (lastMiss != null && (now - lastMiss) < cooldownNs) {
            return cached; // still cooling down -- serve the stale tile if we have one
        }

        Chunk chunk;
        try {
            chunk = World.getChunk(cx, cz);
        } catch (Exception e) {
            misses.put(k, now);
            return cached;
        }
        int[] tile = TileRenderer.render(chunk, config);
        if (tile != null) {
            tiles.put(k, tile);
            dirty.remove(k);
            misses.remove(k);
            return tile;
        }
        misses.put(k, now);
        return cached;
    }

    /**
     * Mark a chunk's tile stale so it gets rebuilt, without discarding the
     * last-known-good tile: the point of an "unexplored"-colored placeholder
     * is for terrain that's never been seen, not terrain whose real look we
     * already know and are just about to refresh. A tree edit (or any terrain
     * edit) invalidates a 3x3 block of chunks at once — with all of them
     * evicted outright, a rebuild pass that can't finish all 9 within its time
     * budget used to show the unfinished ones as blank/dark until a follow-up
     * pass caught up, a visible flash right where the player was just looking.
     * Keeping the stale tile in place means a pending rebuild is invisible
     * until it actually completes.
     */
    public void invalidate(int cx, int cz) {
        long k = key(cx, cz);
        dirty.add(k);
        misses.remove(k); // don't let a stale "not loaded" cooldown block retrying
    }

    public void clear() {
        tiles.clear();
        misses.clear();
        dirty.clear();
    }

    public int size() {
        return tiles.size();
    }

    /** The config this cache renders with (used by MapRenderer for its budget). */
    public MinimapConfig config() {
        return config;
    }
}
