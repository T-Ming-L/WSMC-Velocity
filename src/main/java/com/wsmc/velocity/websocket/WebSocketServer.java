/*
 * WSMC - WebSocket support for Minecraft Java via Velocity proxy.
 * Copyright (C) 2024 WSMC Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wsmc.velocity.websocket;

import com.wsmc.velocity.WSMCConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/**
 * Netty-based WebSocket server that listens for incoming WebSocket connections
 * and proxies them to a backend Minecraft server via Velocity.
 */
public class WebSocketServer {

    private final ProxyServer proxy;
    private final WSMCConfig config;
    private final Logger logger;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public WebSocketServer(ProxyServer proxy, WSMCConfig config, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Start the WebSocket server.
     */
    public ChannelFuture start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new WebSocketChannelInitializer(proxy, config, logger))
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);

        ChannelFuture future = bootstrap.bind(config.getWsPort()).sync();
        serverChannel = future.channel();

        if (config.isDebug()) {
            logger.info("[WSMC] WebSocket server bound to port {}", config.getWsPort());
        }

        return future;
    }

    /**
     * Gracefully shut down the WebSocket server.
     */
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("[WSMC] WebSocket server shut down.");
    }
}
