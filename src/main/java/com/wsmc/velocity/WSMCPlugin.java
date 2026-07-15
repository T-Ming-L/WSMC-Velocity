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

package com.wsmc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.wsmc.velocity.websocket.WebSocketServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(id = "wsmc", name = "WSMC", version = "1.2.0", description = "Enable WebSocket support for Minecraft Java via Velocity proxy.", authors = {
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

        // Suppress Velocity's built-in "[connected player] ... has connected" log
        suppressConnectedPlayerLog();

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

    /**
     * Uses Log4j2's {@code RegexFilter} (via reflection to avoid a compile-time
     * dependency on log4j-core) to suppress Velocity's built-in
     * "[connected player] ... has connected" console line. WSMC provides its own
     * more useful log with the real client IP.
     */
    private void suppressConnectedPlayerLog() {
        try {
            // ── Resolve Log4j2 core classes (provided by Velocity at runtime) ──
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> loggerContext = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Class<?> configuration = Class.forName("org.apache.logging.log4j.core.config.Configuration");
            Class<?> filter = Class.forName("org.apache.logging.log4j.core.filter.RegexFilter");
            Class<?> filterInterface = Class.forName("org.apache.logging.log4j.core.Filter");

            // ── DENY if message matches, NEUTRAL otherwise ──────────────
            Object deny = Class.forName("org.apache.logging.log4j.core.Filter$Result")
                    .getField("DENY").get(null);
            Object neutral = Class.forName("org.apache.logging.log4j.core.Filter$Result")
                    .getField("NEUTRAL").get(null);

            // Build filter: RegexFilter.createFilter(regex, pattern, useRawMsg, level,
            // onMatch, onMismatch)
            Object regexFilter = filter.getMethod("createFilter", String.class,
                    java.util.regex.Pattern.class, boolean.class,
                    Class.forName("org.apache.logging.log4j.Level"),
                    filterInterface, filterInterface)
                    .invoke(null, "\\[connected player\\].*", null, false, null, deny, neutral);

            // ── Apply to root configuration ─────────────────────────────
            Object ctx = logManager.getMethod("getContext", boolean.class).invoke(null, false);
            Object cfg = loggerContext.getMethod("getConfiguration").invoke(ctx);
            configuration.getMethod("addFilter", filterInterface).invoke(cfg, regexFilter);
            loggerContext.getMethod("updateLoggers").invoke(ctx);

            logger.info("[WSMC] Suppressed Velocity connected-player log");
        } catch (Exception e) {
            // Non-critical – just log and continue
            logger.warn("[WSMC] Could not suppress Velocity connected-player log: {}", e.getMessage());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (wsServer != null) {
            wsServer.shutdown();
            logger.info("WSMC WebSocket server stopped.");
        }
    }

    /**
     * Block vanilla TCP logins when {@code wsmc.disableVanillaTCP} is enabled.
     * WebSocket connections arrive from localhost (plugin → Velocity), so only
     * those are permitted.
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!config.isDisableVanillaTCP()) {
            return;
        }
        var addr = event.getPlayer().getRemoteAddress();
        if (addr == null || !"127.0.0.1".equals(addr.getAddress().getHostAddress())) {
            event.setResult(LoginEvent.ComponentResult.denied(
                    Component.text(
                            "Vanilla TCP is disabled on this server. Please connect via WebSocket (ws:// or wss://).")));
            logger.info("[WSMC] Blocked vanilla TCP login from {}", addr);
        }
    }
}
