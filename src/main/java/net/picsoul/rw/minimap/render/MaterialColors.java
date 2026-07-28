package net.picsoul.rw.minimap.render;

import net.risingworld.api.definitions.Terrain;

public final class MaterialColors {

    private static final int DEFAULT = 0xFF808080; 
    private static final int[] BY_ID = new int[256];

    static {
        Terrain[] all = Terrain.values();
        for (int id = 0; id < 256; id++) {
            Terrain best = null;
            int bestId = -1;
            for (Terrain t : all) {
                if (t.id <= id && t.id > bestId) {
                    best = t;
                    bestId = t.id;
                }
            }
            BY_ID[id] = (best == null) ? DEFAULT : colorFor(best);
        }
    }

    private MaterialColors() {
    }

    public static int forTexture(int id) {
        return BY_ID[id & 0xFF];
    }

    public static int water(float depth) {
        float d = depth < 0f ? 0f : (depth > 12f ? 12f : depth);
        float f = 1.0f - (d / 12f) * 0.45f; 
        int r = (int) (0x2f * f * 0.8f);
        int g = (int) (0x6d * f * 0.8f);
        int b = (int) (0x9c * f * 0.8f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Colors below marked "measured" are the average pixel color of that
    // terrain's actual swatch in the game's world-edit terrain picker (see
    // screenshots/textures_with_ids/RisingWorld_pXEoxTwoCd.jpg and the v2.35
    // note in CHANGELOG.md). Matched by name, not the picker's on-screen id badge
    // -- confirmed via javap against Terrain.class that the badge numbers are
    // NOT Terrain.id past the first ~27 entries (e.g. badge 41 "Grass" vs the
    // real Terrain.Grass.id of 100). Everything else is still the older
    // hand-picked estimate (no swatch was available: ores, rarer grass
    // variants, water).
    private static int colorFor(Terrain t) {
        return switch (t) {
            case Air -> 0xFF696969;
            case Stone -> 0xFF676863; // measured
            case Cobble -> 0xFF7B7570;
            case Rubble -> 0xFF726D66;
            case Gravel1 -> 0xFF7A7C74; // measured
            case Gravel2 -> 0xFF8F9186; // measured
            case Gravel3 -> 0xFF847D6D; // measured
            case Desertstone -> 0xFF685D44; // measured
            case Sandstone1 -> 0xFF795D46; // measured
            case Sandstone2 -> 0xFFAC906A; // measured
            case Dirt -> 0xFF65543C; // measured
            case Drydirt -> 0xFF827555; // measured
            case Mud -> 0xFF41331B; // measured
            case Redclay -> 0xFF744724; // measured
            case Farmland -> 0xFF443524; // measured
            case FarmlandWet -> 0xFF45331E;
            case Forestground1 -> 0xFF6F5C3E; // measured
            case Forestground2 -> 0xFF63532C; // measured
            case Forestground3 -> 0xFF533626; // measured
            case Forestmoss -> 0xFF424210; // measured
            case Sanddesert -> 0xFFB29966; // measured
            case Sandbeach -> 0xFF9A8D5B; // measured
            case Sandunderwater -> 0xFF52442C; // measured
            case Volcanic -> 0xFF353844; // measured
            case Obsidian -> 0xFF1C2030; // measured
            case ObsidianGlow -> 0xFF2E2237;
            case Hellstone -> 0xFF882D2B; // measured
            case HellstoneGlow -> 0xFF612A23;
            case Snow -> 0xFFA1B3B1; // measured
            case Ice -> 0xFF28434A; // measured
            case GrassFrozen -> 0xFF98A699;
            case Underwater -> 0xFF4F5633; // measured ("Algae" in the picker)
            case Corals -> 0xFFA55861;
            case Coal -> 0xFF1D1E1A; // measured
            case Sulfur -> 0xFFA0993B;
            case Iron -> 0xFF7B6554;
            case Aluminium -> 0xFF94999E;
            case Tungsten -> 0xFF585C5F;
            case Gold -> 0xFFAD913E;
            case Grass -> 0xFF3E4C21; // measured
            case GrassArid -> 0xFF727C37;
            case GrassDry -> 0xFF7B7F3B;
            case GrassDead -> 0xFF6E6844;
            case GrassForest -> 0xFF326129;
            case GrassJungle -> 0xFF256E2E;
            case GrassAridForest -> 0xFF586E32;
            case GrassSea, GrassSeaweed -> 0xFF326154;
            case Water, WaterFlow, WaterTemp, WaterInfinite, WaterStatic,
                 SaltWater, SaltWaterFlow, SaltWaterTemp, SaltWaterInfinite, SaltWaterStatic
                    -> 0xFF25577D;
        };
    }
}
