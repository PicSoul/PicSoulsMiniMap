package net.picsoul.rw.minimap.session;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.risingworld.api.objects.Player;

/**
 * Registry of active {@link PlayerSession}s, keyed by the player's runtime id.
 */
public class SessionRegistry {

    private final Map<Integer, PlayerSession> byId = new ConcurrentHashMap<>();

    public void put(Player player, PlayerSession session) {
        byId.put(player.getID(), session);
    }

    public PlayerSession get(Player player) {
        return byId.get(player.getID());
    }

    public PlayerSession remove(Player player) {
        return byId.remove(player.getID());
    }

    public Collection<PlayerSession> all() {
        return byId.values();
    }

    public void forEach(Consumer<PlayerSession> fn) {
        byId.values().forEach(fn);
    }

    public void clear() {
        byId.clear();
    }
}
