# Rising World Mini-Map — Architecture & Design Document

**Target game:** Rising World (new Unity version)
**API:** Rising World Plugin API (Java, JDK 20) — **verified against your installed `PluginAPI.jar` (build dated 2026-05-13)**
**Inspiration:** Xaero's Minimap for Minecraft
**Scope for v1:** HUD minimap + zoom, waypoints, player/entity radar
**Status:** Design proposal for approval — no code written yet. All API claims below are confirmed against the actual SDK jar in your game folder (see Appendix A).
**Date:** 2026-07-21

---

## 1. Goals & Non-Goals

### Goals (this project)
A live, top-down minimap rendered on each player's HUD that:

- Shows the terrain around the player, colored by surface material and shaded by elevation.
- Displays a player arrow that rotates with the facing direction (with a "locked north" toggle).
- Supports zoom in/out via hotkeys.
- Shows a live coordinate + compass readout.
- Lets players create, name, color, edit and delete **waypoints**, drawn on the minimap with direction/distance, plus an automatic **death waypoint**.
- Shows a **radar** of other players (and optionally NPCs/animals) as dots within a configurable range.

### Non-goals (deferred to later phases)
- Fullscreen/world map (like Xaero's Worldmap) — explicitly deferred per your priorities, but the tile-cache design below is built so it can be added cheaply later.
- Cave/underground "slice" mode.
- Web-based or external companion map.

---

## 2. The API constraints that drive the whole design

These four facts about the Rising World Plugin API shape every architectural decision. Read this section first — the rest of the document follows from it.

### 2.1 Plugins run on the **server**, not the client
In the new API, plugin code executes server-side (in singleplayer, the local session acts as the server). The client downloads nothing. This has major consequences:

- **All rendering must be produced server-side and pushed to the client as UI.** We cannot run a custom shader or draw call on the client. We build the map as an *image* and/or a tree of *UI elements*, then send it to the player.
- **Per-player state.** Every connected player needs their own minimap instance (their own centered image, their own zoom level, their own waypoint visibility). State is kept in a per-player map keyed by player.
- **Bandwidth & tick budget matter.** In multiplayer, regenerating a large image every frame for every player is too expensive. The rendering strategy (Section 5) is designed around this.

### 2.2 The UI system: UI Toolkit flex, `UIElement` + `Style` + `TextureAsset`
The new UI API is a thin wrapper over Unity's UI Toolkit (flexbox-style layout). Confirmed building blocks:

- `UIElement` — the base container/panel (replaces the old `GuiElement`/`GuiPanel`/`GuiImage`). Public `style` and `hoverStyle` fields; `addChild`/`removeChild`, `setPosition`/`setSize`/`setPivot`, `setBorderEdgeRadius` (→ circular minimap via corner radius), `setVisible`, `setClickable`, and an `onClick` handler. Confirmed.
- `UILabel` — text (for coordinates, waypoint labels, compass). Confirmed.
- **`UIPainter2D` (extends `UIElement`) — an HTML5-canvas-style vector drawing surface.** Confirmed methods: `beginPath`/`moveTo`/`lineTo`/`arc`/`arcTo`/`bezierCurveTo`/`quadraticCurveTo`/`closePath`, `fill(FillRule)`, `stroke()`, `setFillColor`/`setStrokeColor`/`setLineWith`/`setLineCap`/`setLineJoin`, plus `clear()` and `update()`. **This is the ideal tool for every overlay marker** (player arrow, waypoint dots, radar dots, compass, off-screen chevrons) — sharp vector graphics redrawn cheaply, no per-marker child elements needed.
- Each element has a `Style` (CSS-inspired, all fields confirmed): `backgroundColor`, **`backgroundImage.set(TextureAsset)`**, `backgroundImageScaleMode`, `backgroundImageTintColor`, `rotate`, `translate`, `scale`, `transformOrigin`, plus full flex layout + border-radius + font fields. `rotate` + `transformOrigin` give us free map/arrow rotation.
- `TextureAsset` (in `net.risingworld.api.assets`) — **confirmed loaders: `loadFromFile(String)`, `loadFromPlugin(Plugin, String)`, `loadFromGame(String)`, `loadFromURL(String)`, and `load(byte[])`.** The `load(byte[])` variant means we can render a PNG in memory and hand the raw bytes straight to the UI with **zero disk I/O** — this is the terrain-image path. Risk R1 from the previous draft is fully resolved; no HTTP-server or element-grid fallback is needed.
- `player.addUIElement(UIElement)` and **`player.addUIElement(UIElement, UITarget)`** attach a root element to a specific HUD layer. Confirmed `UITarget` values include `HUD`, `HUDIndicators`, `HUDBars`, `HUDCrosshair`, `DeathScreen`, etc. `player.getScreenResolutionX()/Y()` allow resolution-aware sizing.
- Positioning: reference resolution 1920×1080, origin top-left, `setPosition`/`setSize` with a percent flag, plus `setPivot(Pivot.…)`. Good enough to anchor a minimap to any corner and keep it responsive.

### 2.3 World/terrain data: the `Chunk` class
This is what makes a real terrain map possible. Confirmed `Chunk` methods:

- `float[] getLODTerrain()` — flattened **32×32 elevation grid** for the chunk (one height sample per block column). This is the heightmap we shade.
- `float getLODSurfaceLevel(int x, int z, boolean includeWater)` — surface elevation at a local cell.
- `byte getLODSurfaceTexture(int x, int z)` — **surface material/texture id** at a local cell → this is our color lookup (grass, sand, stone, snow, dirt, …).
- `boolean containsWater()` — water presence (combine with surface level to paint water blue).
- `getAllPlants()`, `getAllObjects()`, `getAllConstructionElements()` — vegetation, furniture/objects, and player-built structures in the chunk (used later for richer map detail; v1 can optionally tint tree-dense cells).
- `getChunkPositionX()` / `getChunkPositionZ()`, and `SIZE_X` / `SIZE_Z` constants.

A chunk is 32×32 blocks in the horizontal plane. The LOD terrain + surface texture arrays give us a ready-made **32×32 pixel tile per chunk** — no voxel raymarching required for the surface view.

### 2.4 Player position, events, timers, input — all confirmed
- **Position/orientation** on `Player`: `getPosition()`/`getPreviousPosition()` (Vector3f), `getChunk()`, `getChunkPosition()` (Vector3i), `getBlockPosition()`, and crucially **`getHeading()`** (float compass heading) + **`getCardinalDirection()`** (N/NE/E…) + `getViewDirection()` + `getRotation()` (Quaternion). This resolves the old axis/yaw question (R2) — heading is handed to us directly.
- **Enumeration for radar:** `Server.getAllPlayers()` / `Server.getPlayer(id)` / `Server.findNearestPlayer()`; `World.getAllNpcs()` / **`World.getAllNpcsInRange(Vector3f, float)`** / `World.findNearestNpc()`; also `World.getAllVehiclesInRange(...)`. `Npc` exposes `getPosition()`, `getName()`, `getTypeID()`, `getDefinition()` (for hostile/passive coloring), `isDead()`. Resolves R5.
- **Input:** `Player.registerKeys(Key...)`, `setListenForKeyInput(boolean)`, `isKeyPressed(Key)`, `disableClientsideKeys(...)`, plus the **`PlayerKeyEvent`** (`getKey()`, `isPressed()`). The `Key` enum includes letters, `Numpad0-9`, `NumpadPlus/Minus`, `Minus`, `Period`, `Tab`, etc. Resolves R4.
- **Event model:** main class `extends Plugin implements Listener`; handler methods annotated `@EventMethod`; register with `registerEventListener(this)`. Lifecycle: `onEnable()` / `onDisable()` (and optional `onLoad()`).
- **Relevant events (all confirmed present):** `PlayerConnectEvent` / `PlayerSpawnEvent` (build the HUD), `PlayerDisconnectEvent` (tear down + persist), `PlayerChangePositionEvent` and **`PlayerChangeBlockPositionEvent`** (cheap chunk-crossing detection → when to re-composite), `PlayerCommandEvent` (waypoint commands), `PlayerKeyEvent` (zoom & toggles), **`PlayerDeathEvent`** (+`Cause`) and `PlayerRespawnEvent` (death waypoint), and `NpcSpawnEvent`/`NpcDeathEvent` if we want live radar updates.
- **Timers/threading:** the **`Timer`** class (`new Timer(initialDelay, interval, repetitions, Runnable)` with `start()`/`pause()`/`kill()`) drives the refresh loop. `Plugin.executeDelayed(float, Runnable)` for one-shots, `Plugin.enqueue(Runnable)` to marshal work back onto the main thread, and `Plugin.isMainThread()` to check — exactly what we need for async tile rendering (Section 5.6).

---

## 3. Feature scope for v1

| Feature | Included | Notes |
|---|---|---|
| HUD minimap (terrain, colored + elevation-shaded) | ✅ | Core. Corner-anchored, circular or square. |
| Zoom in/out | ✅ | Hotkeys; N discrete zoom levels (chunks visible). |
| Rotating vs. locked-north minimap | ✅ | Toggle hotkey. |
| Player arrow + coordinate/compass readout | ✅ | Live. |
| Waypoints (create/edit/delete/color/name) | ✅ | Commands + optional simple UI; persisted per world. |
| Death waypoint | ✅ | Auto-created on death, replaces previous. |
| Player radar (other players as dots) | ✅ | Range-limited; on/off. |
| NPC/animal radar (dots) | ✅ (optional toggle) | Same mechanism; off by default for performance. **Per-type NPC icons planned — see §9.1.** |
| **Map unlock gate (craft-to-earn)** | ✅ (core concept) | Minimap stays hidden until the player crafts the in-game paper map. Gate logic in from M0; full wiring at M3.5. See §9.5. |
| Fullscreen world map | ❌ (Phase 4) | Tile cache makes this a natural add-on. |
| Cave/underground mode | ❌ (later) | Would use `getChunkPart` voxel slices. |

---

## 4. High-level architecture

```
                         ┌────────────────────────────────────────┐
                         │          PicSoulsMiniMap                 │
                         │  (extends Plugin, implements Listener)   │
                         │  lifecycle, event wiring, global timer   │
                         └───────────────┬──────────────────────────┘
                                         │ owns
        ┌────────────────────────────────┼─────────────────────────────────┐
        │                                │                                  │
┌───────▼────────┐          ┌────────────▼───────────┐          ┌───────────▼─────────┐
│  MapRenderer   │          │   PlayerSession (per   │          │  Config / I18n /    │
│  terrain→image │          │   connected player)    │          │  Persistence layer  │
│  + TileCache   │          │  HUD elements, zoom,   │          │  (JSON/YAML files)  │
└───────┬────────┘          │  view mode, overlays   │          └─────────────────────┘
        │ uses              └───────┬────────────────┘
┌───────▼────────┐                  │ composes
│   TileCache    │          ┌───────▼────────┬─────────────┬──────────────┐
│ chunk→PNG tile │          │  MinimapView   │ WaypointMgr │  RadarService │
│  LRU, dirty    │          │ (UIElements)   │             │               │
└────────────────┘          └────────────────┴─────────────┴──────────────┘
```

**Responsibilities**

- **PicSoulsMiniMap** (main class) — bootstrap, register listeners, own the global refresh timer, hold the registry of `PlayerSession`s.
- **TileCache** — converts each `Chunk` into a cached 32×32 (or upscaled) PNG "tile"; invalidates tiles when terrain changes (`PlayerDestroyTerrainEvent` / build events); LRU eviction.
- **MapRenderer** — given a player's world position + zoom, composites the needed tiles into one minimap image (with rotation if not north-locked) and hands the resulting `TextureAsset` to the view.
- **PlayerSession** — everything per-player: the HUD `UIElement` tree, current zoom level, view mode (rotating/locked), radar on/off, and the set of overlay marker elements. Created on connect, destroyed on disconnect.
- **MinimapView** — the UI: frame, map-image element, player arrow, compass, coordinate label, and marker child-elements.
- **WaypointManager** — CRUD + persistence of waypoints (per world, optionally per player), and projecting them onto the minimap each refresh.
- **RadarService** — each refresh, gathers nearby players/entities within range and projects them to marker positions.
- **Config/Persistence** — plugin config (default zoom, corner, size, colors, radar range, keybinds) and saved data (waypoints, per-player prefs).

---

## 5. Rendering pipeline (the hard part)

The central problem: we must produce a top-down picture server-side and get it onto the HUD efficiently. The approach is **tile caching + compositing**, with markers as separate lightweight UI elements layered on top.

### 5.1 Terrain → tile
For each chunk we build a 32×32 pixel image:

1. Read `getLODTerrain()` (elevation) and `getLODSurfaceTexture(x,z)` (material id) for the 32×32 cells.
2. Map each material id → a base color via a **material→color table** (grass=green, sand=tan, stone=grey, snow=white, dirt=brown, water handled separately, etc.). This table is data-driven (config file) so colors are tweakable without recompiling.
3. Apply **hill-shading**: compute a slope/normal from neighboring elevation samples and lighten/darken the base color. This is what makes the map read as 3D terrain rather than flat color blobs — exactly the look Xaero's map has.
4. Paint water: where `containsWater()` and the water level is above terrain, blend toward a blue tone.
5. (Optional, cheap) tint cells that contain dense vegetation from `getAllPlants()` to hint forests.

Result: one `BufferedImage` (Java2D) per chunk = a **tile**.

### 5.2 TileCache
- Key: chunk coordinate `(cx, cz)`.
- Value: rendered tile (kept as a `BufferedImage`, and its PNG-on-disk / `TextureAsset` when composited).
- **Dirty invalidation:** listen to terrain/build/destroy events (`PlayerDestroyTerrainEvent`, construction add/remove) and mark the affected chunk's tile dirty so it re-renders next time it's needed.
- **LRU eviction** with a configurable cap (e.g. a few hundred tiles) to bound memory.
- Tiles for chunks not yet loaded on the server are skipped (drawn as "unexplored" dark cells) until that chunk streams in.

### 5.3 Compositing the minimap
Per refresh, per player:

1. Determine the player's chunk and the offset within it.
2. Determine how many chunks the current **zoom level** needs (e.g. zoom 0 → 3×3 chunks ≈ 96×96 blocks; zoom 3 → 9×9 chunks). Zoom = number of blocks-per-minimap-pixel / chunks visible.
3. Blit the relevant cached tiles into one composite `BufferedImage` centered on the player.
4. If **rotating mode**: rotate the composite by the player's yaw so "forward" is up; if **locked-north**: skip rotation and rotate only the player arrow instead.
5. Crop to the minimap's shape (square, or circular via `setBorderEdgeRadius` on the container with `overflow` clipped) and scale to the on-screen size.
6. Encode the composite to PNG bytes in memory (`ImageIO.write` → `ByteArrayOutputStream`) and hand them straight to the client via **`TextureAsset.load(byte[])`**, then `mapImageElement.style.backgroundImage.set(texture)`. **No disk I/O.** (`loadFromFile`/`loadFromPlugin` remain available for static assets like icons.)

### 5.4 Markers layer — one `UIPainter2D` overlay (no per-marker elements)
Player arrow, waypoints, radar dots, compass and off-screen indicators are all drawn onto a **single `UIPainter2D`** canvas layered over the map image. Each refresh we `clear()` and redraw — sharp vector graphics, no growing UI tree, cheap:

- **Player arrow:** triangle at map center via `moveTo`/`lineTo`/`fill`; rotated by heading (rotating mode) or drawn straight while the *map image* rotates (locked-north mode).
- **Waypoint markers:** for each visible waypoint, project world (x,z) → minimap pixel offset; inside the map area → filled dot (`arc`+`fill`) in the waypoint color + a small `UILabel`; outside → clamp to the map edge as a directional chevron with distance text (Xaero-style off-screen indicator).
- **Radar dots:** same projection, styled per type (players vs. NPCs, hostile vs. passive via `Npc.getDefinition()`); refreshed from `RadarService`.

Because this is one canvas redrawn per tick (not N child elements created/destroyed), it stays crisp and fast even at high refresh rates. `UILabel`s are still used for text (coordinates, compass letters, waypoint names).

> Note: the confirmed `TextureAsset.load(byte[])` path removes the need for the HTTP-server / element-grid fallbacks that the previous draft carried as risk mitigations. They are dropped.

### 5.5 Alternative considered (not chosen)
A fully vector map (drawing terrain itself with `UIPainter2D` instead of a raster image) was considered. Rejected for v1: a raster tile scales better to fine terrain detail and hill-shading, and `load(byte[])` makes it cheap. `UIPainter2D` is reserved for the overlay, where vector is the right tool.

### 5.6 Update cadence & performance budget
- **Terrain image:** refresh only when needed — driven by `PlayerChangeBlockPositionEvent` (chunk crossing), zoom/mode changes, or a slow `Timer` (e.g. every 500 ms–1 s). Not every frame.
- **Markers:** redraw the `UIPainter2D` on a fast `Timer` (e.g. 5–10 Hz) or on position change — cheap.
- **Tile rendering:** done once per chunk and cached; a moving player mostly reuses tiles and renders only the 1–2 new edge tiles.
- **Multiplayer:** tiles are shared across all players (same world), so cost scales with explored area, not player count. Only compositing + overlay redraw are per-player.
- **Threading:** tile/composite rendering (Java2D `BufferedImage` + PNG encode) runs on a worker thread; the finished `TextureAsset.load(byte[])` + `backgroundImage.set()` is marshalled back to the main thread with `Plugin.enqueue(...)`, so the game tick never blocks. `Plugin.isMainThread()` guards correctness.

---

## 6. Coordinate systems & math

Three spaces, with explicit conversions in a `MapProjection` helper:

1. **World space** (blocks): player at `(wx, wy, wz)`. Orientation comes straight from `Player.getHeading()` (no axis-convention guessing needed); one in-game check confirms rotation sign.
2. **Chunk space:** `cx = floor(wx / 32)`, local `lx = wx - cx*32` (same for z). Tiles are indexed in chunk space.
3. **Minimap space** (pixels): center = player. For a world point `(wx,wz)`:
   `dx = (wx - playerX) / blocksPerPixel`, `dz = (wz - playerZ) / blocksPerPixel`.
   Apply rotation by yaw θ if rotating mode:
   `px = cos θ·dx − sin θ·dz`, `py = sin θ·dx + cos θ·dz`.
   Then offset to the map's on-screen center. Points with `|·| > radius` are off-screen → edge-clamp for markers.

`blocksPerPixel` is derived from zoom level and minimap pixel size. Distance readouts use plain Euclidean world distance.

---

## 7. Class-by-class design

```
net.picsoul.rw.minimap
├── PicSoulsMiniMap         // main class: extends Plugin implements Listener; bootstrap + timers
├── session
│   ├── PlayerSession        // per-player state (zoom, mode, radar flag, view, prefs)
│   └── SessionRegistry      // player -> PlayerSession
├── render
│   ├── MapRenderer          // composite tiles -> TextureAsset for a session
│   ├── TileCache            // (cx,cz) -> tile; dirty + LRU
│   ├── TileRenderer         // Chunk -> BufferedImage (color table + hillshade)
│   ├── MaterialColors       // surface-texture-id -> color (data-driven)
│   └── MapProjection        // world<->chunk<->minimap math
├── ui
│   ├── MinimapView          // builds/updates the HUD UIElement tree
│   ├── MarkerLayer          // arrow, waypoint dots, radar dots, edge indicators
│   └── HudTheme             // sizes, corners, colors, fonts from config
├── waypoint
│   ├── Waypoint             // id, name, x,y,z, color, world, owner, type(user/death)
│   ├── WaypointManager      // CRUD + projection + persistence
│   └── WaypointCommands     // /wp add|list|remove|... command parsing
├── radar
│   ├── RadarService         // nearby players/entities within range
│   └── IconRegistry         // (post-v1) NPC typeID -> icon TextureAsset; dot fallback
├── unlock
│   └── UnlockService        // craft-to-earn gate: PlayerCraftItemEvent detect + per-UID persisted unlock
├── config
│   ├── MinimapConfig        // defaults: corner, size, zoom levels, keybinds, colors
│   └── Persistence          // load/save JSON/YAML in plugin data dir
└── events
    └── EventHandlers        // @EventMethod handlers, delegate to services
```

---

## 8. Waypoints subsystem

**Data model** (`Waypoint`): id, display name, world coords (x,y,z), color (RGB), world name, owner (player uid, or "shared"), type (`USER` / `DEATH` / `SPAWN`), visible flag.

**Interaction (v1):**
- Chat commands (simplest, robust): `/wp add <name> [color]` (uses current position), `/wp list`, `/wp remove <name>`, `/wp goto <name>` (shows distance/heading), `/wp color <name> <color>`, `/wp hide|show <name>`.
- Optional lightweight UI: a `UIElement` panel listing waypoints with color swatches and delete buttons (`onClick`), openable via a hotkey. Commands ship first; the panel is a fast follow.

**Death waypoint:** on the player death event, auto-create/replace a `DEATH`-type waypoint at the death location (distinct icon/color). Cleared when the player reaches it or on next death.

**Rendering:** `WaypointManager` provides the visible set to `MarkerLayer` each refresh; on-map dots when in range, edge chevrons + distance when out of range.

**Persistence:** saved to `waypoints.json` in the plugin data dir, namespaced by world (and by owner for private waypoints). Loaded on enable, saved on change + on disable.

---

## 9. Radar subsystem

`RadarService` runs each marker refresh:

- Gather all connected players (`getAllPlayers`) and, if enabled, nearby NPCs/animals.
- Filter to those within the configured world-space range of the viewing player.
- Project each to minimap space via `MapProjection`; hand to `MarkerLayer` as dots (players and entities styled differently; optional name labels for players).
- Config: `radarRange` (blocks), `showPlayers` (default on), `showNpcs` (default off), `showEntityNames`.
- Multiplayer etiquette: an optional server setting to disable the player-radar (some servers consider seeing other players "cheating") — respected globally.

### 9.1 Planned: per-type NPC icons (future)
Instead of generic dots, each NPC type will show a custom icon on the minimap. The API already supports this cleanly: `Npc.getTypeID()` (short) and `Npc.getDefinition()` identify the creature type, and `TextureAsset.loadFromPlugin(this, "icons/<type>.png")` loads a bundled icon per type. Design so the radar layer maps `typeID → TextureAsset icon`, falling back to a colored dot when no icon is defined. This becomes a small `IconRegistry` (data-driven `typeID → icon file`), so adding art for a new creature is drop-in with no code change. Icons are drawn as small child `UIElement`s (background image) rather than on the `UIPainter2D` vector layer, since they're textured sprites. Slated for the post-v1 icon pass; v1 ships dots and leaves the `typeID` plumbing in place so the upgrade is additive.

### 9.5 Map unlock gate — earn the minimap (craft-to-reveal)
**Goal:** the minimap is not a hand-out. A player sees no minimap until they craft the in-game paper map, at which point it unlocks and appears — making it feel like a reward.

**Detection hook (confirmed API):** the `PlayerCraftItemEvent` fires when a player crafts something; `event.getItem()` gives an `Item` with `getName()` and `getTypeID()`. On craft, we compare against the configured map item (by type-id and/or name). A fallback/secondary check on `PlayerInventoryAddItemEvent` and an inventory scan (`Inventory.findItemByType(...)`) catches players who already have a map (e.g. obtained by trade/admin) so the gate is possession-based, not strictly craft-timed.

**Unlock state & persistence:** unlock is stored per player, keyed by UID, in the plugin's data store (a small `unlocked_players` set persisted to the plugin folder / SQLite via `getSQLiteConnection`). Once unlocked, a player stays unlocked across sessions. State lives server-side (authoritative), so it can't be spoofed by a client.

**Behavior on unlock:** when the gate flips from locked→unlocked mid-session, the `PlayerSession` builds and shows the HUD immediately (with an optional "Map acquired" status message + sound via `player.showStatusMessage` / `playSound`). On connect, `onEnable`/spawn checks the stored unlock state and only builds the minimap UI if unlocked.

**Config knobs:**
```
unlock:
  mode: CRAFT            # CRAFT = must craft the map; OWN = having it in inventory suffices; OFF = always on
  mapItemName: "Map"     # matched against Item.getName()  (exact id calibrated in-game, see below)
  mapItemTypeId: -1      # optional numeric type-id match; -1 = ignore, use name
  keepUnlockedIfMapLost: true   # once earned, stay unlocked even if the map item is dropped/consumed
  announceOnUnlock: true
```
`mode: OFF` (always-on) is the developer/testing default so we can build and test the renderer without crafting each run; the shipping default is `CRAFT`.

**Calibration note:** the exact type-id/name of Rising World's craftable paper map is confirmed empirically during M3.5 (log `PlayerCraftItemEvent.getItem().getName()`/`getTypeID()` when crafting one). Until then the gate matches by the configurable name.

**M0 stub:** the skeleton includes an `UnlockService` with `isUnlocked(player)` returning `true` when `unlock.mode = OFF` (dev default) and otherwise consulting a persisted set. The `PlayerCraftItemEvent` handler is wired but simply logs crafted item names, so M0 can be used to *discover* the real map item id in-game. Full gate enforcement lands at M3.5.

---

## 10. Controls & configuration

**Default keybinds** (all remappable in config):
- Zoom in / out — e.g. `+` / `-` (or PageUp/PageDown).
- Toggle rotating ↔ locked-north.
- Toggle minimap on/off.
- Toggle radar.
- Open waypoint panel.

Key input is captured via the plugin's key/input event; a small input-router maps keys → session actions.

**Config file (`config.yml` / JSON), with sane defaults:**
```
minimap:
  corner: TOP_LEFT
  shape: CIRCLE            # or SQUARE
  sizePx: 200
  defaultZoom: 1
  zoomLevels: [64, 96, 160, 256, 384]   # blocks visible across the map
  rotateWithPlayer: true
  refreshTerrainMs: 750
  refreshMarkersMs: 100
radar:
  showPlayers: true
  showNpcs: false
  rangeBlocks: 128
waypoints:
  maxPerPlayer: 100
  deathWaypoint: true
colors:
  grass: "#4a7a3a"
  sand:  "#c8b273"
  stone: "#7d7d7d"
  snow:  "#eef2f5"
  water: "#2f5d8a"
  # ... one entry per surface-texture id
```

---

## 11. Multiplayer & performance summary

- **Shared tiles**, per-player composites → cost scales with explored area, not player count.
- **Two-rate refresh** (slow terrain, fast markers) keeps it smooth without hammering the tick.
- **Async tile rendering** off the main thread; results swapped in atomically.
- **Bounded memory** via LRU tile cache.
- **Dirty invalidation** so edits show up on the map without full re-renders.
- Everything is per-player-toggleable so low-end clients/servers can scale it down.

---

## 12. Project structure, build & install

```
Mini_map/
├── src/main/java/net/picsoul/rw/minimap/…    // sources (packages as in §7)
├── src/main/resources/resources/
│   ├── plugin.yml                       // -> jar entry resources/plugin.yml (required)
│   ├── config.default.yml
│   └── materials.default.yml
├── build.gradle (or pom.xml / IntelliJ artifact)
└── dist/PicSoulsMiniMap.jar          // drop into  <RisingWorld>/Plugins/PicSoulsMiniMap/
```

- **Toolchain:** JDK 20 (bundled at `Data/Java/JDK`); API + javadoc at `Data/SDK`. Build with Gradle (or an IntelliJ artifact) that compiles against the SDK jar and packages the manifest + resources.
- **Install:** copy the jar into `<RisingWorld>/Plugins/PicSoulsMiniMap/`; restart the game (the in-game plugin reload is unreliable).
- **⚠ `plugin.yml` location (verified in the game's `Runtime.jar`):** the loader looks up the literal jar entry **`resources/plugin.yml`**. A root-level `plugin.yml` is *not* found and the plugin silently fails to load. Keep the file at jar path `resources/plugin.yml` (source: `src/main/resources/resources/plugin.yml`). It must contain `main:` pointing at `net.picsoul.rw.minimap.PicSoulsMiniMap`, plus name/version metadata.

---

## 13. Development roadmap (milestones)

**M0 — Skeleton & HUD proof-of-concept**
Buildable plugin, `plugin.yml`, lifecycle, per-player HUD panel with a placeholder + live coordinate/heading label. Includes the `UnlockService` stub (dev default = always unlocked) and a `PlayerCraftItemEvent` logger to discover the real map item id in-game. Confirms UI push works in-game.

**M1 — Terrain rendering**
`TileRenderer` (material colors + hillshade), `TileCache`, `MapRenderer` compositing centered on the player, static north-up. First real map on screen.

**M2 — Movement, zoom, rotation**
Recenter on move, zoom hotkeys, rotating vs. locked-north, player arrow, compass, distance readout. Async rendering + two-rate refresh.

**M3 — Waypoints**
Data model, persistence, commands, on-map dots + off-screen edge indicators, death waypoint. (Optional waypoint UI panel.)

**M3.5 — Map unlock gate**
`UnlockService` full enforcement: `PlayerCraftItemEvent` detection of the paper map, per-UID persisted unlock state, locked players get no minimap, unlock reveals it with an announcement. Calibrate the real map item id here. (§9.5)

**M4 — Radar**
`RadarService`, player dots (+ optional NPCs), range config, server toggle. Leaves `typeID` plumbing in place for icons.

**M5 — Polish & config**
Full config file, remappable keys, circular mask + framing, color-table tuning, memory/perf pass, docs.

**Post-v1 — Per-type NPC icons** (`IconRegistry`, bundled icon art per NPC type; §9.1) and **Fullscreen world map** (reuses TileCache directly).

Each milestone is independently testable in-game and ends with a verification step (visual check + a stress test moving fast across chunk borders).

---

## 14. Risk status — verified against your installed SDK

Every open question from the first draft has now been checked against `PluginAPI.jar` (build 2026-05-13). Status:

- **R1 — Image loading. ✅ RESOLVED.** `TextureAsset.load(byte[])`, `loadFromFile`, `loadFromPlugin`, `loadFromGame`, `loadFromURL` all exist, plus `Style.backgroundImage.set(TextureAsset)`. In-memory PNG bytes → UI, no disk I/O. Fallbacks dropped.
- **R2 — Axis/heading. ✅ RESOLVED.** `Player.getHeading()` (float) and `getCardinalDirection()` handed to us directly; `getViewDirection()` + `getRotation()` available for fine control. One quick in-game calibration confirms rotation sign.
- **R3 — Surface-texture ids. ◑ MINOR.** `getLODSurfaceTexture(x,z)` returns a `byte` id; the exact id→material mapping isn't documented, so the color table is finalized by a short empirical pass in-game (fly over grass/sand/stone/snow, log ids). Not a blocker — the table is data-driven config.
- **R4 — Key input. ✅ RESOLVED.** `Player.registerKeys(Key...)` + `setListenForKeyInput` + `PlayerKeyEvent(getKey/isPressed)`; full `Key` enum (letters, numpad, +/− etc.).
- **R5 — Entity enumeration. ✅ RESOLVED.** `Server.getAllPlayers()`; `World.getAllNpcsInRange(Vector3f, float)` and `getAllNpcs()`; `Npc.getPosition/getName/getTypeID/getDefinition/isDead`.
- **R6 — Image update cost. ◑ TUNE.** Only remaining empirical unknown: how frequently `backgroundImage.set()` can be swapped comfortably. Mitigated by design (terrain refreshes only on chunk-cross/zoom, overlay is separate `UIPainter2D`); we tune the `Timer` interval during M2 with a fast-movement stress test.

Net: **no blockers remain.** The only two items are a color-table calibration and a refresh-rate tuning pass, both done naturally during implementation.

---

## Appendix A — Verified API reference (from `PluginAPI.jar`, build 2026-05-13)

Exact signatures confirmed by decompiling the SDK jar in your game folder. These are what the implementation will call.

**Lifecycle / infra — `net.risingworld.api.Plugin`**
`onEnable()`, `onDisable()`, `onLoad()`; `getPath()`; `registerEventListener(Listener)`; `executeDelayed(float, Runnable)`; `enqueue(Runnable)`; `isMainThread()`; `getSQLiteConnection(String)`; `getWorldDatabase(...)`.

**Timers — `net.risingworld.api.Timer`**
`new Timer(float initialDelay, float interval, int repetitions, Runnable)`; `start()`, `pause()`, `kill()`, `setInterval(float)`, `isActive()`.

**Player — `net.risingworld.api.objects.Player`**
`getPosition()`→Vector3f, `getPreviousPosition()`, `getChunk()`, `getChunkPosition()`→Vector3i, `getBlockPosition()`; `getHeading()`→float, `getCardinalDirection()`→String, `getViewDirection()`, `getRotation()`→Quaternion; `isInCave()`, `isIndoor()`, `isUnderwater()`; `addUIElement(UIElement)`, `addUIElement(UIElement, UITarget)`, `removeUIElement(...)`, `getScreenResolutionX()/Y()`; `registerKeys(Key...)`, `setListenForKeyInput(boolean)`, `isKeyPressed(Key)`; `sendTextMessage(String)`, `showColorPicker(...)`, `showRadialMenu(TextureAsset[], ...)`.

**World (static) — `net.risingworld.api.World`**
`getName()`, `getSeed()`, `getWorldFolder()`→File; `getChunk(int cx, int cz)`→Chunk; `getAllNpcs()`, `getAllNpcsInRange(Vector3f, float)`, `findNearestNpc(...)`; `getAllVehiclesInRange(Vector3f, float)`.

**Server (static) — `net.risingworld.api.Server`**
`getAllPlayers()`→Player[], `getPlayer(int)`, `findNearestPlayer(Vector3f)`, `getPlayerCount()`, `getGameTime(...)`, `broadcastTextMessage(String)`.

**Chunk — `net.risingworld.api.objects.world.Chunk`**
`SIZE_X`, `SIZE_Z`; `getLODTerrain()`→float[], `getLODSurfaceLevel(int x,int z,boolean water)`→float, `getLODSurfaceTexture(int x,int z)`→byte, `containsWater()`; `getTerrainIndex(int,int)` (flattening helper); `getAllPlants()`, `getAllObjects()`, `getAllConstructionElements()`; `getChunkPositionX()/Z()`; `getChunkPart(int cy)`.

**NPC — `net.risingworld.api.objects.Npc`**
`getPosition()`, `getName()`, `getTypeID()`→short, `getDefinition()`→NpcDefinition, `getHealth()`, `isDead()`.

**UI — `net.risingworld.api.ui.*`**
`UIElement` (public `style`/`hoverStyle`, `addChild`, `setPosition`/`setSize`/`setPivot`, `setBorderEdgeRadius`, `setVisible`, `setClickable`, `onClick`); `UILabel`; `UITextField`; `UIScrollView`; **`UIPainter2D`** (`beginPath`, `moveTo`, `lineTo`, `arc`, `bezierCurveTo`, `closePath`, `fill(FillRule)`, `stroke()`, `setFillColor`, `setStrokeColor`, `setLineWith`, `clear()`, `update()`); `UITarget.{HUD, HUDIndicators, HUDBars, DeathScreen, …}`.

**Style — `net.risingworld.api.ui.style.Style`**
Fields: `backgroundColor`, `backgroundImage` (`.set(TextureAsset)`), `backgroundImageScaleMode`, `backgroundImageTintColor`, `rotate`, `translate`, `scale`, `transformOrigin`, `opacity`, `visibility`, plus full flexbox + border-radius + font fields.

**Assets — `net.risingworld.api.assets.TextureAsset`**
`load(byte[])`, `loadFromFile(String)`, `loadFromPlugin(Plugin, String)`, `loadFromGame(String)`, `loadFromURL(String)`.

**Events — `net.risingworld.api.events.player.*` / `.npc.*`**
`PlayerConnectEvent`, `PlayerSpawnEvent`, `PlayerDisconnectEvent`, `PlayerChangePositionEvent`, `PlayerChangeBlockPositionEvent`, `PlayerCommandEvent`, `PlayerKeyEvent`, `PlayerDeathEvent`(+`Cause`), `PlayerRespawnEvent`; `NpcSpawnEvent`, `NpcDeathEvent`.

*SDK location on your machine: `X:\SteamLibrary\steamapps\common\RisingWorld\Data\SDK\PluginAPI.jar` (+ `javadoc.zip`). Build against this jar with the bundled JDK 20 at `…\RisingWorld\Data\Java`.*

---

## 15. Summary

The Rising World Plugin API — verified against the actual jar in your game folder — supports everything this needs, and more cleanly than first assumed: `Chunk` LOD terrain + surface-texture data render a real shaded map, `TextureAsset.load(byte[])` + `Style.backgroundImage` display it with no disk I/O, `UIPainter2D` draws every overlay marker as sharp vectors, `Server.getAllPlayers()` + `World.getAllNpcsInRange()` feed the radar, and `Player.getHeading()` + `PlayerKeyEvent` + the `Timer` class handle orientation, controls and the refresh loop. The architecture rests on three ideas that keep it fast and multiplayer-safe: **cache tiles per chunk, composite per player, and draw all markers on one `UIPainter2D` overlay updated separately from the terrain image.** No technical blockers remain — only a color-table calibration and a refresh-rate tuning pass, both done during implementation. The roadmap delivers a visible minimap at M1 and the three priority features (minimap+zoom, waypoints, radar) by M4, with the fullscreen world map as a clean later addition.

*Prepared as a design proposal for approval before implementation. API claims verified against `PluginAPI.jar` (build 2026-05-13) in your local SDK; background from the Rising World Plugin API javadoc (javadoc.rising-world.net) and the official developer forum.*
