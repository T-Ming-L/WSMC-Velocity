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

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for WSMC Velocity plugin.
 *
 * Properties are read from wsmc.properties in the plugin data directory.
 * Equivalent to the system properties used by the WSMC mod.
 */
public class WSMCConfig {

    // ── Property keys (matching WSMC mod) ──────────────────────────────
    // Note: In Velocity, we add "wsPort" and "backendServer" which are
    // Velocity-specific (the mod runs on the game server itself).

    private static final String KEY_DISABLE_VANILLA_TCP = "wsmc.disableVanillaTCP";
    private static final String KEY_WSMC_ENDPOINT = "wsmc.wsmcEndpoint";
    private static final String KEY_DEBUG = "wsmc.debug";
    private static final String KEY_DUMP_BYTES = "wsmc.dumpBytes";
    private static final String KEY_MAX_FRAME_PAYLOAD_LENGTH = "wsmc.maxFramePayloadLength";

    // Velocity-specific keys
    private static final String KEY_WS_PORT = "wsmc.wsPort";
    private static final String KEY_BACKEND_SERVER = "wsmc.backendServer";
    /** Velocity's own bind port – the plugin forwards to localhost on this port. */
    private static final String KEY_VELOCITY_PORT = "wsmc.velocityPort";
    /**
     * forwarding-secret must match the secret in velocity.toml for modern
     * forwarding.
     */
    private static final String KEY_FORWARDING_SECRET = "wsmc.forwardingSecret";

    // Proxy Protocol V2 keys
    private static final String KEY_PROXY_PROTOCOL_ENABLED = "wsmc.proxyProtocol.enabled";
    private static final String KEY_PROXY_PROTOCOL_REAL_IP_HDR = "wsmc.proxyProtocol.realIpHeader";
    private static final String DEFAULT_REAL_IP_HEADER = "X-Forwarded-For";

    // ── Fields ─────────────────────────────────────────────────────────

    private final boolean disableVanillaTCP;
    private final String endpoint;
    private final boolean debug;
    private final boolean dumpBytes;
    private final int maxFramePayloadLength;
    private final int wsPort;
    private final String backendServer;
    private final int velocityPort;
    private final String forwardingSecret;
    private final boolean proxyProtocolEnabled;
    private final String proxyProtocolRealIpHeader;

    public WSMCConfig(boolean disableVanillaTCP, String endpoint, boolean debug,
            boolean dumpBytes, int maxFramePayloadLength,
            int wsPort, String backendServer, int velocityPort, String forwardingSecret,
            boolean proxyProtocolEnabled, String proxyProtocolRealIpHeader) {
        this.disableVanillaTCP = disableVanillaTCP;
        this.endpoint = endpoint;
        this.debug = debug;
        this.dumpBytes = dumpBytes;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.wsPort = wsPort;
        this.backendServer = backendServer;
        this.velocityPort = velocityPort;
        this.forwardingSecret = forwardingSecret;
        this.proxyProtocolEnabled = proxyProtocolEnabled;
        this.proxyProtocolRealIpHeader = proxyProtocolRealIpHeader;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public boolean isDisableVanillaTCP() {
        return disableVanillaTCP;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isDumpBytes() {
        return dumpBytes;
    }

    public int getMaxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    public int getWsPort() {
        return wsPort;
    }

    public String getBackendServer() {
        return backendServer;
    }

    public int getVelocityPort() {
        return velocityPort;
    }

    public String getForwardingSecret() {
        return forwardingSecret;
    }

    public boolean isProxyProtocolEnabled() {
        return proxyProtocolEnabled;
    }

    public String getProxyProtocolRealIpHeader() {
        return proxyProtocolRealIpHeader;
    }

    // ── Load ───────────────────────────────────────────────────────────

    /**
     * Loads configuration from wsmc.properties in the plugin data directory.
     * Creates the file with defaults if it does not exist.
     */
    public static WSMCConfig load(Path dataDirectory, Logger logger) {
        Path configFile = dataDirectory.resolve("wsmc.properties");
        Properties props = new Properties();

        // Defaults (matching WSMC mod defaults)
        boolean disableVanillaTCP = false;
        String endpoint = null;
        boolean debug = false;
        boolean dumpBytes = false;
        int maxFramePayloadLength = 65536;
        int wsPort = 25566;
        String backendServer = "default";
        int velocityPort = 25565;
        String forwardingSecret = "";
        boolean proxyProtocolEnabled = false;
        String proxyProtocolRealIpHeader = DEFAULT_REAL_IP_HEADER;

        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);

                disableVanillaTCP = parseBoolean(props, KEY_DISABLE_VANILLA_TCP, false);
                endpoint = parseString(props, KEY_WSMC_ENDPOINT, null);
                debug = parseBoolean(props, KEY_DEBUG, false);
                dumpBytes = parseBoolean(props, KEY_DUMP_BYTES, false);
                maxFramePayloadLength = parseInt(props, KEY_MAX_FRAME_PAYLOAD_LENGTH, 65536);
                wsPort = parseInt(props, KEY_WS_PORT, 25566);
                backendServer = parseString(props, KEY_BACKEND_SERVER, "default");
                velocityPort = parseInt(props, KEY_VELOCITY_PORT, 25565);
                forwardingSecret = parseString(props, KEY_FORWARDING_SECRET, "");
                proxyProtocolEnabled = parseBoolean(props, KEY_PROXY_PROTOCOL_ENABLED, false);
                proxyProtocolRealIpHeader = parseString(props, KEY_PROXY_PROTOCOL_REAL_IP_HDR, DEFAULT_REAL_IP_HEADER);

            } catch (IOException e) {
                logger.error("Failed to load wsmc.properties, using defaults", e);
            }
        } else {
            // Create default config file
            try {
                Files.createDirectories(dataDirectory);
                StringBuilder sb = new StringBuilder();
                sb.append("# WSMC Velocity Plugin Configuration\n");
                sb.append("# See https://github.com/rikka0w0/wsmc for original mod documentation\n\n");
                sb.append("# Disable vanilla TCP login and server status.\n");
                sb.append(KEY_DISABLE_VANILLA_TCP).append("=false\n\n");
                sb.append("# WebSocket endpoint path. Must start with /. Case-sensitive.\n");
                sb.append("# ").append(KEY_WSMC_ENDPOINT).append("=/mc\n\n");
                sb.append("# Show debug logs.\n");
                sb.append(KEY_DEBUG).append("=false\n\n");
                sb.append("# Dump raw WebSocket binary frames. Works only if wsmc.debug=true.\n");
                sb.append(KEY_DUMP_BYTES).append("=false\n\n");
                sb.append("# Maximum allowable frame payload length.\n");
                sb.append(KEY_MAX_FRAME_PAYLOAD_LENGTH).append("=65536\n\n");
                sb.append("# [Velocity-specific] WebSocket listen port.\n");
                sb.append(KEY_WS_PORT).append("=25566\n\n");
                sb.append("# [Velocity-specific] Target backend server name (as registered in velocity.toml).\n");
                sb.append("# Use 'default' to try all servers in order.\n");
                sb.append(KEY_BACKEND_SERVER).append("=default\n\n");
                sb.append("# [Velocity-specific] Velocity's own Minecraft listen port.\n");
                sb.append("# The plugin connects to localhost on this port to go through Velocity's forwarding.\n");
                sb.append(KEY_VELOCITY_PORT).append("=25565\n\n");
                sb.append("# [Velocity-specific] Forwarding secret (must match forwarding-secret in velocity.toml).\n");
                sb.append(KEY_FORWARDING_SECRET).append("=\n\n");
                sb.append("# ── Proxy Protocol V2 ──\n");
                sb.append("# Enable Proxy Protocol V2 to pass real client IP to Velocity.\n");
                sb.append("# Also enable 'proxy-protocol = true' in velocity.toml.\n");
                sb.append(KEY_PROXY_PROTOCOL_ENABLED).append("=false\n");
                sb.append("# HTTP header containing the real client IP (e.g., X-Forwarded-For, CF-Connecting-IP).\n");
                sb.append(KEY_PROXY_PROTOCOL_REAL_IP_HDR).append("=").append(DEFAULT_REAL_IP_HEADER).append("\n");

                Files.writeString(configFile, sb.toString());
                logger.info("Created default configuration at {}", configFile);
            } catch (IOException e) {
                logger.error("Failed to create default config file", e);
            }
        }

        return new WSMCConfig(disableVanillaTCP, endpoint, debug, dumpBytes,
                maxFramePayloadLength, wsPort, backendServer, velocityPort, forwardingSecret,
                proxyProtocolEnabled, proxyProtocolRealIpHeader);
    }

    // ── Property helpers ───────────────────────────────────────────────

    private static boolean parseBoolean(Properties props, String key, boolean def) {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        return Boolean.parseBoolean(val.trim());
    }

    private static int parseInt(Properties props, String key, int def) {
        String val = props.getProperty(key);
        if (val == null)
            return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String parseString(Properties props, String key, String def) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty())
            return def;
        return val.trim();
    }
}
