package net.picsoul.rw.minimap.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's own minimap preferences - unlike {@link MinimapConfig} (which
 * holds server-wide rendering/tuning knobs shared by every connected player),
 * every field here is genuinely per-player: each connecting player gets their
 * own instance, loaded from and saved to their own file
 * (see {@code PicSoulsMiniMap.playerPrefsFile}, keyed by {@code Player.getUID()}).
 *
 * <p>Defaults here match {@link MinimapConfig}'s old (now-removed) values for
 * these same fields, so a brand-new player sees identical behavior to before
 * this per-player split.
 */
public class PlayerPreferences {

    public boolean waypointPrivacy = true;
    public int defaultZoomCells = 96;
    public String zoomInKeyName = "PageUp";
    public String zoomOutKeyName = "PageDown";
    public int minimapSizePx = 200;
    public MinimapConfig.Corner corner = MinimapConfig.Corner.TOP_LEFT;
    public boolean rotate = false;
    public boolean showOtherPlayers = true;
    public boolean contourEnabled = true;
    /** Hide this player's own position from every other connected player's
     *  minimap (the other-players-on-the-map feature, not waypoints/radar). */
    public boolean hiddenFromOthers = false;

    public static PlayerPreferences defaults() {
        return new PlayerPreferences();
    }

    /** Load from a key=value text file, same format/tolerance as the plugin's
     *  other settings files. Missing/unreadable file leaves defaults in place. */
    public static PlayerPreferences load(Path file) {
        PlayerPreferences p = new PlayerPreferences();
        try {
            if (file == null || !Files.exists(file)) return p;
            for (String line : Files.readAllLines(file)) {
                String s = line.trim().toLowerCase();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String key = s.substring(0, eq).trim();
                String val = s.substring(eq + 1).trim();
                boolean on = val.equals("on") || val.equals("true") || val.equals("1");
                switch (key) {
                    case "wpprivacy" -> p.waypointPrivacy = on;
                    case "zoom" -> {
                        try { p.defaultZoomCells = Integer.parseInt(val); }
                        catch (NumberFormatException ignored) { }
                    }
                    case "zoominkey" -> { if (!val.isEmpty()) p.zoomInKeyName = val; }
                    case "zoomoutkey" -> { if (!val.isEmpty()) p.zoomOutKeyName = val; }
                    case "mapsize" -> {
                        try { p.minimapSizePx = Integer.parseInt(val); }
                        catch (NumberFormatException ignored) { }
                    }
                    case "corner" -> {
                        try { p.corner = MinimapConfig.Corner.valueOf(val.toUpperCase()); }
                        catch (IllegalArgumentException ignored) { }
                    }
                    case "rotate" -> p.rotate = on;
                    case "players" -> p.showOtherPlayers = on;
                    case "contour" -> p.contourEnabled = on;
                    case "hidden" -> p.hiddenFromOthers = on;
                    default -> { }
                }
            }
        } catch (Throwable t) {
            System.out.println("[PicSoulsMiniMap] could not load player prefs " + file + ": " + t.getMessage());
        }
        return p;
    }

    /** Persist to a key=value text file, creating parent directories if needed. */
    public void save(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# PicSoulsMiniMap per-player settings (see /mm settings) - this file"
                    + " belongs to one player, keyed by their UID; do not copy between players.");
            lines.add("wpprivacy=" + (waypointPrivacy ? "on" : "off"));
            lines.add("zoom=" + defaultZoomCells);
            lines.add("zoominkey=" + zoomInKeyName);
            lines.add("zoomoutkey=" + zoomOutKeyName);
            lines.add("mapsize=" + minimapSizePx);
            lines.add("corner=" + corner.name());
            lines.add("rotate=" + (rotate ? "on" : "off"));
            lines.add("players=" + (showOtherPlayers ? "on" : "off"));
            lines.add("contour=" + (contourEnabled ? "on" : "off"));
            lines.add("hidden=" + (hiddenFromOthers ? "on" : "off"));
            Files.write(file, lines);
        } catch (IOException t) {
            System.out.println("[PicSoulsMiniMap] could not save player prefs " + file + ": " + t.getMessage());
        }
    }
}
