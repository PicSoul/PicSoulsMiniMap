package net.picsoul.rw.minimap.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;

import javax.imageio.ImageIO;

import net.risingworld.api.Plugin;
import net.risingworld.api.World;
import net.risingworld.api.objects.world.Chunk;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Composites cached chunk tiles into a top-down map image and encodes it to PNG.
 *
 * <p>Performance: the expensive part (compositing + PNG encode) runs on a worker
 * thread so it never stalls the game. Only a quick tile <em>snapshot</em> is taken
 * on the main thread (where world access is safe); the encode reads that immutable
 * snapshot off-thread, and the finished bytes are handed back on the main thread
 * via {@link Plugin#enqueue}.
 */
public final class MapRenderer {

    private static final int UNEXPLORED = 0xFF1B2026;

    /** Callback for a finished render. {@code complete} is false when the region
     *  still has chunks that weren't built this pass (deferred by the time budget
     *  or not yet loaded), so the caller can schedule a fill-in re-render. */
    public interface RenderCallback {
        void done(byte[] png, boolean complete);
    }

    private final TileCache cache;
    private final Plugin plugin;
    private final ExecutorService worker;

    // How often to log a deferred (time-boxed) snapshot, at most.
    private long lastDeferLogNs = 0;

    // --- perf stats ---
    private long renders = 0;
    private double encodeTotalMs = 0, encodeMaxMs = 0;
    private double snapTotalMs = 0, snapMaxMs = 0;

    public MapRenderer(TileCache cache, Plugin plugin, ExecutorService worker) {
        this.cache = cache;
        this.plugin = plugin;
        this.worker = worker;
    }

    /** Lifetime count of actual tile builds performed so far (see
     *  {@link TileCache#lifetimeRenders()}) - lets a caller tell whether any
     *  genuinely new tile data has appeared since a previous check, e.g. to
     *  avoid a pointless re-encode/re-render when nothing has changed. */
    public long lifetimeRenders() {
        return cache.lifetimeRenders();
    }

    /** Immutable grid of tiles covering a render region; safe to read off-thread. */
    private static final class Snapshot {
        final int minCx, minCz, cols;
        final int[][] tiles;

        Snapshot(int minCx, int minCz, int cols, int rows, int[][] tiles) {
            this.minCx = minCx;
            this.minCz = minCz;
            this.cols = cols;
            this.tiles = tiles;
        }

        int[] tile(int cx, int cz) {
            int i = cx - minCx, j = cz - minCz;
            if (i < 0 || j < 0 || i >= cols) return null;
            int idx = j * cols + i;
            return (idx < 0 || idx >= tiles.length) ? null : tiles[idx];
        }
    }

    /** A snapshot plus whether every chunk in the region was available this pass. */
    private record SnapResult(Snapshot snap, boolean complete) {
    }

    /**
     * Gather the tiles needed to render the region (main thread — touches world).
     * Already-cached tiles are filled for free; missing chunks are built
     * nearest-to-center first, under a wall-clock budget, so a burst of new
     * chunks (e.g. boating into fresh ocean) is spread over several renders
     * instead of freezing the plugin thread. Tiles not built this pass are left
     * null (drawn as unexplored) and {@code complete} is false.
     */
    private SnapResult snapshot(int centerX, int centerZ, int cells, boolean contourOn) {
        int half = cells / 2 + 1;
        int minCx = Math.floorDiv(centerX - half, TileRenderer.SIZE);
        int maxCx = Math.floorDiv(centerX + half, TileRenderer.SIZE);
        int minCz = Math.floorDiv(centerZ - half, TileRenderer.SIZE);
        int maxCz = Math.floorDiv(centerZ + half, TileRenderer.SIZE);
        int cols = maxCx - minCx + 1;
        int rows = maxCz - minCz + 1;
        int[][] tiles = new int[cols * rows][];

        int ccx = Math.floorDiv(centerX, TileRenderer.SIZE);
        int ccz = Math.floorDiv(centerZ, TileRenderer.SIZE);

        // Pass 1: fill everything already cached (free); collect the misses.
        // A dirty chunk (edited since its tile was built) still gets its stale
        // tile filled in here so it has SOMETHING to show immediately, but is
        // also queued in pass 2 for a background rebuild — see TileCache.invalidate.
        // Every peek/isDirty/get call is scoped to THIS viewer's own contour
        // preference — see TileCache's per-chunk dual-variant caching.
        java.util.ArrayList<int[]> missing = new java.util.ArrayList<>();
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int cx = minCx + i, cz = minCz + j;
                int[] t = cache.peek(cx, cz, contourOn);
                if (t != null) {
                    tiles[j * cols + i] = t;
                }
                if (t == null || cache.isDirty(cx, cz, contourOn)) {
                    missing.add(new int[]{i, j});
                }
            }
        }

        // Pass 2: build missing chunks, dirty (edited) ones first regardless of
        // distance, then nearest-to-center among the rest, within a budget.
        //
        // v2.85: previously sorted by distance alone, so a dirty chunk (e.g. a
        // tree just got added/removed near your base) competed on equal
        // footing with newly-explored, never-before-seen terrain far away for
        // the same rate-limited budget (see TileCache#maxChunkRendersPerWindow)
        // - at a low enough rate, an edit could sit stale for a long time
        // simply because unrelated exploration kept winning the budget first.
        // A dirty chunk already has a real prior render (this is a refresh,
        // not first-time discovery), so it's cheap to prioritize and matters
        // more for perceived responsiveness than filling in distant unexplored
        // land a little sooner.
        boolean complete = true;
        if (!missing.isEmpty()) {
            missing.sort((a, b) -> {
                boolean aDirty = cache.isDirty(minCx + a[0], minCz + a[1], contourOn);
                boolean bDirty = cache.isDirty(minCx + b[0], minCz + b[1], contourOn);
                if (aDirty != bDirty) return aDirty ? -1 : 1;
                long da = dist2(minCx + a[0], minCz + a[1], ccx, ccz);
                long db = dist2(minCx + b[0], minCz + b[1], ccx, ccz);
                return Long.compare(da, db);
            });
            long budgetNs = (long) (Math.max(0.5f, cfgBudgetMs()) * 1_000_000.0);
            long start = System.nanoTime();
            for (int[] m : missing) {
                if (System.nanoTime() - start > budgetNs) {
                    complete = false; // out of time this frame — fill the rest later
                    break;
                }
                int[] t = cache.get(minCx + m[0], minCz + m[1], contourOn);
                if (t != null) {
                    tiles[m[1] * cols + m[0]] = t;
                } else {
                    complete = false; // chunk not loaded yet — retry after cooldown
                }
            }
        }

        if (!complete) {
            long now = System.nanoTime();
            if (now - lastDeferLogNs > 2_000_000_000L) {
                lastDeferLogNs = now;
                System.out.println("[PicSoulsMiniMap][perf] map region incomplete this pass"
                        + " (chunks still loading — filling in progressively)");
            }
        }
        return new SnapResult(new Snapshot(minCx, minCz, cols, rows, tiles), complete);
    }

    private float cfgBudgetMs() {
        return cache.config().snapshotBudgetMs;
    }

    private static long dist2(int ax, int az, int bx, int bz) {
        long dx = ax - bx, dz = az - bz;
        return dx * dx + dz * dz;
    }

    private byte[] encode(Snapshot snap, int centerX, int centerZ, int cells, int outPx) {
        BufferedImage img = new BufferedImage(outPx, outPx, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[outPx];
        for (int py = 0; py < outPx; py++) {
            // Screen up = north (+Z at top), so the map is north-up.
            int cellZ = centerZ - Math.round(((py + 0.5f) / outPx - 0.5f) * cells);
            for (int px = 0; px < outPx; px++) {
                int cellX = centerX + Math.round(((px + 0.5f) / outPx - 0.5f) * cells);
                int cx = Math.floorDiv(cellX, TileRenderer.SIZE);
                int cz = Math.floorDiv(cellZ, TileRenderer.SIZE);
                int[] tile = snap.tile(cx, cz);
                row[px] = (tile == null) ? UNEXPLORED
                        : tile[Math.floorMod(cellZ, TileRenderer.SIZE) * TileRenderer.SIZE
                               + Math.floorMod(cellX, TileRenderer.SIZE)];
            }
            img.setRGB(0, py, outPx, 1, row, 0, outPx);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(1024, outPx * outPx / 3));
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.out.println("[PicSoulsMiniMap] map encode failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Render a map centered on (centerX, centerZ). The snapshot is taken now (main
     * thread); the encode runs off-thread; {@code onDone} is invoked on the main
     * thread with the PNG bytes (or null on failure).
     *
     * @param contourOn this viewer's own contour preference ({@code PlayerPreferences
     *        #contourEnabled}) - selects which of TileCache's two cached variants
     *        of each chunk this render uses.
     */
    public void renderAsync(int centerX, int centerZ, int cells, int outPx, boolean contourOn,
                             RenderCallback onDone) {
        long t0 = System.nanoTime();
        SnapResult result = snapshot(centerX, centerZ, cells, contourOn);
        recordSnap((System.nanoTime() - t0) / 1_000_000.0);

        final Snapshot snap = result.snap();
        final boolean complete = result.complete();
        worker.submit(() -> {
            long e0 = System.nanoTime();
            byte[] png = encode(snap, centerX, centerZ, cells, outPx);
            recordEncode((System.nanoTime() - e0) / 1_000_000.0);
            plugin.enqueue(() -> onDone.done(png, complete));
        });
    }

    /**
     * Gather cave-mode tiles for the region (main thread — touches world). Unlike
     * {@link #snapshot}, this never touches {@link TileCache}: a cave tile depends
     * on the player's current Y as well as (x,z), and the shared surface cache is
     * keyed by (x,z) only — running cave tiles through it would either corrupt the
     * surface cache's key space or thrash it every time the player's altitude
     * changes. Cave mode's view is small (a tight zoom, a handful of chunks), so
     * rendering fresh every time is cheap enough not to need a cache of its own.
     */
    private SnapResult snapshotCave(int centerX, int centerY, int centerZ, int cells) {
        int half = cells / 2 + 1;
        int minCx = Math.floorDiv(centerX - half, TileRenderer.SIZE);
        int maxCx = Math.floorDiv(centerX + half, TileRenderer.SIZE);
        int minCz = Math.floorDiv(centerZ - half, TileRenderer.SIZE);
        int maxCz = Math.floorDiv(centerZ + half, TileRenderer.SIZE);
        int cols = maxCx - minCx + 1;
        int rows = maxCz - minCz + 1;
        int[][] tiles = new int[cols * rows][];
        MinimapConfig cfg = cache.config();

        boolean complete = true;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int[] t = null;
                try {
                    Chunk chunk = World.getChunk(minCx + i, minCz + j);
                    if (chunk != null) {
                        t = TileRenderer.renderCave(chunk, cfg, centerY);
                    }
                } catch (Exception ignored) {
                }
                tiles[j * cols + i] = t;
                if (t == null) complete = false;
            }
        }
        return new SnapResult(new Snapshot(minCx, minCz, cols, rows, tiles), complete);
    }

    /**
     * Render a cave-mode map centered on (centerX, centerZ) at altitude centerY.
     * Same async shape as {@link #renderAsync} (snapshot on the main thread,
     * encode off-thread, callback on the main thread), but reads real voxel
     * terrain around the player's current level instead of the surface heightmap.
     */
    public void renderCaveAsync(int centerX, int centerY, int centerZ, int cells, int outPx, RenderCallback onDone) {
        long t0 = System.nanoTime();
        SnapResult result = snapshotCave(centerX, centerY, centerZ, cells);
        recordSnap((System.nanoTime() - t0) / 1_000_000.0);

        final Snapshot snap = result.snap();
        final boolean complete = result.complete();
        worker.submit(() -> {
            long e0 = System.nanoTime();
            byte[] png = encode(snap, centerX, centerZ, cells, outPx);
            recordEncode((System.nanoTime() - e0) / 1_000_000.0);
            plugin.enqueue(() -> onDone.done(png, complete));
        });
    }

    // interval (since-last-report) accumulators for continuous logging
    private long intRenders = 0;
    private double intSnapTotal = 0, intSnapMax = 0;
    private double intEncTotal = 0, intEncMax = 0;
    /** Main-thread snapshot cost above this (ms) is logged immediately as a spike. */
    private static final double SPIKE_MS = 3.0;

    private synchronized void recordEncode(double ms) {
        renders++;
        encodeTotalMs += ms;
        if (ms > encodeMaxMs) encodeMaxMs = ms;
        intRenders++;
        intEncTotal += ms;
        if (ms > intEncMax) intEncMax = ms;
    }

    private void recordSnap(double ms) {
        synchronized (this) {
            snapTotalMs += ms;
            if (ms > snapMaxMs) snapMaxMs = ms;
            intSnapTotal += ms;
            if (ms > intSnapMax) intSnapMax = ms;
        }
        if (ms > SPIKE_MS) {
            System.out.println(String.format(
                    "[PicSoulsMiniMap][perf] SPIKE main-thread snapshot %.1fms (tiles built this frame)", ms));
        }
    }

    public synchronized String statsLine() {
        double avgEnc = renders == 0 ? 0 : encodeTotalMs / renders;
        double avgSnap = renders == 0 ? 0 : snapTotalMs / renders;
        return String.format(
                "renders=%d, main-thread snapshot avg=%.2fms max=%.2fms, off-thread encode avg=%.1fms max=%.1fms",
                renders, avgSnap, snapMaxMs, avgEnc, encodeMaxMs);
    }

    /** Returns stats for the period since the last call, then resets that window.
     *  Used for continuous time-series logging. */
    public synchronized String intervalStatsAndReset() {
        double avgSnap = intRenders == 0 ? 0 : intSnapTotal / intRenders;
        double avgEnc = intRenders == 0 ? 0 : intEncTotal / intRenders;
        String s = String.format(
                "last window: renders=%d, main-thread snapshot avg=%.2fms max=%.2fms, off-thread encode avg=%.1fms max=%.1fms",
                intRenders, avgSnap, intSnapMax, avgEnc, intEncMax);
        intRenders = 0;
        intSnapTotal = 0;
        intSnapMax = 0;
        intEncTotal = 0;
        intEncMax = 0;
        return s;
    }
}
