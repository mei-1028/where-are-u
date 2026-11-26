# 部署说明（deploy/）

本目录是把「你在哪」部署到家中设备的最小模板：服务端容器 + Caddy 反向代理，配合 VPS 上的 frp（首选）或 Cloudflare Tunnel（备选）对外提供 HTTPS。

完整的域名、高德 Key、隧道、备份与排障步骤见 [部署指南](../docs/setup.md)；系统结构与信任边界见 [架构设计](../docs/architecture.md)。

## 1. 拓扑

```
公网 Android / 浏览器
        │ HTTPS + WSS
        ▼
┌──────────────────────────────┐
│ VPS：frps :7000 / :80 / :443 │
└──────────────┬───────────────┘
               │ frp 隧道（家中主动外连，TLS 透传）
┌──────────────┼───────────────┐
│ 家中设备      ▼                │
│  frpc ──► Caddy :80/:443      │
│             ├── /api/*、/ws → server:3000
│             └── 其余 → /srv/web（web/dist）
│  server ──► Prisma ──► SQLite（named volume）
└──────────────────────────────┘
```

- **TLS 在 Caddy 终结**：frp 只转发 TCP，VPS 看不到业务明文。
- **只暴露 80/443**：server 的 3000 仅在 Docker 内网可见。
- **单实例**：SQLite 与设备 WebSocket 在线状态不支持多副本，不要扩容 server。

## 2. 文件

| 文件 | 用途 |
|---|---|
| `docker-compose.yml` | server 构建/运行 + Caddy 反向代理 + 数据目录 |
| `Caddyfile` | 自动 HTTPS、静态资源、`/api` 与 `/ws` 反代 |
| `.env.example` | 部署变量模板，复制为 `.env` 后填写 |
| `frpc.toml.example` | 家中设备 frpc 配置模板，复制为 `frpc.toml` |
| `frps.toml.example` | VPS 上 frps 配置模板 |

真实 `.env`、`frpc.toml`、`frps.toml`、`backups/`、`web-dist/` 均已被根目录 `.gitignore` 忽略；数据库位于命名卷中，不进入仓库。

## 3. 前置条件

- Docker Engine 24+ / Compose v2（Windows 用 Docker Desktop，WSL2 后端）。
- 一个解析到 VPS 的域名（frp 模式必须使用 DNS-only，不能开 CDN 代理）。
- VPS：公网 IP，开放 80/443/7000；frps 已运行。
- 家中设备：80/443 未被占用，能主动访问 VPS:7000。
- `server/Dockerfile` 已由 server agent 提供（见 §6）。
- Web 已构建出 `dist/`（可以先用 API 联调，但没有首页）。

## 4. 部署步骤

1. **配置变量**

   ```powershell
   cd deploy
   Copy-Item .env.example .env
   ```

   至少填写：
   - `SITE_DOMAIN`：如 `where-are-u.example.com`；Cloudflare Tunnel 模式改为 `:80`。
   - `JWT_SECRET`：随机 32 字节以上，生成方式见 [部署指南](../docs/setup.md)。
   - `CORS_ORIGIN`：公网 Web 来源，如 `https://where-are-u.example.com`；不要留空。
   - 数据库：使用命名卷 `where-are-u-data`，无需配置目录；Linux 上不会出现 root 属主导致 `/data` 不可写的问题。
   - `WEB_DIST_DIR`：默认 `./web-dist`，也可设为 `../web/dist`。

2. **准备 Web 静态文件**

   Web 的 mock 模式默认开启，生产构建必须显式关闭，否则页面不会访问真实服务端：

   ```powershell
   cd ../web
   npm install
   $env:VITE_USE_MOCK = "false"
   $env:VITE_API_BASE_URL = "/api"          # 与 API 同源
   $env:VITE_AMAP_KEY = "REPLACE_WITH_YOUR_WEB_KEY"
   $env:VITE_AMAP_SECURITY_CODE = "REPLACE_WITH_SECURITY_CODE"
   npm run build
   New-Item -ItemType Directory -Force ../deploy/web-dist | Out-Null
   Copy-Item -Recurse -Force dist/* ../deploy/web-dist/
   ```

   也可以把这些值写入 `web/.env.production.local`（已被 `.gitignore` 忽略）后直接构建。若 `WEB_DIST_DIR` 已指向 `../web/dist`，可跳过复制。

3. **配置 frpc**（frp 模式）

   ```powershell
   cd ../deploy
   Copy-Item frpc.toml.example frpc.toml
   # 修改 serverAddr 与 auth.token，然后启动 frpc（systemd / 任务计划程序 / NSSM）
   ```

   确保 Caddy 已监听 80/443 后再启动 frpc。VPS 侧配置参考 `frps.toml.example` 与 [部署指南](../docs/setup.md)。

4. **启动**

   ```powershell
   docker compose up -d --build
   docker compose ps
   docker compose logs -f caddy
   ```

   首次启动 Caddy 会申请证书。若 80 端口未通过隧道可达，证书不会签发成功。

5. **验证**

   ```powershell
   curl.exe -I https://where-are-u.example.com
   curl.exe -i https://where-are-u.example.com/api/me
   ```

   主页应返回 200；`/api/me` 未带凭据应返回 `401`（错误格式见 [API 契约](../docs/api-contract.md)）。随后用浏览器注册、建家庭，并在 Android 上填同一 HTTPS 地址登录。

6. **备份**

   数据库在命名卷 `where-are-u_where-are-u-data` 中。热备示例：

   ```bash
   docker run --rm \
     -v where-are-u_where-are-u-data:/data:ro \
     -v "$PWD":/backup \
     alpine tar czf /backup/where-are-u-$(date +%F).tgz -C /data .
   ```

   定时与恢复步骤见 [部署指南](../docs/setup.md)。

## 5. 日常操作

| 操作 | 命令 |
|---|---|
| 查看状态 | `docker compose ps` |
| 查看日志 | `docker compose logs -f server` / `caddy` |
| 修改 Caddyfile 后重载 | `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile` |
| 更新服务端 | 备份 → `docker compose build server` → `docker compose up -d` |
| 停止 | `docker compose down`（不要加 `-v`，否则会删除数据库与 Caddy 证书卷） |
| 验证变量 | `docker compose config` |

## 6. 已知前提与注意事项

- **Dockerfile 依赖**：`docker-compose.yml` 使用 `build: ../server`。若 `server/Dockerfile` 尚未提供，构建会失败；可先由 server agent 构建镜像，并在 compose 中改用注释的 `image:` 占位。数据库初始化与结构同步（当前 `server/package.json` 提供 `db:push`）需要由 server 镜像的启动流程负责；server 已监听 `0.0.0.0`，代理信任（trustProxy）仍待在 server 侧补齐。镜像至少需要：Node 24、安装生产依赖并执行 `prisma generate`、启动前初始化 `/data` 下的数据库、以可写 `/data` 的用户运行、暴露 3000。
- **无 secrets 入库**：所有 token、密钥、域名与 IP 都是占位符；`.env` 与真实 frpc/frps 配置不得提交。
- **Cloudflare Tunnel 备选**：无需公网端口，但需把 `SITE_DOMAIN` 改为 `:80`、让 cloudflared 指向 `http://localhost:80`，并接受边缘终结 TLS 与潜在绕行延迟。步骤见 [部署指南](../docs/setup.md)。
- **Windows 宿主**：Docker Desktop 的绑定挂载在 SQLite 文件锁和 I/O 上弱于 Linux；长期生产建议 Linux 小主机。若端口 80 被 IIS 或 `http.sys` 占用，Caddy 会启动失败。
- **NAT 回环**：家中设备访问自己的公网域名可能因路由器不支持回环而失败；这是网络设备限制，家中调试可用局域网地址。
- **升级不可回滚数据库结构**：升级前先备份；跨版本回滚优先恢复数据库备份。
