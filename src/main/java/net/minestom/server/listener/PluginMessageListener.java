package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.network.packet.client.play.ClientPluginMessagePacket;

public class PluginMessageListener {

    public static void listener(ClientPluginMessagePacket packet, Player player) {
        PlayerPluginMessageEvent pluginMessageEvent = new PlayerPluginMessageEvent(packet.channel, packet.data);
        player.callEvent(PlayerPluginMessageEvent.class, pluginMessageEvent);
    }

}
