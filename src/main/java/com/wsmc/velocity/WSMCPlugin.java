package com.wsmc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.wsmc.velocity.websocket.WebSocketServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "wsmc", name = "WSMC", version = "1.0.0", description = "Enable WebSocket support for Minecraft Java via Velocity proxy.", authors = {
        "WSMC Team" })
public class WSMCPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private WSMCConfig config;
    private WebSocketServer wsServer;

    @Inject
    public WSMCPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Load configuration
        this.config = WSMCConfig.load(dataDirectory, logger);

        if (config.isDebug()) {
            logger.info("WSMC debug logging enabled.");
        }

        // Start WebSocket server
        this.wsServer = new WebSocketServer(proxy, config, logger);
        try {
            wsServer.start();
            logger.info("WSMC WebSocket server started on port {}", config.getWsPort());
            if (config.getEndpoint() != null) {
                logger.info("WSMC WebSocket endpoint: {}", config.getEndpoint());
            }
            logger.info("WSMC max frame payload length: {}", config.getMaxFramePayloadLength());
        } catch (Exception e) {
            logger.error("Failed to start WSMC WebSocket server", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (wsServer != null) {
            wsServer.shutdown();
            logger.info("WSMC WebSocket server stopped.");
        }
    }
}
