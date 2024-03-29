package cn.teleinfo.idpointer.sdk.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.pool.*;

import java.net.InetSocketAddress;

public class ChannelPoolMapDefault extends AbstractChannelPoolMap<InetSocketAddress, TimedChannelPool> {

    private final Bootstrap bootstrap;
    private final ChannelPoolHandler channelPoolHandler;
    private final int minConnectionsPerServer;
    private final int maxConnectionsPerServer;

    public ChannelPoolMapDefault(Bootstrap bootstrap, ChannelPoolHandler channelPoolHandler, int minConnectionsPerServer, int maxConnectionsPerServer) {
        this.bootstrap = bootstrap;
        this.channelPoolHandler = channelPoolHandler;
        this.minConnectionsPerServer = minConnectionsPerServer;
        this.maxConnectionsPerServer = maxConnectionsPerServer;
    }

    @Override
    protected TimedChannelPool newPool(InetSocketAddress key) {
        Bootstrap bootstrapPooled = bootstrap.clone();
        bootstrapPooled.remoteAddress(key);
        //return new FixedChannelPool(bootstrapPooled, channelPoolHandler, ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 5000L, maxConnectionsPerServer, 10000, true, false);
        return new DefaultChannelPool(bootstrapPooled, channelPoolHandler, ChannelHealthChecker.ACTIVE, AbstractFixedChannelPool.AcquireTimeoutAction.FAIL, 5000L, minConnectionsPerServer, maxConnectionsPerServer, 10000, true, false);
    }

}
