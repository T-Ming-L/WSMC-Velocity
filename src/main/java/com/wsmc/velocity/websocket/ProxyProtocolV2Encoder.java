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

import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Encodes Proxy Protocol V2 headers for passing real client IP information
 * through a proxy chain.
 *
 * <p>
 * Reference:
 * <a href="https://www.haproxy.org/download/2.9/doc/proxy-protocol.txt">
 * HAProxy Proxy Protocol specification</a>
 *
 * <p>
 * Format (TCP over IPv4, 28 bytes total):
 * 
 * <pre>
 *   ┌──────────┬──────┬─────────────┬──────────┬──────────────────────┐
 *   │ signature │ ver  │ addr+proto  │ len(2B)  │ src/dst addr+port    │
 *   │  12 bytes │ 0x21 │   0x11 0x01 │ 0x00 0x0C│ 4+4+2+2 = 12 bytes  │
 *   └──────────┴──────┴─────────────┴──────────┴──────────────────────┘
 * </pre>
 */
public final class ProxyProtocolV2Encoder {

    // ── Constants ──────────────────────────────────────────────────────

    /** 12-byte signature that identifies Proxy Protocol V2. */
    private static final byte[] SIG = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    /** Version + command: 0x21 = V2, PROXY command. */
    private static final byte VER_CMD = 0x21;

    /** Address family + protocol: 0x11 = AF_INET + STREAM. */
    private static final byte FAM_PROTO_IPV4 = 0x11;

    /** Address family + protocol: 0x21 = AF_INET6 + STREAM. */
    private static final byte FAM_PROTO_IPV6 = 0x21;

    /** Length of IPv4 address block: 4(src)+4(dst)+2(srcPort)+2(dstPort) = 12. */
    private static final short ADDR_LEN_IPV4 = 12;

    /** Length of IPv6 address block: 16+16+2+2 = 36. */
    private static final short ADDR_LEN_IPV6 = 36;

    private ProxyProtocolV2Encoder() {
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Encode a Proxy Protocol V2 header for the given client address and
     * local (our side) address, writing the result into {@code out}.
     *
     * @param out        target buffer
     * @param clientAddr the real client address
     * @param localAddr  our local address (destination from client's perspective)
     */
    public static void encode(ByteBuf out,
            InetSocketAddress clientAddr,
            InetSocketAddress localAddr) {
        InetAddress srcAddr = clientAddr.getAddress();
        InetAddress dstAddr = localAddr != null ? localAddr.getAddress() : srcAddr;

        if (srcAddr instanceof Inet4Address && dstAddr instanceof Inet4Address) {
            encodeIPv4(out, (Inet4Address) srcAddr, (Inet4Address) dstAddr,
                    clientAddr.getPort(), localAddr != null ? localAddr.getPort() : 0);
        } else {
            encodeIPv6(out, srcAddr, dstAddr,
                    clientAddr.getPort(), localAddr != null ? localAddr.getPort() : 0);
        }
    }

    /**
     * Total header length for the given address type.
     */
    public static int headerLength(InetSocketAddress addr) {
        return addr.getAddress() instanceof Inet4Address ? 28 : 52;
    }

    // ── IPv4 ───────────────────────────────────────────────────────────

    static void encodeIPv4(ByteBuf out, Inet4Address src, Inet4Address dst,
            int srcPort, int dstPort) {
        // Signature (12 B)
        out.writeBytes(SIG);
        // Version + command
        out.writeByte(VER_CMD);
        // Family + protocol
        out.writeByte(FAM_PROTO_IPV4);
        // Address length (2 B, big-endian)
        out.writeShort(ADDR_LEN_IPV4);
        // Source address (4 B)
        out.writeBytes(src.getAddress());
        // Destination address (4 B)
        out.writeBytes(dst.getAddress());
        // Source port (2 B)
        out.writeShort(srcPort);
        // Destination port (2 B)
        out.writeShort(dstPort);
    }

    // ── IPv6 ───────────────────────────────────────────────────────────

    static void encodeIPv6(ByteBuf out, InetAddress src, InetAddress dst,
            int srcPort, int dstPort) {
        out.writeBytes(SIG);
        out.writeByte(VER_CMD);
        out.writeByte(FAM_PROTO_IPV6);
        out.writeShort(ADDR_LEN_IPV6);
        out.writeBytes(src.getAddress()); // 16 B
        out.writeBytes(dst.getAddress()); // 16 B
        out.writeShort(srcPort); // 2 B
        out.writeShort(dstPort); // 2 B
    }
}
