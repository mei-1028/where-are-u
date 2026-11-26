# HTTP + IP 临时部署（无域名）

公网入口：`http://<VPS_IP>:18080`

```
浏览器 / Android
      │ HTTP :18080
      ▼
VPS <VPS_IP>  frps:7012 ──► NAS <NAS_IP>  frpc
                                           │
                                           ▼
                                    Caddy :18080
                                    ├─ /        web/dist
                                    ├─ /api/*   server:3000
                                    └─ /ws      server:3000
```

## 前提

- VPS 已运行 frps，`bindPort=7012`，安全组/防火墙放行 `18080/tcp`。
- NAS 已运行独立 frpc 实例 `whereareu-frpc.service`，代理 `whereareu-web` 将 VPS:18080 转到本机:18080。
- NAS 已安装 Docker 与 Compose。
- 仓库已放到 NAS，例如 `/opt/where-are-u`。

## 1. 构建 Web（在开发机）

Vite 的环境变量会打进产物，生产构建必须显式覆盖：

```powershell
cd D:\dev\workspace\where-are-u\web
npm ci
$env:VITE_USE_MOCK="false"
$env:VITE_API_BASE_URL="/api"
$env:VITE_AMAP_KEY="<你的 Web Key>"
$env:VITE_AMAP_SECURITY_CODE="<你的安全密钥>"
npm run build
```

把 `web/dist` 复制到 NAS：

```powershell
scp -r .\dist root@<NAS_IP>:/opt/where-are-u/web/
```

> 无域名时高德 Web Key 可能无法通过域名白名单校验，Web 会自动退回示意地图；这是预期行为。

## 2. 启动服务端 + Caddy（在 NAS）

```bash
cd /opt/where-are-u/deploy/http-ip
cp .env.example .env
# 编辑 .env，填入 JWT_SECRET（openssl rand -hex 32）
docker compose up -d --build
docker compose ps
```

## 3. 验证

```bash
curl http://127.0.0.1:18080/health          # 本机
curl http://<VPS_IP>:18080/health    # 公网
```

浏览器打开 `http://<VPS_IP>:18080`，注册账号 → 创建家庭 → 邀请家人。

## 4. 数据与备份

SQLite 存放在命名卷 `whereareu-http_whereareu-data`：

```bash
docker run --rm \
  -v whereareu-http_whereareu-data:/data:ro \
  -v "$PWD":/backup \
  alpine tar czf /backup/whereareu-$(date +%F).tgz -C /data .
```

## 5. 切回 HTTPS

有域名后改用 `../docker-compose.yml` + `../Caddyfile`，由 Caddy 自动申请证书；
同时把 Android 的 `API_BASE_URL` 和 Web 的 `VITE_API_BASE_URL` 换成 HTTPS 地址。

## 安全说明

- 当前为 HTTP 明文，仅适合临时联调；不要在公网长期使用。
- 生产前应：更换两台机器的弱密码、关闭密码 SSH、限制安全组端口、启用 HTTPS。
