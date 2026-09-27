package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Commands implements TabExecutor {

    private static final Map<String, int[]> PRESETS = Map.of(
            "small", new int[]{6, 5},
            "medium", new int[]{10, 7},
            "large", new int[]{14, 10},
            "huge", new int[]{22, 14},
            "massive", new int[]{32, 20});

    private static final Component PREFIX = Component.text("[Aegis] ", TextColor.color(0x3FE0FF));

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;

    Commands(AegisTitan plugin, Items items, WallManager walls) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "getshield" -> give(sender, args, items.createShield(), "Aegis Wall Shield");
            case "getaxe" -> give(sender, args, items.createAxe(), "Titan Cleaver");
            case "shieldsize" -> size(sender, args);
            case "aegistitan" -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    plugin.reloadConfig();
                    msg(sender, "Config reloaded.", NamedTextColor.GREEN);
                } else {
                    msg(sender, "Usage: /aegistitan reload", NamedTextColor.GRAY);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void give(CommandSender sender, String[] args, ItemStack item, String name) {
        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("aegistitan.admin")) {
                msg(sender, "You can only give this to yourself.", NamedTextColor.RED);
                return;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                msg(sender, "Player " + args[0] + " isn't online.", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg(sender, "Console must name a player.", NamedTextColor.RED);
            return;
        }
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        target.playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 0.8f);
        msg(target, "You received the " + name + "!", NamedTextColor.AQUA);
        if (!target.equals(sender)) {
            msg(sender, "Gave the " + name + " to " + target.getName() + ".", NamedTextColor.GREEN);
        }
    }

    private void size(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can do that.", NamedTextColor.RED);
            return;
        }
        int w;
        int h;
        if (args.length == 0) {
            int[] now = walls.getSize(player);
            msg(player, "Your wall is " + now[0] + " wide \u00D7 " + now[1] + " tall.", NamedTextColor.AQUA);
            msg(player, "Change it: /shieldsize <width> <height>  or  /shieldsize <small|medium|large|huge|massive>",
                    NamedTextColor.GRAY);
            msg(player, "Limits: " + walls.minSize() + "\u2013" + walls.maxWidth() + " wide, "
                    + walls.minSize() + "\u2013" + walls.maxHeight() + " tall.", NamedTextColor.GRAY);
            return;
        } else if (args.length == 1) {
            int[] preset = PRESETS.get(args[0].toLowerCase(Locale.ROOT));
            if (preset == null) {
                msg(player, "Unknown size. Try small, medium, large, huge, massive, or /shieldsize <width> <height>.",
                        NamedTextColor.RED);
                return;
            }
            w = preset[0];
            h = preset[1];
        } else {
            try {
                w = Integer.parseInt(args[0]);
                h = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                msg(player, "Width and height must be whole numbers.", NamedTextColor.RED);
                return;
            }
        }
        int[] used = walls.setSize(player, w, h);
        msg(player, "Shield wall set to " + used[0] + " wide \u00D7 " + used[1] + " tall.", NamedTextColor.AQUA);
        if (used[0] != w || used[1] != h) {
            msg(player, "(Adjusted to fit the server's size limits.)", NamedTextColor.GRAY);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("shieldsize")) {
            if (args.length == 1) {
                options.addAll(List.of("small", "medium", "large", "huge", "massive", "8", "12", "16", "24"));
            } else if (args.length == 2) {
                options.addAll(List.of("6", "8", "10", "14", "20"));
            }
        } else if ((name.equals("getshield") || name.equals("getaxe")) && args.length == 1
                && sender.hasPermission("aegistitan.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
        } else if (name.equals("aegistitan") && args.length == 1) {
            options.add("reload");
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(PREFIX.append(Component.text(text, color)));
    }
}
