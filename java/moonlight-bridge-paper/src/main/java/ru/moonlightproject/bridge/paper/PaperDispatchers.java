package ru.moonlightproject.bridge.paper;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Correct Paper/Folia dispatch targets for callbacks that touch Minecraft state. */
public final class PaperDispatchers {
    private PaperDispatchers() { }

    /** Creates a dispatcher for server-global state. */
    public static PaperDispatcher global(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return (action, unavailable) -> {
            if (!plugin.isEnabled()) {
                unavailable.run();
                return;
            }
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, action);
        };
    }

    /** Creates a dispatcher for the region owning a cloned location. */
    public static PaperDispatcher at(Plugin plugin, Location location) {
        Objects.requireNonNull(plugin, "plugin");
        Location target = Objects.requireNonNull(location, "location").clone();
        return (action, unavailable) -> {
            if (!plugin.isEnabled()) {
                unavailable.run();
                return;
            }
            plugin.getServer().getRegionScheduler().execute(plugin, target, action);
        };
    }

    /** Creates a dispatcher following an entity's scheduler and retirement state. */
    public static PaperDispatcher forEntity(Plugin plugin, Entity entity) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(entity, "entity");
        return (action, unavailable) -> {
            if (!plugin.isEnabled()) {
                unavailable.run();
                return;
            }
            if (!entity.getScheduler().execute(plugin, action, unavailable, 1L)) {
                unavailable.run();
            }
        };
    }

    /** Chooses entity, block-region, or global dispatch for a command sender. */
    public static PaperDispatcher forSender(Plugin plugin, CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (sender instanceof Entity entity) return forEntity(plugin, entity);
        if (sender instanceof BlockCommandSender block) return at(plugin, block.getBlock().getLocation());
        return global(plugin);
    }
}
