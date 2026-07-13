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
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/**
 * Initializes Netty channels for WebSocket connections.
 * Pipeline: HTTP codec → Aggregator → ChunkedWrite → WS Protocol → Proxy
 * Handler
 */
public class WebSocketChannelInitializer extends ChannelInitializer<Channel> {

    private final ProxyServer proxy;
    private final WSMCConfig config;
    private final Logger logger;

    public WebSocketChannelInitializer(ProxyServer proxy, WSMCConfig config, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    @Override
    protected void initChannel(Channel ch) {
        String endpoint = config.getEndpoint();
        int maxFramePayloadLength = config.getMaxFramePayloadLength();

        if (config.isDebug()) {
            logger.info("[WSMC] New connection from {}", ch.remoteAddress());
        }

        ch.pipeline().addLast(
                // HTTP codec for WebSocket handshake
                new HttpServerCodec(),
                // Aggregate HTTP chunks
                new HttpObjectAggregator(65536),
                // Write chunked data
                new ChunkedWriteHandler(),
                // WebSocket protocol handler (handles handshake & frame aggregation)
                new WebSocketServerProtocolHandler(
                        endpoint != null ? endpoint : "/",
                        null,
                        true,
                        maxFramePayloadLength),
                // Our proxy handler – bridges WebSocket frames to backend
                new WebSocketProxyHandler(proxy, config, logger));
    }
}
