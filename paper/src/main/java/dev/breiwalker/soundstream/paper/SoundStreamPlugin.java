package dev.breiwalker.soundstream.paper;

import dev.breiwalker.soundstream.paper.api.SoundStreamService;
import dev.breiwalker.soundstream.paper.audio.SoundLibrary;
import dev.breiwalker.soundstream.paper.command.SoundStreamCommand;
import dev.breiwalker.soundstream.paper.listener.PlayerConnectionListener;
import dev.breiwalker.soundstream.paper.listener.SoundStreamMessageListener;
import dev.breiwalker.soundstream.paper.stream.SessionManager;
import dev.breiwalker.soundstream.protocol.Protocol;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SoundStreamPlugin extends JavaPlugin {
    private SoundLibrary library;
    private SessionManager sessions;
    private BukkitTask updateTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        library = new SoundLibrary(this, getConfig());
        sessions = new SessionManager(this, library);

        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, Protocol.CHANNEL, new SoundStreamMessageListener(this, sessions));

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(sessions), this);
        SoundStreamCommand command = new SoundStreamCommand(this, sessions);
        if (getCommand("soundstream") != null) {
            getCommand("soundstream").setExecutor(command);
            getCommand("soundstream").setTabCompleter(command);
        }

        getServer().getServicesManager().register(
                SoundStreamService.class, sessions, this, ServicePriority.Normal);
        updateTask = Bukkit.getScheduler().runTaskTimer(this, sessions::tickUpdates, 1L, 1L);

        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sessions.query(player);
            }
        });
        getLogger().info("SoundStream enabled; sounds directory is " + library.root());
    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (sessions != null) {
            sessions.closeAll();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getServicesManager().unregisterAll(this);
    }

    public void reloadSoundStream() {
        reloadConfig();
        library.reload(getConfig());
        sessions.configure();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.query(player);
        }
    }

    public SoundLibrary library() {
        return library;
    }
}
