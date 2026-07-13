# WSMC for Velocity

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

让 Velocity 代理支持 WebSocket 连接的插件。受 [rikka0w0/wsmc](https://github.com/rikka0w0/wsmc) 启发，适用于需要在 CDN 后面隐藏服务器的场景。

> 大部分 CDN 免费套餐不支持 TCP 代理，通过 WebSocket 方式可绕过此限制，有效防御 DDoS 攻击。

---

## 工作原理

```
MC 客户端 ──WebSocket──► WSMC 插件(25566) ──TCP──► localhost:25565(Velocity) ──转发──► 后端服务器
```

1. 客户端通过 `ws://` 或 `wss://` 连接到此插件
2. 插件完成 WebSocket 握手后，将数据转发到 **Velocity 自身的监听端口** (localhost)
3. Velocity 正常处理认证和 **modern forwarding**，保证后端兼容
4. 后端服务器看到的是标准的 Velocity 转发连接，不会报错

---

## 安装

### 要求

- Velocity **3.3.0+**
- Java **17+**

### 构建

```bash
git clone https://github.com/T-Ming-L/WSMC-Velocity.git
cd WSMC-Velocity
./gradlew shadowJar
```

### 部署

将 `build/libs/wsmc-velocity-1.0.0.jar` 放入 Velocity 的 `plugins/` 目录，重启即可。

首次启动会自动在 `plugins/wsmc/wsmc.properties` 生成默认配置文件。

---

## 配置

配置文件位置：`plugins/wsmc/wsmc.properties`

| 属性                              | 类型    | 默认值            | 说明                                                                 |
| --------------------------------- | ------- | ----------------- | -------------------------------------------------------------------- |
| `wsmc.wsPort`                     | int     | `25566`           | WebSocket 监听端口                                                   |
| `wsmc.wsmcEndpoint`               | string  | 不设置            | WebSocket 路径，如 `/mc`。必须以 `/` 开头，区分大小写                |
| `wsmc.debug`                      | boolean | `false`           | 开启调试日志                                                         |
| `wsmc.dumpBytes`                  | boolean | `false`           | 打印原始 WebSocket 帧（需 `debug=true`）                             |
| `wsmc.maxFramePayloadLength`      | int     | `65536`           | 最大帧载荷长度。如 Netty 报错 "Max frame length exceeded" 时增大此值 |
| `wsmc.disableVanillaTCP`          | boolean | `false`           | 禁用原生 TCP 登录和状态查询                                          |
| `wsmc.velocityPort`               | int     | `25565`           | Velocity 自身的监听端口（插件连接 localhost 时使用）                 |
| `wsmc.backendServer`              | string  | `default`         | 目标后端服务器名（已不再使用，保留兼容）                             |
| `wsmc.forwardingSecret`           | string  | 空                | 转发密钥（保留兼容）                                                 |
| `wsmc.proxyProtocol.enabled`      | boolean | `false`           | **🆕** 启用 Proxy Protocol V2，传递真实客户端 IP 给 Velocity         |
| `wsmc.proxyProtocol.realIpHeader` | string  | `X-Forwarded-For` | Proxy Protocol 使用的 HTTP 头（CDN 场景）                            |

---

## 客户端连接

安装此插件后，玩家可使用安装了 WSMC 的客户端连接：

```
# 不安全的 WebSocket
ws://your-proxy.com:25566/mc

# 安全的 WebSocket（wss）
wss://your-proxy.com:25566/mc

# 指定 SNI 和 HTTP Host（适用于 CDN 场景）
ws://host.com@1.2.3.4:25566/mc
wss://sni.com:host.com@1.2.3.4:25566/mc
```

---

## Proxy Protocol V2

在连接 Velocity 前先发送 [Proxy Protocol V2](https://www.haproxy.org/download/2.9/doc/proxy-protocol.txt) 头部，将真实客户端 IP 透传给 Velocity 及后端服务器。

### 配置步骤

**1. 在 `velocity.toml` 中启用：**

```toml
accept-proxy-protocol = true
```

**2. 在 `plugins/wsmc/wsmc.properties` 中启用：**

```properties
wsmc.proxyProtocol.enabled = true
wsmc.proxyProtocol.realIpHeader = X-Forwarded-For
```

### 数据流

```
Client → CDN [X-Forwarded-For: 1.2.3.4] → WSMC Plugin(25566)
  → [PPv2 Header: 1.2.3.4 → 127.0.0.1] → Velocity(25565)
  → [Forwarding: 1.2.3.4] → Backend Server
```

后端服务器即可获取真实 `1.2.3.4` 而非 `127.0.0.1`。

---

## 与 WSMC Mod / Spigot 版对比

|          | [WSMC Mod](https://github.com/rikka0w0/wsmc) | WSMC for Velocity     | [WSMC for Spigot](https://github.com/T-Ming-L/WSMC-Spigot) |
| -------- | -------------------------------------------- | --------------------- | ---------------------------------------------------------- |
| 运行位置 | 游戏服务器内                                 | Spigot/Paper 服务器内 | Velocity 代理                                              |
| 适用场景 | 单服 / 无代理                                | 单服 / 无代理         | 群组服 / Velocity 已有                                     |
| 后端兼容 | 直接处理                                     | 走本地服务器端口      | 走 Velocity forwarding                                     |
| 配置方式 | JVM 系统属性                                 | `wsmc.properties`     | `wsmc.properties`                                          |

---

## 常见问题

**Q: 客户端连接报 "This server requires you to connect with Velocity"？**
A: 更新到最新版本即可。这是一个已知 bug，已通过强制走 Velocity 自身端口修复。

**Q: 走 CDN/WSS 后如何获取真实 IP？**
A: CDN 通常会在 WebSocket 握手请求中添加 `X-Forwarded-For` 等 Header。你可以在后端服务器通过相关方法读取这些 Header。

---

## 开源协议

GNU General Public License v3.0 (GPL-3.0)

详见 [LICENSE](LICENSE) 文件。

---

## 致谢

- [rikka0w0/wsmc](https://github.com/rikka0w0/wsmc) - 原始 WSMC 模组
- [Velocity](https://velocitypowered.com/) - Minecraft Java 代理
- [Netty](https://netty.io/) - 异步网络框架
