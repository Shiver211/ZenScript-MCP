package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.SPacketConfirmTransaction;

/** 只观察原版确认包；Netty 线程不读取 Container 或玩家。 */
public final class TransactionTracker {

    private static final String HANDLER_NAME = "zenscriptmcp_transactions";
    private static final int MAX_TRANSACTIONS = 512;
    private final Map<String, Boolean> confirmations = new LinkedHashMap<>();
    private Channel channel;

    public synchronized void update() {
        Minecraft minecraft = Minecraft.getMinecraft();
        Channel current = minecraft.getConnection() == null ? null
                : minecraft.getConnection().getNetworkManager().channel();
        if (current == channel) {
            return;
        }
        close();
        channel = current;
        if (current != null) {
            current.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                    if (message instanceof SPacketConfirmTransaction) {
                        SPacketConfirmTransaction packet = (SPacketConfirmTransaction) message;
                        record(current, packet.getWindowId(), packet.getActionNumber(), packet.wasAccepted());
                    }
                    super.channelRead(context, message);
                }
            });
        }
    }

    public synchronized String expect(int windowId, short transactionId) {
        if (channel == null || !channel.isOpen()) {
            throw new DomainException("source_unavailable", "容器网络连接不可用");
        }
        String key = windowId + ":" + transactionId;
        confirmations.remove(key);
        confirmations.put(key, null);
        while (confirmations.size() > MAX_TRANSACTIONS) {
            confirmations.remove(confirmations.keySet().iterator().next());
        }
        return key;
    }

    public synchronized Boolean confirmation(String key) {
        return confirmations.get(key);
    }

    private synchronized void record(Channel source, int windowId, short transactionId, boolean accepted) {
        String key = windowId + ":" + transactionId;
        if (source == channel && confirmations.containsKey(key)) {
            confirmations.put(key, accepted);
        }
    }

    public synchronized void close() {
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
        }
        channel = null;
        confirmations.clear();
    }
}
