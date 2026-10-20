package dev.breiwalker.soundstream.paper.command;

import dev.breiwalker.soundstream.paper.SoundStreamPlugin;
import dev.breiwalker.soundstream.paper.api.PlayOptions;
import dev.breiwalker.soundstream.paper.stream.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SoundStreamCommand implements CommandExecutor, TabCompleter {
    private final SoundStreamPlugin plugin;
    private final SessionManager sessions;

    public SoundStreamCommand(SoundStreamPlugin plugin, SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/soundstream play <player> <sound> [--2d|--here|--at x y z] [--volume v] [--loop]");
            sender.sendMessage("/soundstream stop <player> [all|id]");
            sender.sendMessage("/soundstream list | reload");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "play" -> play(sender, args);
            case "stop" -> stop(sender, args);
            case "list" -> list(sender);
            case "reload" -> reload(sender);
            default -> false;
        };
    }

    private boolean play(CommandSender sender, String[] args) {
        if (!sender.hasPermission("soundstream.play")) {
            sender.sendMessage("You do not have permission to play SoundStreams.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /soundstream play <player> <sound> [--2d|--here|--at x y z] [--volume v] [--loop]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return true;
        }
        if (!sessions.isAvailable(target)) {
            sessions.notifyMissing(target);
            sender.sendMessage(target.getName() + " does not have the SoundStream client mod.");
            return true;
        }

        PlayOptions options = PlayOptions.twoD();
        options.volume(sessions.defaultVolume());
        options.range(sessions.defaultRange());
        boolean positional = false;
        Location location = null;

        for (int i = 3; i < args.length; i++) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            switch (flag) {
                case "--2d" -> {
                    positional = false;
                    location = null;
                }
                case "--here" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("--here can only be used by a player.");
                        return true;
                    }
                    positional = true;
                    location = player.getLocation();
                }
                case "--at" -> {
                    if (i + 3 >= args.length) {
                        sender.sendMessage("Usage: --at <x> <y> <z>");
                        return true;
                    }
                    try {
                        double x = Double.parseDouble(args[++i]);
                        double y = Double.parseDouble(args[++i]);
                        double z = Double.parseDouble(args[++i]);
                        positional = true;
                        location = new Location(target.getWorld(), x, y, z);
                    } catch (NumberFormatException exception) {
                        sender.sendMessage("Invalid coordinates for --at.");
                        return true;
                    }
                }
                case "--volume" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage("Usage: --volume <0..1>");
                        return true;
                    }
                    try {
                        options.volume(Float.parseFloat(args[++i]));
                    } catch (NumberFormatException exception) {
                        sender.sendMessage("Invalid volume.");
                        return true;
                    }
                }
                case "--loop" -> options.loop(true);
                default -> {
                    sender.sendMessage("Unknown flag: " + args[i]);
                    return true;
                }
            }
        }

        if (positional) {
            options = PlayOptions.positional(location)
                    .volume(options.getVolume())
                    .range(options.getRange())
                    .loop(options.isLoop());
        }

        sessions.play(target, args[2], options).whenComplete((handle, throwable) -> {
            if (throwable != null) {
                sender.sendMessage("Could not stream " + args[2] + ": " + rootCause(throwable).getMessage());
            } else {
                sender.sendMessage("Streaming " + args[2] + " to " + target.getName()
                        + " (id " + handle.id() + ")");
            }
        });
        return true;
    }

    private boolean stop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("soundstream.stop")) {
            sender.sendMessage("You do not have permission to stop SoundStreams.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /soundstream stop <player> [all|id]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return true;
        }
        if (args.length == 2 || args[2].equalsIgnoreCase("all")) {
            sessions.stopAll(target);
            sender.sendMessage("Stopped all SoundStreams for " + target.getName());
            return true;
        }
        try {
            sessions.stop(target, Integer.parseInt(args[2]));
            sender.sendMessage("Stopped SoundStream " + args[2] + " for " + target.getName());
        } catch (NumberFormatException exception) {
            sender.sendMessage("Invalid stream id.");
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!sender.hasPermission("soundstream.admin")) {
            sender.sendMessage("You do not have permission to list SoundStream files.");
            return true;
        }
        List<String> sounds = plugin.library().list();
        if (sounds.isEmpty()) {
            sender.sendMessage("No .ogg or .mp3 files under " + plugin.library().root());
            return true;
        }
        sender.sendMessage("SoundStream files (" + sounds.size() + "):");
        for (String sound : sounds) {
            sender.sendMessage(" - " + sound);
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("soundstream.admin")) {
            sender.sendMessage("You do not have permission to reload SoundStream.");
            return true;
        }
        plugin.reloadSoundStream();
        sender.sendMessage("SoundStream configuration reloaded.");
        return true;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("play", "stop", "list", "reload"), args[0]);
        }
        if (args.length == 2) {
            boolean play = args[0].equalsIgnoreCase("play");
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (play && !sessions.isAvailable(player)) {
                    continue;
                }
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("play")) {
            return filter(plugin.library().list(), args[2]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("stop")) {
            return filter(List.of("all"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
