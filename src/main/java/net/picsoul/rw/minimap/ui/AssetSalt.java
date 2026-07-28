package net.picsoul.rw.minimap.ui;

import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Makes every PNG we hand to {@code TextureAsset.load} byte-unique, so the game
 * never registers the same texture <i>name</i> twice in one game process.
 *
 * <p><b>Why this exists (the world-switch crash, v2.20).</b> The game names a raw
 * texture asset after the checksum of its bytes — the crash logs show
 * {@code REGISTER ASSET TEXTURE (6) FROM RAW: (CH: 05254f5f…)} and later a request
 * for the asset named {@code -119 05254f5f… -126}. Our HUD textures (player
 * marker, the 8 waypoint icons, the spawn diamond) are generated deterministically,
 * so they are byte-identical in every world and therefore always produce the same
 * checksum/name.
 *
 * <p>On a world switch the plugin is unloaded (its assets are freed) and re-enabled
 * in the new world, where it registers those same names again. The game then
 * crashes natively the first time the client requests one of those re-registered
 * assets — proven in the logs: world 1 registered checksum {@code 05254f5f…} as
 * asset 6, freed it at teardown, world 2 registered the identical checksum as asset
 * 25, and the crash happened immediately after {@code Receive bytes for asset 25}.
 * Loading a world alone never crashes, because each name is only seen once.
 *
 * <p>The fix is to guarantee the bytes — and therefore the checksum and name — are
 * unique for every single load: a PNG {@code tEXt} chunk carrying a per-plugin-load
 * id plus a per-call counter is inserted before {@code IEND}. {@code tEXt} is an
 * ancillary, standard PNG chunk that decoders ignore, so the decoded image is
 * pixel-for-pixel unchanged. The per-load id is regenerated every time the class is
 * loaded, and a world switch always creates a fresh plugin classloader, so world 2
 * can never collide with world 1. The per-call counter additionally means two
 * loads of identical content are distinct assets, so disposing one can never free a
 * texture another UI element is still showing.
 */
public final class AssetSalt {

    /** Unique per plugin load (a world switch reloads the plugin classloader). */
    private static final String LOAD_ID =
            Long.toHexString(System.nanoTime()) + Integer.toHexString(System.identityHashCode(AssetSalt.class));

    /** Distinguishes individual loads within one plugin session. */
    private static final AtomicLong COUNTER = new AtomicLong();

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private AssetSalt() {
    }

    /**
     * @return {@code png} with a unique {@code tEXt} chunk inserted before IEND.
     *         Returns the input unchanged if it doesn't look like a PNG (the
     *         texture still works; it just keeps the game's own name).
     */
    public static byte[] unique(byte[] png) {
        if (png == null || png.length < PNG_MAGIC.length + 12) return png;
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (png[i] != PNG_MAGIC[i]) return png;
        }
        try {
            // Keyword "Comment", NUL separator, then our unique text.
            byte[] text = ("Comment\0psmm-" + LOAD_ID + "-" + COUNTER.incrementAndGet())
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] type = {'t', 'E', 'X', 't'};

            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(text);
            long c = crc.getValue();

            // The final 12 bytes are the IEND chunk (length 0 + type + CRC); our
            // chunk goes immediately before it.
            int cut = png.length - 12;
            byte[] out = new byte[png.length + 12 + text.length];
            System.arraycopy(png, 0, out, 0, cut);
            int p = cut;
            p = writeInt(out, p, text.length);
            System.arraycopy(type, 0, out, p, 4);
            p += 4;
            System.arraycopy(text, 0, out, p, text.length);
            p += text.length;
            p = writeInt(out, p, (int) c);
            System.arraycopy(png, cut, out, p, 12);
            return out;
        } catch (Throwable t) {
            return png; // never break rendering over this
        }
    }

    private static int writeInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
        return p + 4;
    }
}
