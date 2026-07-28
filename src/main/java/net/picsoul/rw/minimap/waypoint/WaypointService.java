package net.picsoul.rw.minimap.waypoint;

import java.io.File;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.risingworld.api.Plugin;
import net.risingworld.api.World;
import net.risingworld.api.database.Database;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Reads the player-visible map markers (waypoints) from the loaded world's
 * {@code Maps.db → mapmarkers} table.
 *
 * <p><b>Why this is safe while the game is running:</b> {@code Maps.db} is in
 * SQLite <b>WAL</b> journal mode, so a second read connection never blocks (and
 * is never blocked by) the game's own writer. We open one cached read connection
 * via {@link Plugin#getSQLiteConnection(String)} (Maps.db is <i>not</i> exposed
 * through {@code getWorldDatabase}, which only covers World/Players/Meta/…), set
 * a short {@code busy_timeout}, and only ever run SELECTs. Reloads are throttled
 * to a few seconds — never on the per-frame path — and any error keeps the last
 * good snapshot rather than disturbing the render loop.
 */
public final class WaypointService {

    private static final String TAG = "[PicSoulsMiniMap]";

    private final Plugin plugin;
    private final MinimapConfig config;

    private Database db;
    private boolean dbUnavailable = false;
    private boolean tableExists = false;
    private boolean loggedReadError = false;

    private volatile List<MapMarker> markers = Collections.emptyList();
    private long lastRefreshNs = 0L;

    public WaypointService(Plugin plugin, MinimapConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** The most recently loaded markers (immutable). Never null. */
    public List<MapMarker> getMarkers() {
        return markers;
    }

    /** Main-thread: reload at most once per {@code waypointRefreshSeconds}. */
    public void maybeRefresh() {
        if (!config.mapDbReads) return; // diagnostics kill-switch (/mm mapdb)
        long now = System.nanoTime();
        long intervalNs = (long) (Math.max(0.25f, config.waypointRefreshSeconds) * 1_000_000_000.0);
        if (lastRefreshNs != 0L && (now - lastRefreshNs) < intervalNs) return;
        lastRefreshNs = now;
        refreshNow();
    }

    /** Force an immediate reload (e.g. from /mm waypoints refresh). */
    public void refreshNow() {
        Database d = connection();
        if (d == null || !tableExists) return;
        try {
            List<MapMarker> out = new ArrayList<>();
            try (ResultSet rs = d.executeQuery(
                    "SELECT id,type,playerdbid,name,x,z,color,iconid,scalex FROM mapmarkers")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    float[] c = parseColor(rs.getString("color"));
                    float scale = (float) rs.getDouble("scalex");
                    if (rs.wasNull() || scale <= 0f) scale = 1f; // absent/zero -> 100%
                    out.add(new MapMarker(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getInt("playerdbid"),
                            name == null ? "" : name,
                            rs.getDouble("x"),
                            rs.getDouble("z"),
                            c[0], c[1], c[2], c[3],
                            rs.getInt("iconid"),
                            scale));
                }
            }
            markers = Collections.unmodifiableList(out);
        } catch (Exception e) {
            if (!loggedReadError) {
                loggedReadError = true;
                System.out.println(TAG + "[waypoints] read failed (keeping last set): " + e.getMessage());
            }
        }
    }

    private Database connection() {
        if (!config.mapDbReads) return null; // diagnostics kill-switch (/mm mapdb)
        if (db != null) return db;
        if (dbUnavailable) return null;
        try {
            File folder = World.getWorldFolder();
            if (folder == null) return null; // world not ready yet; retry next time
            String path = folder.getAbsolutePath() + File.separator + "Maps.db";
            if (!new File(path).exists()) {
                dbUnavailable = true;
                System.out.println(TAG + "[waypoints] Maps.db not found at " + path);
                return null;
            }
            db = plugin.getSQLiteConnection(path);
            try { db.execute("PRAGMA busy_timeout=200;"); } catch (Exception ignored) { }
            try (ResultSet rs = db.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='mapmarkers'")) {
                tableExists = rs.next();
            }
            if (!tableExists) {
                System.out.println(TAG + "[waypoints] table 'mapmarkers' not present in Maps.db");
            } else {
                System.out.println(TAG + "[waypoints] connected to " + path);
            }
            return db;
        } catch (Exception e) {
            dbUnavailable = true;
            System.out.println(TAG + "[waypoints] cannot open Maps.db: " + e.getMessage());
            return null;
        }
    }

    /** Parse {@code #RRGGBBAA} (or {@code #RRGGBB}) into 0..1 rgba; yellow on failure. */
    static float[] parseColor(String s) {
        try {
            if (s != null && s.charAt(0) == '#') {
                String h = s.substring(1);
                if (h.length() >= 6) {
                    int r = Integer.parseInt(h.substring(0, 2), 16);
                    int g = Integer.parseInt(h.substring(2, 4), 16);
                    int b = Integer.parseInt(h.substring(4, 6), 16);
                    int a = (h.length() >= 8) ? Integer.parseInt(h.substring(6, 8), 16) : 255;
                    return new float[]{r / 255f, g / 255f, b / 255f, a / 255f};
                }
            }
        } catch (Exception ignored) { }
        return new float[]{1f, 0.88f, 0.2f, 1f};
    }

    public void close() {
        if (db != null) {
            try { db.close(); } catch (Exception ignored) { }
            db = null;
        }
    }
}
