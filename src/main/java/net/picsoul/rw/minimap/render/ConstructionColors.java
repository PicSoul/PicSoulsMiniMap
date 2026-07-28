package net.picsoul.rw.minimap.render;

/**
 * Block-texture id -> solid color, for unpainted player-built construction
 * elements. Each color is the average pixel color of that texture's swatch in
 * the game's own build-menu picker (see {@code screenshots/textures_with_ids}
 * and the v2.35 note in {@code CHANGELOG.md} for the extraction method), so a
 * block reads on the minimap roughly the color it actually is in-world,
 * instead of the old scheme (a small hand-picked palette cycled by
 * {@code texture % length}, unrelated to the real material).
 *
 * <p>Same lookup shape as {@link MaterialColors}: a flat array built once at
 * class-init, so a render-time lookup is a single array index — no added
 * per-tile cost over the palette it replaces.
 */
public final class ConstructionColors {

    /** Matches the old {@code MinimapConfig.defaultConstructionColor}. Only
     *  used for out-of-range ids (see {@link #forTexture}); ids below the
     *  catalog's lowest known entry fall back to {@link MaterialColors}
     *  instead (see the static initializer below), not this. */
    private static final int DEFAULT = 0xFFBCA88A;
    private static final int MAX_ID = 1024;
    private static final int[] BY_ID = new int[MAX_ID];

    // Known texture ids, ascending, paired 1:1 with COLORS below.
    private static final int[] IDS = {
            100, 101, 103, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 120, 130, 131,
            132, 133, 134, 135, 136, 137, 138, 139, 140, 149, 150, 151, 152, 153, 154, 155, 156, 157,
            158, 159, 160, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 178, 179, 180,
            181, 185, 190, 195, 200, 201, 202, 203, 204, 206, 207, 208, 212, 213, 214, 215, 216, 217,
            218, 219, 220, 221, 222, 225, 227, 228, 229, 230, 233, 234, 235, 236, 239, 240, 245, 246,
            247, 250, 251, 255, 256, 257, 259, 260, 261, 265, 266, 267, 268, 269, 270, 271, 274, 276,
            277, 280, 281, 282, 283, 285, 286, 290, 291, 295, 300, 301, 305, 306, 309, 310, 315, 316,
            317, 318, 325, 326, 340, 345, 347, 400, 401, 402, 405, 406, 408, 410, 411, 415, 420, 425,
            426, 430, 431, 435, 440, 441, 500, 501, 505, 506, 507, 508, 510, 511, 512, 515, 520, 550,
            552, 553, 554, 555, 556, 557, 560, 590, 594, 595, 600, 601, 602, 604, 605, 607, 608, 610,
            615, 616, 618, 619, 622, 623, 624, 625, 628, 630, 635, 636, 638, 639, 640, 641, 642, 647,
            649, 670, 671, 672, 673, 701, 702, 703, 705, 715, 720, 721, 729, 730, 731, 735, 736, 740,
            741, 742, 750, 754, 755, 759, 760, 761, 765, 770, 850, 851, 852, 860, 861, 862, 879, 880,
            881, 882, 883, 884, 885, 886, 887, 888, 889, 890, 891, 900, 905, 906, 910, 915, 920, 930,
            933, 935, 938, 940, 945, 947, 950, 970, 975
    };
    // Colors, 1:1 with IDS above.
    private static final int[] COLORS = {
            0xFF755744, 0xFF7F685D, 0xFF645B50, 0xFF967E65, 0xFF3C3126, 0xFF726053, 0xFF2C241E,
            0xFF978474, 0xFF63554B, 0xFF664D40, 0xFF422F27, 0xFF574A42, 0xFF372D28, 0xFF8A7F77,
            0xFF907F72, 0xFF464242, 0xFF96715C, 0xFF3D2B22, 0xFFAF835B, 0xFF533C27, 0xFF764C40,
            0xFFD9BBAB, 0xFF685851, 0xFF4C3D39, 0xFF333032, 0xFFAD9F94, 0xFF6A615A, 0xFF564E4E,
            0xFF4D3B32, 0xFF5F4B40, 0xFF5F5648, 0xFF705A4A, 0xFF695140, 0xFF705B4A, 0xFF44362A,
            0xFF614935, 0xFF3A2A1D, 0xFF7B6050, 0xFF45342B, 0xFFB19485, 0xFF5D4D45, 0xFF6F5030,
            0xFF422D19, 0xFF634935, 0xFF745A49, 0xFF463226, 0xFF493024, 0xFF523727, 0xFF5F2D1B,
            0xFF2E1F15, 0xFF807471, 0xFF90796E, 0xFFA89B99, 0xFFA77F58, 0xFF785036, 0xFFC18C5E,
            0xFF282527, 0xFF897D70, 0xFF55555C, 0xFF70645B, 0xFF736664, 0xFF423D38, 0xFF7A787A,
            0xFF545259, 0xFF2E2C30, 0xFF292025, 0xFFAD957F, 0xFF575250, 0xFF6A635F, 0xFF5A554E,
            0xFF797475, 0xFF2E2D2D, 0xFF5E5749, 0xFF363128, 0xFF83786A, 0xFF655C44, 0xFF807B7B,
            0xFF322821, 0xFF5F524A, 0xFF2A231F, 0xFF6A5A50, 0xFF68584F, 0xFF74635B, 0xFF948C88,
            0xFF8E837D, 0xFF675E5A, 0xFF5E564D, 0xFF716761, 0xFF312F2C, 0xFF1D1B1A, 0xFF98908E,
            0xFFAB9983, 0xFF62574A, 0xFFA98165, 0xFFC0AA9A, 0xFFA19078, 0xFFC19F7D, 0xFF9B836F,
            0xFFB09171, 0xFF867C78, 0xFF5C5755, 0xFF706C6A, 0xFF5A5759, 0xFF7D7470, 0xFF847C7A,
            0xFF665A4B, 0xFF827C7C, 0xFF9C9594, 0xFF676262, 0xFF776155, 0xFF322825, 0xFF9C9694,
            0xFFA08E7E, 0xFF35343A, 0xFF26252A, 0xFF424142, 0xFF201E20, 0xFF8F8683, 0xFF604833,
            0xFF886A60, 0xFFD0BAA2, 0xFF8A7B6A, 0xFF6D6B72, 0xFF464549, 0xFF5D4E4D, 0xFF8D422F,
            0xFFC9BDB9, 0xFF746C6A, 0xFF5E3B31, 0xFF654E4C, 0xFF8E685E, 0xFF3A3B3F, 0xFF3E3C3E,
            0xFF605F61, 0xFF1D1C1D, 0xFF585A54, 0xFFA37E5F, 0xFFB3885D, 0xFF736F69, 0xFFB5823B,
            0xFFC99C58, 0xFF563838, 0xFFBAA79D, 0xFF998F8D, 0xFFA09593, 0xFFCAC5C9, 0xFFBEBABD,
            0xFF191918, 0xFF595454, 0xFF5D5756, 0xFFDCD4D5, 0xFF9A8E80, 0xFFD2CCD0, 0xFFD3CCD1,
            0xFF736F72, 0xFF7A7679, 0xFF9E948D, 0xFF827F81, 0xFF5A5753, 0xFF7E4B30, 0xFF30545A,
            0xFFA39386, 0xFFC3BFC2, 0xFFD0C6C6, 0xFFCFC9CD, 0xFF6E6B6E, 0xFFC8C3C6, 0xFFB9B1B7,
            0xFFCCC7CA, 0xFF977B65, 0xFF7A6042, 0xFF80644A, 0xFF969393, 0xFF555354, 0xFF8D8583,
            0xFFA09998, 0xFF696464, 0xFFABACB3, 0xFF626267, 0xFF696054, 0xFFA9A1A0, 0xFF605C5B,
            0xFF777477, 0xFF867B76, 0xFF736C69, 0xFF9D9693, 0xFFADA4A3, 0xFF2F2723, 0xFFA4A0A3,
            0xFF777376, 0xFF766D6D, 0xFF968E87, 0xFF737472, 0xFF6F6F6E, 0xFF767675, 0xFF6F706F,
            0xFF7D7E7C, 0xFF51483B, 0xFF564B40, 0xFF252223, 0xFF4F4D4E, 0xFF5B5656, 0xFF5F6166,
            0xFF868185, 0xFF595559, 0xFF696161, 0xFF5B3D2E, 0xFFA87C2A, 0xFF7F4D3B, 0xFF558272,
            0xFF727277, 0xFF848082, 0xFF747172, 0xFF694D05, 0xFF986360, 0xFF747073, 0xFF594F3E,
            0xFF6A4C4E, 0xFF8C7E78, 0xFF76625F, 0xFF646162, 0xFF706A6B, 0xFF484743, 0xFF7E7B7C,
            0xFF514C49, 0xFF928F92, 0xFF454444, 0xFF454444, 0xFF787577, 0xFF141510, 0xFF1A1A16,
            0xFF392A1E, 0xFF484547, 0xFF4A4949, 0xFF484547, 0xFF474546, 0xFF464446, 0xFF4E4C4D,
            0xFF4D4B4D, 0xFF4E4C4E, 0xFF4E4C4D, 0xFF494648, 0xFF484648, 0xFF444243, 0xFF444243,
            0xFF705F52, 0xFFB68C61, 0xFF8F7E77, 0xFF625E60, 0xFFA25935, 0xFF353234, 0xFF736148,
            0xFF6C6462, 0xFF786E6E, 0xFF847C76, 0xFFADA8AC, 0xFFE8E1E4, 0xFFDCCABF, 0xFF5A5341,
            0xFFA9A4AF, 0xFFB29160
    };

    static {
        // Below the lowest id we have a real building-catalog swatch for
        // (100), a construction can still legitimately use one of these ids:
        // Rising World lets you build with "natural" textures (stone, ore,
        // hellstone, ...) that reuse the same id space as Terrain.id, rather
        // than the manufactured brick/wood/tile/etc. catalog scraped into
        // IDS/COLORS. (Confirmed by the user: a block built with the
        // Hellstone texture reports getTexture()==29, exactly Terrain.
        // Hellstone.id.) So until the catalog's first real entry kicks in,
        // fall back to MaterialColors' measured terrain color for that same
        // id instead of a generic tone — it's almost certainly the same
        // texture. Once idx reaches a real catalog entry, gaps between
        // catalog ids (e.g. 102, 104 are unused) still forward-fill from the
        // nearest lower catalog color, same as before.
        int idx = 0;
        int cur = -1;
        for (int id = 0; id < MAX_ID; id++) {
            while (idx < IDS.length && IDS[idx] <= id) {
                cur = COLORS[idx];
                idx++;
            }
            BY_ID[id] = (cur == -1) ? MaterialColors.forTexture(id) : cur;
        }
    }

    private ConstructionColors() {
    }

    /** @return the block color for a construction texture id; a neutral
     *          building tone for ids we have no swatch data for. */
    public static int forTexture(int id) {
        if (id < 0 || id >= MAX_ID) return DEFAULT;
        return BY_ID[id];
    }
}
