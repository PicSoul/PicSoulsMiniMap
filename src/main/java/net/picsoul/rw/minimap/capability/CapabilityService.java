package net.picsoul.rw.minimap.capability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.risingworld.api.Plugin;
import net.risingworld.api.World;
import net.risingworld.api.objects.Inventory;
import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Player;

import net.picsoul.rw.minimap.config.MinimapConfig;

/**
 * Determines each player's minimap tiers (see {@link Capabilities}).
 *
 * <p>Map / compass / clock tiers are <b>equipment-driven</b>: on while the
 * matching item sits in an Equipment slot. The calendar tier is
 * <b>possession-based and permanent</b>: once a player has ever crafted, looted,
 * or is simply carrying a calendar, its UID is persisted so the date stays
 * available forever.
 *
 * <p>Item names are matched by comma-separated tokens (case-insensitive
 * substring), so a single config value can cover variants — e.g. the timepiece
 * is "clockold"/"clockmodern" in-game, so the token list is "watch,clock,pocketwatch".
 */
public class CapabilityService {

    private static final String TAG = "[PicSoulsMiniMap]";

    private final Plugin plugin;
    private final MinimapConfig config;
    private final Set<String> calendarOwners = ConcurrentHashMap.newKeySet();
    private final Path storeFile;

    public CapabilityService(Plugin plugin, MinimapConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.storeFile = resolveStoreFile(plugin);
        load();
    }

    /** Compute the player's current tiers. */
    public Capabilities compute(Player player) {
        if (config.devAllTiers) {
            return new Capabilities(true, true, true, true, true);
        }
        // Owning a calendar (in any slot) latches the calendar tier on permanently.
        if (!hasCalendar(player) && inventoryHasCalendar(player)) {
            markCalendar(player);
        }
        boolean map = equipHas(player, config.mapItemName);
        boolean compass = equipHas(player, config.compassItemName);
        boolean watch = equipHas(player, config.watchItemName);
        boolean calendar = hasCalendar(player);
        // radarItemName ("compassmodern") is a stricter token than compassItemName
        // ("compass", which matches both compassold/compassmodern), so this is
        // independently true only for the upgraded compass.
        boolean radar = equipHas(player, config.radarItemName);
        return new Capabilities(map, compass, watch, calendar, radar);
    }

    /** True if an Equipment-slot item matches any of the comma-separated tokens. */
    private boolean equipHas(Player player, String tokensCsv) {
        return slotHas(player, Inventory.SlotType.Equipment, tokensCsv);
    }

    /** True if any item anywhere in the inventory is (or places) a calendar. */
    private boolean inventoryHasCalendar(Player player) {
        try {
            Item[] items = player.getInventory().getAllItems();
            if (items != null) {
                for (Item it : items) {
                    if (matchesCalendar(it)) return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private boolean slotHas(Player player, Inventory.SlotType slot, String tokensCsv) {
        try {
            return anyMatches(player.getInventory().getItems(slot), tokensCsv);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean anyMatches(Item[] items, String tokensCsv) {
        if (items == null) return false;
        for (Item it : items) {
            if (it != null && nameMatchesAny(it.getName(), tokensCsv)) return true;
        }
        return false;
    }

    /** Case-insensitive: does {@code name} contain any comma-separated token? */
    private static boolean nameMatchesAny(String name, String tokensCsv) {
        if (name == null || tokensCsv == null) return false;
        String n = name.toLowerCase();
        for (String tok : tokensCsv.split(",")) {
            String t = tok.trim().toLowerCase();
            if (!t.isEmpty() && n.contains(t)) return true;
        }
        return false;
    }

    public boolean hasCalendar(Player player) {
        return calendarOwners.contains(worldKey(player));
    }

    private void markCalendar(Player player) {
        if (calendarOwners.add(worldKey(player))) {
            save();
            System.out.println(TAG + "[caps] calendar owned by " + player.getName()
                    + " in world '" + safeWorldName() + "' (date unlocked)");
        }
    }

    /** Calendar ownership is per-world (a calendar in one world doesn't unlock the
     *  date in another), so keys are "worldName|uid". */
    private static String worldKey(Player player) {
        return safeWorldName() + "|" + player.getUID();
    }

    private static String safeWorldName() {
        try {
            String w = World.getName();
            if (w != null && !w.isEmpty()) return w;
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** Called on inventory-add / craft: remember a calendar forever once obtained. */
    public void onItemObtained(Player player, Item item) {
        if (matchesCalendar(item)) {
            markCalendar(player);
        }
    }

    /** Called on craft: the recipe name is the most reliable signal (exactly "calendar"). */
    public void onRecipeCrafted(Player player, String recipeName) {
        if (nameMatchesAny(recipeName, config.calendarItemName)) {
            markCalendar(player);
        }
    }

    /**
     * A calendar may be a direct item (future-proofing), but in the current game
     * it's a placeable <b>object</b> carried in inventory as a generic
     * {@code objectkit}; the {@link Item.ObjectItem} wrapper names the specific
     * object it will place, so we check that too.
     */
    private boolean matchesCalendar(Item item) {
        if (item == null) return false;
        if (nameMatchesAny(item.getName(), config.calendarItemName)) return true;
        try {
            if (item instanceof Item.ObjectItem oi
                    && nameMatchesAny(oi.getObjectName(), config.calendarItemName)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Debug (/mm ids): list Equipment + Quickslot item names so tokens can be calibrated. */
    public String describeSlots(Player player) {
        return "Equipment[" + listSlot(player, Inventory.SlotType.Equipment) + "] Quickslot["
                + listSlot(player, Inventory.SlotType.Quickslot) + "]";
    }

    private String listSlot(Player player, Inventory.SlotType slot) {
        StringBuilder sb = new StringBuilder();
        try {
            Item[] items = player.getInventory().getItems(slot);
            if (items != null) {
                for (Item it : items) {
                    if (it == null) continue;
                    sb.append('\'').append(it.getName()).append("'(").append(it.getTypeID()).append(") ");
                }
            }
        } catch (Throwable t) {
            sb.append("err:").append(t.getMessage());
        }
        return sb.toString().trim();
    }

    // ---- persistence ----

    private static Path resolveStoreFile(Plugin plugin) {
        try {
            String dir = plugin.getPath();
            if (dir != null && !dir.isEmpty()) {
                return Paths.get(dir, "calendar_owners.txt");
            }
        } catch (Throwable ignored) {
        }
        return Paths.get("calendar_owners.txt");
    }

    private void load() {
        try {
            if (storeFile != null && Files.exists(storeFile)) {
                for (String line : Files.readAllLines(storeFile)) {
                    String uid = line.trim();
                    if (!uid.isEmpty()) calendarOwners.add(uid);
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + " could not load calendar store: " + t.getMessage());
        }
    }

    private void save() {
        try {
            if (storeFile != null) Files.write(storeFile, calendarOwners);
        } catch (IOException | RuntimeException t) {
            System.out.println(TAG + " could not save calendar store: " + t.getMessage());
        }
    }
}
