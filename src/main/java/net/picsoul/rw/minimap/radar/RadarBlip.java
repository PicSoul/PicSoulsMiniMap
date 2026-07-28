package net.picsoul.rw.minimap.radar;

/**
 * One npc (animal/human/monster) as shown on the radar overlay: a world
 * position, an optional facing direction, a fallback tint color (used only
 * when no custom icon file is found — see {@link RadarScanner}), and the
 * classification flags the overlay needs to pick which icon file to try
 * ({@code <name>_saddled.png} / {@code <name>_hostile.png} / {@code <name>.png}).
 */
public record RadarBlip(double x, double z, float facingDeg, boolean hasFacing, int color,
                         String iconKey, boolean saddled, boolean hostile, boolean isChild) {
}
