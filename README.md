# PicSouls MiniMap

An Xaero-style minimap plugin for the Unity version of **Rising World**.

## Features

- **Rendered terrain map** — real material colors (measured from the game's
  own texture pickers), hill-shading, water depth blending, optional
  topographic contour lines, and a sharp-edged overlay for player-built
  construction blocks.
- **Item-tiered unlocks** — the map upgrades as you equip/own items: a map
  gives the basic terrain view; a compass adds cardinal directions, waypoints,
  and a spawn-point line; a watch adds the in-game time; a calendar
  (permanently, once owned) adds the date; the upgraded compass
  (`compassmodern`) additionally unlocks the entity radar.
- **Waypoints & spawn point** — reads the game's own map markers and draws a
  line to your current spawn, with configurable privacy.
- **Cave detection & cave mode** — a real hole in the terrain (sinkhole, dug
  shaft, cave mouth) shows as an actual opening instead of fictitious surface,
  and the map automatically switches to a local voxel view of your
  surroundings while underground.
- **Trees**, shaped and colored by species/size, with small colored dots on
  fruit trees that currently have pickable fruit.
- **Entity radar** — nearby animals and npcs shown as blips, classified by
  threat level (hostile/defensive/passive) and saddled state for mounts, with
  support for full-color custom icons per species (drop a PNG in the plugin's
  `icons/` folder) and dead-reckoned smooth movement between scans.
- **Other players** shown on the map, always visible, name included.
- **In-game settings panel** (`/mm settings`, or press **F1**) — rebind zoom
  keys, resize the map and entity icons, move the map to any screen corner,
  toggle rotation/contour lines, and reset everything to defaults.

See [`command_list.txt`](command_list.txt) for the full `/mm` command
reference, and [`CHANGELOG.md`](CHANGELOG.md) for the version-by-version
development history.

## Install (players / server owners)

1. Download `PicSoulsMiniMap.jar` from the [Releases](../../releases) page.
2. Copy it into a folder named after the plugin under the game's `Plugins`
   directory:
   ```
   <Rising World install>/Plugins/PicSoulsMiniMap/PicSoulsMiniMap.jar
   ```
3. Start (or restart) the game with plugins enabled.
4. Equip a map — the minimap should appear. See `command_list.txt` for every
   other tier/command.

Custom radar icons (optional): drop PNG files named after the npc (see
`/mm npcs` in-game for the full list of internal names) into
`Plugins/PicSoulsMiniMap/icons/`, e.g. `cow.png`. No restart needed if the
species hasn't been seen yet this session.

## Build (developers)

Requires **JDK 20** and the Rising World Plugin API jar that ships with the
game.

1. Point Gradle at your own copy of the SDK by editing `gradle.properties`:
   ```properties
   rw.sdk=<path to your Rising World install>/Data/SDK/PluginAPI.jar
   ```
2. Build:
   ```
   gradle jar
   ```
3. The plugin jar is written to `build/libs/PicSoulsMiniMap.jar`.

See [`CLAUDE.md`](CLAUDE.md) for the current architecture overview and
[`RisingWorld_MiniMap_Design.md`](RisingWorld_MiniMap_Design.md) for the
original design document.

## License

[MIT](LICENSE)

## Project layout

```
src/main/java/net/picsoul/rw/minimap/
├── PicSoulsMiniMap.java     # main class: lifecycle, commands, events
├── config/                  # all tunables (in-code defaults)
├── capability/              # item-based tier unlocks
├── session/                 # per-player state
├── ui/                      # the HUD, settings panel, marker/waypoint overlay
├── render/                  # terrain tile rendering pipeline
├── radar/                   # entity/npc scanning + classification
└── waypoint/                # reads the game's own map markers
```
