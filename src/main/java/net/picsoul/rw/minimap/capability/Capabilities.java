package net.picsoul.rw.minimap.capability;

/**
 * The set of minimap tiers a player currently has, derived from equipped/owned
 * items:
 * <ul>
 *   <li>{@code map}      — the default map is equipped → basic minimap (terrain + coords).</li>
 *   <li>{@code compass}  — a compass is equipped → cardinal directions, waypoints, spawn line.
 *       Covers both compassold and compassmodern.</li>
 *   <li>{@code watch}    — a pocket watch is equipped → in-game time under the coords.</li>
 *   <li>{@code calendar} — a calendar has ever been owned → in-game date.</li>
 *   <li>{@code radar}    — the upgraded compassmodern specifically is equipped → nearby
 *       animals/NPCs shown as blips on the map. compassold does not grant this.</li>
 * </ul>
 */
public record Capabilities(boolean map, boolean compass, boolean watch, boolean calendar, boolean radar) {
}
