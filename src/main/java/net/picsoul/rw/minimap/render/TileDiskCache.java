package net.picsoul.rw.minimap.render;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import net.risingworld.api.World;

/**
 * Persists rendered chunk tiles to disk, per world, so a previously-rendered
 * chunk never needs a second native {@code World}/{@code Chunk} read - only
 * genuinely new terrain, or a chunk an edit has invalidated, still goes
 * through {@link TileCache}'s normal rate-limited real-render path. Added
 * (v2.88) after a long investigation (v2.76-v2.87) traced a recurring native
 * crash to sustained calls into that API: rate-limiting could only ever space
 * those calls out, never reduce how many total calls a long session makes.
 * Most play revisits already-seen terrain far more than it discovers new
 * terrain, so eliminating the read entirely for anything already on disk cuts
 * the real call volume far more effectively than any rate limit could.
 *
 * <p>One small binary file per chunk per contour variant ({@code int[1024]}
 * raw, 4096 bytes - no compression needed at this size), mirroring {@code
 * TileCache.Variants}' own contour-on/contour-off split so only the variant(s)
 * actually rendered ever get written. Stored under a format-version folder so
 * a future rendering change can just bump {@link #FORMAT_VERSION} and orphan
 * the old cache automatically, rather than silently showing stale colors
 * forever.
 *
 * <p>Every operation is defensive: any I/O failure (missing file, wrong size,
 * permission error, world not ready yet) is treated as a cache miss and never
 * thrown - this cache is purely an optimization, never a correctness
 * requirement for rendering.
 */
public final class TileDiskCache {

    private static final int FORMAT_VERSION = 1;
    private static final int TILE_INTS = TileRenderer.SIZE * TileRenderer.SIZE;
    private static final int TILE_BYTES = TILE_INTS * 4;

    private final String pluginFolder;

    /** Resolved lazily and cached once found - {@code World.getWorldFolder()}
     *  isn't necessarily ready the instant the plugin enables, mirroring the
     *  same "not ready yet, retry next call" pattern already used by {@code
     *  WaypointService.connection()}. */
    private Path worldDir;

    public TileDiskCache(String pluginFolder) {
        this.pluginFolder = pluginFolder;
    }

    private Path worldDir() {
        if (worldDir != null) return worldDir;
        try {
            File wf = World.getWorldFolder();
            if (wf == null) return null; // world not ready yet; try again next call
            String safeName = wf.getName().replaceAll("[^A-Za-z0-9_-]", "_");
            String base = (pluginFolder != null && !pluginFolder.isEmpty()) ? pluginFolder : ".";
            worldDir = Paths.get(base, "tilecache", "v" + FORMAT_VERSION, safeName);
        } catch (Throwable ignored) {
            return null;
        }
        return worldDir;
    }

    private Path fileFor(int cx, int cz, boolean contourOn) {
        Path dir = worldDir();
        if (dir == null) return null;
        return dir.resolve(cx + "_" + cz + (contourOn ? "_on.tile" : "_off.tile"));
    }

    /** @return the cached tile, or null if not present/corrupt/unavailable. */
    public int[] load(int cx, int cz, boolean contourOn) {
        try {
            Path f = fileFor(cx, cz, contourOn);
            if (f == null || !Files.exists(f)) return null;
            byte[] raw = Files.readAllBytes(f);
            if (raw.length != TILE_BYTES) return null;
            int[] tile = new int[TILE_INTS];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(tile);
            return tile;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Persists a freshly-rendered tile. Silently does nothing on failure. */
    public void save(int cx, int cz, boolean contourOn, int[] tile) {
        try {
            if (tile == null || tile.length != TILE_INTS) return;
            Path f = fileFor(cx, cz, contourOn);
            if (f == null) return;
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            ByteBuffer bb = ByteBuffer.allocate(TILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bb.asIntBuffer().put(tile);
            Files.write(f, bb.array());
        } catch (Throwable ignored) {
        }
    }

    /** Removes both variant files for a chunk - called when a real edit
     *  invalidates it, so a stale on-disk render is never reloaded instead of
     *  a fresh one. */
    public void delete(int cx, int cz) {
        try {
            Path on = fileFor(cx, cz, true);
            Path off = fileFor(cx, cz, false);
            if (on != null) Files.deleteIfExists(on);
            if (off != null) Files.deleteIfExists(off);
        } catch (Throwable ignored) {
        }
    }

    /** Deletes the entire on-disk cache for the current world - the manual
     *  release valve behind {@code /mm tilecache clear}. */
    public void clear() {
        try {
            Path dir = worldDir();
            if (dir == null || !Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
