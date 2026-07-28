package net.picsoul.rw.minimap.ui;

/** One other connected player, as shown on the map: world position, heading
 *  (for the marker's facing arrow), and display name. */
record OtherPlayerBlip(double x, double z, float headingDeg, String name) {
}
