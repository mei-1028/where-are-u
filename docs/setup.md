# 部署与环境配置指南

本文覆盖从零到可用的环境准备：高德 Key、三端环境变量、局域网开发、生产隧道、HTTPS、备份与排障。接口与字段以 [API 契约](api-contract.md) 为准，系统结构见 [架构设计](architecture.md)。

> 命令以 **Windows PowerShell** 为主，Linux 差异单独标注。文中域名、IP、Token 均为占位符（`where-are-u.example.com`、`203.0.113.10`），请替换为自己的值；占位符没有任何真实性。

## 1. 前置条件

| 项 | 要求 |
|---|---|
| 家中设备 | 常开的小主机/NAS，2 vCPU / 2 GB 内存 / 10 GB 可用空间；**推荐 Linux**（Debian 12 / Ubuntu 22.04+）。Windows 11 + Docker Desktop 可用于开发与试运行，长期生产不推荐（文件 I/O 与文件锁较弱） |
| 容器 | Docker Engine 24+ 与 Compose v2（Windows 用 Docker Desktop，WSL2 后端） |
| 公网入口 | 方案 A：一台有公网 IPv4 的 VPS（1 vCPU / 1 GB 足够），80/443/7000 可用；方案 B：域名托管在 Cloudflare，使用 Tunnel |
| 域名 | 可添加 A 记录；生产证书只能由公网 CA 签发 |
| 本地开发 | Node.js 24 LTS（server 要求 `>=24`，web 要求 `>=20`）+ npm 10；JDK 17；Android Studio（含 Android SDK）；一台真机 |
| 高德 | 高德开放平台账号（按控制台要求完成开发者认证），可创建 Android 与 Web 端 Key |
| 网络 | 家中宽带上行能维持 WebSocket 与少量位置请求；手机与开发机同一局域网时可直连调试 |

部署端口约定：Caddy `80/443`，服务端 `3000`（不对外），VPS frps `7000`（控制）、`80/443`（转发）。

## 2. 配置高德 Key

> 控制台文案可能随版本调整，以高德开放平台当前界面为准。所有 Key 都不要提交到仓库。

### 2.1 Android Key（绑定包名 + SHA1）

1. 登录高德开放平台控制台，进入「应用管理 → 我的应用」，创建一个应用（例如 `where-are-u`）。
2. 添加 Key，服务平台选择「Android 平台」。
3. 填写 **PackageName** 与 **SHA1**。控制台通常同时提供「发布版 SHA1」和「调试版 SHA1」两个输入框，推荐一个 Key 同时登记两者（与当前 `AMAP_KEY` 单变量配置一致）。若确实拆成两个 Key，需要 Android 构建脚本按 buildType 注入不同值。
4. PackageName 必须与 `android/app/build.gradle.kts` 中的 `applicationId` **完全一致**。本仓库约定为 `com.whereareu.app`；若 Android 模块实际值不同，以实际值为准。
5. 获取调试 SHA1（首次构建后系统会自动生成 `debug.keystore`）：

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -alias androiddebugkey -storepass android -keypass android
```

或直接让 Gradle 输出所有变体的签名信息：

```powershell
cd android
.\gradlew.bat signingReport
```

6. 获取发布 SHA1（签名证书由 Android 端生成并离线保管，绝不入库）：

```powershell
keytool -list -v -keystore where-are-u-release.jks -alias whereareu
```

7. 将 Key 写入 `android/local.properties`（该文件已被 `.gitignore` 忽略；键名与 `android/app/build.gradle.kts` 一致）：

```properties
# Windows 路径建议使用正斜杠，避免 properties 转义问题
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
AMAP_KEY=REPLACE_WITH_YOUR_ANDROID_KEY
# 服务端根地址，不带 /api
API_BASE_URL=http://192.168.1.10:3000
```

> 构建时取值优先级为 `local.properties` → Gradle 属性（`-PKEY=...`）→ 构建脚本默认值。生产构建需要 HTTPS 地址；若 `local.properties` 已写死调试地址，它会覆盖 `-PAPI_BASE_URL`，请直接修改该文件或使用 App 内的服务器地址设置。

**常见坑**：

- 每台开发机的 `debug.keystore` SHA1 不同：多人开发时各自登记调试 SHA1，或统一使用一份共用的调试签名。
- 不要给 debug 设置 `applicationIdSuffix`（如 `.debug`），否则包名变化会导致 Key 不匹配；确需后缀时为该包名单独申请 Key。
- 应用商店可能对 APK 重签名（含 Google Play App Signing、部分国内商店）：必须把商店签名证书的 SHA1 也登记到 Key，否则安装后定位报错。
- 高德 SDK 要求初始化前完成隐私合规声明（常见接口为 `AMapLocationClient.updatePrivacyShow / updatePrivacyAgree`，以所用 SDK 版本为准），并在隐私政策中披露。未调用时定位会失败。

### 2.2 Web JS API Key + 安全密钥 + 域名白名单

1. 在同一应用中添加 Key，服务平台选择「Web 端（JS API）」。
2. 该 Key 会配对一个**安全密钥**（`securityJsCode`）。JS API 2.0 起，加载地图前必须设置：

```js
window._AMapSecurityConfig = { securityJsCode: 'REPLACE_WITH_SECURITY_CODE' }
```

3. 在控制台为该 Key 配置**域名白名单**：生产填写不带协议与路径的域名，例如 `where-are-u.example.com`。
4. 写入 `web/.env.local`：

```properties
VITE_USE_MOCK=false
# 桌面浏览器用 localhost；手机浏览器联调改成 http://<开发机 IP>:3000/api
VITE_API_BASE_URL=http://localhost:3000/api
VITE_AMAP_KEY=REPLACE_WITH_YOUR_WEB_KEY
VITE_AMAP_SECURITY_CODE=REPLACE_WITH_SECURITY_CODE
```

**本地开发说明**：

- Web Key 与安全密钥最终都会出现在浏览器代码里，属于公开信息，安全边界靠域名白名单，不要试图把它们藏起来。
- 若本地开发被白名单拦截，优先在控制台临时加入 `localhost` / `127.0.0.1`；控制台不支持时，使用一个单独的调试 Key（不设或放宽白名单），不要关闭生产 Key 的白名单。
- 通过局域网 IP（如 `http://192.168.1.10:5173`）访问时，浏览器把非 localhost 的 HTTP 视为不安全上下文，**浏览器定位（定位到自己）可能被禁用**；地图展示与家人位置不受影响。需要调试定位时用 `http://localhost:5173` 或直接上 HTTPS。

### 2.3 可选：服务端高德 Key

M1 的逆地理编码由客户端调用高德（见 [架构设计](architecture.md)），服务端不需要高德 Key。若后续改为服务端反查地址，需要申请「Web 服务」Key，且**只放在服务端环境变量中**，绝不能进 Web 或 App。

## 3. 环境变量

### 3.1 服务端

变量名与 `server/src/lib/config.ts` / `server/.env.example` 对齐；若服务端实现调整，需同步修改 `deploy/docker-compose.yml` 与本表。

| 变量 | 必填 | 示例 | 说明 |
|---|---|---|---|
| `NODE_ENV` | 生产 | `production` | 运行模式（由 Node 生态使用） |
| `PORT` | 否 | `3000` | 容器内端口；不要映射到公网 |
| `DATABASE_URL` | 是 | `file:/data/where-are-u.db` | Prisma SQLite 连接串；生产用绝对路径 |
| `JWT_SECRET` | 是 | 随机 32 字节以上 | JWT HS256 签名密钥（server 要求至少 16 字符）；生成方式见 3.4 |
| `CORS_ORIGIN` | 否 | `https://where-are-u.example.com` | 逗号分隔的浏览器来源白名单，默认 `*`；同源部署时浏览器不触发 CORS |
| `LOCATE_TIMEOUT_MS` | 否 | `30000` | 立即定位超时；契约固定为 30 秒 |
| `LOG_LEVEL` | 否 | `info` | `fatal/error/warn/info/debug/trace/silent` |

补充：

- 本地开发时 `DATABASE_URL` 可用相对路径（相对 `schema.prisma` 所在目录），默认 `file:./dev.db`。
- server 固定监听 `0.0.0.0`；但当前未启用 Fastify trustProxy，经 Caddy 后限流会按代理 IP 统计。见 [架构设计](architecture.md) §10。
- 历史保留天数目前没有对应配置项，见 [架构设计](architecture.md) §10。

### 3.2 Web

| 变量 | 必填 | 示例 | 说明 |
|---|---|---|---|
| `VITE_USE_MOCK` | 接真实服务端时 | `false` | **默认 `true`**。mock 模式不访问服务端；生产构建必须显式设为 `false` |
| `VITE_API_BASE_URL` | 否 | `http://localhost:3000/api` / `/api` | 必须包含 `/api`；生产同源用 `/api`，本地直连写完整地址 |
| `VITE_AMAP_KEY` | 否 | `REPLACE_...` | 高德 JS API Key；留空时使用项目内置的离线示意地图 |
| `VITE_AMAP_SECURITY_CODE` | 否 | `REPLACE_...` | 高德 JS API 安全密钥，与 Key 配对 |

- 当前 `vite.config.ts` 未配置 `/api` 代理，本地开发需直连服务端完整地址，并在服务端 `CORS_ORIGIN` 中放行 Web 来源。
- Vite 变量在**构建时**注入；生产包必须在构建前设置，运行后改 `.env` 无效。
- Vite 只会注入 `VITE_` 前缀变量；绝不要把 `JWT_SECRET`、数据库路径或设备 Token 放进 Web 环境变量。

### 3.3 Android

| 位置 | 键 | 说明 |
|---|---|---|
| `android/local.properties` | `sdk.dir` | Android SDK 路径 |
| `android/local.properties` | `AMAP_KEY` | 高德 Android Key（已绑定包名 + SHA1） |
| `android/local.properties` | `API_BASE_URL` | 服务端根地址（不带 `/api`）；模拟器默认 `http://10.0.2.2:3000` |
| 应用内设置 | 服务器地址 | 若 Android 端实现运行时覆盖，可在此修改服务器地址 |

- 取值顺序：`local.properties` → Gradle 属性（`-PAMAP_KEY=... -PAPI_BASE_URL=...`）→ `build.gradle.kts` 默认值；本地文件优先级最高。
- 当前 `app/build.gradle.kts` 尚未配置 release signingConfig；正式发布前需由 Android 端补齐签名，并用该证书的 SHA1 申请/登记高德 Key。
- 设备 Token 只存系统安全存储，不写日志、不进云备份。
- release 构建必须使用 HTTPS。当前 `AndroidManifest.xml` 为方便本地调试设置了 `usesCleartextTraffic="true"`，release 变体必须覆盖为 `false`（或用仅 debug 生效的 network security config）。
- 包名为 `com.whereareu.app`，minSdk 26；高德 Key 的 PackageName 必须与此一致。

### 3.4 生成密钥

```powershell
# JWT_SECRET（输出 64 个十六进制字符，足够 HS256 使用）
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
([BitConverter]::ToString($b) -replace '-', '').ToLower()

# frp token
$b = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
([BitConverter]::ToString($b) -replace '-', '').ToLower()
```

> 上面的写法兼容 Windows PowerShell 5.1（`[Convert]::ToHexString` 需要 .NET 5+，在 5.1 中不可用）。

Git Bash / Linux：

```bash
openssl rand -hex 32   # JWT_SECRET
openssl rand -hex 24   # frp token
```

Android 发布签名证书示例（密码交互输入，不写入脚本与仓库）：

```powershell
keytool -genkeypair -v -keystore where-are-u-release.jks -alias whereareu `
  -keyalg RSA -keysize 2048 -validity 10000
```

## 4. 局域网本地开发（HTTP）

> 以下为三端约定命令。若对应目录或脚本尚未实现，先跳过该端；以各端 `package.json` / README 为准。

### 4.1 服务端

```powershell
cd server
npm install
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 编辑 .env：DATABASE_URL、JWT_SECRET、CORS_ORIGIN
npm run db:push     # 创建/同步 SQLite 结构
npm run dev
```

- 需要 Node.js 24+（`server/package.json` 的 engines 要求）。
- server 已固定监听 `0.0.0.0`，局域网手机可直接访问；公网仍只经 Caddy 暴露。
- `CORS_ORIGIN` 为逗号分隔来源列表；若 Web 直连 API，加入 `http://localhost:5173` 与/或 `http://<开发机 IP>:5173`。
- 验证：`curl.exe -i http://localhost:3000/api/me` 应返回 `401`（无凭据），说明 API 已启动。
- 若同时跑 Docker 版本，不要与本地开发实例共用同一个 SQLite 文件。
- `server/prisma/seed.ts` 存在时可执行 `npm run db:seed` 生成示例数据。

### 4.2 Web

```powershell
cd web
npm install
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
npm run dev
```

- Web 默认 `VITE_USE_MOCK=true`，不访问服务端；联调时改为 `false`。
- 默认开发地址 `http://localhost:5173`；手机浏览器访问用 `http://<开发机 IP>:5173`。
- 当前没有 Vite 代理，`VITE_API_BASE_URL` 写完整地址，例如 `http://192.168.1.10:3000/api`；同时在服务端 `CORS_ORIGIN` 放行 `http://192.168.1.10:5173`。
- 首次打开地图前确认已设置 `window._AMapSecurityConfig`，否则 JS API 2.0 会鉴权失败；未填高德 Key 时使用内置示意地图。

### 4.3 Android

1. 用 Android Studio 打开 `android/` 目录，等待 Gradle Sync。
2. 按 §2.1 / §3.3 准备 `local.properties`：`AMAP_KEY`、`API_BASE_URL`。
3. 真机与开发机连同一 Wi-Fi；`API_BASE_URL` 填 `http://<开发机 IP>:3000`（不带 `/api`）。
4. 模拟器访问宿主机用 `http://10.0.2.2:3000`（不能写 `localhost`）。
5. 首次运行按引导授予定位、后台定位、通知权限；Android 13+ 需单独通知权限，Android 14+ 前台服务需声明定位类型。
6. 模拟器通常没有真实卫星定位，需要设置模拟位置或使用真机验证轨迹与立即定位。

### 4.4 防火墙与网络

```powershell
# 管理员 PowerShell，仅开发机；公网机器不要照抄开放端口
New-NetFirewallRule -DisplayName "where-are-u dev" -Direction Inbound `
  -Protocol TCP -LocalPort 3000,5173 -Action Allow
```

- 用 `ipconfig` 查看开发机局域网 IPv4，手机与电脑需在同一网段。
- 关闭访客 Wi-Fi / AP 隔离；企业网络可能禁止设备互访。
- 注册、建家庭、上报等业务操作都在 App / Web 内完成。

### 4.5 本地 HTTP 的限制

- Android release 构建要求 HTTPS；debug 可临时放行 cleartext，release 必须关闭（当前骨架需覆盖 manifest 配置）。
- 浏览器仅在安全上下文提供精确定位；局域网 IP 的 HTTP 页面可能无法「定位到自己」。
- 本地发现的问题（CORS、WebSocket、cleartext）在 HTTPS 生产环境会表现不同，上线前必须用真机走一遍公网域名。

## 5. 生产部署：家中设备 + 隧道

### 5.1 方案选择

| 方案 | 公网要求 | TLS 终点 | 延迟 | 适用 |
|---|---|---|---|---|
| A. frp + VPS（首选） | VPS 公网 80/443/7000 | 家中 Caddy（端到端） | 低（选近地区 VPS） | 有 VPS、追求稳定与低延迟 |
| B. Cloudflare Tunnel | 无需开放端口 | Cloudflare 边缘 | 不确定，中国大陆常绕行境外 | 不想维护 VPS、可接受延迟波动 |

> 两套方案不要同时对同一域名生效。切换时先停用旧入口，再改 DNS 与 `SITE_DOMAIN`。

### 5.2 准备域名与 VPS（方案 A）

1. 域名添加 A 记录：`where-are-u.example.com → <VPS 公网 IP>`。
2. 若 DNS 托管在 Cloudflare，**必须设为「仅 DNS」（灰云）**，否则代理会拦截 ACME 校验。
3. 等待解析生效：`Resolve-DnsName where-are-u.example.com`。

### 5.3 VPS 上的 frps

从 frp 官方 Releases 下载对应架构的包（v0.52+ 使用 TOML 配置），把 `frps` 安装到 `/usr/local/bin/`，配置文件放到 `/etc/frp/frps.toml`：

```toml
# /etc/frp/frps.toml
bindPort = 7000
auth.method = "token"
auth.token = "REPLACE_WITH_FRP_TOKEN"
```

systemd 单元（`/etc/systemd/system/frps.service`）：

```ini
[Unit]
Description=frps for where-are-u
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now frps
sudo ufw allow 7000/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

- 云厂商安全组同样要放行 80/443/7000。
- VPS 上不能有其他程序占用 80/443（否则 frps 无法绑定）。
- 有固定家庭出口 IP 时，用防火墙把 7000 限制到该 IP 更安全。

### 5.4 家中设备上的 frpc

```powershell
cd deploy
Copy-Item frpc.toml.example frpc.toml
# 编辑 frpc.toml：serverAddr 填 VPS 公网 IP，auth.token 与 frps 完全一致
```

启动方式（任选其一）：

- **Linux**：为 frpc 写 systemd 单元（与 frps 类似），`ExecStart=/usr/local/bin/frpc -c /opt/where-are-u/deploy/frpc.toml`。
- **Windows**：任务计划程序创建「计算机启动时」任务运行 `frpc.exe -c D:\path\to\deploy\frpc.toml`；或使用 NSSM 注册为服务。
- 启动顺序建议：先完成 §5.5 的 `docker compose up -d`，让 Caddy 监听 80/443（此时证书可能尚未签发），再启动 frpc；通道就绪后 Caddy 会自动重试签发，必要时执行 `docker compose restart caddy`。

frpc 需要主动出网访问 VPS 的 7000，不需要在路由器上做端口映射。

### 5.5 启动应用栈

```powershell
cd deploy
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 编辑 .env：SITE_DOMAIN、JWT_SECRET、CORS_ORIGIN、WEB_DIST_DIR、DATA_DIR
```

构建 Web 并放入 `deploy/web-dist`（默认值）。**必须关闭 mock 模式并使用构建期变量**：

```powershell
cd web
npm install
$env:VITE_USE_MOCK = "false"
$env:VITE_API_BASE_URL = "/api"          # 与 API 同源
$env:VITE_AMAP_KEY = "REPLACE_WITH_YOUR_WEB_KEY"
$env:VITE_AMAP_SECURITY_CODE = "REPLACE_WITH_SECURITY_CODE"
npm run build
New-Item -ItemType Directory -Force ..\deploy\web-dist | Out-Null
Copy-Item -Recurse -Force dist\* ..\deploy\web-dist\
```

也可以把上述变量写入 `web/.env.production.local`（已被 `.gitignore` 忽略）后直接构建。若 `WEB_DIST_DIR` 指向 `../web/dist`，可跳过复制。Vite 变量在构建时固化，部署后再改环境变量不会生效。

```powershell
cd deploy
docker compose up -d --build
docker compose ps
docker compose logs -f caddy
```

说明：

- `docker-compose.yml` 使用 `build: ../server`，需要 **server agent 提供 `server/Dockerfile`**。若该文件尚不存在，构建会失败；此时可先构建镜像并改用 compose 中注释的 `image:` 占位。
- 首次启动时 Caddy 会通过公网 80 端口申请证书；frp 必须已把 VPS 的 80/443 转回家中。若先启动应用栈、后启动 frpc，Caddy 可能已在重试，通道就绪后通常会自动完成；必要时重启 caddy 容器。
- 数据库初始化/结构同步由 server 镜像的启动流程负责（当前 `server/package.json` 提供 `db:push`，以 server 实现为准）。首次启动前确保 `DATA_DIR` 可写。
- 服务端固定监听 `0.0.0.0`；若未启用 trustProxy，限流会按 Caddy 的 IP 统计（见 [架构设计](architecture.md) §10）。
- Windows 宿主的绑定挂载目录如果出现权限/文件锁问题，改用 Linux 宿主或把 `DATA_DIR` 指向性能更好的磁盘。

### 5.6 验证

1. `curl.exe -I https://where-are-u.example.com`：返回 200/308，证书有效。
2. `curl.exe -i https://where-are-u.example.com/api/me`：返回 `401` 与 JSON 错误体，证明 API 已路由。
3. 浏览器打开域名，注册账号、创建家庭、邀请成员。
4. Android 安装 debug/release 包，构建时 `API_BASE_URL` 或 App 内设置填 `https://where-are-u.example.com`，登录后确认设备显示在线。
5. 另一台设备点「立即定位」，确认 30 秒内返回新位置（设备 WebSocket 在线）。
6. 断开一台手机网络，确认家人端显示离线与最后在线时间；恢复网络后队列补传。

### 5.7 备选：Cloudflare Tunnel

前提：域名已接入 Cloudflare；家中设备安装 `cloudflared`。

```powershell
cloudflared tunnel login
cloudflared tunnel create where-are-u
cloudflared tunnel route dns where-are-u where-are-u.example.com
```

`%USERPROFILE%\.cloudflared\config.yml` 示例（`<TUNNEL_ID>` 由 create 命令输出）：

```yaml
tunnel: <TUNNEL_ID>
credentials-file: C:/Users/<you>/.cloudflared/<TUNNEL_ID>.json
ingress:
  - hostname: where-are-u.example.com
    service: http://localhost:80
  - service: http_status:404
```

1. 运行 `cloudflared tunnel run where-are-u` 验证；随后 `cloudflared service install`（或 Linux 的 systemd）设为开机自启。
2. 修改 `deploy/.env`：`SITE_DOMAIN=:80`；`CORS_ORIGIN` 仍填写公网 https 来源（如 `https://where-are-u.example.com`），然后 `docker compose up -d`（Caddy 不再申请证书，只做本地 HTTP 反代）。
3. 公网 HTTPS 由 Cloudflare 边缘提供；WebSocket 由 Tunnel 代理，设备 30 秒心跳足以保持连接。
4. **延迟提示**：中国大陆访问 Cloudflare 免费套餐常被调度到境外节点，RTT 可能 100–300 ms 且波动；地图 10 秒轮询影响不大，但「立即定位」体感会变慢。若延迟不可接受，改用 frp + 近地区 VPS。
5. 安全边界变化：Cloudflare 边缘能看到明文业务流量（TLS 在边缘终结），这是使用该方案的信任代价。

## 6. HTTPS 与证书

- Caddy 自动申请与续期公网 CA 证书（Let's Encrypt / ZeroSSL 等），需要公网可达的 80 端口；80 不可达时会尝试 443 的 TLS-ALPN 校验。
- 证书保存在 `caddy-data` 卷中，**不要删除该卷**；删除后重新签发，频繁操作可能触发 ACME 速率限制。
- frp 模式下 DNS 必须直达 VPS，不能让 Cloudflare 代理，否则 HTTP-01 失败。
- Cloudflare Tunnel 模式下 Caddy 使用 `:80` 站点，**不要**再开启自动 HTTPS，也不要把 frp 与 Tunnel 同时指向该域名。
- Android 生产必须使用公网 CA 证书；自签名证书在 Android 7+ 默认不被信任（即使用户手动安装 CA，App 也可能拒绝）。
- 可选 HSTS：确认域名长期专用后再在 Caddyfile 中加 `Strict-Transport-Security`，避免过早锁定。
- 证书异常时第一时间看 `docker compose logs caddy`，再检查 80/443、DNS 与 80 端口占用。

## 7. 数据备份与恢复

### 7.1 备份对象

| 对象 | 路径 | 说明 |
|---|---|---|
| 数据库 | `deploy/data/where-are-u.db`（随 `DATA_DIR` 变化） | 最重要；含账号、设备与位置历史 |
| 服务端配置 | `deploy/.env` | 含 `JWT_SECRET`；泄露可伪造会话，丢失会强制全员重登 |
| 隧道配置 | `deploy/frpc.toml`、VPS 的 `frps.toml` | 含 token |
| Android 签名证书 | 仓库外 | 丢失将无法发布同包名更新 |
| Caddy 数据卷 | `caddy-data` | 可选；证书可重新签发 |

### 7.2 在线热备（推荐）

需要 `sqlite3` CLI（Windows 可用 `winget install SQLite.SQLite`）：

```powershell
New-Item -ItemType Directory -Force deploy/backups | Out-Null
sqlite3 deploy/data/where-are-u.db ".backup 'deploy/backups/where-are-u-$(Get-Date -Format yyyyMMdd-HHmm).db'"
```

Linux：

```bash
sqlite3 deploy/data/where-are-u.db ".backup 'deploy/backups/where-are-u-$(date +%F-%H%M).db'"
```

`.backup` 在写入期间也能得到一致快照。若改用直接复制文件，必须**先停服务**，并把同目录的 `-wal` / `-shm` 文件一起复制；否则可能得到损坏或陈旧数据。

### 7.3 定时与保留

- Windows：任务计划程序每日 03:00 运行上面的备份命令。
- Linux：cron（注意 `%` 在 crontab 中要转义为 `\%`）：

```cron
0 3 * * * cd /opt/where-are-u/deploy && sqlite3 data/where-are-u.db ".backup 'backups/where-are-u-$(date +\%F).db'" && find backups -name 'where-are-u-*.db' -mtime +30 -delete
```

- 每天至少一份、保留 30 天（与位置保留期匹配），并定期复制到另一台设备/NAS。
- 备份含完整位置历史，属于敏感数据：使用 7-Zip AES-256、restic 等加密后再放到云盘或移动介质。
- 修改 `JWT_SECRET` 会让所有用户会话失效；设备 Token 独立存储，不受影响，但仍应把 `.env` 一起备份到受控位置。

### 7.4 恢复

1. `docker compose stop server`。
2. 把备份文件放回 `DATA_DIR/where-are-u.db`；确认属主/权限（Linux 下容器用户需可读写）。
3. 确认备份对应的数据结构版本与当前 server 匹配；跨版本恢复后让 server 执行结构同步（当前为 `db:push`），或先恢复到同版本服务端。
4. `docker compose start server`，验证登录、地图与上报。
5. 每季度做一次恢复演练，记录 RTO/RPO 实际表现。

## 8. 升级与回滚

1. 先做一次 §7.2 备份。
2. 拉取新代码，按 §5.5 用生产变量重新构建 Web（`VITE_USE_MOCK=false`、`VITE_API_BASE_URL=/api`、高德 Key）并更新 `web-dist`；Caddy 会直接提供新文件，无需重启。
3. `cd deploy && docker compose build server && docker compose up -d`。
4. 观察 `docker compose logs -f server`，确认数据库结构同步完成、WS 正常。
5. 回滚：切回上一个镜像/提交，必要时用备份恢复数据库。数据库结构变更只建议向前，回滚优先恢复备份而不是手改表。

## 9. 常见问题排查

| 症状 | 优先检查 | 处理 |
|---|---|---|
| 域名打不开 / 连接超时 | DNS、frpc 是否运行、VPS 防火墙 80/443 | `Resolve-DnsName`；VPS 上看 frps 日志；家中 `curl.exe -I http://localhost` |
| 502 Bad Gateway | server 容器是否退出、`DATA_DIR` 权限、数据库是否可写 | `docker compose ps`、`docker compose logs server`；修正目录权限 |
| 证书签发失败 | 80 端口是否经隧道可达、Cloudflare 是否开着橙云代理、ACME 日志 | 改灰云；检查 frpc 80 转发；`docker compose logs caddy` |
| 端口 80/443 被占用（Windows） | IIS / `http.sys` / 其他 Web 服务 | 用 `netstat -ano` 按端口查出占用 PID，停止占用进程或改用其他宿主 |
| Android 登录失败 | 服务器地址是否 HTTPS、证书是否有效、模拟器是否用 `10.0.2.2` | 浏览器先访问域名验证；检查 release 是否已关闭 cleartext |
| Web 页面数据像演示数据 | `VITE_USE_MOCK` 是否仍为默认 `true` | 用 `VITE_USE_MOCK=false` 重新构建；Vite 变量构建期固化 |
| Web 请求被 CORS 拦截 | 服务端 `CORS_ORIGIN` 是否包含当前页面来源 | 加入完整来源（含协议与端口），多个来源用逗号分隔，重启 server |
| 限流误伤所有用户（429） | server 是否启用 trustProxy，限流是否把 Caddy 的 IP 当成客户端 | 由 server 启用 Fastify trustProxy；或临时调高限流阈值 |
| Android release 能走 HTTP | Manifest 是否仍全局 `usesCleartextTraffic="true"` | release 变体覆盖为 `false`，仅 debug 允许 cleartext |
| 高德定位失败（错误码 7/12/4） | 7=Key 与包名/SHA1 不匹配；12=缺少定位权限；4=网络异常 | 核对 `applicationId` 与签名 SHA1；检查系统权限与网络；以高德文档为准 |
| Web 地图黑屏 / 鉴权失败 | 是否用了 Android Key、是否设置安全密钥、域名白名单 | 改用 JS API Key；在加载地图前设置 `window._AMapSecurityConfig`；核对 Referer |
| 浏览器「定位到自己」不可用 | 页面是否安全上下文 | 用 `https://` 或 `http://localhost`；局域网 IP 的 HTTP 会被浏览器限制 |
| 设备一直离线 | 设备 WebSocket 是否连通、Token 是否被吊销、网络是否封锁 WS | 检查 Caddy 访问日志、Token 来源；换网络测试；确认 `/ws` 路径 |
| 立即定位总是超时 | 设备是否在线、是否授予定位权限、室内无 GPS、系统定位是否关闭 | 看 `GET /api/commands/:id` 状态；让手机到窗边重试；检查 30 秒 deadline |
| SQLite 报锁 / 只读 | 是否有两个进程共用同一文件、目录属主、磁盘是否满 | 停掉多余实例；Linux 下修正属主；检查磁盘 |
| 所有人突然需要重新登录 | `JWT_SECRET` 是否变化、`.env` 是否未挂载/被覆盖 | 恢复原 secret 或接受重登；确保 `.env` 持久化并备份 |
| frpc 连不上 frps | serverAddr、token、7000 防火墙、系统时间 | 核对配置与安全组；看 frpc 日志中的具体错误 |
| 家中访问域名失败，外面正常 | 路由器 NAT 回环 | 家中改用局域网地址调试；或在路由器开启回环；生产手机仍用公网域名 |
| 数据库或备份异常增大 | 保留期清理是否已实现/运行、删除后是否 VACUUM | 当前保留期尚无配置入口（架构 §10），需手工清理过期数据；低峰期停服执行 `VACUUM` |
| 备份文件比数据库小很多 | WAL 未合并、复制方式错误 | 使用 `.backup`，或停服后连同 `-wal`/`-shm` 一起复制 |

## 10. 上线检查清单

- [ ] 域名解析指向正确入口（frp 为灰云 A 记录；Cloudflare 为 Tunnel 路由）。
- [ ] VPS 仅开放 22/80/443/7000，frps 使用强 token，7000 尽量限制来源。
- [ ] 家中设备仅 Caddy 暴露 80/443，server 3000 未映射到宿主机。
- [ ] `deploy/.env` 已填写且未提交；`JWT_SECRET` 为随机值并已离线备份；`CORS_ORIGIN` 已填公网来源。
- [ ] Caddy 成功签发证书，`https://<域名>` 无证书警告。
- [ ] Web 以 `VITE_USE_MOCK=false` 构建并部署，登录、地图、轨迹、家庭/设备管理走真实 API。
- [ ] Android debug 与 release 分别验证登录、上报、在线状态、立即定位；release 只接受 HTTPS 且已关闭 cleartext。
- [ ] 服务端容器内监听 `0.0.0.0`，且限流按真实客户端 IP 生效（trustProxy）。
- [ ] 高德 Android Key 已包含所有调试/发布/商店签名 SHA1；Web Key 已配置域名白名单。
- [ ] 每台家人手机完成权限引导与保活设置，锁屏放置 30 分钟仍持续上报。
- [ ] 每日备份任务运行成功，恢复流程演练通过。
- [ ] 家人已知晓共享范围、暂停方式与数据保留策略。
