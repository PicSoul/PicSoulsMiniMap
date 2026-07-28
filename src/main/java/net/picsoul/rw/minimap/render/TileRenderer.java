package net.picsoul.rw.minimap.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.risingworld.api.World;
import net.risingworld.api.definitions.Plants;
import net.risingworld.api.objects.world.Chunk;
import net.risingworld.api.objects.world.ChunkPart;
import net.risingworld.api.objects.world.Plant;
import net.risingworld.api.utils.Utils;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Renders one Rising World chunk into a 32x32 ARGB tile (top-down surface view):
 * material color, relief shading from slope, an absolute-height brightness cue,
 * and an optional topographic contour overlay. Water is detected by elevation
 * vs sea level.
 */
public final class TileRenderer {

    public static final int SIZE = 32; // == Chunk.SIZE_X / SIZE_Z

    /** Fallback sea level if the game's constant can't be read yet. */
    private static final int SEA_LEVEL_FALLBACK = 100;
    private static int cachedSeaLevel = Integer.MIN_VALUE;

    /** Byte order of the 16-bit level values in the raw LOD array. Determined once
     *  by comparing the raw terrain-level short to the known elevation. Null until
     *  calibrated (depth shading falls back to a fixed value meanwhile). */
    private static Boolean littleEndian = null;

    private static int readShort(byte[] raw, int loLayer, int hiLayer, int rx, int rz, boolean little) {
        int a = raw[Chunk.getRawTerrainIndex(loLayer, rx, rz)] & 0xFF;
        int b = raw[Chunk.getRawTerrainIndex(hiLayer, rx, rz)] & 0xFF;
        return little ? (a | (b << 8)) : ((a << 8) | b);
    }

    /** Figure out the level short's byte order by matching the raw terrain-level
     *  short against getLODTerrain()'s known elevation at the center cell. */
    private static void calibrateEndian(byte[] raw, float[] ground) {
        int x = SIZE / 2, z = SIZE / 2;
        float g = ground[Chunk.getTerrainIndex(x, z)];
        int le = readShort(raw, 1, 2, x + 2, z + 2, true);
        int be = readShort(raw, 1, 2, x + 2, z + 2, false);
        double dle = Math.abs(le - g), dbe = Math.abs(be - g);
        if (Math.min(dle, dbe) < 8.0) { // only trust a close match
            littleEndian = dle <= dbe;
        }
    }

    private TileRenderer() {
    }

    /** Diagnostics: the sea level currently in use (game value or fallback). */
    public static int currentSeaLevel() {
        return seaLevel();
    }

    /**
     * Read {@code Utils.SEA_LEVEL} lazily and defensively. The Utils class
     * initializer depends on the game runtime and can throw if touched too early
     * (e.g. during plugin enable), so we guard it and only cache a successful read.
     */
    private static int seaLevel() {
        if (cachedSeaLevel != Integer.MIN_VALUE) return cachedSeaLevel;
        try {
            int v = Utils.SEA_LEVEL;
            cachedSeaLevel = v;
            return v;
        } catch (Throwable t) {
            return SEA_LEVEL_FALLBACK; // don't cache; retry next time
        }
    }

    /**
     * @param contourOn whether to bake contour lines into this render - explicit
     *        per-call rather than {@code cfg.contourEnabled} (no longer a field;
     *        contour is a per-player preference, and {@link TileCache} caches
     *        both variants of a chunk so each viewer's own preference selects
     *        which one they get).
     * @return a 32*32 ARGB array indexed as {@code tile[z*SIZE + x]}, or {@code null}
     *         if the chunk is unavailable/invalid.
     */
    public static int[] render(Chunk chunk, MinimapConfig cfg, boolean contourOn) {
        if (chunk == null || !chunk.isValid()) return null;
        float[] ground = chunk.getLODTerrain();
        if (ground == null || ground.length < SIZE * SIZE) return null;

        final float seaLevel = seaLevel();
        int[] tile = new int[SIZE * SIZE];

        // Read the whole chunk's surface-texture layer in ONE native call (the raw
        // LOD array), instead of 1024 getLODSurfaceTexture() calls per tile — this
        // was the cause of ~1s main-thread stalls when many tiles built at once.
        // Raw grid is 36x36 (2-cell border); surface texture is layer 0.
        byte[] raw = null;
        try {
            raw = chunk.getRawLODTerrain();
        } catch (Throwable ignored) {
        }
        final boolean useRaw = raw != null && raw.length >= Chunk.getRawTerrainIndex(0, SIZE + 3, SIZE + 3) + 1;
        if (useRaw && littleEndian == null) {
            calibrateEndian(raw, ground);
        }

        // Base material color per cell, pre-computed into a grid padded by 2
        // cells on every side so a smoothing blur can sample past the tile
        // edge without a seam at chunk boundaries. The raw LOD read already
        // carries a 2-cell border from the neighboring chunks (that's what
        // the "+2" offsets below are), so the padding is real terrain data,
        // not a guess. Construction blocks are painted on top afterwards, in
        // overlayConstructions, so they stay sharp-edged regardless of blur.
        final int border = 2;
        final int padded = SIZE + border * 2;
        int[] baseGrid = new int[padded * padded];
        for (int pz = 0; pz < padded; pz++) {
            int z = pz - border;
            for (int px = 0; px < padded; px++) {
                int x = px - border;
                int texId;
                if (useRaw) {
                    texId = raw[Chunk.getRawTerrainIndex(0, x + 2, z + 2)] & 0xFF;
                } else {
                    // No cross-chunk border available in the fallback path;
                    // clamp to the tile edge instead of guessing.
                    int cx = Math.max(0, Math.min(SIZE - 1, x));
                    int cz = Math.max(0, Math.min(SIZE - 1, z));
                    texId = chunk.getLODSurfaceTexture(cx, cz) & 0xFF;
                }
                baseGrid[pz * padded + px] = MaterialColors.forTexture(texId);
            }
        }
        final int blurR = Math.max(0, Math.min(2, cfg.terrainBlurRadius));

        // Cave-opening detection (v2.38): getLODTerrain() is a 2D heightmap, so
        // a real hole (sinkhole, cave mouth, dug shaft) still reports whatever
        // height it has for that column — the map draws fictitious ground over
        // it. Pre-fetch the real voxel data for the chunk's height range (one
        // bulk read per vertical ChunkPart, capped by caveMaxChunkParts) so each
        // cell can cheaply check whether the reported surface is actually solid.
        Map<Integer, byte[]> caveParts = java.util.Collections.emptyMap();
        if (cfg.caveDetectionEnabled) {
            float minG = Float.MAX_VALUE, maxG = -Float.MAX_VALUE;
            for (float g : ground) {
                if (g < minG) minG = g;
                if (g > maxG) maxG = g;
            }
            if (minG <= maxG) {
                caveParts = prefetchParts(chunk,
                        (int) Math.floor(minG) - cfg.caveScanDepth, (int) Math.ceil(maxG),
                        cfg.caveMaxChunkParts);
            }
        }

        // Marks which cells were a detected cave opening, so the rim-to-black
        // vignette below (see applyHoleVignette) can find, for each hole
        // cell, its distance to the nearest solid/non-hole neighbor.
        boolean[] holeGrid = cfg.caveDetectionEnabled ? new boolean[SIZE * SIZE] : null;

        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                float g = ground[Chunk.getTerrainIndex(x, z)];

                // Water presence + depth from the game's actual per-cell data
                // (raw layer 3 = water texture; nonzero = water present).
                boolean water = false;
                float depth = 0f;
                if (useRaw) {
                    int waterTex = raw[Chunk.getRawTerrainIndex(3, x + 2, z + 2)] & 0xFF;
                    if (waterTex != 0) {
                        water = true;
                        depth = 4f;
                        if (littleEndian != null) {
                            int wl = readShort(raw, 4, 5, x + 2, z + 2, littleEndian);
                            int tl = readShort(raw, 1, 2, x + 2, z + 2, littleEndian);
                            float d = wl - tl;
                            if (d > 0f && d < 400f) depth = d;
                        }
                    }
                } else if (g < seaLevel - 0.25f) {
                    water = true;
                    depth = seaLevel - g;
                }

                // Does the real voxel column disagree with the LOD height here —
                // i.e. is there actually open air where the heightmap thinks
                // there's solid ground? Require two consecutive air voxels just
                // below the reported surface (not one) so a boundary-rounding
                // fluke on ordinary sloped terrain can't false-positive.
                boolean isHole = false;
                boolean holeVoid = false;
                int holeFloorId = -1;
                if (!caveParts.isEmpty()) {
                    int gi = (int) Math.floor(g);
                    if (voxelAt(caveParts, x, gi, z) == 0 && voxelAt(caveParts, x, gi - 1, z) == 0) {
                        isHole = true;
                        int floorY = Integer.MIN_VALUE;
                        int limit = gi - cfg.caveScanDepth;
                        for (int y = gi - 2; y >= limit; y--) {
                            int v = voxelAt(caveParts, x, y, z);
                            if (v < 0) break; // ran past the fetched part data
                            if (v != 0) {
                                floorY = y;
                                holeFloorId = v;
                                break;
                            }
                        }
                        if (floorY == Integer.MIN_VALUE) {
                            holeVoid = true;
                        } else {
                            g = floorY; // shade against the real depth, not the fictitious one
                            water = false; // the surface's water flag doesn't apply to the real floor
                        }
                    }
                }

                if (holeVoid) {
                    tile[z * SIZE + x] = cfg.caveVoidColor;
                    if (holeGrid != null) holeGrid[z * SIZE + x] = true;
                    continue;
                }
                if (isHole && holeGrid != null) holeGrid[z * SIZE + x] = true;

                int base;
                float relief;
                if (isHole) {
                    // The real floor material, unblurred — blending it with the
                    // surrounding surface grass would look wrong across a hole.
                    base = MaterialColors.forTexture(holeFloorId);
                    relief = 0f; // neighboring LOD heights aren't meaningful across an opening
                } else {
                    // Shaded land / sea-floor color for this cell: a smoothed
                    // blend of the surrounding material colors, so terrain type
                    // boundaries (e.g. grass into dirt) read as a soft gradient
                    // instead of a hard 1-cell line. Radius 0 disables it.
                    base = blurR == 0
                            ? baseGrid[(z + border) * padded + (x + border)]
                            : blurredBase(baseGrid, padded, border, x, z, blurR);

                    // Relief from slope. Reflect the opposite-side slope at tile
                    // edges so shading stays continuous across chunk boundaries.
                    float west = (x > 0) ? ground[Chunk.getTerrainIndex(x - 1, z)]
                                         : 2f * g - ground[Chunk.getTerrainIndex(x + 1, z)];
                    float north = (z > 0) ? ground[Chunk.getTerrainIndex(x, z - 1)]
                                          : 2f * g - ground[Chunk.getTerrainIndex(x, z + 1)];
                    relief = clampf(((g - west) + (g - north)) * cfg.hillshadeStrength,
                            -cfg.reliefClamp, cfg.reliefClamp);
                }
                float elevTint = clampf((g - seaLevel) * cfg.elevationBrightness,
                        cfg.elevationTintMin, cfg.elevationTintMax);
                float factor = clampf(1f + relief + elevTint, cfg.shadeFactorMin, cfg.shadeFactorMax);
                int landColor = shade(base, factor);

                if (contourOn && !water && !isHole) {
                    int band = (int) Math.floor(g / cfg.contourInterval);
                    int bandW = (x > 0) ? (int) Math.floor(ground[Chunk.getTerrainIndex(x - 1, z)] / cfg.contourInterval) : band;
                    int bandN = (z > 0) ? (int) Math.floor(ground[Chunk.getTerrainIndex(x, z - 1)] / cfg.contourInterval) : band;
                    if (band != bandW || band != bandN) {
                        boolean major = cfg.contourMajorEvery > 0 && (Math.floorMod(band, cfg.contourMajorEvery) == 0);
                        landColor = darken(landColor, major ? cfg.contourMajorDarken : cfg.contourMinorDarken);
                    }
                }

                int color;
                if (water) {
                    // Blend the shaded sea floor with water: shallow water is nearly
                    // transparent (land shows through), deep water is full blue.
                    int waterCol = MaterialColors.water(depth);
                    float a = clampf(depth / cfg.waterBlendDepth, cfg.waterMinAlpha, 1f);
                    color = lerp(landColor, waterCol, a);
                } else {
                    color = landColor;
                }
                tile[z * SIZE + x] = color;
            }
        }

        if (holeGrid != null && cfg.caveEdgeGlowEnabled) {
            applyHoleVignette(tile, holeGrid, cfg);
        }

        if (cfg.showVegetation) {
            overlayVegetation(chunk, tile, cfg);
        }
        if (cfg.showConstructions) {
            overlayConstructions(chunk, tile);
        }
        return tile;
    }

    /** Radius multiplier for a plant's growth stage, so a sapling reads as
     *  visibly smaller than a mature tree instead of the same-size blob. */
    private static float stageScale(Plants.Stage stage, MinimapConfig cfg) {
        if (stage == null) return 1f;
        return switch (stage) {
            case Sapling -> cfg.vegetationSaplingScale;
            case Growing -> cfg.vegetationGrowingScale;
            default -> 1f;
        };
    }

    /**
     * Paint trees onto the tile as small canopy stamps (only
     * {@code Plants.Type.Tree}/{@code FruitTree} are rendered at all — v2.42
     * dropped flowers/crops/bushes/ore per the user's request). Drawn before
     * constructions, so a building on a former tree's cell still reads as the
     * building. Canopy radius comes from the species' real size class
     * ({@code TreeColors.radiusForExtent}, not a guess), scaled by the actual
     * instance's runtime scale and growth stage; shape and color come from
     * {@code TreeColors.lookFor}, keyed off {@code windparam} (repurposed —
     * see that class — as the best available species-shape signal) with a
     * name-keyword fallback. Each tree also gets a small deterministic
     * brightness jitter (seeded by its stable global id, so it doesn't
     * flicker between renders) so a dense forest reads as a mass of
     * individual canopies instead of one flat color.
     *
     * <p>Scans the surrounding 3x3 chunks, not just this one: a tree near a
     * chunk edge has a canopy that overlaps into the neighboring tile, and
     * since {@code Plant[]} only comes from its own chunk's
     * {@code getAllPlants()}, that neighbor-owned tree would otherwise be
     * completely invisible to this tile — the overlapping half would just be
     * missing, i.e. the tree reads as "cut off" exactly at the (invisible)
     * chunk boundary. That looked patternless because chunk boundaries
     * aren't drawn on the map, not because it was actually random. Positions
     * from a neighbor chunk translate to negative/over-SIZE local
     * coordinates in this tile, which the existing bounds clipping in
     * {@link #paintPixel} already handles — only the true overlapping sliver
     * ever gets painted here.
     */
    /** A fruit tree's accent dots, queued during the canopy pass and painted
     *  only after every tree's canopy (including neighboring chunks') is
     *  down — see {@link #overlayVegetation}. */
    private record PendingFruit(int lx, int lz, float radius, int color, long seed) {
    }

    private static void overlayVegetation(Chunk chunk, int[] tile, MinimapConfig cfg) {
        int cx = chunk.getChunkPositionX();
        int cz = chunk.getChunkPositionZ();
        int baseX = cx * SIZE;
        int baseZ = cz * SIZE;
        List<PendingFruit> fruitAccents = new ArrayList<>();

        // Pass 1: every tree's canopy, across this chunk and (unless disabled)
        // its 8 neighbors.
        if (!cfg.vegetationScanNeighbors) {
            paintTreesFrom(chunk, tile, baseX, baseZ, cfg, fruitAccents);
        } else {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Chunk source = (dx == 0 && dz == 0) ? chunk : safeGetChunk(cx + dx, cz + dz);
                    if (source != null) {
                        paintTreesFrom(source, tile, baseX, baseZ, cfg, fruitAccents);
                    }
                }
            }
        }

        // Pass 2: fruit accents, strictly after every canopy. Painting a
        // fruit tree's accent dots in the same pass as canopies (right after
        // that tree's own canopy) meant any OTHER tree processed later —
        // same chunk or a neighbor, easily the case in a dense orchard —
        // could paint its own canopy right over an earlier tree's dots and
        // silently erase them. A second pass guarantees dots are always the
        // last thing painted, regardless of tree order/proximity.
        for (PendingFruit f : fruitAccents) {
            stampFruitAccents(tile, f.lx(), f.lz(), f.radius(), f.color(), f.seed());
        }
    }

    /** Fetch a neighboring chunk defensively for the edge-overflow scan
     *  above: null (silently skipped by the caller) on any failure or if it
     *  isn't loaded, rather than risking a stall on an ungenerated chunk. */
    private static Chunk safeGetChunk(int cx, int cz) {
        try {
            Chunk c = World.getChunk(cx, cz);
            return (c != null && c.isValid()) ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Stamp every tree's canopy from {@code source}'s own plant list onto
     *  {@code tile}, positioned relative to (baseX,baseZ) — the tile's own
     *  chunk origin, not necessarily {@code source}'s. Fruit accents are not
     *  painted here; a fruiting tree is queued into {@code fruitAccents}
     *  instead (see {@link #overlayVegetation} for why). */
    private static void paintTreesFrom(Chunk source, int[] tile, int baseX, int baseZ, MinimapConfig cfg,
                                        List<PendingFruit> fruitAccents) {
        Plant[] plants = source.getAllPlants();
        if (plants == null) return;
        for (Plant p : plants) {
            if (p == null || !p.isValid() || p.isCut()) continue; // a felled tree is a stump, not a tree
            var def = p.getDefinition();
            if (def == null) continue;
            if (def.type != Plants.Type.Tree && def.type != Plants.Type.FruitTree) continue;

            var pos = p.getWorldPosition();
            int lx = (int) Math.floor(pos.x) - baseX;
            int lz = (int) Math.floor(pos.z) - baseZ;
            if (lx < -8 || lx >= SIZE + 8 || lz < -8 || lz >= SIZE + 8) continue; // nowhere near this tile

            float scale = 1f;
            try {
                var s = p.getScale();
                scale = (s.x + s.z) * 0.5f;
                if (scale <= 0.05f || Float.isNaN(scale)) scale = 1f;
                if (scale > 4f) scale = 4f; // guard against a bogus/extreme scale value
            } catch (Throwable ignored) {
            }

            TreeColors.Look look = TreeColors.lookFor(def.type, def.windparam, def.name);
            float radius = Math.min(8f, TreeColors.radiusForExtent(def.extent) * look.radiusMul()
                    * scale * stageScale(def.stage, cfg));

            // Deterministic ±10% brightness jitter from the plant's stable id,
            // so repeated renders of the same forest look the same, not noisy.
            long h = p.getGlobalID();
            h ^= (h >>> 33);
            h *= 0xFF51AFD7ED558CCDL;
            h ^= (h >>> 29);
            float jitter = 1f + (((h & 0xFFFF) / 65535f) - 0.5f) * 0.20f;
            int shaded = shade(look.color(), jitter);

            switch (look.shape()) {
                case CONIFER -> stampConifer(tile, lx, lz, radius, shaded, cfg.vegetationEdgeDarken);
                case PALM -> stampPalm(tile, lx, lz, radius, shaded);
                case SPARSE -> stampSparse(tile, lx, lz, radius, shaded);
                case CROSS -> stampCross(tile, lx, lz, radius, shaded);
                default -> stampCanopy(tile, lx, lz, radius, shaded, cfg.vegetationEdgeDarken);
            }

            // v2.47's /mm fruitdebug ran (confirmed: forced re-render logged,
            // player standing next to a visibly fruiting/pickable apple tree)
            // and printed ZERO lines, because that logging was gated on
            // def.type == Plants.Type.FruitTree -- meaning that gate itself
            // was never true. Apple trees are most likely just
            // Plants.Type.Tree in this game, not a separate FruitTree type,
            // which would explain why every previous fix here (paint order,
            // radius floor) changed nothing: this whole code path was being
            // skipped before any of that logic ever ran. Gate purely on
            // pickupitem being present now, regardless of type, and log
            // every tree (not just FruitTree) while debugging so the real
            // type is visible if this still isn't it.
            boolean hasFruit = def.pickupitem != null && !def.pickupitem.isBlank();
            if (cfg.debugFruitLogging) {
                System.out.println("[PicSoulsMiniMap][fruitdebug] \"" + def.name + "\" id=" + def.id
                        + " type=" + def.type + " pickupitem=" + def.pickupitem + " hasFruit=" + hasFruit
                        + " radius=" + radius + " lx=" + lx + " lz=" + lz
                        + " chunk=(" + (source.getChunkPositionX()) + "," + (source.getChunkPositionZ()) + ")");
            }
            if (hasFruit) {
                fruitAccents.add(new PendingFruit(lx, lz, radius, TreeColors.fruitAccentColor(def.pickupitem), h));
            }
        }
    }

    /** Fill a small rounded blob centered at (lx,lz), clipped to the tile,
     *  darkened quadratically toward the rim (bright/flat near the center,
     *  visibly shaded near the edge) for a slight "lit dome" look instead of
     *  a flat-colored disc. Used for deciduous/young trees, and as the shared
     *  "fill a disc" primitive the other tree shapes build on. */
    private static void stampCanopy(int[] tile, int lx, int lz, float radius, int color, float edgeDarken) {
        if (radius <= 0.55f) {
            paintPixel(tile, lx, lz, color);
            return;
        }
        int r = Math.round(radius);
        float rOuter = radius + 0.4f;
        float r2 = rOuter * rOuter;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                float d2 = dx * dx + dz * dz;
                if (d2 > r2) continue;
                float t = d2 / r2; // 0 at center, 1 at rim
                float factor = 1f - edgeDarken * t * t;
                paintPixel(tile, lx + dx, lz + dz, shade(color, factor));
            }
        }
    }

    /** Coniferous canopy: the same domed disc as {@link #stampCanopy}, plus a
     *  handful of single-pixel spikes poking past the rim at fixed angles —
     *  a plain smooth circle reads as deciduous, a slightly jagged edge reads
     *  as needled/pointed even at this tiny a scale. Darkens less than a
     *  deciduous canopy (conifers read as dense/solid, not softly lit). */
    private static void stampConifer(int[] tile, int lx, int lz, float radius, int color, float edgeDarken) {
        stampCanopy(tile, lx, lz, radius, color, edgeDarken * 0.6f);
        if (radius < 1.2f) return;
        int spikeR = Math.round(radius) + 1;
        double[] angles = {0, 45, 90, 135, 180, 225, 270, 315};
        int darker = shade(color, 0.8f);
        for (double a : angles) {
            int dx = (int) Math.round(Math.cos(Math.toRadians(a)) * spikeR);
            int dz = (int) Math.round(Math.sin(Math.toRadians(a)) * spikeR);
            paintPixel(tile, lx + dx, lz + dz, darker);
        }
    }

    /** Palm canopy: a small bright center tuft plus several thin frond arms
     *  radiating outward — the recognizable top-down "starburst" silhouette
     *  of palm fronds, instead of a plain disc. */
    private static void stampPalm(int[] tile, int lx, int lz, float radius, int color) {
        float coreR = Math.max(0.6f, radius * 0.45f);
        stampCanopy(tile, lx, lz, coreR, color, 0.3f);
        int armLen = Math.max(1, Math.round(radius));
        int lighter = shade(color, 1.12f);
        int fronds = radius >= 1.6f ? 7 : 5;
        for (int i = 0; i < fronds; i++) {
            double a = (360.0 / fronds) * i;
            double ca = Math.cos(Math.toRadians(a)), sa = Math.sin(Math.toRadians(a));
            for (int s = 1; s <= armLen; s++) {
                int dx = (int) Math.round(ca * s);
                int dz = (int) Math.round(sa * s);
                paintPixel(tile, lx + dx, lz + dz, s == armLen ? lighter : color);
            }
        }
    }

    /** Dead tree: a sparse, patchy fill (roughly half the cells within the
     *  radius, dithered by position so it stays stable across renders)
     *  instead of a solid disc — bare branches read as thin/gappy, not a
     *  full canopy. */
    private static void stampSparse(int[] tile, int lx, int lz, float radius, int color) {
        if (radius <= 0.55f) {
            paintPixel(tile, lx, lz, color);
            return;
        }
        int r = Math.round(radius);
        float r2 = (radius + 0.4f) * (radius + 0.4f);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                if (((dx * 928371 + dz * 12289 + dx * dz) & 1) == 0) continue; // ~half-density dither
                paintPixel(tile, lx + dx, lz + dz, color);
            }
        }
    }

    /** Cactus: a thin plus/cross instead of a canopy disc — cacti don't have
     *  broad foliage from directly above, just a narrow vertical body. */
    private static void stampCross(int[] tile, int lx, int lz, float radius, int color) {
        int arm = Math.max(1, Math.round(radius));
        paintPixel(tile, lx, lz, color);
        for (int i = 1; i <= arm; i++) {
            paintPixel(tile, lx + i, lz, color);
            paintPixel(tile, lx - i, lz, color);
            paintPixel(tile, lx, lz + i, color);
            paintPixel(tile, lx, lz - i, color);
        }
    }

    /** A few small bright dots near a fruit tree's canopy rim, at angles
     *  derived from its stable id (deterministic, not random), so it reads as
     *  "fruiting" without needing a whole separate shape. */
    private static void stampFruitAccents(int[] tile, int lx, int lz, float radius, int accentColor, long seed) {
        int count = radius >= 2.5f ? 5 : (radius >= 1.5f ? 4 : 3);
        boolean chunky = radius >= 2f; // bigger canopy -> chunkier (2-3px) fruit blobs, not single pixels
        for (int i = 0; i < count; i++) {
            long h = seed + i * 0x9E3779B97F4A7C15L;
            h ^= (h >>> 31);
            double angle = ((h & 0xFFFF) / 65535.0) * 360.0;
            float rr = radius * 0.65f;
            int dx = (int) Math.round(Math.cos(Math.toRadians(angle)) * rr);
            int dz = (int) Math.round(Math.sin(Math.toRadians(angle)) * rr);
            paintPixel(tile, lx + dx, lz + dz, accentColor);
            if (chunky) {
                paintPixel(tile, lx + dx + 1, lz + dz, accentColor);
                paintPixel(tile, lx + dx, lz + dz + 1, accentColor);
            }
        }
    }

    private static void paintPixel(int[] tile, int x, int z, int color) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return;
        tile[z * SIZE + x] = color;
    }

    /**
     * Render a 32x32 tile for cave mode: a local top-down slice of the real
     * voxel terrain around {@code centerY} (the player's current altitude),
     * instead of the world-surface heightmap {@link #render} uses (which is
     * meaningless underground). For each column: look for an opening within
     * {@code caveWindowUp}/{@code caveWindowDown} of centerY; if the column is
     * solid throughout, it's a wall; if open, look further down for the floor
     * and shade it darker the deeper it is below the player; if the opening
     * never hits a floor within the window, it's an unexplored/void drop.
     * There's no relief/hillshade here — a few dozen blocks of real vertical
     * terrain doesn't have the "world height" the surface shading assumes.
     *
     * @return a 32*32 ARGB array, or {@code null} if the chunk or its voxel
     *         data isn't available (caller should draw this as unexplored).
     */
    public static int[] renderCave(Chunk chunk, MinimapConfig cfg, int centerY) {
        if (chunk == null || !chunk.isValid()) return null;
        int top = centerY + cfg.caveWindowUp;
        int bottom = centerY - cfg.caveWindowDown;
        Map<Integer, byte[]> parts = prefetchParts(chunk, bottom - 2, top + 2, cfg.caveMaxChunkParts + 1);
        if (parts.isEmpty()) return null;

        int[] tile = new int[SIZE * SIZE];
        boolean[] voidGrid = cfg.caveEdgeGlowEnabled ? new boolean[SIZE * SIZE] : null;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int airY = Integer.MIN_VALUE;
                boolean sawUnknown = false;
                for (int y = top; y >= bottom; y--) {
                    int v = voxelAt(parts, x, y, z);
                    if (v < 0) { sawUnknown = true; break; }
                    if (v == 0) { airY = y; break; }
                }
                if (airY == Integer.MIN_VALUE) {
                    // Solid (or unresolvable) at every level checked -> wall.
                    if (sawUnknown) {
                        tile[z * SIZE + x] = cfg.caveVoidColor;
                        if (voidGrid != null) voidGrid[z * SIZE + x] = true;
                    } else {
                        tile[z * SIZE + x] = cfg.caveWallColor;
                    }
                    continue;
                }
                int floorY = Integer.MIN_VALUE, floorId = -1;
                for (int y = airY - 1; y >= bottom; y--) {
                    int v = voxelAt(parts, x, y, z);
                    if (v < 0) break;
                    if (v != 0) { floorY = y; floorId = v; break; }
                }
                if (floorY == Integer.MIN_VALUE) {
                    tile[z * SIZE + x] = cfg.caveVoidColor; // open all the way down this window
                    if (voidGrid != null) voidGrid[z * SIZE + x] = true;
                    continue;
                }
                int base = MaterialColors.forTexture(floorId);
                float depthBelow = centerY - floorY;
                float factor = clampf(1f - depthBelow * cfg.caveDepthDarken, cfg.caveDepthMin, 1.1f);
                tile[z * SIZE + x] = shade(base, factor);
            }
        }
        if (voidGrid != null) {
            applyHoleVignette(tile, voidGrid, cfg);
        }
        if (cfg.showConstructions) {
            overlayConstructions(chunk, tile);
        }
        return tile;
    }

    /** Paint player-built construction blocks onto the tile in their block color. */
    private static void overlayConstructions(Chunk chunk, int[] tile) {
        var elements = chunk.getAllConstructionElements();
        if (elements == null) return;
        int baseX = chunk.getChunkPositionX() * SIZE;
        int baseZ = chunk.getChunkPositionZ() * SIZE;
        for (var e : elements) {
            if (e == null || !e.isValid()) continue;
            var p = e.getWorldPosition();
            int lx = (int) Math.floor(p.x) - baseX;
            int lz = (int) Math.floor(p.z) - baseZ;
            if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE) continue;
            tile[lz * SIZE + lx] = constructionColor(e.getColor(), e.getTexture());
        }
    }

    /** Painted blocks use their paint color; unpainted blocks use the real
     *  average color of their block texture (see {@link ConstructionColors}),
     *  so different materials read as themselves instead of a generic tone. */
    private static int constructionColor(int paint, int texture) {
        int rgb = paint & 0xFFFFFF;
        if (rgb != 0) {
            return 0xFF000000 | rgb;
        }
        return ConstructionColors.forTexture(texture);
    }

    // Binomial (approximate-gaussian) blur kernels, indexed [radius][dz+radius][dx+radius].
    // Cheap integer weights that sum to a power of two, so dividing by KERNEL_SUM is exact.
    private static final int[][] KERNEL_R1 = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}}; // sum 16
    private static final int[][] KERNEL_R2 = { // sum 256
            {1, 4, 6, 4, 1},
            {4, 16, 24, 16, 4},
            {6, 24, 36, 24, 6},
            {4, 16, 24, 16, 4},
            {1, 4, 6, 4, 1},
    };

    /** Weighted blur of the padded base-color grid around tile cell (x,z). */
    private static int blurredBase(int[] baseGrid, int padded, int border, int x, int z, int r) {
        int[][] kernel = (r == 1) ? KERNEL_R1 : KERNEL_R2;
        int sum = (r == 1) ? 16 : 256;
        int sr = 0, sg = 0, sb = 0;
        for (int dz = -r; dz <= r; dz++) {
            int pz = z + border + dz;
            int rowBase = pz * padded;
            int[] krow = kernel[dz + r];
            for (int dx = -r; dx <= r; dx++) {
                int c = baseGrid[rowBase + x + border + dx];
                int w = krow[dx + r];
                sr += ((c >> 16) & 0xFF) * w;
                sg += ((c >> 8) & 0xFF) * w;
                sb += (c & 0xFF) * w;
            }
        }
        return 0xFF000000 | ((sr / sum) << 16) | ((sg / sum) << 8) | (sb / sum);
    }

    // ---- Real voxel data (ChunkPart), used for cave detection and cave mode ----
    // getLODTerrain()/getRawLODTerrain() are a 2D heightmap: one height/texture
    // per column, so they cannot represent an actual hole. ChunkPart carries the
    // real 3D terrain voxels (id 0 = Air). Bulk-fetched once per needed vertical
    // part (SIZE_Y=64 blocks) and cached here for the duration of one render()
    // call, mirroring the "one native call, then index locally" pattern already
    // used for the raw LOD read above.

    /** Bulk-fetch every ChunkPart needed to cover [minY, maxY], keyed by part
     *  index. Capped at {@code maxParts} so a chunk with a huge elevation range
     *  can't force an unbounded number of bulk reads — returns an empty map in
     *  that case (callers treat that as "no voxel data available", falling
     *  back to normal behavior). Public: used by both {@link #renderCave} and
     *  {@link #render}'s cave-opening detection. */
    public static Map<Integer, byte[]> prefetchParts(Chunk chunk, int minY, int maxY, int maxParts) {
        int minCy = Math.floorDiv(minY, ChunkPart.SIZE_Y);
        int maxCy = Math.floorDiv(maxY, ChunkPart.SIZE_Y);
        Map<Integer, byte[]> parts = new HashMap<>();
        if (maxCy - minCy + 1 > maxParts) return parts;
        for (int cy = minCy; cy <= maxCy; cy++) {
            try {
                ChunkPart part = chunk.getChunkPart(cy);
                if (part != null) parts.put(cy, part.getTerrain());
            } catch (Throwable ignored) {
            }
        }
        return parts;
    }

    /** Terrain id at a world Y within the pre-fetched parts; -1 if that part
     *  wasn't fetched (out of range / ungenerated) — callers treat -1 as
     *  "unknown", never as air, so missing data can't be mistaken for a hole.
     *  Public: see {@link #prefetchParts}. */
    public static int voxelAt(Map<Integer, byte[]> parts, int x, int worldY, int z) {
        int cy = Math.floorDiv(worldY, ChunkPart.SIZE_Y);
        byte[] part = parts.get(cy);
        if (part == null) return -1;
        int localY = worldY - cy * ChunkPart.SIZE_Y;
        return part[ChunkPart.getTerrainIndex(x, localY, z)] & 0xFF;
    }

    /**
     * Makes a detected hole/void read as an actual hole instead of a flat
     * grey patch: cells near the boundary of a hole patch (i.e. close to a
     * solid/non-hole neighbor — the rim) blend toward a bright highlight
     * color; cells deep inside a patch (far from any solid neighbor) fade
     * toward black. {@code isHole} marks which cells in the tile are
     * eligible; every other cell is the solid boundary the vignette measures
     * distance from. Shared by {@link #render}'s surface cave-opening
     * detection and {@link #renderCave}'s void/chasm cells.
     */
    private static void applyHoleVignette(int[] tile, boolean[] isHole, MinimapConfig cfg) {
        float falloff = Math.max(0.5f, cfg.caveEdgeFalloff);
        int maxR = Math.max(1, cfg.caveEdgeSearchMax);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int idx = z * SIZE + x;
                if (!isHole[idx]) continue;
                int edgeDist = nearestNonHoleDist(isHole, x, z, maxR);
                float t = clampf(edgeDist / falloff, 0f, 1f); // 0 at the rim, 1 deep inside

                // At the rim (t=0): lighten toward the highlight color. Using
                // the cell's own resolved color as the blend base (rather
                // than painting a uniform ring) keeps a hint of the real
                // material/depth near the edge instead of a flat highlight.
                int rimBlend = lerp(tile[idx], cfg.caveRimColor, (1f - t) * cfg.caveRimStrength);
                // Toward the center: darken quadratically down to caveCenterDarkness.
                float darkFactor = 1f - t * t * (1f - cfg.caveCenterDarkness);
                tile[idx] = shade(rimBlend, darkFactor);
            }
        }
    }

    /** Chebyshev distance from (cx,cz) to the nearest in-bounds cell that is
     *  NOT marked as a hole (the solid boundary), searched ring by ring up to
     *  {@code maxR}. Out-of-bounds cells (past the tile edge) are treated as
     *  unknown, not solid — a hole patch that runs off the edge of the tile
     *  just falls back to {@code maxR} (fully dark) rather than assuming
     *  there's solid ground just past the edge. */
    private static int nearestNonHoleDist(boolean[] isHole, int cx, int cz, int maxR) {
        for (int r = 1; r <= maxR; r++) {
            int top = cz - r, bottom = cz + r, left = cx - r, right = cx + r;
            for (int x = left; x <= right; x++) {
                if (isSolid(isHole, x, top) || isSolid(isHole, x, bottom)) return r;
            }
            for (int z = top + 1; z <= bottom - 1; z++) {
                if (isSolid(isHole, left, z) || isSolid(isHole, right, z)) return r;
            }
        }
        return maxR;
    }

    private static boolean isSolid(boolean[] isHole, int x, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return false; // unknown, not a match
        return !isHole[z * SIZE + x];
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int shade(int argb, float f) {
        int r = clamp((int) (((argb >> 16) & 0xFF) * f));
        int g = clamp((int) (((argb >> 8) & 0xFF) * f));
        int b = clamp((int) ((argb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int argb, float f) {
        return shade(argb, f);
    }

    /** Linear blend between two opaque ARGB colors; t=0 -> c1, t=1 -> c2. */
    private static int lerp(int c1, int c2, float t) {
        int r = clamp((int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t));
        int g = clamp((int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t));
        int b = clamp((int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
