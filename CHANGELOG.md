# PicSouls MiniMap — Changelog

Append-only, version-by-version development history. For current architecture,
working conventions, and the release workflow, see `CLAUDE.md` — this file is
historical record, not a live status doc, so don't expect the newest entry to
necessarily describe "current state" beyond what it says at the time it was
written.

## Feature History

- **v1.0:** Base minimap functionality.
- **v1.1 – v1.11:** Map rotation (`/mm rotate`), cardinal direction labels,
  heading smoothing, repeated UI-layout reworks, circular map (fixes rotation
  clipping), `/mm version`, and the per-build source-backup process.
- **v1.12 – v2.0:** Terrain rendering pipeline maturation — raw-LOD single-call
  tile read (stall fix), tile cache with miss-cooldown (open-ocean stall fix),
  async render worker, water depth blending, construction overlay, contour toggle,
  smooth panning, vehicle-follow tracking. (Source snapshots in `_build_backups/`
  up to `source_v2.0.tar.gz`.)
- **v2.1 – v2.2:** Text-outline readability, centered coordinate readout, reduced
  terrain brightness, rotation toggle-delay fix, marker rotation correctness in
  both fixed and rotating modes. **Note:** v2.1/v2.2 source was not snapshotted by
  the previous agent; a `source_v2.2.tar.gz` fallback was created on return.
- **v2.3:** HUD text readability overhaul. New `ui/OutlinedLabel` renders
  bold text with a genuine OUTWARD black outline (a rear black silhouette layer
  the white text fills, so the outline can never eat into the glyph the way
  Unity's edge-centered `textOutlineWidth` did) plus a subtle dark rounded
  backing chip. Cardinal (N/S/E/W) and X/Y/Z coordinate labels now use it.
  New config knobs: `textOutlineWidth`, `textBackingAlpha`, `textPaddingPx`.
- **v2.4:** Removed the dark backing chip behind HUD text (looked odd); kept the
  bold outward outline. `textBackingAlpha` now defaults to 0 (backing off).
- **v2.5:** Added **map waypoints + spawn-point** overlays. New `waypoint/`
  package (`WaypointService`, `MapMarker`) reads the game's own
  `Worlds/<world>/Maps.db → mapmarkers` table via `getSQLiteConnection` (that DB
  is WAL-mode, so reads are safe while the game runs, and it is NOT in the
  `WorldDatabase.Target` enum). New `ui/MarkerOverlay` draws waypoint dots +
  names and a dashed line to the spawn point on a single `UIPainter2D`, with
  edge-clamping + distance for off-map points. Spawn comes from the API
  (`Player.getSpawnPosition(Primary)` else `Server.getDefaultSpawnPosition()`),
  no DB. Commands: `/mm waypoints [refresh]`, `/mm spawn`. Config: `showWaypoints`,
  `waypointLabels`, `showSpawnLine`, `waypointRefreshSeconds`, `waypointDotPx`,
  spawn line color/dash. Shows all markers, per user choice.
- **v2.6:** Spawn + edge-label fixes. Spawn now tracks the player's **active**
  spawn (was hard-coded to Primary, so it always showed world spawn): initialized
  from the save via `getWorldDatabase(Players)` — `player.lastspawn` picks the
  active type (0=world,1/2/3=primary/secondary/tertiary) and its position blob is
  decoded (type byte + 3 LE floats) — and kept live by `PlayerSetSpawnPointEvent`
  / `PlayerRespawnEvent`. Spawn distance now shows at the map edge when off-map.
  Off-map **waypoints are dots-only** (no text) to stop edge labels overlapping;
  names reappear when a waypoint is on-map (Xaero-style).
- **v2.7:** Waypoints now render their **actual map icon** (from `mapmarkers.iconid`)
  instead of a plain dot. New `ui/IconRenderer` draws all 8 game icons as vectors
  on the overlay painter, colored by the marker color: 0=cross, 1=ring, 2=down-arrow,
  3=house, 4=exclamation, 5=question, 6=dot, 7=hatched-square (order from the game
  marker picker; verify in-game and reorder in IconRenderer if any differ). Config
  `waypointDotPx` renamed to `waypointIconPx` (default 15).
- **v2.8:** Waypoint **proximity fade** to declutter near a base: a waypoint is
  full opacity beyond `waypointFadeStartM` (50m), fades linearly as you approach,
  and is hidden within `waypointFadeEndM` (25m). Icon halo + name label fade
  together; fully-hidden ones are skipped. Horizontal distance; set start<=end to
  disable. Spawn line is not affected.
- **v2.9:** Fixed edge-waypoint flicker. Markers at the map boundary used to
  toggle on/off each frame (size/position snap + label show/hide) from player
  position jitter. Now the icon is drawn every frame at constant size, clamped
  continuously to the rim (no snap), and label visibility uses hysteresis
  (show when well inside, hide only near the rim). Same hysteresis applied to
  the spawn distance label.
- **v2.10:** Edge-icon flicker (persisted from v2.9, and present when standing
  still) fixed properly. Root cause: edge markers were clamped to radius-2, so
  the icon's body crossed the overlay's circular clip and the painter's own
  bounds, compositing inconsistently each frame. Now icons/glyphs clamp to
  (radius - iconRadius - 3) so the whole shape stays inside the clip/painter.
  Default fade tuned to 40->20m (user preference).
- **v2.11:** Root-caused and fixed the marker flicker (it was on inside markers
  too, worse when overlapping). Cause: markers were drawn on a `UIPainter2D`
  cleared + re-tessellated every frame, which flickers (child elements like the
  player marker never did). Rewrote the overlay to render markers as pooled
  child image-elements: new `MarkerTextures` bakes the 8 icons + spawn diamond
  as white+dark-halo PNGs, tinted per marker via `backgroundImageTintColor`,
  faded via element opacity; the dashed spawn line is a pool of thin rotated
  segment elements. `UIPainter2D`/`IconRenderer` removed. This matches the
  design doc (icons as child sprites, not painter vectors).
- **v2.12:** Replaced the single craft-to-unlock gate with a **tiered, item-driven** system (the API cannot register a custom item/recipe — confirmed in SDK: `ItemDefinition`/`Crafting.Recipe` are final with no public ctor, defs live in `definitions.db` — and by red51 on the forum). New `capability/` package (`CapabilityService`, `Capabilities`) drives tiers from EQUIPPED items (scan `Inventory.getItems(SlotType.Equipment)` by name token) and one possession-persistent item:
    - map equipped -> basic minimap (terrain + coords)
    - compass equipped -> cardinals + waypoints + spawn line
    - pocket watch equipped -> in-game time under coords (`Server.getGameTime(Time.Unit.Hours/Minutes)`)
    - calendar ever crafted/looted (persisted `calendar_owners.txt`) -> in-game date (`getGameTime(Days/Years)` + `getCurrentSeason()`)
  Recompute on `PlayerChangeEquippedItemEvent` / `PlayerInventoryAddItemEvent` / `PlayerCraftItemEvent` / spawn + a 1s safety-net sweep. HUD info area now stacks coords/time/date labels; cardinals+overlay gated on the compass tier. Commands: `/mm status|caps` (show tiers), `/mm dev` (all tiers on for testing), `/mm ids` now also logs equipment item names for token calibration. UnlockService removed.
  NOTE: item name tokens ("map","compass","watch","calendar") and whether these items sit in Equipment slots must be calibrated in-game via `/mm ids`.
- **v2.13:** Fixes on the tiered system. (1) Waypoints no longer show without a compass — `MarkerOverlay.hide()` now hides every pooled sprite explicitly (container-visibility did not cascade). (2) Item tokens are now comma-separated alias lists; the timepiece is the in-game "clock" (clockold/clockmodern) so the watch token defaults to "watch,clock,pocketwatch". (3) Calendar now also detected by scanning the inventory (already-owned counts), not just craft/pickup events. `/mm ids` now dumps Equipment + Quickslot item names for calibration.
- **v2.14:** Verified item names from the game's `definitions.db` (granted game-folder access): map=`map`, compass=`compassold`/`compassmodern`, timepiece=`clockold` (all type=Equipment, category=Utility → Equipment slots). The **calendar** is a placeable OBJECT (id 537, recipe 334: 1x lumber+8x paper @ workbench), carried in inventory as a generic `objectkit` — so it can't be matched by item name. Now detected via `Item.ObjectItem.getObjectName()` (carried/looted) and `PlayerCraftItemEvent.getRecipe().name == "calendar"` (crafted); craft log now prints the recipe name too. map/compass/clock tokens confirmed correct.
- **v2.15:** Time/date correctness + per-world calendar. (1) The calendar tier is
  now **per-world**: ownership is keyed by `worldName|playerUID` (was global per
  UID), so a fresh world no longer inherits the date readout — persisted in
  `calendar_owners.txt` with the composite key. (2) The date was stuck on
  "Spring Day 1 Year 1" because `Server.getGameTime(Time.Unit.Days/Years)`
  returns a constant; switched to the `Time` object from `Server.getGameTime()`
  and read `getDay()` (day-of-year 1–124; the year is 4 seasons × 31 days, no
  months) + `getYear()`, mapping day-of-year → season + day-of-season. (3) Time
  is now a **12-hour clock with AM/PM** (config `time24Hour`, default false);
  formatted from the `Time` object's `getHours()/getMinutes()`. `/mm status`
  now also prints the raw time it reads (day-of-year, hour, minute, year) for
  diagnostics. Also created **`command_list.txt`** in the project root (all `/mm`
  commands + descriptions + the tier explanation) and added "keep command_list.txt
  in sync" to the house rules above.
- **v2.16:** Fixed the **world-switch crash**. Symptom: joining a second world
  from the main menu (without restarting the game) crashed the game shortly after
  load. Crash logs showed a native crash (`jvm.dll` ← game ← `UnityPlayer`) during
  world streaming, with the last plugin activity always a main-thread terrain
  snapshot (chunk LOD read); confirmed it predates the SQLite/waypoint feature, so
  it's the terrain read, not the DB. Root cause: on every world load the plugin
  re-enables and immediately starts reading chunk LOD terrain (and creating
  `TextureAsset` textures) while the freshly-loaded world is still initializing —
  a native, uncatchable crash. Fix: a **world-load grace window**. New config
  `renderGraceSeconds` (default 6s); `PicSoulsMiniMap.tick()` holds a `renderReady`
  flag that flips true once `getRunningTime()` passes the grace window, then enables
  rendering on all sessions and un-gates `waypointService.maybeRefresh()`. Until
  then the map frame, player marker and coordinates still show, but terrain reads,
  texture creation and Maps.db waypoint reads are deferred. `MinimapHud` gained a
  `renderingEnabled` gate on its render path; `PlayerSession.setRenderingEnabled`
  forwards it. Increase `renderGraceSeconds` if a slower machine still crashes on
  world switch.
- **v2.17:** World-switch crash — the v2.16 grace window did **not** fix it. A
  fresh crash log showed the grace window elapsing (`terrain rendering enabled
  (world-load grace elapsed)`), the plugin then reconnecting to `Maps.db` and
  resuming chunk reads, and the game crashing natively moments later during its
  own DB autosave (`[DB] Saved … players/storages` → `Crash!!!`). So the crash is
  **not** a "world not ready yet" timing problem — delaying only moves it later.
  Notable in this log: **two** `sqlite-3.41.2.1-…-sqlitejdbc.dll` native libs were
  mapped (a classloader-reload artifact of the world switch). Because prior
  evidence shows world-switch crashes predate the SQLite feature (last activity is
  always a main-thread chunk read), terrain reads are the prime suspect, but this
  has not been proven. This version is a **diagnostic build to isolate the cause**,
  not a fix. Added two persisted master kill-switches (config `terrainRendering`,
  `mapDbReads`; default ON) saved to `diagnostics.txt` in the plugin folder so a
  setting survives the world switch (the crash happens within seconds, before a
  command could be typed in the new world). `terrainRendering=off` skips ALL chunk
  LOD reads (map frame/marker/coords/waypoints still show); `mapDbReads=off` skips
  Maps.db waypoint reads AND the initial spawn-DB lookup (spawn falls back to world
  default). Commands `/mm terrain [on|off]` and `/mm mapdb [on|off]` (persisted);
  `/mm status` now prints both states. Wiring: `WaypointService.maybeRefresh`/
  `connection` early-return on `!mapDbReads`; `PlayerSession.resolveInitialSpawn`
  guards the DB read on `mapDbReads`; `tick()`/`setupPlayer` only enable session
  rendering when `terrainRendering`. **Test protocol:** in world A run `/mm terrain
  off`, switch to world B — if no crash, terrain reads are confirmed (then fix
  properly); if it still crashes, additionally run `/mm mapdb off` and retry to
  implicate the DB; if it crashes even with both off, the cause is elsewhere
  (UI/textures/spawn) and we look there.
- **v2.18:** **World-switch crash — root cause found and fixed.** The v2.17
  diagnostic proved it: with BOTH `/mm terrain off` and `/mm mapdb off` (plugin
  doing no chunk reads and no DB reads at all), switching worlds and then equipping
  the map still crashed — and removing the plugin jar entirely made the crash
  disappear (multiple world switches + map opens, no crash). So the cause is the
  plugin's UI/texture layer, not terrain or DB. Root cause: **the plugin never
  disposed the `TextureAsset`s it created.** `MinimapHud.onRenderDone` called
  `TextureAsset.load(png)` every render (a new native texture per frame, never
  freed), and the baked marker/icon/spawn textures (`MinimapHud` marker teardrop +
  `MarkerOverlay`'s 8 icons + spawn diamond) were also never freed. `Asset` exposes
  `dispose()`/`isDisposed()` (confirmed in the SDK) but nothing called it. On a
  world switch those orphaned native asset handles survive into the next world, and
  the game's own asset-heavy default map trips over the corrupted asset registry →
  native crash (fits the log: crash on equipping the map / `Refresh Map Markers` /
  1024×1024 map-tile download after the switch). Fix: (1) `onRenderDone` now
  disposes the texture the back layer previously held before loading the new one
  (stops the per-frame leak; only ~2 map textures live at once); (2) new
  `MinimapHud.dispose()` detaches the UI and disposes the marker + both map-layer
  textures and calls `MarkerOverlay.dispose()` (disposes the 8 icon + spawn
  textures); (3) `PlayerSession.destroy()` calls `hud.dispose()`, so on `onDisable`
  (every world switch) all native textures are freed before the classloader
  unloads; (4) `onDisable` now stops the render worker BEFORE disposing session
  textures, so a finishing render can't marshal back onto a freed handle. The v2.17
  `/mm terrain` and `/mm mapdb` diagnostic switches are retained (still handy, and a
  fallback if any crash remains).
- **v2.19:** World-switch crash — **actual root cause & fix.** v2.18 did NOT fix
  it and made it worse: new crash logs showed the game crashing within seconds of a
  world switch even while standing still and never opening the map, and the crash
  tail was our own `REGISTER ASSET TEXTURE (N)` / `FREE ASSET (N-1)` cycle
  repeating. Root cause: every map render calls `TextureAsset.load(png)`, and each
  such texture is a **server-streamed asset** (the log shows "Requesting new asset
  from server" + "Receive bytes" per texture). The HUD re-rendered the map every
  `FILL_RETRY_NS` (400ms) whenever the region was incomplete — which is constantly
  true on a freshly-loaded world while its chunks stream in — so we churned a new
  server-streamed texture several times a second. On a just-switched world (asset
  system in a reset/fragile state) that rapid register/free churn crashes natively.
  (v2.18's per-frame dispose ADDED the `FREE` half of the churn, making it crash
  sooner — even before the map was opened.) The minimap was confirmed visible in
  those crashes, so the one-time texture creation at HUD build is tolerated; only
  the *continuous* churn is fatal. Fix (`MinimapHud.updateView`): **removed the
  continuous fill-retry re-render.** The map now re-renders ONLY when the player
  pans to a new region (`needRender = !hasTexture || movedFar`), so texture
  creation is rare and bursty→calm instead of several/second. Chunks that stream in
  after you arrive appear on your next pan (minor cosmetic trade-off; the ~6s grace
  means the first render after a switch is already on loaded chunks). v2.18's proper
  texture disposal (per-swap + on teardown) is kept — it's safe now that renders are
  infrequent, and it prevents the orphaned-handle crash on switch.
- **v2.20:** **World-switch crash — TRUE root cause found (duplicate asset names)
  and fixed.** v2.19 did not fix it, and disproved its own premise: the new crash
  log shows only **11** texture registrations in world 2 and **zero** frees, with
  the crash on the *first* map texture — so churn was never the cause. The real
  cause, proven by tracing one checksum through the log: **the game names a raw
  texture asset after the checksum of its bytes.** Our HUD textures (player marker,
  8 waypoint icons, spawn diamond) are generated deterministically, so they are
  byte-identical in every world. Timeline in the log for checksum `05254f5f…`
  (1015 b): world 1 `REGISTER ASSET TEXTURE (6) … CH: 05254f5f…` → used fine →
  world-1 teardown `FREE ASSET 6` → world 2 `REGISTER ASSET TEXTURE (25)` with the
  *identical* checksum/name `-119 05254f5f… -126` → `Receive bytes for asset 25` →
  `Crash!!!`. So re-registering an asset name the process has already used (and
  freed) across a plugin reload crashes the game natively on first use. This
  explains every earlier observation: removing the jar avoided it (no such assets);
  `/mm terrain off` + `/mm mapdb off` still crashed (the 10 HUD textures are created
  regardless, and crashed when the HUD was shown / map equipped); world 1 alone is
  always fine (each name is seen once). It is a game/API-side bug in asset
  re-registration across plugin reload; the plugin can only avoid tripping it.
  Fix: new **`ui/AssetSalt`** — every PNG passed to `TextureAsset.load` gets a
  unique PNG `tEXt` chunk inserted before `IEND`, carrying a per-plugin-load id
  (regenerated on each classloader load, i.e. each world switch) plus a per-call
  counter. `tEXt` is a standard ancillary chunk, so the decoded image is unchanged
  — verified in a test harness: salted PNGs decode pixel-for-pixel identical to the
  original, every call yields a different checksum, +45 bytes, and null/non-PNG
  input passes through untouched. Applied at all four load sites (marker, map
  layers, 8 icons, spawn diamond). The per-call counter also makes disposal
  unambiguous: two loads of identical content are now distinct assets, so freeing
  one can never pull a texture out from under another element. Also **restored the
  progressive chunk fill-in** that v2.19 removed (it caused missing chunks on the
  map until the player moved) — safe now that the true cause is understood.
- **v2.21:** v2.20 did **not** fix the crash either. The salting itself worked (the
  v2.20 log shows every world-2 checksum unique — no reuse of world-1 names), so
  **duplicate asset names were not the cause**. What the v2.20 log adds: several map
  textures streamed in successfully (11726 → 21984 → 31001 → 33390 bytes) before the
  crash. Ruled out so far, with evidence: terrain/chunk reads (`/mm terrain off`
  still crashed), DB reads (`/mm mapdb off` still crashed), texture churn (crash
  occurred on the *first* texture with zero frees), duplicate asset names (v2.20),
  render threading (`MapRenderer` marshals its callback back via `Plugin.enqueue`,
  so textures/UI are only touched on the main thread), and leaked UI/assets on
  teardown — the world-1 teardown log reads `Reset PluginUIManager (0 elements)`,
  `Reset PluginAssetManager (0 assets)`, so the plugin *is* releasing cleanly.
  Confirmed by the user: a fresh game start + any world never crashes; only
  main-menu → switch world does; and with the jar removed it never crashes.
  So the trigger is something the plugin does in the *second* world of a session.
  This release is therefore a **decisive bisect build**, not a fix. Two new
  persisted master switches narrow it to a single subsystem:
    - `/mm notex [on|off]` → `useTextures`. When off the plugin creates **zero**
      textures: the marker, waypoint icons and spawn glyph become plain colored UI
      shapes and the terrain image is skipped. Everything else still works.
    - `/mm hud [on|off]` → `hudEnabled`. When off the plugin attaches **zero** UI
      elements — loaded and responsive to commands, but completely invisible.
  Both persist in `diagnostics.txt` (keys `textures`, `hud`) alongside `terrain`
  and `mapdb`, and all four are shown by `/mm status` and in the enable log.
  **Bisect protocol:** (1) `/mm notex on`, switch worlds → no crash ⇒ texture
  creation is the trigger (then the fix is a texture-free or plugin-file-based
  minimap); still crashes ⇒ (2) `/mm hud off`, switch worlds → no crash ⇒ UI
  element creation is the trigger; still crashes ⇒ (3) the plugin's mere
  presence/reload is enough, which would make it an engine-side bug in plugin
  reload, to be reported upstream with the plugin as the repro.
- **v2.22:** **WORLD-SWITCH CRASH SOLVED.** The v2.21 bisect returned a decisive
  result: `/mm notex on` (zero textures) still crashed, `/mm hud off` (zero UI
  elements attached) **stopped the crash** — and then, critically, re-enabling the
  HUD *manually, later, in that same second world* was completely safe, textures and
  all. So the trigger is not UI creation as such, but **attaching UI elements too
  early — during the world-load window of a second world in the same session.**
  This also explains why v2.16's grace window failed: it gated only
  `setRenderingEnabled` (the terrain image), while the actual `addUIElement` call
  (`recomputeCapabilities` → `showMinimap` → `hud.attach`) was never gated at all.
  Right idea, wrong subsystem — for six versions the HUD kept attaching immediately.
  Fix: a **HUD attach grace window applied only after a world switch.**
  `PlayerSession` gained `hudAllowed` (checked in `showMinimap`, so no
  `addUIElement` can happen while false; flipping it true re-evaluates so the map
  appears as soon as it is safe). `PicSoulsMiniMap` holds `hudReady` and flips it in
  `tick()` once `getRunningTime() > config.hudGraceSeconds` (new, default 15s).
  Crucially the delay is applied **only on a world switch**: a fresh game start
  attaches immediately, exactly as before, so normal play is unaffected. World
  switches are detected via `detectWorldSwitch()` — the plugin classloader (and all
  static state) is destroyed on every switch, but the OS process is not, so the
  process start time (`ProcessHandle.current().info().startInstant()`) is compared
  against the value stored in `session.txt` in the plugin folder: equal ⇒ same game
  process ⇒ world switch; different/absent ⇒ game restart ⇒ fresh start. Verified in
  a test harness (fresh→false, re-enable→true, new process with stale marker→false,
  corrupt marker→false/safe); it fails safe toward "world switch", which only delays
  the HUD. New commands: `/mm hudgrace <seconds>` (tune + persist, raise if a slower
  machine still crashes) and `/mm diagreset` (restore all diagnostic switches to ON).
  `/mm status` now also reports `worldSwitch`, `hudReady` and `hudgrace`.
- **v2.23:** v2.22's grace worked exactly as designed — the log confirms
  `[world switch detected: HUD deferred 15.0s]` and `HUD enabled (post-world-switch
  grace elapsed)` — and the game then ran fine for minutes, rendering map textures.
  **The crash came later, at a completely different moment**, so raising
  `hudgrace` cannot help. Reading the crash tails of v2.20, v2.21 and v2.22
  side by side shows one shared instant: **the player switching to the vanilla map
  item.** v2.22's tail is unmistakable — `UNEQUIP pickaxe` → `EQUIP ITEM (2,
  Equipment) map (59)` → `Refresh Map Markers: 1` → `Requesting map tile 0 0 from
  server` → `Crash!!!`. v2.20 and v2.21 both die on the same `UNEQUIP pickaxe`
  step. Also re-verified: in the v2.21 `notex` run world 2 registered **0** textures
  and still crashed, so the bisect was sound and textures really are irrelevant.
  Refined conclusion: **in a second world, the crash happens when the game opens its
  own map UI while our HUD is attached** — which is also why `/mm hud off` was the
  only stable configuration, and why the user's later manual re-enable seemed fine
  (the vanilla map was presumably not opened during it). Two changes, both aimed at
  that instant:
    1. **No UI work inside game event callbacks.** Our `PlayerChangeEquippedItemEvent`
       handler used to run `recomputeCapabilities()` synchronously — mutating labels
       and possibly attaching the HUD at the exact moment the game was opening its
       map UI. Now the equip/inventory/craft/spawn events only set a flag or queue
       the player (`capsDirty`, `pendingSetup`); every UI mutation happens on our own
       timer tick instead. Good practice regardless, and it removes our code from the
       game's map-opening call stack.
    2. **Map guard** (`config.mapGuard`, default on, `/mm mapguard [on|off]`). While
       the player is holding the vanilla map (`getEquippedItem()` type id 59 / name
       "map"), the HUD is detached, so our UI is never on screen while the game's own
       map is. Evaluated on the tick, never from an event. `PlayerSession` gained
       `hudSuppressed` for this.
  If this is right, the minimap simply vanishes while the vanilla map is held and
  returns when it is put away. Should it still crash, `/mm mapguard off` isolates
  change 2 from change 1.

- **v2.24:** Stabilisation. v2.23 failed on both counts and, importantly, **ruled
  out its own hypothesis**: with `mapguard` ON the user confirmed the minimap
  *visibly disappeared* while holding the vanilla map — and the game crashed anyway;
  with `mapguard off` it also crashed. Moving all UI work off the game event
  callbacks (also in v2.23, and kept — it is correct regardless) changed nothing
  either. Both runs produced **no crash dump at all**, unlike every previous crash,
  though the on-screen behaviour was the same instant exit. Installed build was
  verified by hash against the game's own plugin folder, so these results are sound.
  The surviving invariant across every experiment is blunt: **with our HUD attached
  in the second world of a session the game crashes; with it never attached
  (`/mm hud off`) the game is stable.** Timing, textures, terrain reads, DB reads,
  asset names, event-callback re-entrancy and coexistence with the vanilla map are
  all eliminated. So v2.24 stops guessing and guarantees stability instead: new
  `config.worldSwitchSafeMode` (default ON) means that after a **world switch** the
  HUD is never attached for the rest of that game session, and the player is told
  once in chat why, with the remedy (restart the game). A **fresh game start is
  completely unaffected** — the minimap behaves normally in the first world of every
  session, which is the common case. `/mm safemode off` attaches it anyway without
  needing a restart (expect a crash; for diagnosis only), `/mm safemode on` restores
  the guarantee, and the state is persisted and shown by `/mm status`.
  Deployment note: the plugin folder
  (`X:\SteamLibrary\steamapps\common\RisingWorld\Plugins\PicSoulsMiniMap`) is now
  connected to the session, so builds are installed directly and verified by hash —
  no manual copying, and no more ambiguity about which version is running.

- **v2.25:** Live-log analysis (the game's own `Player.log` /
  `Player-prev.log` under `AppData\LocalLow\JIW-Games\Rising World`, now readable —
  the crash-dump folder is written only for *handled* crashes and these produce
  none). Findings from the two v2.23 runs: (1) **no crash handler runs at all** —
  both logs simply stop mid-line, with no `Crash!!!`, no exception and no dump, so
  the process is being killed outright rather than faulting in a way the game can
  catch; (2) the sequence in the second world is **identical and deterministic in
  both runs**: HUD grace elapses → HUD attaches → a burst of exactly 10 texture
  registrations (player marker + 8 waypoint icons + spawn diamond) → map rendering
  begins → the process dies after ~4 map textures, i.e. 14 registrations into the
  world every time; (3) the world-1/world-2 register/free asymmetry (37/37 vs 14/2)
  is *not* a leak — it is just the 10-texture HUD burst, which is correctly held
  until teardown. This confirms HUD attachment as the precondition but shows the
  death happens a few seconds later, during ordinary rendering.
  Next hypothesis, now testable: **UI element count.** A full HUD allocates roughly
  **190** UI elements per player — the marker overlay alone builds 48 icon sprites,
  24 dash segments and 48 outlined labels (two elements each = 96), plus the map
  containers, layers, marker and cardinal/coordinate labels. Allocating that many on
  a plugin reload is the remaining suspect. New `config.uiLite` (`/mm uilite on`)
  rebuilds the HUD with 8/8/8 pools — about **40** elements — leaving everything
  else identical. Overlay pool sizes moved from `static final` constants to
  per-instance values resolved from config (`markerIconPool`, `markerDashPool`,
  `uiLiteIconPool`, `uiLiteDashPool`).
  **Experiment:** `/mm uilite on` + `/mm safemode off`, then switch worlds. Stable ⇒
  element pressure is the cause, and tuning the pools gives a **working minimap
  after a world switch** rather than safe mode. Still crashes ⇒ element count is
  eliminated too, and the remaining candidate is the act of attaching any UI at all
  after a plugin reload, which is an engine defect to report upstream.

- **v2.26:** `/mm uilite on` (~40 UI elements instead of ~190) **still crashed** —
  verified valid from `diagnostics.txt` (`uilite=on`, `safemode=off`), the installed
  jar hash, and the log (`world switch detected`, HUD attached after grace, 15
  texture registrations, then the crash). So **UI element count is eliminated too**.
  Everything now points to a single conclusion: **attaching any plugin UI in the
  second world of a game session is fatal**, independent of what that UI is, how
  much of it there is, when it is attached, and what else the plugin does.
  This release adds the last discriminating test and the upstream report:
    - `/mm minimal [on|off]` (`config.minimalUi`) replaces the entire HUD with one
      container holding a single plain `UILabel` showing coordinates — **2 UI
      elements**, no map circle, no marker, no overlay, no textures. If a switched
      world is stable with this, there is a *threshold* rather than an absolute
      prohibition, and a reduced minimap may be viable after a switch (and the pools
      can be tuned to find the ceiling). If it still crashes, two elements are
      enough to kill it, which is as minimal a reproduction as exists.
    - **`BUG_REPORT_world_switch_ui_crash.md`** in the project root — a complete,
      self-contained write-up for red51/the forum: environment, reproduction steps,
      the full elimination table, both observed termination modes (handled crash
      with `GameAssembly`→`UnityPlayer` stack, and outright process kill with no
      dump), representative log tails, the asset-id observation (the client's asset
      id counter does not reset across a switch while the server-side managers do),
      a minimal code repro, and the workaround in use.

- **v2.27:** **Root cause found — incomplete UI teardown.** `/mm minimal on`
  (one container + one label, and **zero** textures in the second world) still
  crashed, which is the minimal reproduction. But the log of that run gave up the
  mechanism. At world-1 teardown the game logs:
  `Reset PluginUIManager (47 elements)...` — i.e. **47 of our UI elements were still
  registered** when the plugin unloaded. `MinimapHud.detach()` only ever called
  `player.removeUIElement()` on the two root containers; every child (map box, image
  layers, marker, cardinal/coordinate labels, and the whole marker-overlay pool)
  stayed registered and was force-reset by the game. The second world then starts
  issuing element ids from **1** again (`CLIENT: Create new visual element (1)` /
  `API: Add UI element 1`) straight on top of that stale state — and dies. This also
  explains why *everything* we tried was irrelevant: textures, timing, element count,
  the vanilla map and event re-entrancy never mattered, because the damage was done
  at the *previous* world's teardown. (An earlier log did read
  `Reset PluginUIManager (0 elements)`, which is what led to teardown being wrongly
  cleared as a suspect — that run simply never attached the HUD.)
  Fix, in `MinimapHud.dispose()` (called from `PlayerSession.destroy()`, which runs
  on `onDisable`, i.e. on every world switch): (1) new `purgeChildren()` walks the
  element tree depth-first and calls `removeAllChilds()` from the leaves up, using
  the API's `getChilds()`; (2) the two roots are then removed as before; (3) a final
  sweep calls `player.getAllUIElements(false)` — which returns only *this plugin's*
  elements — and removes anything still registered; (4) all element/texture fields
  are nulled and `built` is reset, so a later re-attach rebuilds cleanly.
  `onDisable` additionally logs the number of elements still registered per player
  after teardown, so the fix is **objectively verifiable**: the game should now log
  `Reset PluginUIManager (0 elements)`.
  Note the light `detach()` (used for ordinary hide/show, e.g. unequipping the map)
  is deliberately unchanged — only the teardown path purges, so normal play does not
  pay for a rebuild.

- **v2.28:** Bug fix in the diagnostics themselves: **`/mm diagreset` did not reset
  `uilite` or `minimal`.** Those two switches were added in v2.25/v2.26 and never
  added to the reset list, so after running `diagreset` the plugin still showed only
  the minimal coordinates label — it looked like the setting was stuck. `diagreset`
  now restores every switch (terrain, mapdb, textures, hud, mapguard, safemode ON;
  uilite, minimal OFF; hudgrace back to 15s).
  Also, the switches that change the *shape* of the element tree (`minimal`,
  `uilite`, `notex`) previously required rejoining the world to take effect. Now they
  apply immediately: new `PlayerSession.rebuildHud()` hides the HUD, calls
  `MinimapHud.dispose()` (which since v2.27 purges the element tree and resets
  `built`), and re-shows it — so the HUD is rebuilt in place from the current config.
  The command help was updated accordingly ("applied immediately" rather than
  "rejoin the world to apply").

- **v2.28 test result:** The teardown fix **worked** — the game now logs
  `Reset PluginUIManager (0 elements)` where it previously said 47 — **and the crash
  is unchanged.** So incomplete UI teardown was a real bug (now fixed, worth keeping)
  but not the cause. The same log also disproves the element-id-collision theory:
  world 1 recorded **380** element creations spanning ids **1–190**, because
  `/mm diagreset` rebuilt the HUD and the client *recycled* the same id range — and
  that rebuild was perfectly stable. So reusing ids within a world is safe; only
  reusing them after a plugin reload is fatal. Also of note: the client logs 570
  element *creations* and **zero** destructions, though ids are demonstrably
  recycled. Conclusion: this is an engine-side defect around plugin UI after a
  plugin reload, not something fixable in this plugin. `BUG_REPORT_world_switch_ui_crash.md`
  has been updated with all of it (2-element repro, clean-teardown-still-crashes,
  id-recycling evidence, both termination modes, and the two plugin bugs fixed along
  the way so they can be excluded).

- **v2.29:** **The invariant was wrong, and the corrected one is much narrower.**
  With `safemode=on` the second world attaches **0** UI elements and creates **0**
  textures — the plugin does nothing at all — and the game *still* crashed
  (teardown clean: `Reset PluginUIManager (0 elements)`). This reconciles with the
  one stable configuration, `/mm hud off`: that setting suppressed the UI in **both**
  worlds, world 1 included. So the correct statement is **"UI created in world 1 ⇒
  crash on the next world switch"**, not "UI attached in world 2 ⇒ crash". Everything
  world 2 does is irrelevant; the damage is already done.
  That leaves exactly one untested variable: whether it is the *creation* of the UI
  in world 1 or our *removal* of it during `onDisable`. Both removal strategies have
  now been tried and both crash — partial (roots only, leaving 47 elements, pre-v2.27)
  and complete (purge + sweep, 0 elements, v2.27+). **Removing nothing has never been
  tried.** New `config.teardownMode` (`/mm teardown full|roots|none`, persisted):
  `full` = current behaviour; `roots` = pre-v2.27; `none` = leave every element
  registered and let the game's own `Reset PluginUIManager` deal with it
  (`MinimapHud.disposeTexturesOnly()` still frees textures so that stays constant).
  **Test:** `/mm teardown none`, then use the minimap normally in world 1, then
  switch worlds. Stable ⇒ our `removeUIElement` calls during plugin unload are the
  trigger, and the fix is simply not to make them. Still crashes ⇒ creating plugin UI
  at all before a switch is fatal, which is squarely an engine defect.

- **v2.30:** **WORLD-SWITCH CRASH SOLVED** (verified in-game: switching worlds with
  the minimap active no longer crashes). `/mm teardown none` was stable — so the
  trigger is the plugin calling `Player.removeUIElement(...)` **while it is being
  unloaded** (`onDisable`, which runs on every world switch). Doing that corrupts
  something that kills the *next* world a few seconds after it loads. Removing
  nothing and letting the game's own `Reset PluginUIManager` clean up is fine.
  Crucially this is specific to the unload path: removing UI during normal play is
  perfectly safe — the HUD is detached and rebuilt in-world routinely (world 1 logged
  380 element creations across two builds, ids recycled 1–190, no crash).
  Both removal strategies crashed — partial (roots only, 47 leftover) and complete
  (recursive purge + sweep, 0 leftover) — which is why v2.27's "correct" teardown
  changed nothing. The one thing never tried was doing nothing.
  Defaults changed accordingly: `teardownMode = "none"` and
  `worldSwitchSafeMode = false`, so the minimap now works normally in every world.
  `PlayerSession.destroy()` skips UI removal in this mode and calls
  `MinimapHud.disposeTexturesOnly()`, so textures are still freed (that half was
  always safe and remains). `/mm diagreset` restores these new defaults.
  The bug report was rewritten around the confirmed cause and is much sharper: a
  three-line repro (attach one label, remove it in `onDisable`, switch worlds),
  the observation that both partial and complete removal crash while no removal is
  stable, and both termination modes.

- **v2.31:** Waypoint improvements (privacy + size from the map DB). Confirmed the
  `mapmarkers` schema against two user-made markers in test3: `type` is the
  visibility class (**"1" = Global/public, "0" = Default/private**), `playerdbid` is
  the marker's owner, and `scalex` is the size (1.0=100%, 0.5=50%, 2.0=200%);
  `color` is `#RRGGBBAA` as already parsed. Changes:
    - **Privacy** (`config.waypointPrivacy`, default on, `/mm wpprivacy [on|off]`):
      a viewer sees their OWN markers (default + global) plus every player's GLOBAL
      markers, but not other players' default markers. Filter is
      `isGlobal() || playerDbId == viewer`, applied per-player in
      `MarkerOverlay.draw` using `player.getDbID()` (cached in the HUD). `MapMarker`
      gained `playerDbId`, `scale` and `isGlobal()`; `WaypointService` now selects
      `playerdbid` and `scalex`.
    - **Size from DB scale** (`config.waypointUseDbScale`, default on): on-screen
      size = `waypointIconPx` × the marker's scale. Base bumped 15→20px (markers
      read a bit larger). Inside the map the multiplier is clamped **10%–500%**; a
      marker clamped to the map EDGE is capped at **200%** (`waypointScaleMaxEdge`)
      so edge markers never dominate — "at most twice the base size", per the user.
      Floor is 10% (`waypointScaleMin`) since the game allows down to 0% = invisible.
      Icon element size is now set per-marker in `placeIcon`. Verified the math and
      the privacy predicate against the two real markers in a test harness.

- **v2.32:** Waypoint size/opacity legibility (user feedback: below 50% size was
  too small, below 50% opacity nearly invisible). (1) Size now has a **pixel floor**
  `waypointMinPx` (14px) applied after the DB scale, so small-scale markers stay
  readable (a 50% marker was 10px, now 14; the old 10% scale floor is replaced by
  this). Larger markers still scale (100%=20, 200%=40, 500% inside=100, edge cap 40).
  (2) Marker alpha is remapped into `[waypointMinOpacity, 1]` (default 0.6), so a
  faint marker still shows: 50% opacity now renders at 80%, 0% at 60%, 100% unchanged.
  Both are config knobs.
- **v2.33:** **Zoom + a settings window.** SDK research: input is via
  `Player.registerKeys(Key...)` → `PlayerKeyEvent`; there is **no mouse-scroll**
  input (only Left/Right/Middle mouse buttons) and **no way to add to the game's own
  controls menu**, so zoom is on keyboard keys, rebindable through our own UI/command.
  Clickable UI works (`UIElement.setClickable` + `PlayerUIElementClickEvent`, matched
  by reference) and `Player.setMouseCursorVisible` frees the cursor.
    - **Zoom:** `MinimapHud` gained a live `setZoom(cells)` that recomputes the map
      geometry, resizes the two image layers and forces a fresh render. Discrete
      levels `config.zoomSteps = {48,64,96,128,192,256}`. Default keys **Page Up
      (in) / Page Down (out)** (`config.zoomInKeyName`/`zoomOutKeyName`), registered
      per player in `setupPlayer`. `/mm zoom [in|out|reset|<cells>]`. The level is
      persisted (`zoom=` in diagnostics.txt) so it survives world switches.
    - **Rebinding:** `/mm zoomkey in|out <KeyName>` (persisted; canonicalised via a
      tolerant `parseKey`), or interactively in the settings window.
    - **Settings window** (`ui/SettingsPanel`, `/mm settings` / `menu` / `ui`): a
      centered panel showing the two zoom keys with Change buttons and a Close
      button. Click Change → the plugin registers all keys and the next
      `PlayerKeyEvent` becomes the binding (Esc cancels), then re-registers just the
      zoom keys. Crash-safe by the v2.30 rule: the panel is only detached on user
      close (normal play), never from `onDisable` — on a world switch its elements
      are left for the game's own `Reset PluginUIManager`.
  Verified zoom-stepping and key-parsing in a test harness. NOTE: cursor/click
  behaviour and the exact key defaults want an in-game eyeball. Roadmap: the panel
  is the seed for the planned size/position settings UI.
- **v2.34:** **Zoom/rebind keys fixed — root cause was a missing SDK opt-in, not a key
  conflict.** User report: Page Up/Page Down did nothing, and worse, pressing *any*
  key in the settings panel's rebind capture also did nothing (mouse/clicks in that
  same panel worked fine). The SDK javadoc for `PlayerKeyEvent` states plainly: key
  registration via `Player.registerKeys(...)` is not sufficient by itself — the
  client only forwards key input to the server (and only then does
  `PlayerKeyEvent` fire) once the plugin has also called
  `Player.setListenForKeyInput(true)`. That call was never made anywhere in the
  codebase, so **no `PlayerKeyEvent` ever fired at all**, regardless of which keys
  were registered — explaining both symptoms at once (mouse clicks go through a
  separate `PlayerUIElementClickEvent` path, so the panel's buttons still worked).
  Fix: `registerZoomKeys()` (called once per player in `setupPlayer`, and again by
  `restoreZoomKeyRegistration()` after a rebind) now also calls
  `player.setListenForKeyInput(true)` right after `registerKeys(...)`. No other
  changes needed — the zoom-step logic, rebind-capture flow and `Key.values()`
  wildcard registration for capture were all already correct. NOTE: wants an
  in-game confirmation that Page Up/Page Down now zoom and that rebinding via the
  settings panel captures a key press.

- **v2.35:** **Terrain and block colors now come from the real textures**, not
  hand-picked hex guesses. The user dropped a new folder,
  `screenshots/textures_with_ids/`, with screenshots of the game's own texture
  pickers (world-edit terrain brush + the building menu's block-texture
  catalog), each swatch labeled with its numeric id. Wrote a one-off Python
  extraction pipeline (Pillow/numpy/scipy, not part of the build) that:
  auto-detects each swatch's bounding box in a screenshot by thresholding
  against the near-black picker backdrop, groups them into reading-order grid
  cells, and averages a center-cropped band of each swatch (inset away from
  the rounded corners and the id/name label bars top and bottom) to get one
  solid RGB per texture id. Fed it the id/name transcribed from every swatch
  by eye, cross-checked the row/column counts the detector found against that
  transcription, and it recovered 261 construction-block texture ids and the
  27 most common terrain surface types cleanly.
    - **Investigation note:** initially assumed the terrain picker's on-screen
      id badges were the same id space `MaterialColors.forTexture()` already
      keys on (`Terrain.id`, confirmed via `Terrain.get(id)` in
      `dumpSurfaceIds()`). `javap -c -p` on `Terrain.class` in `PluginAPI.jar`
      disproved that: `Terrain.id` is a real field set in the enum's
      `<clinit>` (e.g. `Grass` -> ordinal 38, `id` 100), but the picker's
      badge for "Grass" reads 41 — not the id, not the ordinal either, no
      clean formula found. Whatever list that picker enumerates, it isn't
      `Terrain.id`. The names, however, read straight off the swatch and match
      the enum constants exactly, so the terrain table was matched **by
      name**, not badge number; the construction/block picker has no such
      enum to cross-check against, but is a build-menu catalog the game must
      key builds on consistently, so its badge numbers were taken at face
      value as real texture ids.
    - **New `render/ConstructionColors.java`**: id -> color for player-built
      blocks, same shape as `MaterialColors` (a flat `int[1024]` filled once at
      class-init via forward-fill from a sorted id/color table, so a lookup at
      render time is still a single array index — no added per-tile cost).
      Replaces the old `TileRenderer.constructionColor()` scheme, which had no
      relation to the real material: an unpainted block picked a color from a
      10-entry hand-tuned palette by `texture % 10`. `MinimapConfig.
      constructionPalette` / `defaultConstructionColor` are gone (fully
      superseded; `ConstructionColors` bakes the same neutral fallback for
      unknown ids).
    - **`MaterialColors.colorFor()`**: 27 of the `Terrain` enum's most common
      surface types (Stone, the Gravel/Forestground/Sandstone variants, Dirt,
      Mud, Farmland, Forestmoss, Sanddesert, Sandbeach, Sandunderwater,
      Volcanic, Obsidian, Hellstone, Snow, Ice, Underwater, Coal, Grass,
      Redclay, Drydirt, Desertstone) now use the measured average, marked
      `// measured` at each case. Types with no swatch in the screenshots
      (ores, rarer grass variants, Cobble/Rubble/glow variants, water) keep
      the old hand-picked estimate.
  Net effect: terrain and player builds should both read closer to their
  actual in-game color at a glance. Performance impact is zero beyond the
  existing lookup-table pattern (still one `int[]` index per cell/block; the
  extraction itself is a one-time offline step, not part of the plugin or the
  build). NOTE: wants an in-game eyeball, especially on `Underwater` (matched
  from the picker's "Algae" label) and a few construction ids that fell in
  gaps between screenshots (e.g. 102, 104) and so inherit their nearest lower
  neighbor's color by the same forward-fill rule `MaterialColors` already used.

- **v2.36:** Follow-up on v2.35's real texture colors, from user feedback: (1)
  terrain colors should blend into each other for a smoother base map, but
  construction blocks should stay sharp-edged; (2) where are the colors stored
  for manual tweaking.
    - **Terrain blur.** `TileRenderer.render()` now builds a base-material-color
      grid padded 2 cells on every side (from the raw LOD read's existing
      2-cell cross-chunk border — real neighboring-chunk data, not a guess),
      then each cell's base color is a weighted (binomial/approx-gaussian)
      blend of its neighborhood before relief/elevation shading and water
      blending are applied exactly as before. New `config.terrainBlurRadius`
      (default 1): 0 = off (old sharp behavior), 1 = 3x3 kernel, 2 = 5x5.
      Command `/mm blur [0|1|2]` (no arg cycles 0->1->2->0), clears the tile
      cache and re-renders so it's visible immediately. Because the border
      comes from real adjacent-chunk data, the blur doesn't introduce a seam
      at chunk boundaries. The fallback path (no raw LOD access) clamps to the
      tile edge instead, since it has no cross-chunk data to blend with.
      Construction blocks are painted in `overlayConstructions()` *after* this
      whole pass, unchanged, so they're never blurred — confirmed by reading
      the code path, not just by design intent.
    - **Where to hand-tune colors:** answered in chat, not a code change —
      terrain material colors live in `render/MaterialColors.java`
      (`colorFor()`, one `case EnumName -> 0xFFRRGGBB` per `Terrain` constant)
      and construction/block colors live in `render/ConstructionColors.java`
      (the parallel `IDS`/`COLORS` arrays from v2.35, one hex per texture id).
      Both are plain `0xFFRRGGBB` hex — edit in place and rebuild. `/mm ids`
      still prints the surface/equipment ids seen at the player's position for
      finding which id to change.

- **v2.37:** Fixed wrong colors on constructions built with a "natural"
  texture (stone/ore/hellstone/etc.), reported by the user: a block built with
  the Hellstone texture rendered as a generic tan instead of red-black. Root
  cause, confirmed by the user checking `getTexture()` on the block: it
  reported id 29 — exactly `Terrain.Hellstone.id` — so Rising World's building
  system lets you construct with "natural" textures that reuse the same id
  space as `Terrain.id`, separate from the manufactured brick/wood/tile/glass/
  etc. catalog `ConstructionColors` was scraped from (which only starts at id
  100). Ids below 100 therefore had no catalog entry and fell back to
  `ConstructionColors.DEFAULT`, a flat generic tan, regardless of which
  natural material it actually was.
    - Note this was never a natural-vs-placed ambiguity in the renderer itself
      — `TileRenderer.overlayConstructions()` only ever touches cells with a
      real `ConstructionElement` from `chunk.getAllConstructionElements()`, so
      a natural terrain body was never mistaken for a placed block. The bug
      was purely a coverage gap in the color table for one id range placed
      blocks can legitimately use.
    - Fix (`ConstructionColors`'s static initializer): for any texture id
      below the catalog's lowest known id, fall back to
      `MaterialColors.forTexture(id)` — the same measured terrain color —
      instead of the flat default, since it's almost certainly the same
      underlying texture. Catalog ids (100+) are unaffected; gaps between
      *those* (e.g. 102, 104, genuinely unused) still forward-fill from the
      nearest lower catalog color as before. A Hellstone-textured block now
      reads as `MaterialColors`'s measured Hellstone color (`0xFF882D2B`).
  NOTE: wants an in-game look at a few natural-textured builds (stone, ore,
  hellstone) to confirm they now read as their real material instead of tan.

- **v2.38: Cave entrances + a first cave mode.** User report (with screenshot
  `cave_issue.jpg`): standing right in front of an obvious sinkhole/cave mouth,
  the minimap showed unbroken flat grass. Root cause: the surface map is built
  entirely from `Chunk.getLODTerrain()`/`getRawLODTerrain()`, a 2D heightmap —
  one height + one texture id per (x,z) column. A real hole (sinkhole, cave
  mouth, dug shaft) still has *some* height value in that heightmap, so the
  renderer just draws whatever ground it thinks is there; the heightmap has no
  way to represent "actually open here." Confirmed via the SDK that real 3D
  terrain exists via a separate API: `Chunk.getChunkPart(cy)` -> `ChunkPart`,
  which exposes the actual per-voxel terrain (`getTerrain()`/`getTerrainID`,
  id 0 = Air), 32 x 64(`SIZE_Y`) x 32 per part, multiple parts stacked
  vertically per chunk. The user also pointed out `Player.isInCave()` (used by
  the game's own F3 debug overlay), which the SDK confirms exists and returns
  whether the player is currently underground.
    - **Cave-opening detection** (`TileRenderer.render()`, gated by new
      `config.caveDetectionEnabled`, default on, `/mm caves`): before the main
      per-cell loop, bulk-fetches the real voxel data (`ChunkPart.getTerrain()`,
      one native call per vertical part needed, same "read once, index
      locally" pattern as the existing raw LOD read) for the chunk's actual
      height range, capped at `caveMaxChunkParts` (4) parts so a chunk with a
      huge elevation range can't force unbounded reads. Per cell, checks
      whether the real voxel at (and one below) the LOD-reported surface is
      air — requiring *two* consecutive air voxels, not one, so ordinary
      sloped terrain's rounding noise can't false-positive. If it's a real
      hole, scans downward (bounded by `caveScanDepth`, 48 blocks) for the
      real floor and renders *that* material/depth through the existing
      relief/elevation-tint pipeline (just fed the real found Y instead of the
      fictitious LOD height — reused as-is, no new shading math), skipping the
      slope-relief term (neighboring LOD heights aren't meaningful across an
      opening) and the surface's water flag (it was computed for the
      fictitious surface, not the real floor). No floor found in range ->
      `caveVoidColor` (a dark "unknown depth" tone). This is Part A: the map
      now looks *down through* an entrance instead of papering over it.
    - **Cave mode** (Part B, `config.caveModeEnabled`, default on,
      `/mm cavemode`): while `Player.isInCave()` reads true, the whole minimap
      switches to a local view of the real terrain around the player's current
      altitude instead of the (meaningless, underground) surface heightmap.
      New `TileRenderer.renderCave(chunk, cfg, centerY)`: per column, scans a
      window (`caveWindowUp`=4 above / `caveWindowDown`=40 below centerY) for
      an opening; solid the whole window -> `caveWallColor` (rock mass); open
      but no floor within the window -> `caveVoidColor` (drop-off/chasm); open
      with a floor -> that material's color, darkened per block of depth below
      the player (`caveDepthDarken`, floored at `caveDepthMin` so distant
      floors don't go black). This turns a top-down slice of real voxels into
      roughly the same kind of floor-plan other voxel games' cave-minimap
      modes produce. New `MapRenderer.renderCaveAsync`/`snapshotCave`
      deliberately bypass `TileCache` — cave tiles depend on player Y as well
      as (x,z), and the shared surface cache is keyed by (x,z) only, so
      running cave tiles through it would either corrupt its key space or
      thrash it on every altitude change; cave mode's view is small (its own
      independent zoom, `caveZoomCells`=48) so rendering fresh each time is
      cheap enough not to need a cache. `MinimapHud` gained `setCaveMode()`
      (swaps which renderer/zoom geometry is active, tints the map border
      amber as a visual cue, forces a clean re-render) and factors the
      resize-and-reset logic it shares with `setZoom()` into
      `applyGeometryChange()`. `PlayerSession.tick()` polls `isInCave()` and
      debounces it (`caveModeDelaySeconds`, 1.5s) before calling
      `hud.setCaveMode()`, so standing right at a cave mouth doesn't flicker
      the map between modes.
  Both features share the same low-level voxel-scan helpers
  (`prefetchParts`/`voxelAt`) in `TileRenderer`. Neither is cached beyond the
  existing per-render throttling — cost is bounded to a couple of bulk voxel
  reads per chunk render (surface) or per cave-mode re-render (small, tight
  zoom), not a per-frame tax.
  NOTE: wants real in-game verification — the exact sinkhole from the
  screenshot, plus general spelunking to check cave mode's wall/floor/void
  read sensibly and that the two-consecutive-air-voxel check doesn't
  false-positive on ordinary steep slopes. Known limitation, not attempted
  here: a hole whose real floor is genuinely underwater (e.g. the swirling
  water visible at the bottom of the user's screenshot) renders as dry floor
  material — the surface water flag is suppressed for hole cells because its
  depth doesn't apply to the real floor, and computing real underwater depth
  down there is unadressed follow-up work.

- **v2.39: World objects — trees/flowers/crops and ore rock markers.** User
  asked for feasibility of rendering trees/ore/flowers on the minimap, live
  (a chopped tree must disappear). SDK research: `Chunk.getAllPlants()`/
  `getAllObjects()` are fresh, uncached reads every call (a chopped tree is
  just absent next call — no staleness concern). Trees/flowers/ore are all
  `Plant` instances (`Plants.Type`: Tree, FruitTree, Crop, Plant, Rock, ...) —
  ore isn't its own SDK class, just `Rock`-typed plants distinguished by their
  definition name (`Plant.getDefinition().name`, e.g. "Iron Rock"). Full
  place/remove/destroy vegetation events exist (`PlayerDestroyVegetationEvent`,
  `PlayerRemoveVegetationEvent`, `PlayerCreativeRemoveVegetationEvent`,
  `PlayerPlaceVegetationEvent`, `PlayerCreativePlaceVegetationEvent`), but
  there is **no "spawned" event** for naturally-generated vegetation — only
  player-driven place/destroy is event-covered, so discovering *new* objects
  is necessarily periodic polling, confirmed against the SDK rather than
  assumed. User chose (asked directly): trees **and** flowers/crops included,
  ore markers on a **tight fixed radius** (~64m) rather than tied to zoom.
    - **Vegetation** (`config.showVegetation`, default on, `/mm vegetation` /
      `/mm trees`): new `TileRenderer.overlayVegetation()`, called in
      `render()` right before `overlayConstructions` (so a building on a
      former tree's cell still reads as the building, not a tree). Same
      pixel-paint technique already used for construction blocks — a flat
      tint per `Plants.Type` (new `render/VegetationColors.forType`), written
      straight into the tile at the plant's cell. Cheap: one more
      `getAllPlants()` bulk read per chunk render (same amortization as
      everything else — only on cache miss, not per frame) plus a trivial
      per-plant loop. A felled tree's `Plant` object often survives as a stump
      (`isCut()==true`); those are explicitly skipped so the map doesn't keep
      painting a "tree" over what's now just a stump. Rocks are excluded here
      (color 0 from `VegetationColors.forType`) — they're sparse and worth
      calling out individually, so they get real markers instead (below). All
      five vegetation events now call the existing `onWorldEdit()` chunk-tile
      invalidation (same path terrain edits already used), so a planted or
      removed plant updates the map immediately, not just on the next natural
      re-render.
    - **Resource (ore rock) markers** (`config.showResourceMarkers`, default
      on, `/mm resources` / `/mm ore`): real icon markers, like waypoints —
      trees/flowers are dense (hundreds per chunk, hence pixels), ore is
      sparse and worth an actual marker. New `resource/ResourceService`
      (world-shared, one instance for the plugin, like `TileCache`): a
      access-order-LRU map of tracked rocks (`resourceMaxTracked`=4096, bounds
      memory over a long exploring session) keyed by the plant's global id.
      **Discovery** (`maybeRefreshNear`, called from `PlayerSession.tick()`)
      re-scans the chunks within `resourceMarkerRadius` (64 blocks) of the
      player every `resourceRefreshSeconds` (5s), with a per-chunk cooldown
      (same shape as `TileCache`'s not-loaded-chunk cooldown) so overlapping
      requests from nearby players don't double-scan. **Removal** is instant
      and event-driven — the three destroy/remove vegetation event handlers
      call `resourceService.remove(plant.getGlobalID())` unconditionally
      (a no-op if it wasn't tracked), so a mined rock vanishes from the map the
      moment it's mined, not on the next 5-second scan. Ore color is resolved
      once at discovery time by keyword-matching the rock's definition name
      (`VegetationColors.forOreName`: gold, silver, copper, iron, tungsten,
      aluminium, coal, sulfur, saltpeter, quartz/crystal; unrecognized falls
      back to `resourceDefaultColor`).
    - **Rendering**: extended `ui/MarkerOverlay` (not a new overlay class) with
      a second, independent pooled icon set (`resourceIconPool`=24, its own
      texture — a new baked gem/crystal shape in `MarkerTextures.gem()`, kept
      visually distinct from the spawn diamond and the 8 waypoint icons) so
      resource markers never compete with the waypoint icon budget. Reuses the
      existing circular clip box and `project()` math. Unlike waypoints,
      off-map resource markers are simply skipped rather than edge-clamped —
      "a rock is somewhere in that direction" isn't useful information the way
      an edge-clamped waypoint hint is. `MinimapHud`/`PlayerSession` now carry
      a `ResourceService` reference alongside the existing `WaypointService`
      one; drawn together with waypoints, gated on the same compass tier (no
      new tier introduced).
  NOTE: wants in-game verification — tree/crop colors read reasonably at a
  glance, a chopped tree's pixel disappears immediately, ore markers appear
  within the 5s scan window and vanish immediately when mined, and the ore
  keyword-color guesses aren't wildly wrong for the actual in-game rock names
  (only verified against the API doc's example "Iron Rock" naming pattern, not
  a full survey of every ore's real definition name the way the v2.35 terrain
  colors were verified against actual screenshots).

- **v2.40:** Three fixes on v2.39's vegetation/resource work, from user feedback
  after trying it in-game (with a reference screenshot, `screenshots/
  trees_flowers.png`, of the rounded-canopy-blob style being aimed for):
    - **Trees now stamp a rounded canopy blob, not one pixel.** A full-grown
      tree read as noise at one pixel. `TileRenderer.overlayVegetation()`
      rewritten: each plant stamps a filled circle (`stampCanopy`) sized from
      its *actual* in-world scale (`Plant.getScale()`, base radius ~1.7 blocks
      for Tree/1.4 for FruitTree at scale 1.0, smaller for
      Crop/Plant/Trunk so flower fields still read as a scatter of dots, not a
      solid patch), radius/scale both clamped against a bogus extreme value.
      Each tree also gets a small deterministic ±10% brightness jitter seeded
      from its stable `getGlobalID()` (hashed, not random — same forest looks
      the same on every render) so a dense forest reads as a mottled mass of
      individual canopies, matching the reference image, instead of one flat
      color. Felled-tree stumps (`isCut()==true`) are still skipped.
    - **Species-based color, not just type-based.** `render/VegetationColors`
      rewritten around name-keyword matching (same technique as the ore-name
      matching added in v2.39) instead of one flat tone per `Plants.Type`:
      separate keyword tables for trees (pine/fir/spruce → dark blue-green,
      birch → pale yellow-green, palm/jungle/dead/redwood/oak/maple/etc.),
      fruit trees, crops (wheat gold, pumpkin orange, cotton white, ...), and
      — the user's specific complaint — flowers, now a large table of
      deliberately vivid/saturated colors (Heather → pink, per the user's
      example; rose, poppy, tulip, sunflower, dandelion, bluebell, lavender,
      thistle, orchid, and more) with an unmatched species still getting a
      vivid fallback (`0xFFE8D44D`) rather than the old dull green default —
      the user's core complaint ("many colors blended into the terrain too
      much... I want all flowers to be vibrant") was that every unmatched
      species fell back to a muddy green that read almost the same as grass.
      **Calibration note (repeated in the class doc):** these are best-effort
      guesses at Rising World's real species names — there was no way to
      screenshot-survey the full flower/tree list the way v2.35's terrain/
      construction colors were measured. `/mm ids` now additionally logs the
      unique plant species names (deduplicated) in the player's current
      chunk, specifically so real names can be reported back and the tables
      corrected.
    - **Ore markers fixed — wrong data source, not a rendering bug.** The user
      reported standing beside a real ore rock with no marker ever appearing.
      Root cause: v2.39 scanned `Chunk.getAllPlants()` for
      `Plants.Type.Rock`, but mineable ore in Rising World is a **terrain
      material** — Coal/Sulfur/Iron/Aluminium/Tungsten/Gold are literal
      `Terrain` enum entries (confirmed already in this codebase since
      v2.35's `MaterialColors`) — i.e. a colored rock outcrop embedded in the
      voxel terrain, not a `Plant` object at all. `Plants.Type.Rock` covers
      decorative boulders, which is why the scan found nothing near real
      deposits. `resource/ResourceService` now scans the same bulk raw-LOD-
      terrain layer `TileRenderer` already reads (one native call, then a
      cheap per-cell check against the 6 ore material ids) as the primary
      discovery path, with a minimum spacing between markers so one vein
      doesn't produce a marker per block; the old Plant-based Rock scan is
      kept as a harmless secondary path. Since a terrain-material "rock"
      doesn't have a stable object id or a destroy *event* the way a `Plant`
      does, add/remove is now handled by diffing each chunk's scan against
      its previous scan (`nodeKeysByChunk`) — anything found before and not
      found now is removed. `onWorldEdit` (already called on every terrain
      dig) additionally clears that chunk's scan cooldown, so a mined vein's
      marker disappears on the *next tick* rather than waiting out the normal
      5-second `resourceRefreshSeconds` interval — not instant the way a
      chopped tree's vegetation-event removal is, but close.
  NOTE: wants real in-game verification, particularly: do tree canopies at the
  new size/jitter actually look like the reference image at normal zoom; do
  ore markers now reliably appear near real deposits (the terrain-material
  hypothesis is strong evidence but unverified in-game); and — most
  actionable — running `/mm ids` near a few different flower/tree species and
  reporting the exact names back so the keyword tables can be corrected rather
  than left as guesses.

- **v2.41:** Four more fixes on the vegetation/resource work, from user
  feedback after trying v2.40 in-game:
    - **Canopy blobs now darken toward the rim.** `TileRenderer.stampCanopy`
      shades each pixel by its squared distance from the blob's center (0 at
      center, up to `config.vegetationEdgeDarken`=0.45 darker at the rim,
      quadratic falloff so the center stays bright/flat and only the edge
      visibly shades) instead of a single flat fill — a cheap "lit dome" look
      requested to make individual canopies read better against neighbors.
    - **Unlabeled "Flower" entries are no longer rendered at all.** Per the
      user: only flowers with an actual specific name should show — a
      species literally named just "Flower" (or "Wildflower") has nothing
      meaningful to color it by. `VegetationColors.flowerColor` now returns 0
      (skip) for those exact generic labels before falling through to the
      keyword table, whereas any *other* specifically-named species still
      renders (with the vivid fallback color if no keyword matches yet).
    - **Saplings/growing trees now stamp a smaller blob.** Added
      `Plants.Stage`-based radius multiplier (`stageScale`):
      `vegetationSaplingScale`=0.35, `vegetationGrowingScale`=0.65, both
      applied on top of the existing scale-based sizing, `Default`/`Dead`
      stages unaffected.
    - **Ore markers: found the actual bug this time.** Still zero markers
      after v2.39/40's terrain-material fix. Root cause: that fix scanned
      only the top-down LOD surface layer (one texture id per column, the
      *topmost visible* block) — the exact same class of bug the cave-
      detection work fixed weeks earlier: an ore vein exposed on a cliff face
      or as a small outcrop isn't necessarily the topmost block of its
      column, so a 2D heightmap scan can miss real, visible ore entirely.
      `ResourceService.scanTerrainOre` now scans the real 3D voxel data
      instead, reusing the exact same bulk-fetch-once helpers cave detection
      already established (`TileRenderer.prefetchParts`/`voxelAt`, both
      promoted from `private` to `public` for this reuse): for each column, a
      window from `oreScanDepthAbove` (3 blocks) above to `oreScanDepthBelow`
      (20 blocks) below the LOD surface height is checked for an ore material
      id, bounded to `oreMaxChunkParts` (4) vertical ChunkPart fetches per
      chunk so a tall chunk can't force unbounded reads. This is a real
      architectural fix, not a tuning tweak — the previous version could
      structurally never find ore that wasn't the literal highest point of
      its column.
  NOTE: wants real in-game verification, most importantly whether ore markers
  *finally* appear near a real deposit — that's now been wrong twice, so
  treat it as unconfirmed until seen working. Also worth a look: whether the
  new edge-darkening reads as intended at normal zoom (too subtle/too strong),
  and whether sapling sizing is visually distinct enough from mature trees.

- **v2.42: Scope narrowed to trees only, and a real tree-shape redesign.**
  User request: drop flowers/crops/ore/bushes/branches entirely (per their
  feedback, ore markers never worked and the pixel-blob look wasn't good
  regardless), keep only trees, and give each tree type a genuinely distinct
  look — but "not sure what we have access to read while playing," so this
  needed real SDK research rather than more name-keyword guessing.
    - **Removed entirely:** the whole `resource/` package
      (`ResourceService`, `ResourceNode` — the ore-marker system, both the
      Plants.Type.Rock scan and the later terrain-voxel scan), the resource
      icon pool + `MarkerTextures.gem()` in `MarkerOverlay`, flower/crop color
      tables, and every config knob/command tied to them
      (`showResourceMarkers`, `resourceMarkerRadius`, `oreScanDepth*`, `/mm
      resources`, `/mm ore`). `ResourceService`/`MinimapHud`/`PlayerSession`/
      `PicSoulsMiniMap` wiring for it all reverted. `TileRenderer.
      overlayVegetation` now hard-filters to `Plants.Type.Tree`/`FruitTree`
      only — nothing else is painted.
    - **Better SDK signals found for tree look**, replacing the old
      `getScale()`-only sizing and name-keyword-only coloring:
      `Plants.PlantDefinition.extent` (`Plants.Size`: None/Tiny/SmallLight/
      Small/Medium/MediumLarge/Large/Huge — the species' actual *designed*
      size class, not a runtime guess) for canopy radius, and `windparam`
      (an `int`, documented as "the tree wind sound parameter" but its 8
      values are exactly a shape taxonomy: 1/2 = dead thin/thick, 3/4 =
      deciduous thin/thick, 5 = coniferous, 6 = palm, 7 = young, 8 = cactus)
      repurposed as the shape/category signal, since there's no dedicated
      visual-style field. Both are plain fields that come for free with the
      same `getDefinition()` call already being made — no extra cost.
      **Calibration note** (this mapping is taken from the javadoc, not
      verified in-game): `/mm ids` now logs each nearby tree's name plus its
      `windparam`/`extent`/`stage`, specifically so the categorization can be
      checked against real data.
    - **New `render/TreeColors.java`** (replaces the old
      `VegetationColors.java`): `lookFor(type, windparam, name)` resolves a
      `Look` (color, `Shape` enum, radius multiplier) primarily from
      `windparam`, falling back to a name-keyword guess only when `windparam`
      is outside the documented 1-8 range; `radiusForExtent(extent)` gives
      the base canopy radius. Fruit trees get a warm base tone plus an
      `fruitAccentColor(name)` (cherry/apple/orange/peach/plum/olive/fig/
      pear, else a generic warm accent) for a few bright rim dots.
    - **New per-shape pixel stamps in `TileRenderer`** (`Shape.ROUND` reuses
      the existing domed-disc `stampCanopy`; the rest are new):
      `stampConifer` (the domed disc plus single-pixel spikes past the rim at
      8 fixed angles — a plain circle reads as deciduous, a jagged edge reads
      as needled), `stampPalm` (a small bright core plus 5-7 thin frond arms
      radiating outward — the recognizable top-down palm starburst),
      `stampSparse` (a deterministically-dithered ~half-density fill for dead
      trees — bare branches read as thin/gappy, not a solid canopy), and
      `stampCross` (a thin plus-sign for cacti, which don't have broad
      top-down foliage). `stampFruitAccents` scatters a few small accent dots
      near a fruit tree's rim at angles derived from its stable id
      (deterministic, not random).
  NOTE: wants real in-game verification — most importantly whether
  `windparam`'s assumed 1-8 mapping actually matches real tree data (run
  `/mm ids` near a few different trees and compare), and whether the new
  shapes (conifer spikes, palm starburst, dead-tree dither, cactus cross)
  read as intended at normal zoom rather than as noise.

- **v2.43:** Three more fixes from user feedback on v2.42:
    - **Tree canopies were being cut off at chunk boundaries — root cause
      found.** A tree's canopy is stamped only into the tile of the chunk it
      belongs to (`Plant[]` comes from that chunk's own `getAllPlants()`); a
      tree near a chunk edge has a canopy that geometrically overlaps into
      the *neighboring* tile, but that neighbor never sees this tree at all
      (it's not in the neighbor's own plant list), so the overlapping half
      was simply never painted — the canopy read as sliced off exactly at
      the chunk boundary. Looked patternless to the user because chunk
      boundaries aren't drawn on the map, but it isn't tied to any specific
      tree type or size — any tree within its canopy radius of any of its
      chunk's 4 edges was affected, which is a meaningful fraction of all
      trees. Fixed in `TileRenderer.overlayVegetation`: now scans the
      surrounding 3x3 chunks (new `safeGetChunk`, a defensive
      try/catch-wrapped `World.getChunk`, matching the existing precedent in
      `MapRenderer.snapshotCave`), translating each neighbor's trees into
      this tile's coordinate space — the existing `paintPixel` bounds
      clipping then naturally keeps only the true overlapping sliver.
      New `config.vegetationScanNeighbors` (default on) is an escape hatch
      back to single-chunk scanning, since this costs up to 9x the
      tree-scanning work per chunk render (still only on cache miss/rebuild,
      not per frame).
    - **Fruit trees: gated on actually having fruit, not just being a fruit
      species.** User wanted accent dots only on a tree that currently has
      pickable fruit, and asked whether that's even readable — it is:
      `Plants.PlantDefinition.pickupitem` is set to the item name (e.g.
      "apple") while fruit is present, and picking presumably swaps the plant
      to a barren definition (`pickuprestplant`) with no `pickupitem` — so
      `def.pickupitem != null && !isBlank()` is a live "has fruit right now"
      signal, not a species check. `TreeColors.fruitAccentColor` now keys off
      `pickupitem` directly (more precise than the tree's own name) instead of
      `def.name`. Dots are also bigger/chunkier now for larger canopies
      (`stampFruitAccents`: up to 5 accent spots, 2-3px blobs instead of
      single pixels once the canopy itself is large enough).
    - **Cave openings now vignette (bright rim → black center) instead of a
      flat grey patch.** New `TileRenderer.applyHoleVignette`: for each hole
      cell, finds the Chebyshev distance to the nearest non-hole (solid)
      neighbor (`nearestNonHoleDist`, a bounded ring search, cheap since hole
      cells are rare), then blends the cell's own resolved color toward a
      bright highlight (`config.caveRimColor`) near the rim and darkens it
      quadratically toward near-black (`caveCenterDarkness`) deep inside the
      patch — reading as an actual hole with light catching the edge, not a
      uniform tone. Shared between `render()`'s surface cave-opening
      detection and `renderCave()`'s void/chasm cells (wall cells are
      untouched — they're solid rock, not a hole). New config:
      `caveEdgeGlowEnabled` (default on), `caveRimColor`, `caveRimStrength`,
      `caveCenterDarkness`, `caveEdgeFalloff`, `caveEdgeSearchMax`.
  NOTE: wants real in-game verification for all three — whether tree canopies
  near chunk edges are now whole, whether fruit dots only appear on trees that
  visibly have fruit (this depends on `pickupitem` behaving as inferred from
  its javadoc, not confirmed against actual gameplay), and whether the cave
  vignette actually reads as "a hole" rather than just a differently-shaped
  patch.

- **v2.44:** v2.43's fruit-presence gating was wrong — user confirmed with two
  screenshots (`screenshots/with_apples.jpg`, `without_apples.jpg`): a
  visibly fruiting apple tree (red apples clearly visible in-world, hand
  cursor prompting to pick) never got accent dots on the minimap at all, in
  either state. So `PlantDefinition.pickupitem != null` is not the "has fruit
  right now" signal it was assumed to be from its javadoc.
    - **Reverted the gate.** `TileRenderer.overlayVegetation` now stamps
      fruit accents on every `FruitTree` unconditionally again (matching
      pre-v2.43 behavior), keying the accent color off `def.name` instead of
      `def.pickupitem` (more robust — always populated, whereas pickupitem's
      actual behavior is now unconfirmed). Better a dot that's sometimes
      wrong than a feature that silently never shows anything, while the real
      signal is still unknown.
    - **This is the second wrong guess in a row on this specific mechanism**,
      so rather than guess a third field blindly, `/mm ids`'s tree dump was
      substantially expanded: every `PlantDefinition` field that could
      plausibly relate to fruiting state is now logged (`cangrow`,
      `nextgrowthstage`, `pickupitem`/`pickupitemcount`/`pickuprestplant`,
      `harvestable`/`harvestitem`/`harvestitemcount`/`harvestrestplant`,
      `destroyitem`/`destroyitemcount`/`destroyrestplant`, `assetpath`), and
      — importantly — **deduplicated by definition `id` instead of `name`**.
      If a fruiting vs. barren apple tree turns out to be two distinct
      `PlantDefinition`s that just happen to share the display name "Apple
      tree" (the same pattern growth stages already use via
      `nextgrowthstage` — a differently-`id`'d definition swapped in), the
      old name-based dedup would have silently collapsed them into one log
      entry and hidden the exact field we're looking for.
  NOTE: this needs the user to run `/mm ids` near the same tree in both the
  fruiting and just-harvested state and compare the two console dumps —
  whichever field(s) differ between them is almost certainly the real signal,
  which can then be wired into `overlayVegetation`'s fruit-accent gate
  properly instead of guessed again.

- **v2.45: v2.44 misdiagnosed this — corrected.** The user clarified: v2.43's
  `pickupitem`-based fruiting check was already correct (the canopy look
  *did* differ correctly between fruiting/harvested states); the only real
  bug was that the accent dots themselves never appeared on screen even when
  the check fired. v2.44 wrongly concluded the check itself was broken and
  reverted it to unconditional — that's undone here.
    - **Root cause: paint-order overwrite, introduced by v2.43's own
      neighbor-chunk scan.** Fruit accent dots were painted immediately after
      each tree's own canopy, interleaved per-tree. Once `overlayVegetation`
      started scanning the surrounding 3x3 chunks (for the chunk-edge
      canopy-clipping fix), any *other* tree processed later in that same
      pass — same chunk or a neighbor, unremarkable in a dense orchard where
      fruit trees stand close together — would paint its own canopy directly
      over an earlier tree's already-placed accent dots, silently erasing
      them before the frame ever reached the screen. This bug could even
      have existed before the neighbor-chunk scan (two nearby trees in the
      same chunk, processed in array order), just less reliably triggered.
    - **Fix:** split `overlayVegetation` into two passes. Pass 1 paints every
      tree's canopy (this chunk + neighbors, as before); fruiting trees are
      no longer painted immediately but queued into a `PendingFruit` list
      (position, radius, color, seed). Pass 2 runs only after *all* canopies
      from *all* 9 chunks are down, painting every queued fruit accent last
      — guaranteeing they're always on top regardless of tree order or
      proximity, which the old interleaved approach could never guarantee.
    - Restored the `pickupitem`-based `hasFruit` gate exactly as it was in
      v2.43, and the accent color lookup back to `def.pickupitem` (more
      precise than the tree's own name, and now trusted).
  The `/mm ids` expansion from v2.44 (dumping every pickup/harvest/destroy
  field, deduplicated by definition id) is kept regardless — harmless, and
  still useful groundwork if another fruiting-adjacent field ever needs
  calibrating.
  NOTE: wants in-game confirmation that fruit dots now actually render and
  stay visible on a fruiting tree, especially in a cluster of several fruit
  trees close together (the exact scenario the paint-order bug would have
  hit hardest).

- **v2.46:** Still no red pixels, per a new screenshot (`screenshots/no_red.jpg`)
  showing a visibly fruiting apple tree whose minimap canopy renders as only
  "a few green shaded pixels" — no red at all, even after v2.45's paint-order
  fix. The small canopy size in that screenshot points at a second,
  independent bug: `overlayVegetation`'s fruit-accent call was gated on
  `radius > 0.8f`, and `stampFruitAccents` spreads its dots at
  `radius * 0.65` from center — for a small enough canopy (a modest extent
  class, a thin windparam multiplier, sub-1.0 runtime scale all compounding),
  the radius can easily land under that 0.8 floor, silently skipping the
  fruit-accent call entirely regardless of the v2.45 paint-order fix. That's
  backwards: a small tree is exactly where the accent is needed most to
  signal fruiting at all, since the canopy alone doesn't have room to look
  different. Removed the radius floor — `hasFruit` alone now gates it.
  `stampFruitAccents` already degrades gracefully for a tiny radius (the
  angled offsets round down to 0, so all "dots" collapse onto the canopy's
  own center pixel rather than spreading — still a clearly visible color
  swap, not a no-op).
  NOTE: wants in-game confirmation again — and, since this is now the third
  attempt, worth double-checking the obvious operational thing too: the
  installed jar is only picked up on a fresh game start or world switch, not
  hot-reloaded, so a screenshot taken without restarting/rejoining after a
  new build was deployed would still show the previous version's behavior
  regardless of what changed in source.

- **v2.47: Stopped guessing, added real instrumentation.** Three consecutive
  fixes to the fruit-accent logic (v2.45's paint-order two-pass, v2.46's
  radius-floor removal) produced no visible change per the user — still zero
  red pixels. Continuing to patch the paint logic blind isn't productive
  anymore; the next step has to be seeing what the *actual render path*
  observes, not a side-channel `/mm ids` snapshot that runs on a different
  call (and, worth noting separately: fruit *regrowing* on an existing tree
  over time isn't a player action, so none of the place/destroy/remove
  vegetation events this plugin hooks would fire for it — meaning a chunk's
  cached tile could in principle be stale from before fruit regrew, and no
  amount of paint-logic fixing would ever show up until that chunk's tile
  happens to get invalidated some other way. Not confirmed as the cause, but
  a real possibility worth ruling out.)
    - New `config.debugFruitLogging` + `/mm fruitdebug [on|off]`: logs every
      `Plants.Type.FruitTree` instance `TileRenderer.paintTreesFrom` actually
      processes, straight from inside the render call — name, definition id,
      `pickupitem`, the resulting `hasFruit`, canopy radius, and local
      tile/chunk position. Toggling it also force-clears the tile cache and
      invalidates the session's map, so the very next render (immediate, not
      whenever that chunk would naturally rebuild) logs. This directly
      answers the open questions: is `pickupitem` actually populated for a
      visibly fruiting tree at the moment the renderer looks at it, does
      `hasFruit` resolve true, and is a `PendingFruit` actually being queued
      — rather than guessing which link in that chain is broken.
  NOTE: needs the user to run `/mm fruitdebug on` while standing near a
  visibly fruiting tree and share the `[fruitdebug]` console lines. That
  output should make the actual root cause obvious instead of requiring
  another blind guess.

- **v2.48: found via the log, not another guess.** User ran `/mm fruitdebug on`
  standing next to a confirmed pickable/fruiting apple tree, then exited —
  the plugin's console output goes to Rising World's own live log
  (`AppData\LocalLow\JIW-Games\Rising World\Player.log`), so this was read
  directly rather than needing anything pasted. The command ran (confirmed:
  chat acknowledgement + forced-re-render SPIKE lines right after), but
  **zero `[fruitdebug]` lines ever printed**. That log line was gated on
  `def.type == Plants.Type.FruitTree`; since it never fired even once next to
  a tree that definitely had fruit, apple trees are almost certainly just
  `Plants.Type.Tree` in this game, not a separate `FruitTree` type — meaning
  every fruit-accent fix since v2.42 (the whole feature) has been silently
  skipped by this type check before any of that logic ever ran. All three
  "fixes" (v2.45 paint order, v2.46 radius floor) were real, sound
  improvements, but couldn't have mattered — the code they touched was
  unreachable for an actual apple tree the whole time.
    - **Fix:** `hasFruit` in `TileRenderer.paintTreesFrom` now gates purely on
      `def.pickupitem` being present, with no type restriction — works for a
      `Tree`-typed fruit tree exactly the same as a `FruitTree`-typed one.
      `/mm fruitdebug` logging is likewise no longer restricted to
      `FruitTree`, and now also prints `type=` per tree, so if this still
      isn't the whole picture the real type is visible in the next log
      capture instead of assumed.
    - Not addressed here (separate, lower-stakes issue): `TreeColors.lookFor`
      still uses `def.type == FruitTree` to pick a warmer canopy tone for
      fruit trees; if apple trees are really plain `Tree` type, they've also
      been getting the generic tree canopy color instead of the fruit-tree
      tone this whole time. Worth revisiting once the dots themselves are
      confirmed working.
  NOTE: wants in-game confirmation — restart (v2.48 must actually load),
  stand near a fruiting tree, check the minimap directly (no need for
  `/mm fruitdebug` this time, since the actual gate is fixed now — that
  command is only needed again if dots are still missing).

- **v2.49: terrain flash on tree edit — fixed.** After v2.48, the user reported
  a new symptom: picking apples or chopping a tree makes nearby terrain tiles
  flash or briefly go blank. Root cause traced through the existing edit/render
  pipeline rather than guessed: `onWorldEdit` invalidates a 3x3 block of chunks
  around the player on every vegetation edit (always has, for any terrain edit),
  but `TileCache.invalidate()` fully removed each tile from the cache
  (`tiles.remove(k)`), so the next render pass saw them as outright misses. Pass
  2 of `MapRenderer.snapshot()` rebuilds misses nearest-to-center under a
  `snapshotBudgetMs` time budget (default 3ms); since v2.43 added a 3x3
  neighbor-chunk scan to every tree render (for canopy chunk-edge clipping),
  each chunk rebuild got roughly 9x more expensive, making it much more likely
  that several of the 9 simultaneously-invalidated chunks miss the budget in a
  single pass. Chunks not rebuilt in time stay `null` in the snapshot and get
  drawn as the dark `UNEXPLORED` placeholder in `encode()` — and
  `MinimapHud.onRenderDone()` reveals that PNG immediately regardless of
  completeness, only scheduling a later fill-retry (~400ms) for anything still
  missing. That's the flash: dark placeholder shown immediately, real terrain
  patched back in a beat later.
    - **Fix:** `TileCache` no longer discards a tile on invalidate. A new
      `Set<Long> dirty` field is marked instead (`invalidate()`), and the old
      tile stays in the LRU map. `peek()` (used by pass 1) still returns it
      immediately, so the map always has *something* correct-looking to show
      the instant it's needed. `get()` (used by pass 2) still attempts a real
      rebuild for a dirty entry, but if that rebuild can't happen right now
      (still on the chunk's retry cooldown, or the chunk fails to load) it now
      falls back to returning the stale tile instead of null. New
      `isDirty(cx, cz)` accessor; `clear()` also clears the dirty set.
      `MapRenderer.snapshot()`'s pass 1 now queues a chunk into the pass-2
      rebuild list whenever `cache.isDirty(...)` is true, even though it
      already filled that chunk's (stale) tile from `peek()` — so a dirty
      chunk keeps showing its last known-good appearance on screen while a
      background rebuild is attempted within the same time budget, and only
      ever gets replaced once the rebuild actually finishes.
  NOTE: wants in-game confirmation — restart (v2.49 must actually load), pick
  fruit from a tree or chop one down, and confirm the surrounding terrain no
  longer flashes/blanks out before settling to the updated look.

- **v2.50: entity/radar tier — new feature.** User asked for a radar showing
  nearby animals/npcs as markers, gated behind an equipped item like the other
  tiers, specifically: equipping the upgraded compass ("compassmodern") should
  grant the radar tier IN ADDITION to the normal compass tier, while the older
  "compassold" keeps only the normal compass tier it already had. For now,
  simple colored dots/blips (matching the player marker's own look) rather
  than per-species icons, with facing direction if feasible.
    - **SDK research first** (no prior familiarity with the npc API in this
      codebase): `net.risingworld.api.objects.Npc` represents "animals, humans,
      monsters etc." — `getPosition()`, `getViewDirection()` (normalized aim
      vector), `getBehaviour()` (`Npcs.Behaviour`: Default/Shy/
      DefensiveAggressive/Aggressive/Dummy), `getDefinition()` →
      `Npcs.NpcDefinition.type` (`Npcs.Type`: Human/Animal/Mount/Skeleton),
      `isDead()`/`isInvisible()`. `World.getAllNpcsInRange(Vector3f, float)`
      gives every live npc within a radius directly — no need to iterate all
      loaded chunks by hand the way tree scanning does.
    - **Tier plumbing:** `Capabilities` gained a fifth flag, `radar`.
      `CapabilityService.compute()` derives it from a new, independent,
      stricter equipment-slot token: `radarItemName = "compassmodern"` (vs.
      the existing `compassItemName = "compass"`, which already matches both
      compassold/compassmodern by substring for the base compass tier) — so a
      player with compassmodern equipped gets both `compass=true` (from the
      broad token) and `radar=true` (from the strict one), while compassold
      only ever matches the broad token. `MinimapHud.setTiers(...)` gained a
      fourth parameter; `PlayerSession` passes `caps.radar()` through at both
      call sites.
    - **Scanning:** new `net.picsoul.rw.minimap.radar` package —
      `RadarBlip` (a resolved world position + optional facing + color) and
      `RadarScanner` (one instance per player, owned by that player's
      `MinimapHud`, since — unlike the world-shared waypoint list read once
      from Maps.db — an npc scan is inherently position-dependent and can't
      be shared across players the same way). Throttled to once per
      `radarScanIntervalSeconds` (0.4s default) so a stationary player doesn't
      re-query `getAllNpcsInRange` every tick; nearest-first sort + a
      `radarMaxTracked` cap (24 default) so a big herd can't flood the icon
      pool. Dead/invisible npcs are skipped.
    - **Classification (first pass, unverified — see NOTE):**
      `Npcs.Behaviour.Aggressive` → hostile red; `DefensiveAggressive` →
      caution amber (a lower threat level, reads differently at a glance);
      otherwise falls back to `Npcs.Type`: Animal → green, Human → blue,
      Mount → tan; anything else → a neutral gray default. All six colors are
      config knobs (`radarColor*`) so they can be retuned without a rebuild
      once real npcs have been checked against them.
    - **Facing direction:** feasible, using `getViewDirection()`'s horizontal
      (x,z) components: `facingDeg = atan2(view.x, view.z)`, the same
      clockwise-from-north bearing convention already established (by
      reverse-engineering `MinimapHud.updateCardinalLabels`' north-label
      placement formula) for the player's own `heading`. Reused the exact
      same teardrop pointer texture as the player marker (`MarkerTexture
      .teardrop`, per the user's "same style as the player marker" ask) so a
      facing blip is immediately recognizable as the same visual language,
      just tinted per classification and smaller (`radarIconPx`, 10px vs. the
      player marker's 16px).
    - **Rendering:** added directly onto the existing `MarkerOverlay` (which
      already owns the circular clip box, the world→screen `project()`
      helper, and the rotate-mode math for waypoints) rather than a new
      parallel overlay class — a `drawRadar(...)` method + its own pooled
      teardrop-icon array, reusing `project()` for position and applying the
      same rotate-mode correction (`facingDeg - headingDeg` when the map
      itself is rotated to face-up, since the overlay box is a sibling of the
      rotating map image, not a child of it, so both position AND the icon's
      own rotation need the same manual counter-rotation — same reasoning the
      existing waypoint code already uses for position). Off-map blips clamp
      to just inside the rim like waypoints do. `/mm radar [on|off]` toggles
      `config.showRadar`; `hideRadar()` is called separately from
      `setTiers()` when only the radar sub-tier (not the whole compass
      overlay) is off, so losing radar doesn't also hide waypoints/spawn.
    - **Calibration tooling:** extended `/mm ids` to also dump every npc
      within radar range (name, definition name, type, behaviour, dead/
      invisible, raw view-direction vector) to the server console, matching
      the same "verify the guess against real data" pattern used successfully
      for the terrain/tree work all session.
  NOTE: wants in-game confirmation on several fronts, since none of this could
  be tested without the game running: (1) does `compassmodern` actually exist
  as an equip-slot item name containing that exact substring — run
  `/mm ids` with it equipped and check the Equipment listing; (2) do the
  behaviour/type-based colors actually match red=dangerous/green=passive
  intuition for real animals and monsters — run `/mm ids` near a few different
  live npcs and compare; (3) does the `atan2(view.x, view.z)` facing angle
  visually point the blip the right way on the map, in both rotate and
  north-up mode.

- **v2.51: saddle detection for mounts + a full npc registry dump.** User praised
  v2.50's radar as a strong first attempt but flagged one gap: equipping a
  saddle on a horse didn't change its blip at all, so there was no way to tell
  a tamed/rideable horse from a wild one at a glance. Also asked to eventually
  design proper per-species icons, which first needs a full list of what npc
  types even exist in the game.
    - **Saddle detection — feasible, and reliable.** `Npc.getClothes()` returns
      a `Clothes` object; its javadoc explicitly says non-human npcs "can only
      wear certain clothes (like mounts, which can only wear saddles etc)" —
      and `Clothing.Function` (an enum for *what a piece of gear does*, not
      just its body slot) has literal `Saddle` and `Saddlebag` entries, plus
      `Clothes.hasSpecialGear(Clothing.Function)` to check for one. So
      `npc.getClothes().hasSpecialGear(Clothing.Function.Saddle)` is a direct,
      reliable saddle check — no guessing or name-matching required.
      `RadarScanner.colorFor` now special-cases `Npcs.Type.Mount`: saddled ->
      new `radarColorMountSaddled` (purple, for strong contrast against tan),
      unsaddled -> the existing `radarColorMount` (tan). This is a color-only
      distinction for now (still simple dots, per the current phase) — real
      per-species icons are the next step, see below.
    - **Full npc registry:** new `/mm npcs` (alias `/mm entities`) dumps
      *every* npc type the game has registered — not just what's nearby, the
      way `/mm ids` does — via the static
      `Definitions.getAllNpcDefinitions()`, grouped by `Npcs.Type` with each
      one's internal id and display name. This is the "list of all of them"
      the user asked for, to plan icon work against real data instead of
      whatever happens to wander by.
    - **`/mm ids`'s npc dump** also gained a `saddled=` field per nearby npc,
      for calibrating the same detection this session's `/mm ids` pattern has
      used for every other guess so far.
    - **Advice on custom per-entity icons (design conversation, not yet built):**
      the plugin has no way to extract real in-game model art — every icon in
      this project (player marker, waypoint shapes, tree canopy colors) is
      hand-authored pixel/vector art baked into a texture at runtime (see
      `MarkerTexture`, `MarkerTextures`, `TreeColors`), not a screenshot of the
      actual asset. So per-species icons means drawing simple recognizable
      silhouettes by hand (or supplying pre-made PNGs to embed), the same way
      the teardrop player marker was made. Given `/mm npcs` will likely turn
      up a few dozen definitions, the practical middle ground recommended
      (not yet decided/built) is a small set of *silhouette shapes* — a paw
      print or four-legged silhouette for generic animals, a humanoid outline
      for human npcs, a horse silhouette (plain vs. saddled) for mounts, a
      skull or angular shape for hostile monsters — reusing the existing
      color classification for the tint, rather than one hand-drawn icon per
      individual species name, unless the user specifically wants that level
      of fidelity once they see the full `/mm npcs` list.
  NOTE: wants in-game confirmation that `hasSpecialGear(Clothing.Function
  .Saddle)` actually flips on saddling a real horse (the same horse the user
  already tested with) — and the `/mm npcs` output is the next thing to
  actually look at together before committing to an icon plan.

- **v2.52: fixed a crash in the new `/mm npcs` command.** User ran it and got a
  chat error they couldn't copy — read directly from the live
  `Player.log` instead (same approach as v2.48's fruit-dot bug), which had the
  full stack trace: `NullPointerException: Cannot read field "type" because
  "b" is null` at `PicSoulsMiniMap.java:572`, inside the sort comparator added
  in v2.51. `Definitions.getAllNpcDefinitions()` apparently returns an array
  that can contain null elements (plausible: probably a slot for an id with no
  definition registered, e.g. a removed/reserved npc id) — the v2.51 code
  cloned the array and sorted it directly with no null check, so the
  comparator crashed the first time it had to compare against a null entry.
    - **Fix:** null entries are filtered out before sorting (into a fresh
      `ArrayList` instead of sorting the cloned array in place), and the
      comparator itself is now null-safe on `type`/`name` too, in case a real
      (non-null) definition ever has either field unset the same way. The
      console dump now also reports how many null entries were skipped, so
      that's visible instead of silently invisible.
  NOTE: wants in-game confirmation — restart (v2.52 must actually load), run
  `/mm npcs` again, and confirm it now prints the full grouped list to the
  server console instead of erroring. That list is what we actually need
  before deciding on the icon plan.

- **v2.53: custom per-species radar icons, baby scaling, and other-player
  markers.** Three asks from one message. User confirmed v2.52's `/mm npcs`
  worked (60 defs, 171 null slots skipped — read straight from the roster the
  user pasted from the console: 53 Animal, 4 Human, 2 Mount, 1 Skeleton).
  Asked how much icon detail they wanted; answered "full per-species icons
  that I will create myself," plus: draw a *second*, duplicate icon with a red
  outline for npcs that go hostile, rather than a runtime tint. Also asked for
  baby npcs to render smaller (since growth stage is readable), and for a
  permanent, always-visible marker for other real players (name always shown,
  clamped-to-rim like the spawn marker, no dashed line) — with an explicit
  caveat that they have nobody to test the latter with.
    - **Icon loading is file-based, not baked into the jar.** SDK research:
      `TextureAsset.loadFromFile(String)` loads an image straight off disk (vs.
      `loadFromPlugin`, which reads from inside the jar — would require a
      rebuild every time the user updates a drawing). Icons live in
      `<plugin folder>/icons/` (created automatically on enable if missing —
      `ensureRadarIconsFolder()` logs its absolute path once so it's easy to
      find), named after the npc's internal name from `/mm npcs`
      (`config.radarIconsSubfolder`, default `"icons"`).
    - **State variants, generalizing the user's "duplicate hostile icon" idea:**
      `MarkerOverlay.resolveRadarIcon` tries, in priority order,
      `<name>_saddled.png` (mounts only, when saddled — reuses the v2.51
      saddle detection), then `<name>_hostile.png` (when the npc's *live*
      `Npcs.Behaviour` is Aggressive/DefensiveAggressive — checked fresh each
      scan, not the species' default), then plain `<name>.png`, falling back
      to the existing tinted teardrop shape if nothing is found — so the icon
      set can be filled in gradually, species by species, with zero code
      changes. Every lookup (hit or miss) is cached by filename so a missing
      file is only stat'd from disk once, not every frame. Since art is
      full-color, no runtime tint is applied once a custom file is found —
      only the no-art fallback still gets the classification tint.
    - **Baby scaling:** `Npcs.NpcDefinition.ischild` (confirmed via SDK
      javadoc: "Is this a child?" — a property of the *definition*, e.g. calf/
      piglet/foal are separate defs from cow/pig/horse, not a runtime age
      value) now scales a blip's icon size by `radarBabyScale` (0.75 default).
    - **Other-player markers:** new `net.picsoul.rw.minimap.ui.OtherPlayerBlip`
      + `MinimapHud.gatherOtherPlayers()` (every tick — cheap accessor calls on
      already-connected `Player` objects via `Server.getAllPlayers()`, no
      world/DB IO, so unlike the npc radar this isn't throttled), excluding
      the viewer by reference, nearest-first capped at `maxOtherPlayersTracked`
      (32). Rendered by a new `MarkerOverlay.drawOtherPlayers` using the same
      teardrop shape + rotate-mode math as radar blips/waypoints, a fixed
      `otherPlayerColor` tint (cyan, distinct from the player's own amber and
      from every radar color) set once at pool-build time (no per-blip tint
      churn, since this color never varies), clamped to the rim exactly like
      the spawn glyph, and a name label with no hysteresis gating (always
      shown, unlike waypoint names). Gated behind the compass tier (same
      bucket as waypoints/spawn) rather than the radar tier, since this is
      about other people, not animals/npcs — a default choice, not something
      the user specified; easy to move if it turns out wrong.
      `/mm players [on|off]` toggles it independently.
    - Threaded a `Plugin` reference down `PlayerSession` → `MinimapHud` →
      `MarkerOverlay` (needed for `plugin.getPath()`, to resolve the icons
      folder) — the first place in the render/UI stack that needed it.
  NOTE: everything here wants in-game confirmation, and part of it (other
  players) explicitly can't be tested solo: (1) the user still needs to
  actually paint icons and confirm `loadFromFile` + the `_hostile`/`_saddled`
  fallback chain resolves correctly once files exist; (2) whether
  `Npcs.NpcDefinition.ischild` is actually true for the calf/piglet/cub/foal
  definitions found in `/mm npcs` (not verified — only the javadoc description
  was checked); (3) the other-player marker needs a second person online to
  see at all — flagged back to the user rather than assumed working.

- **v2.54: icon size + a settings slider, north-up locked, and smoothed radar
  movement.** Four pieces of feedback on v2.53's radar/icon work: 10px icons
  read as "way too small" once real per-species art was in the picture (want
  at least 20px, and a slider to adjust it, "within reason"); rotating icons
  to face direction "not sure I like" — lock north-up for now; and npc icons
  visibly lag behind the player marker, especially riding a horse at speed,
  unlike the player's own marker which never does this.
    - **Size:** `radarIconPx` default raised 10 -> 20. New `radarIconSizeMinPx`
      (8) / `radarIconSizeMaxPx` (48) bound what the slider (below) can set.
      `MarkerOverlay.drawRadar` already recomputed each blip's on-screen size
      from `config.radarIconPx` every frame (needed anyway for the per-child
      baby-scale multiplier), so this and the slider both take effect
      immediately with no further plumbing.
    - **Settings slider:** the SDK has `PlayerUIElementClickEvent
      .getRelativeMousePositionX()` (0-100% position of a click within the
      clicked element) but no drag event at all — so a real click-and-drag
      handle isn't possible here; built a click-to-set bar instead
      (`SettingsPanel`'s new `iconSizeTrack`/`iconSizeFill` + `Action
      .ICON_SIZE_TRACK`, wired in `PicSoulsMiniMap.onUiClick` to
      `panel.applyIconSizeFromRelativeX(event.getRelativeMousePositionX())`):
      clicking anywhere along the bar jumps `radarIconPx` straight to that
      position's value, clamped to the new min/max, with a numeric px readout
      and a proportional fill.
    - **North-up lock:** simplest possible fix — `radarShowFacing` default
      flipped `true` -> `false`. `RadarScanner.classify` already only computes
      `facingDeg`/`hasFacing` when that flag is on, and `drawRadar` already
      draws unrotated (`screenRotate = 0`) whenever `hasFacing` is false — so
      this reuses the exact gate that was already there for the config option,
      no new code path. The detection logic itself is untouched and still
      runs the moment the flag flips back on, in case this gets revisited.
    - **Smoothed radar movement — the real fix, not a band-aid.** Root cause:
      `RadarScanner` only actually queries `World.getAllNpcsInRange` once per
      `radarScanIntervalSeconds` (0.4s), and until v2.53 `getBlips()` just
      handed back whatever that last scan found — so a blip's on-screen
      position was frozen for up to 0.4s, then snapped straight to the next
      scanned position, over and over. At any real speed (a galloping/ridden
      horse especially) that reads as visible lag/stutter against the
      player's own marker, which is driven by `MinimapHud.updateSmoothed`'s
      continuous nanoTime-based extrapolation every single frame. Rather than
      just shortening the scan interval (which would reduce but not eliminate
      the stutter, and cost more `getAllNpcsInRange` calls), `RadarScanner`
      now tracks each npc across scans by `Npc.getGlobalID()` (a new private
      `Track` record: the last two scanned positions/times) and `getBlips()` —
      called every frame from `MinimapHud`, not just once per scan —
      extrapolates each tracked npc's current position forward from its last
      known segment using the identical alpha-based formula
      `MinimapHud.updateSmoothed` already uses for the player's own marker,
      holding at the latest position rather than overshooting once a full
      interval has passed with no fresher data (a lag spike, or an npc just
      entering range). Classification (color/icon/facing/etc) still just
      snaps to the newest scan — only position is smoothed, since that's the
      only thing that visibly stutters.
  NOTE: wants in-game confirmation — restart (v2.54 must actually load), open
  `/mm settings` and try the new slider, confirm radar icons now sit at 20px
  and stay north-up, and specifically watch a ridden/galloping horse's blip
  against the player marker to confirm the stutter is actually gone and not
  just reduced.

- **v2.55: fixed the persistent-first-load icon fallback, and replaced replay-
  style radar smoothing with real dead reckoning.** Two more pieces of
  feedback after v2.54: npcs already in radar range at world load never pick
  up their custom icon — only the shared fallback teardrop — until the user
  walks them off the map and back into range; and the horse-lag improvement
  from v2.54 was real but incomplete: a mount still visibly trails the player
  while moving at speed, only snapping back underneath once both stop.
    - **Icon fallback stuck after first load — root cause and fix.**
      `MarkerOverlay.iconFor` cached a failed load (`TextureAsset
      .loadFromFile` returning null, or the file not existing yet) as
      PERMANENT — once null was cached for a species key, that species could
      never show its real icon for the rest of the session, since the cache
      was consulted before ever attempting to load again. A load can plausibly
      fail the very first time it's attempted (right at world load, before
      other engine systems have settled — this project has hit exactly this
      class of "too early after world load" issue repeatedly for other
      systems, see hudGraceSeconds/renderingEnabled/mapGuard) with nothing
      actually wrong with the file. Fixed the same way `TileCache` already
      handles an analogous "not ready yet" case: a failed load is retried
      after a cooldown (`radarIconRetryCooldownSeconds`, 4s default) instead
      of being written off forever — a `radarIconMissNs` map records the last
      failed attempt per file key, mirroring `TileCache.misses`.
    - **Radar smoothing was replaying old motion, not tracking the present —
      redesigned as dead reckoning.** v2.54's fix reused the exact interpolation
      formula `MinimapHud.updateSmoothed` uses for the player's own marker:
      interpolate between the previous and current sample, paced to arrive at
      the current sample right as wall-clock time reaches one sample-interval
      past it. That works well for the player's own position because it
      resamples every tick — the "target" is never more than a tick stale. Npc
      scans only happen once per `radarScanIntervalSeconds` (0.4s) though, so
      reusing that formula meant the rendered position was always catching up
      to where the npc WAS up to 0.4s ago, never where it actually is now —
      exactly the "trails while moving, snaps into place once both stop"
      behavior reported (stopping makes the last two samples identical, so the
      lag naturally disappears). `RadarScanner.getBlips()` now derives each
      tracked npc's velocity from its last two scanned samples
      (`(curPos - prevPos) / sampleInterval`) and projects its position
      forward by the actual elapsed wall-clock time since the last sample —
      proper dead reckoning, converging on the true current position for
      roughly-straight-line movement (a galloping mount) rather than
      perpetually trailing it by a fixed delay. Extrapolation is capped at 1.5x
      the scan interval so a delayed scan or an abrupt stop can't run away.
  NOTE: wants in-game confirmation — restart (v2.55 must actually load), check
  that an npc already in range when you spawn in shows its custom icon
  (given a matching file exists) without needing to walk it off-screen first,
  and watch a ridden/galloping mount's blip against the player marker again to
  confirm it now tracks alongside rather than trailing behind.

- **v2.56: ridden-mount blip hidden, and stopped guessing at the icon-at-load
  bug.** v2.55's icon-cooldown-retry and dead-reckoning fixes were both
  real, reasoned improvements, but the user reported neither fully landed:
  npcs already in range at world load still don't get their custom icon until
  walked off-map and back (even with the v2.55 retry-cooldown, which by the
  math should have self-healed within a few seconds on its own — so whatever
  is actually happening isn't fully explained yet), and a ridden horse still
  visibly trails the player at speed. The user separately proposed just hiding
  a ridden mount's own blip entirely, since the player marker already shows
  where they are.
    - **Ridden-mount hiding — implemented, and it also sidesteps the
      remaining lag report for that specific case.** SDK check:
      `Npc.getRider()` returns the riding `Player` (Mounts only, null
      otherwise). `RadarScanner` now takes the viewing `Player` in its
      constructor (threaded from `MinimapHud`, which already has one) and
      `classify()` returns null immediately for a Mount whose rider is that
      viewer — before any of the rest of classification runs. New
      `radarHideRiddenMount` config flag (default true).
    - **Icon-at-load bug: stopped guessing, added the same kind of
      instrumentation that actually solved the v2.47/v2.48 fruit-dot bug**,
      rather than shipping a third unverified theory. Two v2.55 fixes
      (permanent-miss cache -> cooldown retry; the dead-reckoning rewrite)
      were each independently reasoned and correct for the mechanism they
      targeted, but the user's report after both shipped means the actual
      root cause of the load-order issue specifically is still unconfirmed —
      guessing a third mechanism blind risks the same outcome again.
      `MarkerOverlay.loadRadarIcon` now logs every attempt, not just
      failures: which file key, the full resolved path, whether the file
      existed, and whether `TextureAsset.loadFromFile` itself returned an
      object or null (or threw). Log volume stays bounded by the existing
      cooldown (retries at most every `radarIconRetryCooldownSeconds`, 4s).
  NOTE: this is the one still actually unresolved. Wants the user to play with
  v2.56, then exit, so the `Player.log` can be read directly (same as the
  fruit-dot bug) — the `[radar] icon '<name>': ...` lines from right after
  spawning in near an npc that should have art will show definitively whether
  the file is being found, whether the load call is failing, or something
  else entirely. Confirm separately whether hiding the ridden mount's blip
  reads as expected, and whether it also resolves the remaining lag
  complaint for that specific case (an unridden/wild mount would still use
  the v2.55 dead-reckoning path, unaffected by this change).

- **v2.57: icon-at-load bug — found via the log, root-caused for real this
  time, and fixed by eager preload.** User pushed back correctly: they'd only
  ever drawn `cow.png`, and were specific that it's the cow that shows the
  fallback at world load, only fixed by leaving and re-entering range.
  Read `Player.log` (same approach as v2.48's fruit-dot bug) with v2.56's
  logging in place, and it settled the question definitively:
  `[radar] icon 'cow': loaded from ...\icons\cow.png -> OK` appears exactly
  once, right at world load — immediately after the game engine's own
  `REGISTER ASSET TEXTURE ... FROM FILE: ...\cow.png` line — with no earlier
  failure logged at all, and the icons folder on disk (checked directly)
  contains only `cow.png`. So the load itself was never slow, never failing,
  and never needed a retry — it succeeded on its one and only attempt,
  effectively instantly.
    - **Real root cause:** icon loading was still *lazy* — the first attempt
      for a species only happens inside the very same {@code drawRadar} call
      that's trying to render its first blip that frame. Even though the load
      itself is fast, "lazy on first sighting" means a species already in
      radar range at world load could still render one frame with the
      fallback teardrop before that first, same-frame load call resolves —
      a real (if narrow) window that v2.55's retry-cooldown was never going
      to touch, since there was nothing to retry: the single attempt already
      succeeded.
    - **Fix:** `MarkerOverlay.preloadRadarIcons()`, called once at the end of
      the constructor (HUD build time), lists every `.png` already in the
      icons folder and eagerly runs each through the existing `iconFor` cache
      path — before any npc has ever been scanned or drawn. By the time the
      first real blip is ever rendered, every icon file already on disk is
      guaranteed to be a cache hit, closing the lazy-load window entirely
      rather than trying to shrink it. Logs a one-line summary
      (`preloaded N/M icon file(s) from ...`) so this is directly verifiable
      from the log too.
  NOTE: wants in-game confirmation — restart (v2.57 must actually load), spawn
  in near a cow, and confirm the custom icon shows immediately with no
  fallback flash and no need to leave/re-enter range. If this still doesn't
  fully resolve it, the next diagnostic step would be checking whether it's
  actually about UI-element pool-slot state rather than icon loading at all
  (the preload log line will make that easy to rule in or out from the log).

- **v2.58: user pushed back again — two definite cows, one showed the icon,
  one didn't. Added targeted per-npc diagnostics rather than guessing a
  fourth mechanism.** v2.57's preload fix is confirmed working (v2.57's own
  log had zero `icon 'cow'` miss lines all session — only the one successful
  preload) and the "it was actually a bull" theory was a reasonable read of
  the same log, but the user was explicit and precise on a re-check: two
  cows, one bull, one calf, and only one of the two *cows* got the custom
  icon after the leave-and-return workaround. That's not explainable by
  species confusion, and it's architecturally not supposed to be possible
  under the current code either — `resolveRadarIcon` is a pure function of a
  blip's own `iconKey`/`saddled`/`hostile`, so two blips both genuinely
  classified as `"cow"` MUST resolve to the identical cached texture, every
  frame, with no room for one to differ from the other. Something about the
  two individual npcs must differ upstream of that, or the bug is in the
  rendering layer beyond what could be reasoned out from the code alone —
  either way, guessing a specific mechanism again risked another round-trip
  for nothing, so this ships instrumentation instead of a fix.
    - `RadarScanner.classify()` now logs immediately if `npc.getDefinition()`
      returns null, throws, or returns a definition with a null/blank
      `name` — the specific failure mode that would make a real cow silently
      resolve to a null `iconKey` (and thus the fallback teardrop) with
      **zero** trace in the existing icon-load log, since `resolveRadarIcon`
      short-circuits to the fallback before ever touching the file-load path
      for a null key. This was invisible in every log captured so far.
    - `RadarScanner.scanNow()` also now logs once per individual npc (keyed by
      `Npc.getGlobalID()`, only on first sighting — not every scan, so this
      stays cheap): its resolved `iconKey`/`saddled`/`hostile`/`child` flags.
      If both cows log `iconKey=cow` here, that proves classification is
      identical for both and definitively moves the search to
      `MarkerOverlay`'s rendering/pool-slot code instead; if one logs
      something else (null, a different key, unexpected flags), that's the
      answer directly.
  NOTE: still open. Wants the user to reproduce once more (spawn near the
  same two-cow/bull/calf group, do the leave-and-return workaround) and then
  exit so `Player.log` can be read — this round's logging is specifically
  built to distinguish "classification differs between the two cows" from
  "classification is identical and the bug is in how MarkerOverlay renders
  it," which is the fork this bug has been stuck on for three versions now.

- **v2.59: icon-at-load bug — actually root-caused this time, via the
  v2.58 diagnostics.** The user reproduced once more; this time BOTH cows
  ended up showing the custom icon after the leave-and-return workaround, and
  the v2.58 per-npc logging (id + iconKey, logged once on first sighting)
  showed both cow ids logging `iconKey=cow` — proving classification was
  100% consistent between them the whole time. That ruled out every theory
  tried in v2.55/v2.57/v2.58 (retry timing, lazy-load-on-first-sight,
  getDefinition() inconsistency) in one shot.
    - **The actual clue was buried in the log's volume, not its content**: the
      same "new npc id=..." line — logged only when a Track is freshly
      created, i.e. only when that specific npc WASN'T already being tracked —
      appeared 22 times across roughly 11 actual animals. Every animal near
      the herd was repeatedly dropping out of the tracked set and getting
      re-added, purely from wandering near the edge of the 60m
      `radarRangeM` boundary — nothing to do with the player leaving and
      returning at all. Each drop hides that npc's pool-slot UIElement; each
      return re-shows it.
    - **Root cause:** `MarkerOverlay.drawRadar` reassigns a pool slot's
      `style.backgroundImage` in place when the resolved texture changes,
      while the element stays continuously visible the whole time. That
      works fine for a slot that's never been shown before (a brand new
      npc), but for a slot that was ALREADY visible showing the fallback
      teardrop, swapping the underlying image reference apparently doesn't
      reliably force this UI system to redraw with the new texture — it
      stays visually stuck. A slot that happens to cycle
      invisible-then-visible again (which — per the "new npc" log volume —
      happens constantly and automatically just from ordinary npc movement,
      not only via the player's manual "leave and return" workaround) gets a
      fresh render pass and picks up the correct texture, which is exactly
      why some animals in the same herd would "fix themselves" while others,
      sitting still nearer the herd's center, stayed stuck all session.
    - **Fix:** when a pool slot's resolved texture actually changes,
      `drawRadar` now explicitly hides the element and commits that with its
      own `updateStyle()` call *before* reassigning `backgroundImage`, so the
      later re-show (with the new texture already in place) is a genuine
      hidden→visible transition instead of an in-place swap on a
      continuously-visible element. Only triggers on an actual texture
      change for that slot (rare - once per npc's icon resolving for the
      first time, not every frame), so this doesn't add any per-frame cost
      for the common case of an already-correct, unchanged icon.
  NOTE: wants in-game confirmation — restart (v2.59 must actually load), spawn
  near the same cow/cow/bull/calf group again, and confirm every animal shows
  its correct look (custom icon or classification-tinted fallback) immediately
  and consistently, with no dependency on which ones happen to wander near
  the range boundary.

- **v2.60: icon-at-load bug — the user's own diagnostic test found the real
  fix.** v2.59's hidden-then-shown `setVisible()` nudge didn't work either
  (user reproduced it stuck again). Rather than guess a fifth mechanism, the
  user ran a genuinely useful A/B test themselves: re-equipping the
  **compass** (which only toggles element visibility, the same category of
  operation as the v2.59 fix) did nothing; re-equipping the **map** — which
  tears the whole minimap off the player's screen and re-adds it
  (`PlayerSession.hideMinimap/showMinimap` -> `MinimapHud.detach/attach` ->
  `Player.removeUIElement`/`addUIElement`) — immediately fixed both cows.
  That pinpointed the mechanism precisely: this UI system doesn't reliably
  redraw an element whose `backgroundImage` changed while it stayed
  continuously visible, but it does when the element is actually detached
  from the player's UI and reattached, not just hidden.
    - **Fix:** `UIElement.removeChild()` is documented to "also detach [the
      child] from the player UI", with `addChild()` as its pair — the same
      detach/reattach mechanism the map-requip path uses, just scoped to one
      child element instead of the whole minimap. `MarkerOverlay.drawRadar`
      now calls `box.removeChild(e)` + `box.addChild(e)` (replacing v2.59's
      ineffective `setVisible` toggle) whenever a radar slot's resolved
      texture actually changes, before reassigning `backgroundImage` — then
      re-applies the position-mode/pivot/scale-mode baseline properties in
      case re-parenting reset them (size/tint/rotate/position already get
      reapplied every frame regardless, unaffected either way). Only runs on
      an actual texture change per slot (rare), so no added per-frame cost
      for an icon that's already showing correctly.
  NOTE: wants in-game confirmation — restart (v2.60 must actually load), spawn
  near the cow/cow/bull/calf group again, and confirm every animal shows its
  correct look immediately without needing the map-requip workaround (or any
  workaround) at all this time.

- **v2.61: icon-at-load bug — v2.60's removeChild/addChild didn't work either;
  stopped trying to refresh the existing element at all.** User reproduced
  again: still stuck. Both v2.59 (setVisible toggle) and v2.60 (removeChild
  + addChild on the same element, mirroring what the user's own map-requip
  test proved fixes it at the top level) failed to force a redraw of an
  already-visible radar icon element with a newly-assigned texture. Since the
  ONE thing conclusively known to work is the user's map-requip test — which
  doesn't just detach/reattach the minimap's elements, it fully **disposes
  and recreates** them (`PlayerSession.rebuildHud`-style teardown territory,
  not a lighter detach/reattach) — the safer read of that evidence is "a
  genuinely fresh element renders correctly; something about mutating an
  existing, already-rendered one doesn't," not "detaching is what matters."
  v2.60's fix bet on the latter and lost.
    - **Fix:** stopped trying to refresh the stuck element by any means.
      `MarkerOverlay.drawRadar` now discards the pool slot's existing
      UIElement entirely and replaces it with a brand-new one whenever that
      slot's resolved texture changes — `box.removeChild(oldE)`, construct a
      new `UIElement` with the correct image/scale-mode/position-mode/pivot
      already set on it BEFORE it's ever shown, `box.addChild(newE)`, and
      `radarEls[used]` now points at the new instance from then on. A
      freshly-constructed element has never had a chance to get stuck in the
      first place — this sidesteps the "how do I force a re-render" question
      entirely instead of trying yet another way to answer it. `UIElement`
      has no `dispose()`/`destroy()` in this SDK (only removal from a
      parent, which `removeChild` already does), so the discarded element is
      just left for GC once dereferenced, consistent with how this SDK
      expects elements to be discarded elsewhere in this codebase.
  NOTE: still open, now on its fourth attempt. Wants in-game confirmation —
  restart (v2.61 must actually load), reproduce with the same herd, and
  report back either way. If a freshly-constructed element STILL shows the
  same stuck behavior, that would rule out "something about element reuse"
  entirely and point at something else — possibly the icon-resolution/tint
  logic itself under some condition not yet captured by the v2.58 logging
  (still present in this build), which would be the next thing to instrument.

- **v2.62: confirmed fixed (v2.61's fresh-element replacement worked), plus
  radar range now tracks zoom.** User confirmed the icon-stuck bug is
  resolved. Separately noted the radar only ever showed npcs while zoomed in
  close: `radarRangeM` (60m) was a fixed scan radius regardless of the
  minimap's actual zoom, so any time the visible circle was wider than 60m
  across (any zoom level past the most-zoomed-in few steps), real,
  on-screen-visible npcs beyond 60m were never scanned at all.
    - **Fix:** `RadarScanner.maybeScan` now takes the minimap's current
      `zoomCells` (world-cell span actually visible on screen) and computes
      the real per-scan radius as half of that, clamped to
      [`radarRangeM`, new `radarRangeMaxM`] (150m default) — `radarRangeM`
      changes meaning from "the range" to "the minimum range" (so zoomed in
      tight still scans a sensible minimum area rather than shrinking toward
      nothing), and the new max caps cost at extreme zoom-out. `MinimapHud`
      passes `zoomCells` (or `caveZoomCells` in cave mode, matching
      `recomputeZoomGeometry`'s own logic) through on every scan call.
    - Also removed the now-unneeded per-npc "new npc id=..." diagnostic
      logging added in v2.58 for this investigation (would otherwise log
      continuously for the life of every session) — the file-load and
      null-definition diagnostics from the same investigation stay, since
      those are naturally low-volume (only log on an actual miss/anomaly).
    - Added a durable note under "Working practices" (not just this entry)
      documenting the general lesson — pooled UIElement image swaps need
      fresh-element replacement, not in-place mutation — since this class of
      bug could plausibly resurface in a different pooled-icon feature later.
  NOTE: wants in-game confirmation that npcs now show up across the full
  range of zoom levels, not just when zoomed in tight.


- **v2.63: split `gemini.md` into `CLAUDE.md` + `CHANGELOG.md`.** The user
  created `gemini.md` while using Google Gemini, but works with Claude Code
  day to day, which auto-loads a `CLAUDE.md` at the start of every session if
  one exists — `gemini.md` never got that benefit; it had to be pointed at
  manually. Renaming it straight across wasn't the right move though: it had
  grown into a 1900+ line, version-by-version changelog, and auto-loading all
  of that on every session start would burn a lot of context on historical
  detail irrelevant to whatever the current task is.
    - `CLAUDE.md` (new, auto-loaded): a lean rewrite of the "Working
      practices" and "Architecture" sections — the architecture section in
      particular was stale (still described a long-removed `unlock/` package
      and an outdated command list from around v2.2), so this is a fresh
      pass against the actual current source tree, not a copy. Also adds a
      "Release workflow" checklist (the version-bump/build/deploy/backup
      cycle this project already follows every change) and a "Diagnosing
      stuck bugs" note pointing at this session's own track record (the
      fruit-dot bug, the radar icon bug) as precedent for when to stop
      guessing and add instrumentation instead.
    - `CHANGELOG.md` (renamed from `gemini.md`): just the "Feature History"
      section, unchanged, going forward append-only. The old "Current Status"
      / "Next Steps" sections were dropped rather than carried over — they'd
      drifted just as stale as the architecture section (e.g. still framed
      radar as an unbuilt roadmap item, and the bulk of it was the
      long-since-resolved world-switch crash saga), and a "current status"
      narrative competing with `CLAUDE.md` for the same job is exactly the
      kind of drift this split is meant to avoid. That history isn't lost —
      it's still readable in the version entries themselves, just not
      restated as a separate "current" claim.
    - Two source comments (`ConstructionColors.java`, `MaterialColors.java`)
      that pointed at "gemini.md" for the v2.35 color-extraction method were
      updated to point at `CHANGELOG.md` instead.
  NOTE: no functional/gameplay change - documentation only. Still goes
  through the full version-bump/build/deploy/backup cycle per the
  now-documented convention.

- **v2.64: settings panel gets map size, corner, rotate, and contour
  controls.** User said all the main features they wanted are in place and
  asked what settings would be worth exposing in `/mm settings`, which until
  now only had zoom-key rebind + the entity icon size slider. Offered a
  menu (map size/position, map behavior toggles, layer visibility toggles,
  radar/waypoint fine-tuning); user picked map size, map corner, rotate-with-
  heading, and contour lines for this pass.
    - **Map size**: a second click-to-set slider (`SettingsPanel`'s existing
      icon-size-slider shape, factored out into shared `addSliderTrack`/
      `addSliderFill` helpers), bound to `config.minimapSizePx` with new
      `minimapSizeMinPx`/`minimapSizeMaxPx` (120-360) config bounds.
    - **Map corner**: a single button cycling TL -> TR -> BL -> BR through
      `MinimapConfig.Corner`.
    - Both of these are baked into fixed HUD geometry at `MinimapHud.build()`
      time (unlike icon size, which `MarkerOverlay.drawRadar` re-reads from
      config every frame), so — unlike the icon slider — changing either one
      routes through `PlayerSession.rebuildHud()` (the same teardown/rebuild
      already used by the `/mm uilite`/`/mm minimal`/`/mm notex` diagnostic
      toggles) to actually take visual effect. Also persisted to
      `diagnostics.txt` (new `mapsize`/`corner` keys) the same way zoom
      already is, so they survive a world switch.
    - **Rotate-with-heading** and **contour lines**: ON/OFF toggle buttons
      that just call the exact same code the `/mm rotate`/`/mm contour` chat
      commands already run (including contour's `tileCache.clear()` +
      `session.invalidateMap()`, since contour lines are baked into terrain
      tile pixels) — no new toggle logic, just a second way to trigger the
      existing one. Not persisted, matching the chat commands' existing
      (also not persisted) behavior — kept consistent rather than adding new
      persistence the commands themselves don't have.
    - Panel grew from 300x234 to 300x380 to fit the four new rows.
  NOTE: wants in-game confirmation — restart (v2.64 must actually load), open
  `/mm settings`, and check the map-size slider and corner button actually
  resize/reposition the minimap live, that rotate/contour toggle correctly
  and match the chat-command behavior, and that map size + corner survive a
  world switch.

- **v2.65: map size/corner settings actually take effect — found the real
  `rebuildHud()` bug.** User reported the new size slider barely did anything
  (just a slight cardinal-label shift) and the corner button did nothing at
  all. Root cause: `PlayerSession.rebuildHud()` called `MinimapHud.dispose()`
  to force a rebuild, but `dispose()`'s actual behavior is gated on
  `config.teardownMode`, which defaults to `"none"` — a deliberate,
  hard-won fix for the world-switch crash (removing UI elements while the
  plugin unloads is what crashes the *next* world; see the v2.30 entry).
  Under `"none"`, `dispose()` only frees textures (`disposeTexturesOnly()`)
  and never actually purges the element tree or resets the `built` flag — so
  the next `attach()` -> `build()` call hit `if (built) return;` and silently
  kept the OLD geometry. The map-size slider updated `config.minimapSizePx`
  correctly, but nothing ever rebuilt the box/container to match it; only the
  cardinal labels moved, since those recompute their position live from
  config every frame instead of being baked in at `build()` time.
    - **Fix:** new `MinimapHud.rebuildElements()` — the exact same full-purge
      logic `dispose()` used to run only under `teardownMode == "full"`,
      pulled out into its own method that runs unconditionally, regardless of
      `teardownMode`. `dispose()` itself is unchanged for its actual job
      (`onDisable`/world-switch teardown, where `"none"` must stay the
      default). `PlayerSession.rebuildHud()` now calls `rebuildElements()`
      instead of `dispose()` — it was never actually the plugin-unload path,
      just a live, in-session structural rebuild, so there's no crash-timing
      risk being protected against there in the first place.
    - This is the same `rebuildHud()` used by `/mm uilite`/`/mm minimal`/
      `/mm notex`/`/mm diagreset`, so this fix plausibly applies to those too
      (i.e. they may have shared this exact bug under the default
      teardownMode) — not specifically re-tested, but worth knowing if any of
      those ever seemed like they "didn't do anything" before.
  NOTE: wants in-game confirmation — restart (v2.65 must actually load), open
  `/mm settings`, and check the map-size slider and corner button now visibly
  resize/reposition the minimap immediately.

- **v2.66: "Reset to Defaults" button + contour lines on by default.**
  Mentioning `/mm diagreset` prompted the user to realize the settings panel
  itself should have an equivalent reset option. Also asked for contour lines
  to default on.
    - **Contour lines default ON**: `MinimapConfig.contourEnabled` flipped
      `false` -> `true`.
    - **Reset to Defaults button**: new `SettingsPanel.Action.RESET_DEFAULTS`
      + a button next to Close. Deliberately scoped to only what's actually
      on this panel — zoom-key bindings, entity icon size, map size/corner,
      rotate, contour — pulled fresh from `MinimapConfig.defaults()` rather
      than hand-copied literals, so it can't drift from the real defaults.
      Explicitly does NOT touch the `/mm diagreset` diagnostic switches
      (terrain/mapdb/textures/hud/mapguard/safemode/uilite/minimal/teardown)
      — those are crash-diagnosis dev toggles, a different concern from
      player-facing settings, and mixing them into a "reset my settings"
      button would risk silently re-enabling something a player or the dev
      deliberately turned off for stability.
    - Resetting re-registers zoom keys for every session
      (`restoreZoomKeyRegistration`, the same call `/mm zoomkey` already
      uses), clears the tile cache + invalidates every session's map (in
      case contour changed), and rebuilds every session's HUD via the v2.65
      `rebuildElements()` fix (shared `MinimapConfig`, so — like
      `/mm diagreset` — this is a reset for every connected player, not just
      whoever clicked it).
  NOTE: wants in-game confirmation — restart (v2.66 must actually load),
  confirm contour lines are on immediately with no toggle needed, and that
  clicking Reset to Defaults actually restores every one of those settings
  (including a visible map resize/reposition, thanks to v2.65).

- **v2.67: found the settings-panel crash via the log, and fixed the
  bottom-corner label overlap.** User reported changing map size/position
  intermittently crashed the game (sometimes fine, sometimes not), and that
  the coords/time/date block needs to sit ABOVE the map instead of below it
  for the bottom two corners.
    - **Crash — root-caused from `Player-prev.log`** (the session that ended
      abruptly with no quit sequence): right before the log goes dead, there's
      a burst of dozens of `CLIENT: Create new visual element` / `API: Add UI
      element` lines with element ids climbing into the high 2000s-2800s —
      2681 `Add UI element` lines total in that one session, versus the ~150-
      200 a single HUD actually needs. That's consistent with roughly a dozen
      or more full HUD teardown-and-rebuild cycles happening back-to-back in
      a short window: `PicSoulsMiniMap.onUiClick`'s `MAP_SIZE_TRACK` case
      called `PlayerSession.rebuildHud()` (a full element-tree purge +
      rebuild, hundreds of elements) on every single click — and a slider is
      naturally clicked many times in quick succession while feeling out the
      right value. This is the same general class of UI-churn fragility as
      the world-switch crash this project fixed back in v2.30 (native UI
      state getting corrupted by heavy element churn), just triggered by
      rapid-fire live rebuilds instead of a world switch — which also
      explains why it was intermittent: a single isolated click was fine,
      only a fast burst of clicks was at risk.
    - **Fix:** `PlayerSession.requestRebuild()` replaces every direct
      `rebuildHud()` call across the plugin (settings panel AND the
      `/mm uilite`/`minimal`/`notex`/`diagreset` commands, for the same
      defense-in-depth reason). Repeated requests update config + the
      settings panel's own slider visuals immediately and cheaply every time,
      but the actual expensive rebuild is coalesced to at most once per new
      `MinimapConfig.hudRebuildCooldownSeconds` (0.4s default), applied from
      `PlayerSession.tick()` — so a burst of rapid clicks now produces at
      most a couple of rebuilds a second instead of one per click.
    - **Bottom-corner label overlap:** `MinimapHud.build()` always positioned
      `infoContainer` at `corner.yPercent + mapHeight%` — correct for the top
      corners (pushes the info block down, below the map), but for the
      bottom corners this pushed it even further toward (or past) the bottom
      of the screen instead of above the map. Fixed by subtracting instead of
      adding for `BOTTOM_LEFT`/`BOTTOM_RIGHT`: since those corners already use
      a `Pivot.Lower*` (anchors the block's bottom edge and grows upward from
      it), subtracting the map's height places the info block's bottom edge
      exactly at the map's top edge — directly above it, no overlap — with no
      other change needed.
  NOTE: wants in-game confirmation — restart (v2.67 must actually load), try
  rapidly clicking across the map-size slider (the exact scenario that
  crashed before) and confirm it no longer does, and check that bottom-left/
  bottom-right now show the coords/time/date block above the map instead of
  overlapping or running off-screen.

- **v2.68: map size/corner crashes — root-caused for real, fixed by never
  rebuilding at all.** v2.67's debounce was a real improvement but the user
  reproduced the crash again on both settings (once rapidly clicking the size
  slider, once rapidly clicking the corner button) even with it in place.
  Checked the live log again: both crash sessions independently hit UI
  element ids in roughly the same high-2000s range (2836, then 2869) —
  that consistency across two unrelated sessions pointed at a cumulative
  ceiling on total elements created, not simply "rebuilds happening too fast"
  (which a rate-limit alone can reduce but not eliminate over a several-
  second burst of clicking).
    - **The actual fix: map size and corner never needed a full rebuild in
      the first place.** Live zoom changes already prove this — `setZoom()`
      /`applyGeometryChange()` resize the *existing* image layers in place
      and have never rebuilt anything. Extended that exact "mutate, don't
      recreate" approach to size/corner: new `MinimapHud.applyLayoutChange()`
      resizes/repositions `mapContainer`, `infoContainer`, and `mapBox` in
      place, reuses `applyGeometryChange()` for the image layers (unchanged),
      and a new `MarkerOverlay.updateGeometry(minimapSizePx, mapAreaSize)`
      updates the three fields (`center`/`radius`/`labelOffset`, no longer
      `final`) every pooled waypoint/radar/other-player element's on-screen
      position is already recomputed from every frame — so resizing the
      overlay needs zero new elements either. Cardinal labels were already
      live (recomputed every frame in `updateCardinalLabels`). Net result:
      a size/corner change is now a handful of `updateStyle()` calls on
      already-existing elements, regardless of click speed — there is no
      element count left to climb, so the crash mechanism (UI element churn)
      can't happen here anymore, not just less often.
    - `PicSoulsMiniMap.onUiClick`'s `MAP_SIZE_TRACK`/`CORNER_CYCLE`/
      `RESET_DEFAULTS` cases now call `session.getHud().applyLayoutChange()`
      instead of the v2.67 debounced `requestRebuild()`. The debounce itself
      stays in place for what it's still needed for — `/mm uilite`/`minimal`/
      `notex`/`diagreset`, which are genuine structural changes (they change
      whether elements exist at all / how many are pooled), unlike a plain
      resize/reposition.
  NOTE: wants in-game confirmation — restart (v2.68 must actually load), and
  specifically try to reproduce both crashes again (rapid slider clicks,
  rapid corner-button clicks) to confirm the game stays stable now that
  neither path touches the element tree at all.

- **v2.69: settings panel restyled to match the game's own settings screen,
  and fixed the Reset/Close-overlapping-hint-text bug.** User attached a
  screenshot showing the hint text and the Reset to Defaults/Close buttons
  overlapping at the bottom of the panel, and a screenshot of the game's own
  settings screen asking for a similar look.
    - **Overlap bug:** straightforward layout math error — the hint block and
      the button row were packed too close together for how much text the
      hint actually wrapped to. Root-fixed by laying out the whole panel with
      an explicit running Y cursor (each row/section advances it by its own
      real height) instead of hand-picked fixed offsets, so there's no more
      guessing whether two blocks fit — and it makes the next addition to
      this panel much less likely to silently overlap again too.
    - **Visual style pass**, matching the game's dark panel + gold section
      headers + segmented ON/OFF toggle language shown in the reference
      screenshot (not a pixel clone — this SDK's plain `UIElement`/`UILabel`
      primitives have no texture-backed panel art, icon font, or dropdown
      widget to work with): rows now grouped under gold, uppercase section
      headers with a thin separator line (`ZOOM`, `MAP DISPLAY`,
      `MAP BEHAVIOR` — new `addSectionHeader` helper), and the rotate/contour
      toggles changed from a single button that flips on click to a two-part
      segmented ON/OFF control (mirroring the reference's VSync-style row)
      where the active side is highlighted blue and the inactive side is
      dim gray — clicking a side explicitly SETS that value rather than
      blindly flipping it. `SettingsPanel.Action` gained `SET_ROTATE_ON/OFF`
      and `SET_CONTOUR_ON/OFF`, replacing the old single `TOGGLE_ROTATE`/
      `TOGGLE_CONTOUR`.
    - Panel grew from 300x390 to 320x512 to comfortably fit the reorganized
      layout with real spacing.
  NOTE: wants in-game confirmation — restart (v2.69 must actually load), open
  `/mm settings`, and check nothing overlaps and the new segmented toggles
  read clearly at a glance.

- **v2.70: Ctrl+M hotkey opens/closes the settings panel.** User wanted a
  keybind alternative to typing `/mm settings`, suggesting Left/Right
  Control+M specifically.
    - SDK check confirmed this is directly supported and even has an
      official worked example for exactly this pattern:
      `Player.isKeyPressed(Key)` — "Checks if a particular key is currently
      pressed... may be useful in the PlayerKeyEvent (if you want to
      implement key combos, e.g check if the shift key is also pressed
      etc)" — with a "Check if player pressed shift + c" example in the
      javadoc matching this almost verbatim: check `evt.getKey() == Key.C`
      then `evt.getPlayer().isKeyPressed(Key.LeftShift)`. Same requirement
      applies as the zoom keys already relied on: the key(s) must be
      registered via `registerKeys` and the player must be listening for key
      input, or `isKeyPressed` can't report anything.
    - `registerZoomKeys` renamed `registerHotkeys` and now also registers
      `Key.LeftCtrl`/`Key.RightCtrl` (queried, never a trigger on their own)
      alongside the configurable trigger key (new `MinimapConfig
      .settingsKeyName`, default `"M"`). `PicSoulsMiniMap.onKey` toggles the
      settings panel when the trigger key is pressed while either Ctrl is
      held — checked after the existing rebind-capture branch (so it can't
      interfere with actually rebinding a zoom key to M) but before the
      `!s.isVisible()` gate the zoom keys use, matching `/mm settings`
      itself, which already opens the panel regardless of whether the
      minimap is currently shown.
    - Not yet rebindable in-game (no settings-panel row or `/mm` command for
      it) — only the trigger key is a config default, and Ctrl itself is
      intentionally fixed, not configurable, since that's specifically what
      was asked for rather than an arbitrary modifier-key system.
  NOTE: wants in-game confirmation — restart (v2.70 must actually load), and
  confirm both Left Ctrl+M and Right Ctrl+M open/close the panel, including
  while the minimap itself is hidden.

- **v2.71: settings hotkey switched from Ctrl+M to plain F4 - M collided with
  the base game's own map key.** User reported Ctrl+M also opened the vanilla
  map. Checked `PlayerKeyEvent`: it only exposes `getKey()`/`isPressed()`, no
  `setCancelled()` or any other way to consume/suppress a key press, so
  there's no way to stop the base game reacting to M being pressed - our
  Ctrl requirement was purely our own plugin's logic, invisible to the game's
  own binding, which fires on the raw key regardless of what we additionally
  check for. Any key already bound to something in the base game would have
  hit the same collision no matter what modifier we paired it with.
    - Asked the user to pick a replacement; they checked the game's own
      Controls screen and chose **F4**, unbound there. `MinimapConfig
      .settingsKeyName` default changed `"M"` -> `"F4"`, and the Ctrl
      requirement (and the now-unneeded `Key.LeftCtrl`/`Key.RightCtrl`
      registration added in v2.70) was removed entirely - F4 alone is the
      trigger now, no modifier check.
  NOTE: wants in-game confirmation — restart (v2.71 must actually load), and
  confirm F4 opens/closes the settings panel without also opening the vanilla
  map or triggering anything else bound to F4.

- **v2.72: split `diagnostics.txt` into `settings.txt` + `diagnostics.txt`.**
  User noticed the file was misnamed for what it actually stores (mostly
  player settings, e.g. zoom keys/level, map size/corner) and asked why not
  call it `settings.txt`. Correct catch, but with a wrinkle: the file had
  always mixed two genuinely different things — real player settings and the
  crash-diagnosis dev toggles (`terrain`/`mapdb`/`textures`/`hud`/`mapguard`/
  `safemode`/`uilite`/`minimal`/`teardown`/`hudgrace`) this file originally
  existed for, back when it was built purely for the world-switch crash
  investigation. A straight rename would've made the diagnostic half's name
  wrong instead. Agreed to split properly.
    - New `PicSoulsMiniMap.settingsFile()`/`loadSettings()`/`saveSettings()`,
      parallel to the existing `diagnosticsFile()`/`loadDiagnostics()`/
      `saveDiagnostics()`. `settings.txt` now holds `wpprivacy`, `zoom`,
      `zoominkey`, `zoomoutkey`, `mapsize`, `corner` — every call site that
      changes one of those (the settings-panel size slider/corner button/
      reset button, `/mm zoomkey`, `/mm wpprivacy`, the zoom-step chat
      command, and the in-panel zoom-key rebind capture) now calls
      `saveSettings()` instead. `diagnostics.txt` keeps exactly what's left:
      the ten actual dev/crash-diagnosis toggles, unchanged in content or
      behavior — `/mm diagreset` still only touches those, as it always did.
      `onEnable` now calls both `loadDiagnostics()` and `loadSettings()`.
  NOTE: user is about to delete every file except the jar to test a fresh
  install — this should already work cleanly with the split in place, since
  both load methods no-op gracefully when their file doesn't exist yet
  (falls back to `MinimapConfig.defaults()`, same as any first-ever launch
  before either file existed) and every other file (`calendar_owners.txt`,
  `session.txt`, the `icons` folder) is independently recreated on demand
  the same way — but this is exactly the scenario worth having them confirm.


- **v2.73: settings hotkey switched from F4 to F1 - F4 turned out to be bound
  to something in creative mode.** `MinimapConfig.settingsKeyName` default
  changed `"F4"` -> `"F1"`; same standalone-key mechanism from v2.71,
  unchanged otherwise.
  NOTE: wants in-game confirmation — restart (v2.73 must actually load), and
  confirm F1 opens/closes the settings panel without triggering anything
  else. Worth specifically checking creative mode too, given that's exactly
  where F4 turned out to collide, and F1 is a common "help"/menu key in many
  games generally (not confirmed either way for Rising World specifically).

- **v2.74: full set of custom radar icons added, covering every entity
  species the radar can show.** No code changes - `icons/` grew from the
  original 10 farm-animal PNGs to 60, adding wildlife (deer, moose, wolves,
  foxes, bears, lions, elephants, rhinoceros, zebra, hare, penguin, etc.),
  hostile npcs (bandit, barbarian, ghoul, skeleton, scorpion, spider, snake),
  and the remaining farm-animal age/sex variants (calf, foal, goatling, etc.).
  `MarkerOverlay.resolveRadarIcon`/`preloadRadarIcons` already load whatever
  is present in `icons/` by npc name with no per-species registration needed,
  so this is a pure asset addition.
  NOTE: wants in-game confirmation — spot-check a few of the newly-added
  species on the radar to confirm the file-name-to-npc-name mapping is
  correct for each (a typo'd filename just silently falls back to the
  default blip rather than erroring).

- **v2.75: real per-player settings, replacing the single shared config, plus
  a new "hide me from others" privacy toggle.** User asked how to let a player
  opt out of appearing on other players' minimaps, and wanted it as a
  settings-panel toggle - turned out that feature didn't exist at all yet
  (`/mm players` only controls whether *you* see others, not whether others
  see *you*). Investigating turned up a deeper problem: `MinimapConfig` was a
  single object shared by the whole server, so map size, corner, rotate,
  contour, zoom, zoom keybinds, waypoint privacy and the other-players toggle
  were all actually server-wide, not personal - confirmed three real bugs this
  caused: one player's key rebind or `/mm zoomkey` silently rebound *every*
  connected player's zoom keys, and simultaneous zoom changes stomped each
  other in the shared field.
  - New `config.PlayerPreferences`: one instance per connected player, holding
    exactly the fields that should be personal (`waypointPrivacy`,
    `defaultZoomCells`, `zoomInKeyName`, `zoomOutKeyName`, `minimapSizePx`,
    `corner`, `rotate`, `contourEnabled`, `showOtherPlayers`, and the new
    `hiddenFromOthers`), with the same key=value load/save format the old
    settings.txt used. `MinimapConfig` no longer owns any of these fields -
    everything else in it (rendering thresholds/colors, capability item
    names, diagnostics.txt toggles) stays shared/global, since those were
    never per-player exposed in the first place.
  - Storage: one file per player, `players/<uid>.txt`, keyed by
    `Player.getUID()` (confirmed via the SDK: globally unique, never changes
    across reconnects or servers - unlike `getID()`, which is per-connection
    and was the wrong key). Migration: a player's first-ever connect after
    this upgrade seeds their new file from the old shared `settings.txt` if it
    exists (instead of resetting to hardcoded defaults), so existing tuning
    isn't silently lost; `settings.txt` itself is left in place, untouched,
    no longer written to.
  - `PlayerSession`/`MinimapHud`/`SettingsPanel`/`MarkerOverlay` now take a
    `PlayerPreferences` alongside the shared `MinimapConfig`, and every
    settings-panel control + `/mm` command that used to mutate `config`
    directly (wpprivacy, zoom, zoomkey, rotate, contour, players, map
    size/corner, reset-to-defaults) now mutates that player's own prefs and
    saves only their own file. `/mm zoomkey` in particular now only re-registers
    the calling player's own hotkey, instead of looping over every connected
    session - the actual fix for the cross-player rebind bug above.
  - New `/mm hidden [on|off]` command and a matching settings-panel toggle
    ("Hide me from others", next to rotate/contour): sets the calling
    player's own `hiddenFromOthers`. `MinimapHud.gatherOtherPlayers()` now
    skips any candidate player whose own session has this flag set (looked up
    via a `SessionRegistry` reference threaded into `MinimapHud`), so a hidden
    player is filtered out of every other viewer's other-players overlay.
  - Contour lines needed special handling: they're baked per-cell into
    `TileRenderer`'s output, cached in the single shared `TileCache` - not
    read live by the HUD - so a naive per-player flag would either need a
    full second tile cache (expensive) or a live per-frame overlay pass
    (recomputes on every view change for every player; risks the existing
    `snapshotBudgetMs` main-thread budget this plugin already has to guard).
    Discussed the cost tradeoff with the user and landed on a middle path:
    `TileCache` now stores up to *two* rendered variants per chunk
    (contour-on / contour-off, via a new `Variants` holder with independent
    per-variant dirty flags), and each viewer's own `contourEnabled`
    preference picks which variant their render asks for
    (`TileRenderer.render(chunk, config, contourOn)`,
    `MapRenderer.renderAsync(..., contourOn, ...)`). Cost scales with distinct
    chunks visited x distinct preferences actually in use, not player count or
    frame rate - free if everyone shares one preference, bounded otherwise.
    Side benefit: toggling contour no longer calls `tileCache.clear()` (which
    used to wipe the *entire shared cache* for every player on a single
    player's toggle) - flipping your own preference just means your own next
    render asks for the other already-independently-cached variant.
  - NOTE: wants in-game confirmation, ideally with a second connected
    player/account since most of this is only observable with 2+
    simultaneous players - solo testing can at least confirm: an existing
    `settings.txt` correctly seeds a new `players/<uid>.txt` on first load
    post-upgrade (no reset to defaults), the settings panel's new "Hide me
    from others" toggle works and persists across a world switch, and normal
    single-player operation is otherwise unaffected. The cross-player fixes
    (rebind isolation, zoom isolation, `/mm zoomkey` scope, and hide-me
    actually hiding you from another client's map) need a second
    player/account to truly verify.

- **v2.76: diagnostic-only - added cumulative session-total logging while
  investigating a recurring native crash.** User reported the game crashing
  repeatedly (four times in one play session) with no exception, error, or
  Unity crash-dump ever appearing in `Player.log` - the same "silent, native,
  uncatchable" signature as two previously-fixed crashes in this project (the
  world-switch UI crash, the settings-panel rebuild crash), but neither of
  those root causes applied here (no world switch, no settings changes). A
  clean A/B test by the user (plugin disabled: no crash under the same play,
  including fast Creative flight; plugin enabled: crashes) confirmed the
  plugin is the cause. Closing background virtual-display software (Meta
  Virtual Monitor / Virtual Desktop Monitor) did not stop it.
  Crash timing across the four sessions clustered around 8-12 minutes in
  regardless of activity (fast flying, slow walking, standing still
  gardening) - a tighter pattern than instantaneous movement speed would
  predict, and more consistent with a resource that accumulates over the
  whole session. Leading theory: the number of distinct new terrain chunks
  rendered, since `TileRenderer.render` does a bulk voxel read per chunk for
  cave-opening detection (`caveDetectionEnabled`, on by default) - the one
  part of the render path that reads meaningfully more native data than the
  base terrain rendering that's been stable for a long time, and it runs on
  every new chunk regardless of how fast the player is moving.
  Per this project's established practice of adding instrumentation rather
  than guessing a second time, added `TileCache.lifetimeRenders()` (a
  never-reset lifetime counter of actual tile builds performed, distinct from
  the existing `/mm perf` interval stats which reset every report) and an
  always-on periodic log line (`[diag] session totals: uptimeSec=... tilesCached=...
  lifetimeRenders=...`, every 20s, no `/mm perf` toggle needed) so that
  whichever test reproduces the crash next, the log's last line before the
  cutoff gives a concrete number to correlate against the four sessions
  already on record. No behavior change; nothing about the crash is fixed by
  this version.
  NOTE: wants the user to (a) try `/mm caves off` as a quick, zero-code
  bisection test of the cave-detection theory, and (b) reproduce the crash
  again regardless, so the new log line's last value can be read and compared
  across sessions.
