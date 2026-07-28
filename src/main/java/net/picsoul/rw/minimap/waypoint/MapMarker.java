package net.picsoul.rw.minimap.waypoint;

/**
 * One marker read from the game's own map database (Maps.db → table
 * {@code mapmarkers}). Coordinates are world X/Z (same space as player position);
 * color is pre-parsed from the stored {@code #RRGGBBAA} string into 0..1 floats.
 *
 * <p>{@code type} is the marker's visibility class as the game stores it —
 * {@code "1"} = <b>Global</b> (public, visible to everyone) and {@code "0"} =
 * <b>Default</b> (private to the player who made it). {@code playerDbId} is the
 * database id of the player who created the marker (the game's {@code playerdbid}
 * column). {@code scale} is the marker's size multiplier ({@code scalex} in the DB;
 * 1.0 = 100%, 0.5 = 50%, 2.0 = 200%).
 */
public record MapMarker(long id, String type, int playerDbId, String name,
                        double x, double z,
                        float r, float g, float b, float a,
                        int iconId, float scale) {

    /** Global (public) markers have {@code type == "1"}; anything else is private. */
    public boolean isGlobal() {
        return "1".equals(type);
    }
}
