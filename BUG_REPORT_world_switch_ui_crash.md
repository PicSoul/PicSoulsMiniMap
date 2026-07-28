# Bug report: calling removeUIElement while a plugin unloads crashes the next world

**Game:** Rising World (Unity), version 6000.0.60.57267
**OS:** Windows 10.0.26100 (x64)
**Java:** bundled JDK 20 (`Data/Java/JDK`)
**Plugin API:** PluginAPI.jar build 2026-05-13

## Summary

**Confirmed cause:** if a plugin calls `Player.removeUIElement(...)` while it is
being unloaded (i.e. from `onDisable`, which runs on every world switch), the game
crashes shortly after the *next* world loads. Leaving the elements alone and letting
the game's own `Reset PluginUIManager` clean them up is completely stable.

Removing UI elements during normal play is fine — a plugin can detach and rebuild
its whole UI in-world repeatedly without incident. It is specifically removal during
plugin unload that does the damage.

Loading a single world after starting the game is completely stable, indefinitely.
Returning to the main menu and joining another world (which unloads and re-enables
the plugin, destroying and recreating its classloader) crashes the game within
seconds of the plugin attaching its UI.

The crash originates entirely in the **first** world's unload path. With the plugin
attaching no UI at all in the second world (0 elements, 0 textures added there), the
game still crashed — as long as world 1 had UI that the plugin then removed on
unload. The second world need not touch the UI system.

Both removal strategies crash:

* removing only the root containers (the game then reports
  `Reset PluginUIManager (47 elements)` for the leftover children), and
* removing everything recursively plus a `getAllUIElements` sweep
  (`Reset PluginUIManager (0 elements)`).

Removing **nothing** — letting the game reset the manager itself — is stable.

## Reproduction

1. Start the game and load any world.
2. Have the plugin call `player.addUIElement(element, UITarget.HUD)` — one container
   with a single `UILabel` is enough.
3. In the plugin's `onDisable`, call `player.removeUIElement(...)` on it.
4. Return to the main menu and join a different world (do not restart the game).
   The plugin need not add any UI in this second world.
5. The game crashes within roughly 5–20 seconds of the second world loading.

Omit step 3 (remove nothing on unload) and the crash does not occur.

The crash needs no interaction: it happens while simply standing still.

## What was ruled out

Each of these was tested individually with the rest of the plugin unchanged, and
none of them prevents the crash:

| Eliminated | How it was tested |
|---|---|
| World-load timing | Delaying the UI attach 15s after the switch; the attach then succeeded and the crash still arrived later. |
| Texture creation | A run creating **zero** textures (`REGISTER ASSET TEXTURE` count in the second world = 0) still crashed. |
| Terrain / chunk reads | All `getLODTerrain` / `getRawLODTerrain` calls disabled. |
| SQLite reads | All `getSQLiteConnection` / `getWorldDatabase` reads disabled. |
| Duplicate asset names | Textures made byte-unique per plugin load, so no checksum/name is ever re-registered. |
| Off-thread access | All UI and texture work confirmed on the main thread (`Plugin.enqueue`). |
| Leaked UI/assets at teardown | The game's own teardown log reports `Reset PluginUIManager (0 elements)` and `Reset PluginAssetManager (0 assets)`. |
| Re-entrancy from event callbacks | All UI work moved out of event handlers onto the plugin's own `Timer` tick. |
| Coexistence with the game's map UI | Detaching the plugin UI whenever the player holds the vanilla map (verified visually); still crashed. |
| Number of UI elements | ~190, ~40 and **2** elements all crash. |
| Incomplete UI teardown | Fixed: every child element is now detached recursively before unload, and the game confirms `Reset PluginUIManager (0 elements)` (it previously reported 47). **The crash is unchanged.** |

The single factor that changes the outcome is whether the plugin calls
`removeUIElement` during unload.

## Crash characteristics

Two different terminations were observed for the same scenario:

1. **Handled** — the crash handler runs and writes `Crashes/Crash_<timestamp>/`
   with `Player.log` + `crash.dmp`. The stack is unsymbolicated and consistently
   passes through `GameAssembly.dll` → `UnityPlayer.dll`, e.g.

   ```
   0x00007FFC28F7130F (GameAssembly)
   0x00007FFC296B58E5 (GameAssembly)
   0x00007FFC2D26367D (UnityPlayer)
   0x00007FF722EA11F6 (RisingWorld)
   ```

2. **Unhandled** — `Player.log` simply stops mid-line. No `Crash!!!`, no exception,
   no dump written; the process is killed outright.

In the handled case the log tail is ordinary plugin activity — texture streaming for
the plugin's map image:

```
REGISTER ASSET TEXTURE (30) FROM RAW:  (EXT: asset, CH: d570ab3f...)
Requesting new asset from server: 30
[ASSET] Receive meta data for asset 30 - type: Texture, source: Raw, ...
Requesting raw data for asset 30 from server...
[ASSET] Receive bytes for asset 30 - name: -119d570ab3f...-126, bytes: 33444 b
[02:32:35] [DB] Saved 1 players in database (2 ms)
Crash!!!
```

The sequence in the second world is deterministic across runs: the UI attaches, a
burst of texture registrations follows, map rendering begins, and the process dies
a few seconds later.

One possibly relevant detail: the client's asset id counter does **not** reset across
a world switch (world 1 ends around id 18, world 2 continues from 19 upward), while
the server-side managers are reset (`Reset PluginAssetManager (0 assets)`).

## Minimal reproduction

The smallest case that still crashes can be produced with a plugin that, on
`onEnable`, attaches one container holding a single `UILabel`:

```java
UIElement box = new UIElement();
box.setPivot(Pivot.UpperLeft);
box.setPosition(2f, 3f, true);
box.setSize(232, 24, false);
UILabel label = new UILabel("hello");
box.addChild(label);
player.addUIElement(box, UITarget.HUD);   // <- crashes in the 2nd world of a session
```

**Two elements is enough.** With this in place the plugin creates *no textures at
all* in the second world (`REGISTER ASSET TEXTURE` count = 0) and still crashes.

## Two further observations

**1. Element ids are recycled correctly *within* a world, and that is safe.**
In one session the plugin's UI was torn down and rebuilt in the first world (via a
command). The client log shows 380 element creations spanning ids **1–190** — i.e.
the same id range was allocated twice, because removal correctly returned the ids to
the pool. This did not crash. In the second world the plugin creates ids 1–190 once
and the game dies. So reusing element ids is not itself the problem; reusing them
**after a plugin reload** is.

**2. The client logs element creation but never destruction.**
A representative session logs 570 `CLIENT: Create new visual element` /
`CLIENT: Create new label` lines and **zero** removal or destruction lines, even
though ids are demonstrably recycled (see above). If that reflects the actual
client-side lifetime rather than just missing logging, the client's visual elements
may outlive a plugin unload while the server-side `PluginUIManager` is correctly
emptied — which would match the symptom exactly.

## Workaround in use

The plugin no longer removes any UI element in `onDisable`; it frees only its
textures and leaves the elements for the game's own `Reset PluginUIManager`. This is
stable across unlimited world switches with the minimap fully active.

The obvious concern is that this is the opposite of what a well-behaved plugin
should do, and it presumably relies on the engine's own cleanup being correct.

## Plugin-side issues found and fixed while investigating

These were genuine bugs in the plugin and are fixed, but **neither resolved the
crash**; they are listed so they can be excluded as causes:

1. `TextureAsset`s were never disposed. Every map render created a new
   server-streamed texture and none were released. Now disposed on swap and at
   teardown (`Reset PluginAssetManager (0 assets)`).
2. UI teardown removed only the two root containers, leaving all children
   registered (`Reset PluginUIManager (47 elements)`). Teardown now walks the tree
   depth-first, removes the roots, and sweeps `getAllUIElements(false)`; the game
   now reports 0 elements.
