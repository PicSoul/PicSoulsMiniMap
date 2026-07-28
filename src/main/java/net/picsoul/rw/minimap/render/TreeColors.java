package net.picsoul.rw.minimap.render;

import net.risingworld.api.definitions.Plants;

/**
 * Tree look-up (only trees are rendered on the minimap as of v2.42 — flowers,
 * crops, bushes and ore markers were removed at the user's request). Two
 * independent SDK signals drive the look, both plain fields on
 * {@code Plants.PlantDefinition} that come for free with the same
 * {@code getDefinition()} call {@code TileRenderer} already makes:
 *
 * <ul>
 *   <li>{@code windparam} — documented as "the tree wind sound parameter",
 *       but its 8 values are exactly a tree-shape taxonomy: 1/2 = dead
 *       (thin/thick), 3/4 = deciduous (thin/thick), 5 = coniferous, 6 = palm,
 *       7 = young, 8 = cactus. Repurposed here as the best available shape
 *       signal, since there's no dedicated visual-style field.</li>
 *   <li>{@code extent} ({@code Plants.Size}: None/Tiny/SmallLight/Small/
 *       Medium/MediumLarge/Large/Huge) — the species' actual designed size
 *       class, used for canopy radius instead of guessing from
 *       {@code Plant.getScale()} alone (that's the *runtime* scale variance
 *       on top of this, applied multiplicatively — see
 *       {@code TileRenderer.overlayVegetation}).</li>
 * </ul>
 *
 * <p><b>Calibration note:</b> {@code windparam}'s 1-8 mapping is taken
 * directly from its javadoc, not verified against real plant data (no in-game
 * access). {@code /mm ids} logs each nearby tree's name/windparam/extent/stage
 * specifically so this can be checked and corrected if the mapping is off.
 */
public final class TreeColors {

    /** Pixel-stamp shape {@code TileRenderer} should use for a category —
     *  top-down canopies mostly read as round blobs at this scale, but a
     *  slightly jagged edge, a starburst, a sparse dither, or a thin cross
     *  still reads as a distinct silhouette even at a handful of pixels. */
    public enum Shape { ROUND, CONIFER, PALM, SPARSE, CROSS }

    /** Resolved look for one tree instance: base color, canopy shape, and a
     *  radius multiplier applied on top of the extent-based base radius
     *  (e.g. cacti and dead trees read smaller/thinner than their extent
     *  alone would suggest). */
    public record Look(int color, Shape shape, float radiusMul) {
    }

    private TreeColors() {
    }

    /** Resolve a tree's look from its windparam (shape category) and name
     *  (fruit-tree accent color, and a fallback guess if windparam is out of
     *  the documented 1-8 range). */
    public static Look lookFor(Plants.Type type, int windparam, String name) {
        String n = name == null ? "" : name.toLowerCase();
        boolean fruit = type == Plants.Type.FruitTree;

        switch (windparam) {
            case 1: return new Look(0xFF4A4438, Shape.SPARSE, 0.8f);   // dead, thin
            case 2: return new Look(0xFF4A4438, Shape.SPARSE, 1.0f);   // dead, thick
            case 3: return new Look(fruit ? 0xFF33501C : 0xFF3E5C28, Shape.ROUND, 0.85f); // deciduous, thin
            case 4: return new Look(fruit ? 0xFF2E4A1E : 0xFF2E4A1E, Shape.ROUND, 1.05f); // deciduous, thick
            case 5: return new Look(0xFF17332A, Shape.CONIFER, 1.0f); // coniferous
            case 6: return new Look(0xFF4F8A3D, Shape.PALM, 1.0f);    // palm
            case 7: return new Look(0xFF4E7A3B, Shape.ROUND, 0.5f);   // young
            case 8: return new Look(0xFF4E7A3B, Shape.CROSS, 0.45f);  // cactus
            default: break;
        }

        // windparam wasn't one of the documented 1-8 values -- fall back to a
        // name-based best guess for the most recognizable species, then a
        // generic round deciduous canopy.
        if (n.contains("pine") || n.contains("fir") || n.contains("spruce") || n.contains("conifer")) {
            return new Look(0xFF17332A, Shape.CONIFER, 1.0f);
        }
        if (n.contains("palm") || n.contains("coconut")) {
            return new Look(0xFF4F8A3D, Shape.PALM, 1.0f);
        }
        if (n.contains("cactus")) {
            return new Look(0xFF4E7A3B, Shape.CROSS, 0.45f);
        }
        if (n.contains("dead") || n.contains("burnt") || n.contains("burned") || n.contains("charred")) {
            return new Look(0xFF4A4438, Shape.SPARSE, 0.9f);
        }
        return new Look(fruit ? 0xFF33501C : 0xFF2E4A20, Shape.ROUND, 1.0f);
    }

    /** Base canopy radius (world blocks) for a species' designed size class,
     *  before the per-instance runtime scale ({@code Plant.getScale()}),
     *  growth-stage shrink, and the category's {@code radiusMul} are applied. */
    public static float radiusForExtent(Plants.Size extent) {
        if (extent == null) return 1.6f;
        return switch (extent) {
            case None -> 1.0f;
            case Tiny -> 0.7f;
            case SmallLight -> 1.0f;
            case Small -> 1.2f;
            case Medium -> 1.7f;
            case MediumLarge -> 2.1f;
            case Large -> 2.6f;
            case Huge -> 3.3f;
        };
    }

    /** Small bright accent-dot color for a fruit tree, by fruit keyword —
     *  scattered near the canopy rim in {@code TileRenderer} so a fruit tree
     *  still reads as "fruiting" without needing a whole distinct shape. */
    public static int fruitAccentColor(String rawName) {
        String n = rawName == null ? "" : rawName.toLowerCase();
        if (n.contains("cherry")) return 0xFFD62839;
        if (n.contains("apple")) return 0xFFC81E2C;
        if (n.contains("orange") || n.contains("citrus")) return 0xFFF08A1E;
        if (n.contains("lemon") || n.contains("lime")) return 0xFFE8D93A;
        if (n.contains("peach") || n.contains("apricot")) return 0xFFF2A65A;
        if (n.contains("plum")) return 0xFF7A3FA0;
        if (n.contains("olive")) return 0xFF6E7A2E;
        if (n.contains("fig")) return 0xFF5C3A5C;
        if (n.contains("pear")) return 0xFFC7D14A;
        return 0xFFE0642E; // unmatched fruit: still a warm, visible accent
    }
}
