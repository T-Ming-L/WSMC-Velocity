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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bridges a WebSocket connection into Velocity's normal Minecraft pipeline.
 *
 * <p>
 * Instead of connecting directly to backend servers (which would miss the
 * Velocity forwarding handshake), this handler connects to <b>localhost</b>
 * on Velocity's own listening port. Velocity then handles login,
 * authentication and backend-connection forwarding normally.
 *
 * <p>
 * Data flow:
 * 
 * <pre>
 * Client WS BinaryFrame  ──►  localhost:velocityPort(Velocity)  ──►  Backend
 * Backend                ──►  Velocity                           ──►  BinaryFrame → Client
 * </pre>
 */
public class WebSocketProxyHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final ProxyServer proxy;
    private final WSMCConfig config;
    private final Logger logger;

    private Channel backendChannel;
    private NioEventLoopGroup backendGroup;
    /** Real client IP resolved from WebSocket handshake headers. */
    private InetSocketAddress realClientAddr;
    /** Whether a LOGIN handshake has been detected (not a STATUS ping). */
    private boolean loginDetected = false;
    /** Player username extracted from LoginStart packet. */
    private String playerName = null;
    /**
     * Buffer for the first data frame(s) arriving before the backend
     * connection is established. Without this, the initial Minecraft
     * handshake packet is silently dropped, causing connection timeouts.
     */
    private ByteBuf pendingFrame = null;
    /**
     * Periodic keepalive ping task – prevents CDN / proxy
     * (e.g. Cloudflare 60–100s) from closing the WebSocket due to
     * inactivity.
     */
    private ScheduledFuture<?> keepaliveTask = null;

    public WebSocketProxyHandler(ProxyServer proxy, WSMCConfig config, Logger logger) {
        super(false); // Do not auto-release – we forward the buffer
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Not yet connected to backend – will do so on first frame or explicitly here.
        // We connect to backend once the WebSocket handshake completes.
        // The WebSocketServerProtocolHandler fires
        // userEventTriggered(HandshakeComplete).
        if (config.isDebug()) {
            logger.info("[WSMC] Client channel active: {}", ctx.channel().remoteAddress());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // WebSocket handshake complete – capture real IP, then connect.
        if (evt instanceof io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete complete) {
            // Resolve real client IP (checks CDN headers before fallback)
            this.realClientAddr = resolveRealClientAddress(ctx, complete);

            // Start keepalive pings to prevent CDN/proxy idle timeout
            startKeepalive(ctx);

            if (config.isDebug()) {
                logger.info("[WSMC] Handshake complete, URI: {}, direct addr: {}",
                        complete.requestUri(), ctx.channel().remoteAddress());
            }
            connectToBackend(ctx);
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Starts a periodic keepalive ping (every 15 seconds) so that CDNs /
     * reverse proxies do not close the WebSocket due to inactivity.
     * The client Netty stack auto-responds with Pong per RFC 6455.
     */
    private void startKeepalive(ChannelHandlerContext ctx) {
        keepaliveTask = ctx.channel().eventLoop().scheduleWithFixedDelay(
                () -> {
                    if (ctx.channel().isActive()) {
                        ctx.channel().writeAndFlush(new PingWebSocketFrame());
                    }
                },
                15, 15, TimeUnit.SECONDS);
        if (config.isDebug()) {
            logger.info("[WSMC] Keepalive ping started (interval: 15s)");
        }
    }

    /**
     * Connect to the target backend Minecraft server.
     */
    private void connectToBackend(ChannelHandlerContext ctx) {
        // Resolve backend server from Velocity config
        InetSocketAddress backendAddr = resolveBackendAddress();

        if (backendAddr == null) {
            logger.error("[WSMC] No backend server available. Closing connection.");
            ctx.channel().writeAndFlush(
                    new CloseWebSocketFrame(1011, "No backend server available"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        backendGroup = new NioEventLoopGroup(1);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(backendGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new BackendHandler(ctx.channel(), config, logger));
                    }
                });

        ChannelFuture future = bootstrap.connect(backendAddr);
        future.addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                backendChannel = f.channel();

                // ── Inject Proxy Protocol V2 header ───────────────────
                if (config.isProxyProtocolEnabled() && realClientAddr != null) {
                    io.netty.buffer.ByteBuf ppHeader = backendChannel.alloc().buffer(52);
                    InetSocketAddress localAddr = (InetSocketAddress) backendChannel.localAddress();
                    ProxyProtocolV2Encoder.encode(ppHeader, realClientAddr, localAddr);
                    backendChannel.writeAndFlush(ppHeader);
                    if (config.isDebug()) {
                        logger.info("[WSMC] Sent Proxy Protocol V2 header ({} bytes) for {} → {}",
                                ppHeader.readableBytes(), realClientAddr, localAddr);
                    }
                }

                // ── Flush any data buffered while backend was connecting ──
                if (pendingFrame != null) {
                    backendChannel.writeAndFlush(pendingFrame);
                    if (config.isDebug()) {
                        logger.info("[WSMC] Flushed buffered frame ({} bytes) to backend",
                                pendingFrame.readableBytes());
                    }
                    pendingFrame = null;
                }

                if (config.isDebug()) {
                    logger.info("[WSMC] Connected to backend: {}", backendAddr);
                }
            } else {
                logger.error("[WSMC] Failed to connect to backend {}: {}", backendAddr,
                        f.cause().getMessage());
                ctx.channel().writeAndFlush(
                        new CloseWebSocketFrame(1011, "Failed to connect to backend"))
                        .addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    /**
     * Resolve the address to connect to. Returns <b>localhost:velocityPort</b>
     * so the data goes through Velocity's own Minecraft pipeline. Velocity
     * then handles login, authentication and backend forwarding.
     */
    private InetSocketAddress resolveBackendAddress() {
        return new InetSocketAddress("127.0.0.1", config.getVelocityPort());
    }

    // ── Real IP resolution ────────────────────────────────────────────

    /**
     * Resolve the real client IP address from the WebSocket handshake
     * HTTP headers. Checks common CDN / reverse-proxy headers
     * ({@code X-Forwarded-For}, {@code CF-Connecting-IP},
     * {@code X-Real-IP}) and logs all available headers for
     * diagnostics. Falls back to the direct socket remote address.
     */
    private InetSocketAddress resolveRealClientAddress(ChannelHandlerContext ctx,
            io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete complete) {
        var headers = complete.requestHeaders();

        // ── Dump all HTTP headers for debug ────────────────────────────
        if (config.isDebug()) {
            StringBuilder sb = new StringBuilder("[WSMC] WS request headers:");
            headers.forEach(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue()));
            logger.info(sb.toString());
        }

        // ── Collect candidate headers ───────────────────────────────────
        String configuredHeader = config.getProxyProtocolRealIpHeader();
        String[] candidates = {
                configuredHeader,
                "X-Forwarded-For",
                "CF-Connecting-IP",
                "X-Real-IP",
                "True-Client-IP",
                "X-Client-IP",
                "WL-Proxy-Client-IP",
                "Proxy-Client-IP"
        };

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String hdr : candidates) {
            if (hdr == null || hdr.isEmpty() || !seen.add(hdr))
                continue;

            String headerValue = headers.get(hdr);
            if (headerValue == null || headerValue.isEmpty())
                continue;

            // Proxy chains: take the first (leftmost) IP
            String ip = headerValue.split(",")[0].trim();
            try {
                return new InetSocketAddress(ip, 0);
            } catch (Exception e) {
                if (config.isDebug()) {
                    logger.warn("[WSMC] Failed to parse header '{}' value '{}'", hdr, headerValue);
                }
            }
        }

        // ── Fallback: direct socket address ─────────────────────────────
        java.net.SocketAddress remote = ctx.channel().remoteAddress();
        if (remote instanceof InetSocketAddress isa) {
            if (config.isDebug()) {
                logger.info("[WSMC] No proxy header, using direct addr: {}", isa.getHostString());
            }
            return isa;
        }
        return null;
    }

    // ── Client → Backend ───────────────────────────────────────────────

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof BinaryWebSocketFrame binaryFrame) {
            ByteBuf content = binaryFrame.content();

            // Phase 1: Check Minecraft handshake to distinguish login vs status ping
            if (!loginDetected && content.readableBytes() > 0) {
                checkMinecraftHandshakeAndLog(ctx, content);
            }
            // Phase 2: If login was detected, try to extract player name from LoginStart
            if (loginDetected && playerName == null && content.readableBytes() > 0) {
                int ri = content.readerIndex();
                tryParseLoginStart(content);
                content.readerIndex(ri);
            }

            if (config.isDumpBytes()) {
                logger.info("[WSMC] C→S ({} bytes):\n{}", content.readableBytes(),
                        ByteBufUtil.prettyHexDump(content));
            }

            if (backendChannel != null && backendChannel.isActive()) {
                // Retain before writing since auto-release is off
                backendChannel.writeAndFlush(content.retain());
            } else {
                // Backend not yet connected – buffer the frame instead of dropping it.
                // This is critical: the first frame contains the Minecraft handshake
                // and must not be lost.
                if (pendingFrame == null) {
                    pendingFrame = content.retain();
                } else {
                    // Concatenate with existing buffered data (rare edge case)
                    pendingFrame = Unpooled.wrappedBuffer(pendingFrame, content.retain());
                }
                if (config.isDebug()) {
                    logger.info("[WSMC] Backend not ready, buffered frame ({} bytes)", content.readableBytes());
                }
            }
        } else if (frame instanceof CloseWebSocketFrame) {
            // Client closed WebSocket
            if (backendChannel != null) {
                backendChannel.close();
            }
            ctx.channel().writeAndFlush(frame.retain()).addListener(ChannelFutureListener.CLOSE);
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        }
    }

    // ── Minecraft handshake inspection ────────────────────────────────

    /**
     * Peeks at the first binary frame to check the Minecraft handshake
     * {@code nextState} field. Sets {@code loginDetected} for LOGIN (2)
     * requests, and tries to parse a subsequent LoginStart packet
     * from the same frame if present.
     */
    private void checkMinecraftHandshakeAndLog(ChannelHandlerContext ctx, ByteBuf content) {
        int readerIndex = content.readerIndex();
        try {
            // ── Skip VarInt packet length ──────────────────────────────
            readVarInt(content);
            // ── Packet ID ──────────────────────────────────────────────
            int packetId = readVarInt(content);
            if (packetId != 0x00) {
                if (config.isDebug()) {
                    logger.info("[WSMC] First frame packetId={}, raw hex dump:", packetId);
                    content.readerIndex(readerIndex);
                    int len = Math.min(content.readableBytes(), 64);
                    logger.info("\n{}", ByteBufUtil.prettyHexDump(content, readerIndex, len));
                    content.readerIndex(readerIndex);
                }
                return; // Not a handshake packet
            }
            // ── Handshake fields ───────────────────────────────────────
            // Protocol version
            readVarInt(content);
            // Server address
            readString(content);
            // Server port (2 bytes)
            if (content.readableBytes() < 2)
                return;
            content.readShort();
            // Next state
            int nextState = readVarInt(content);

            if (nextState == 2) {
                // LOGIN – real player connection
                loginDetected = true;
                String ip = (realClientAddr != null) ? realClientAddr.getHostString() : "unknown";
                logger.info("[WSMC] Player connecting - IP: {}, port: ws{}", ip, config.getWsPort());
                // Try to parse LoginStart from remaining bytes in the same frame
                if (content.readableBytes() > 0) {
                    tryParseLoginStart(content);
                }
            }
        } catch (Exception e) {
            if (config.isDebug()) {
                logger.warn("[WSMC] Handshake parse error: {}", e.getMessage());
                content.readerIndex(readerIndex);
                int len = Math.min(content.readableBytes(), 64);
                logger.warn("First {} bytes:\n{}", len, ByteBufUtil.prettyHexDump(content, readerIndex, len));
            }
        } finally {
            content.readerIndex(readerIndex);
        }
    }

    /**
     * Tries to parse a Minecraft LoginStart packet (packet ID 0x00 in the LOGIN
     * state) to extract the player username. The Minecraft wire format is:
     * {@code VarInt packetLength, VarInt packetId(0x00), String username}.
     * Does NOT modify the readerIndex on failure — the caller is responsible
     * for restoring it.
     */
    private void tryParseLoginStart(ByteBuf content) {
        int localRi = content.readerIndex();
        try {
            // ── Skip VarInt packet length ──────────────────────────────
            readVarInt(content);
            // ── Packet ID ──────────────────────────────────────────────
            int packetId = readVarInt(content);
            if (packetId != 0x00) {
                return; // Not a LoginStart packet
            }
            String name = readString(content);
            if (name != null && !name.isEmpty()) {
                playerName = name;
                String ip = (realClientAddr != null) ? realClientAddr.getHostString() : "unknown";
                logger.info("[WSMC] {} connected - IP: {}:{}", playerName, ip, config.getWsPort());
                // Overwrite the earlier "Player connecting" message with the named one
            }
        } catch (Exception e) {
            // Restore only on failure – LoginStart might be in a separate frame
            content.readerIndex(localRi);
        }
    }

    private static int readVarInt(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return result;
    }

    private static String readString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > buf.readableBytes()) {
            throw new RuntimeException("Invalid string length");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Cancel keepalive ping task
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
        if (loginDetected) {
            String ip = (realClientAddr != null) ? realClientAddr.getHostString() : "unknown";
            logger.info("[WSMC] {} disconnected - IP: {}", playerName != null ? playerName : "unknown", ip);
        }
        // Release any buffered frame that was never flushed
        if (pendingFrame != null) {
            pendingFrame.release();
            pendingFrame = null;
        }
        if (backendChannel != null) {
            backendChannel.close();
        }
        if (backendGroup != null) {
            backendGroup.shutdownGracefully();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String msg = cause.getMessage();
        // Log transient errors at debug level, unexpected ones at error level
        if (msg != null && (msg.contains("Connection reset") || msg.contains("closed")
                || msg.contains("timeout") || msg.contains("refused"))) {
            if (config.isDebug()) {
                logger.warn("[WSMC] WS proxy: {} (expected)", msg);
            }
        } else {
            logger.error("[WSMC] WS proxy error: {}", msg);
            if (config.isDebug()) {
                cause.printStackTrace();
            }
        }
        // Only close if the channel is still open – avoids cascading close
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    // ── Backend → Client Handler (inner class) ─────────────────────────

    /**
     * Handles data from the backend Minecraft server and forwards it
     * as binary WebSocket frames back to the client.
     */
    private static class BackendHandler extends ChannelInboundHandlerAdapter {

        private final Channel clientChannel;
        private final WSMCConfig config;
        private final Logger logger;

        BackendHandler(Channel clientChannel, WSMCConfig config, Logger logger) {
            this.clientChannel = clientChannel;
            this.config = config;
            this.logger = logger;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                if (config.isDumpBytes()) {
                    logger.info("[WSMC] S→C ({} bytes):\n{}", buf.readableBytes(),
                            ByteBufUtil.prettyHexDump(buf));
                }

                if (clientChannel.isActive()) {
                    clientChannel.writeAndFlush(new BinaryWebSocketFrame(buf.retain()));
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // Backend disconnected – close the WebSocket
            if (clientChannel.isActive()) {
                clientChannel.writeAndFlush(new CloseWebSocketFrame())
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("[WSMC] Backend connection error: {}", cause.getMessage());
            ctx.close();
        }
    }
}
