# PicSouls MiniMap

An Xaero-style minimap plugin for the Unity version of **Rising World** (Java,
JDK 20, built against `Data/SDK/PluginAPI.jar`). Not a git repo — versioning is
done by hand (see "Release workflow" below).

Full version-by-version history lives in `CHANGELOG.md` — read it on demand
(it's long) rather than all at once. The user-facing `/mm` command reference
lives in `command_list.txt`. The original design/roadmap doc is
`RisingWorld_MiniMap_Design.md`.

## Working practices (important)

- **SDK is the source of truth.** The `SDK/` folder holds the full Rising
  World Plugin API javadoc (HTML) plus `PluginAPI.jar`. Confirm class/method
  signatures there before using them — don't guess at the API.
- **The user can't run the game themselves in this session** — every feature
  or fix is verified by the user playing separately and reporting back (or by
  reading `Player.log` directly — see below). Don't claim something works
  in-game; say what wants confirmation.
- **You can read the live game log directly** — `C:\Users\PicSoul-PC\AppData\LocalLow\JIW-Games\Rising World\Player.log`
  (NOT the stale copy that may exist under the project folder). Once the user
  has played (doesn't need to have exited), this is real-time and is almost
  always faster and more reliable than asking them to copy/paste console text.
  This is how several stubborn bugs got solved — see the "Diagnosing stuck
  bugs" note below.
- **The plugin does not hot-reload.** A rebuilt jar takes effect only after a
  world/game restart or world switch. Always say so when asking for
  confirmation.
- **Keep `command_list.txt` in sync.** Whenever a chat command is added,
  removed, renamed, or its behaviour changes, update it in the same change.
- **Keep `CHANGELOG.md` in sync.** Every version gets an entry: root cause (if
  it's a fix), what changed, and an honest NOTE on what still wants in-game
  confirmation. Don't claim a fix is confirmed unless the user actually said so.

### Diagnosing stuck bugs

When a bug survives one reasoned fix attempt, **stop guessing and add
instrumentation** before trying a second fix blind — this project has a track
record of 2-3 "reasonable" guesses in a row failing on bugs that were actually
something else entirely (the fruit-dot bug, v2.45-2.48; the radar icon bug,
v2.55-2.61). Add a log line, ask the user to reproduce, then read
`Player.log` yourself. A concrete log line beats another theory every time.

See the "pooled UIElement" note below for one specific, already-solved gotcha
worth knowing before touching similar code again.

### Known gotchas

- **A pooled `UIElement` doesn't reliably redraw when you swap its
  `backgroundImage` while it stays continuously visible.** Found via the
  radar icon bug (v2.55-v2.61, see `CHANGELOG.md` for the full trail): neither
  a `setVisible(false)`/`setVisible(true)` toggle around the swap nor
  `UIElement.removeChild()` + `addChild()` on the *same* element instance
  fixed it, despite `removeChild`'s javadoc saying it "detaches from the
  player UI". **What actually works: discard the element and construct a
  brand-new one whenever its assigned image needs to change** — see
  `MarkerOverlay.drawRadar` for the reference implementation. If a future
  feature needs to swap a pooled UIElement's image/texture at runtime, budget
  for element replacement, not in-place mutation, from the start.
- **A live, in-session full HUD rebuild (tear down + recreate every element)
  is dangerous if it can be triggered repeatedly in a short window.** Found
  via the map-size/corner settings crash (v2.65-v2.68): `PlayerSession
  .rebuildHud()` recreates hundreds of UI elements, and routing a settings-
  panel slider/button through it meant every rapid click did a full rebuild.
  Two independent crash sessions hit UI element ids in the same high-2000s
  range (checked via `Player.log`), pointing at a cumulative-churn ceiling,
  not just rebuild frequency — a rate-limit (`PlayerSession.requestRebuild()`
  + `MinimapConfig.hudRebuildCooldownSeconds`) helped but didn't eliminate it.
  **The real fix was to not rebuild at all**: a plain resize/reposition never
  needed new elements in the first place — `MinimapHud.applyLayoutChange()` /
  `MarkerOverlay.updateGeometry()` mutate the existing containers' size and
  position in place (the same approach live zoom changes,
  `MinimapHud.setZoom()`, already used successfully). Before adding a live
  settings control, check whether the change can be applied by mutating
  existing elements' style (size/position/etc.) instead of reaching for
  `requestRebuild()`/`rebuildHud()` — reserve the full rebuild path for
  changes that actually alter which elements exist or how many are pooled
  (`/mm uilite`/`minimal`/`notex`), not simple geometry tweaks.

## Architecture

Package `net.picsoul.rw.minimap`:

- **`PicSoulsMiniMap`** — main class (`extends Plugin implements Listener`).
  Owns the session registry, shared `TileCache` + `MapRenderer`, a
  single-thread render worker, and the update `Timer`. Handles all `/mm`
  commands, world-edit events (invalidates affected tiles), spawn/disconnect.
- **`config/MinimapConfig`** — every tunable, as in-code defaults (no config
  file yet — that's still a roadmap item).
- **`capability/`** (`Capabilities`, `CapabilityService`) — the tiered
  unlock system: which minimap features a player currently has, derived from
  equipped/owned items (map, compass, watch, calendar, radar). Calibrate
  item-name tokens in-game with `/mm ids`.
- **`session/`** (`PlayerSession`, `SessionRegistry`) — per-player state and
  visibility; follows the vehicle position when boating; owns the settings
  panel and zoom-key capture state.
- **`ui/`** — everything actually drawn on screen:
  - `MinimapHud` — the HUD itself: circular map box, double-buffered map
    image layers with cross-fade, teardrop player marker, X/Y/Z + time/date
    labels, N/S/E/W cardinals, smoothed follow-position/heading, cave-mode
    switching, and the radar/other-player scan-and-draw calls.
  - `MarkerOverlay` — waypoint icons, the spawn marker + dashed line, radar
    npc blips, and other-player markers — all pooled child `UIElement`s
    repositioned/retinted each frame (never re-tessellated, to avoid flicker).
  - `SettingsPanel` — the `/mm settings` window (zoom-key rebind, entity icon
    size slider). Click-to-set only — this SDK has no drag event.
  - `AssetSalt` — makes every procedurally-generated PNG byte-unique per
    plugin load, so the game never re-registers a texture asset name it's
    already used (prevents a world-switch asset-registry crash).
  - `OutlinedLabel`, `MarkerTextures`, `OtherPlayerBlip` — supporting pieces.
- **`render/`** — terrain tile pipeline:
  - `TileRenderer` — one chunk → ARGB tile (terrain color, hill-shading,
    water, contour lines, construction overlay, tree canopies, cave-opening
    detection/vignette).
  - `MaterialColors`, `ConstructionColors`, `TreeColors`, `MarkerTexture` —
    color/shape tables and procedural icon generation.
  - `TileCache` — LRU tile cache with a dirty-flag (not full-evict) on
    invalidate, plus a not-loaded "miss cooldown" so open ocean / edits don't
    re-block every frame or flash to blank while rebuilding.
  - `MapRenderer` — composites cached tiles into the view; main-thread
    snapshot under a wall-clock time budget, off-thread PNG encode.
- **`radar/`** (`RadarScanner`, `RadarBlip`) — scans nearby npcs
  (`World.getAllNpcsInRange`), classifies each (hostile/animal/human/mount,
  saddled state, child scaling), and dead-reckons positions between scans
  (scans are throttled; every-frame drawing is not) so a fast-moving mount
  doesn't visibly lag. One instance per player (position-dependent, unlike
  waypoints). Per-species custom icons load from
  `<plugin folder>/icons/<name>.png` on disk (not the jar) — see
  `MarkerOverlay.resolveRadarIcon`/`preloadRadarIcons`.
- **`waypoint/`** (`WaypointService`, `MapMarker`) — reads the game's own
  `Maps.db → mapmarkers` table (WAL mode, safe to read live), throttled
  reload, world-shared (one instance, unlike radar).

## Release workflow

Every change, however small, gets this full cycle — not just "big" features:

1. `gradle compileJava` to verify.
2. Bump `PLUGIN_VERSION` in `PicSoulsMiniMap.java` (and the version line in
   `command_list.txt`).
3. Update `command_list.txt` if any command changed.
4. Write a `CHANGELOG.md` entry: root cause (for a fix), what changed, and an
   honest NOTE on what needs in-game confirmation.
5. `gradle jar`.
6. Copy `build/libs/PicSoulsMiniMap.jar` to both `dist/` and the live plugin
   folder: `X:\SteamLibrary\steamapps\common\RisingWorld\Plugins\PicSoulsMiniMap\`.
7. Verify all three copies hash-match (`sha256sum`).
8. `_build_backups/source_vX.XX.tar.gz` — a fresh source tarball (excludes
   `_build_backups`, `build`, `.gradle`).

The plugin only loads at world/game start or world switch, so none of this
takes effect until the user restarts or switches worlds — always say so.
