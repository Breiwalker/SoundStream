package dev.breiwalker.soundstream.paper.listener;

import dev.breiwalker.soundstream.paper.SoundStreamPlugin;
import dev.breiwalker.soundstream.paper.stream.SessionManager;
import dev.breiwalker.soundstream.protocol.Protocol;
import dev.breiwalker.soundstream.protocol.ProtocolCodec;
import dev.breiwalker.soundstream.protocol.ProtocolException;
import dev.breiwalker.soundstream.protocol.ProtocolMessages;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class SoundStreamMessageListener implements PluginMessageListener {
    private final SoundStreamPlugin plugin;
    private final SessionManager sessions;

    public SoundStreamMessageListener(SoundStreamPlugin plugin, SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.CHANNEL.equals(channel)) {
            return;
        }
        try {
            ProtocolMessages decoded = ProtocolCodec.decode(message);
            if (decoded instanceof ProtocolMessages.Hello hello) {
                plugin.getLogger().info("Received SoundStream handshake from " + player.getName());
                sessions.onHello(player, hello);
            } else if (decoded instanceof ProtocolMessages.Credit credit) {
                sessions.onCredit(player, credit.streamId(), credit.bytes());
            } else if (decoded instanceof ProtocolMessages.Done done) {
                sessions.onDone(player, done.streamId());
            } else {
                plugin.getLogger().fine("Ignoring unexpected SoundStream message from " + player.getName());
            }
        } catch (ProtocolException exception) {
            plugin.getLogger().warning("Rejected malformed SoundStream message from "
                    + player.getName() + ": " + exception.getMessage());
        }
    }
}
