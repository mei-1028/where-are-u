# 「你在哪」服务端 · M1

自建家庭位置共享的 M1 后端：账号、家庭、设备、位置、立即定位、设备 WebSocket。

- 接口契约（唯一事实来源）：[`../docs/api-contract.md`](../docs/api-contract.md)
- 技术栈：Node.js 24 · TypeScript（strict）· Fastify 5 · Prisma 6 + SQLite · zod · argon2 · JWT（HS256）
- 运行方式：ESM，开发用 `tsx`，生产用 `tsc` 构建后 `node dist/index.js`

## 快速开始

要求 Node.js >= 24、npm 11。

```bash
cd server
npm install
cp .env.example .env
# 生成至少 32 字节的随机密钥并填入 .env 的 JWT_SECRET（空值/示例值会拒绝启动）
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
npm run db:push           # 建库 + 生成 Prisma Client
npm run db:seed           # 可选：写入演示数据
npm run dev               # http://localhost:3000
```

健康检查：`curl http://localhost:3000/health` → `{"status":"ok"}`

### 演示数据（`npm run db:seed`）

| 项目 | 值 |
|---|---|
| 家庭 | 陈家 |
| 邀请码 | `824137`（24 小时有效，重新 seed 会刷新） |
| 演示账号 | `demo` / `demo12345` |
| 其他成员 | `mama`、`baba`、`xiaoyu`（同一密码） |
| 设备/位置 | 4 台设备，每台 3 条杭州附近的示例位置 |

Seed 只清理 `陈家` / `824137` 及上述演示账号，不触碰其他本地数据。

## 环境变量

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `PORT` | 否 | `3000` | HTTP 监听端口（绑定 `0.0.0.0`） |
| `DATABASE_URL` | 是 | — | SQLite，如 `file:./dev.db`（相对路径相对 `prisma/`） |
| `JWT_SECRET` | 是 | — | 用户 JWT HS256 密钥，至少 32 字节的随机值；空值、`replace-with...`、`change-me...`、低熵值等会在启动时被拒绝 |
| `CORS_ORIGIN` | 否 | `*` | 允许的来源，逗号分隔多个；生产请显式配置 |
| `LOCATE_TIMEOUT_MS` | 否 | `30000` | 立即定位指令的超时时间 |
| `TRUST_PROXY` | 否 | `false` | 信任代理头的方式：`false` / `true` / proxy-addr 列表（如 `loopback,uniquelocal`）。反向代理后需开启，限流才能按真实客户端 IP 计数 |
| `LOG_LEVEL` | 否 | `info` | pino 日志级别；Authorization 等敏感请求头已脱敏 |

不要提交 `.env`；`.env.example` 仅含占位值。

## npm scripts

| 命令 | 说明 |
|---|---|
| `npm run dev` | `tsx watch` 启动开发服务器 |
| `npm run build` | `tsc` 输出到 `dist/` |
| `npm start` | 运行构建产物 |
| `npm test` | vitest 全量测试（每个测试文件独立临时 SQLite） |
| `npm run test:watch` | vitest watch |
| `npm run db:push` | `prisma db push`（SQLite 建库/同步 + 生成 Client） |
| `npm run db:seed` | 写入演示数据 |
| `npm run db:generate` | 仅生成 Prisma Client |
| `npm run typecheck` | `tsc --noEmit` |

## API 摘要

Base URL：`/api`；用户接口 `Authorization: Bearer <userJwt>`，位置上报与设备 WS 使用 `Authorization: Bearer <deviceToken>`。
完整字段与状态码见 [`docs/api-contract.md`](../docs/api-contract.md)。错误统一为 `{"error":{"code":"...","message":"..."}}`。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/auth/register` | — | 注册；可选 `inviteCode` 直接入家 |
| POST | `/auth/login` | — | 登录，返回 7 天 JWT |
| GET | `/me` | 用户 | 当前用户、家庭、自己的 active 设备 |
| POST | `/families` | 用户 | 创建家庭（自动管理员） |
| POST | `/families/join` | 用户 | 6 位邀请码加入 |
| GET | `/families/me` | 用户 | 家庭 + 成员 + 最后位置（管理员额外返回 `inviteCode`、`inviteExpiresAt`） |
| PATCH | `/families/me/sharing` | 用户 | 暂停/恢复自己的共享（`{"paused":true}` → `{"sharingPaused":true}`） |
| POST | `/families/invite/reset` | 管理员 | 重置邀请码，24h 有效 |
| DELETE | `/families/members/:userId` | 管理员 | 移除成员并软解绑其设备（历史保留） |
| POST | `/families/leave` | 成员 | 退出家庭（管理员不可退出，设备软解绑） |
| POST | `/devices` | 用户 | 注册设备，`deviceToken` 只返回一次 |
| GET | `/devices` | 用户 | 家庭内全部 active 设备 + 最后位置 |
| PATCH | `/devices/:id` | 用户（本人/管理员） | 重命名设备 |
| DELETE | `/devices/:id` | 用户（本人/管理员） | 软解绑设备，Token 立即失效，历史轨迹保留 |
| POST | `/locations` | 设备 Token | 批量上报 1–100 点；未来时间不得超过 5 分钟；可携带 `commandId` 完成立即定位 |
| GET | `/devices/:id/locations?from=&to=&limit=` | 用户 | 家庭内历史轨迹（含已解绑设备） |
| GET | `/families/me/latest` | 用户 | 家庭地图轮询（仅 active 设备的最新位置） |
| POST | `/devices/:id/locate` | 用户 | 立即定位；30s 内重复请求返回已有指令 |
| GET | `/commands/:id` | 用户 | 指令状态：pending/completed/timeout/failed |
| WS | `/ws` | 设备 Token | 设备长连接、在线状态、指令下发 |
| GET | `/health` | — | 健康检查 |

### curl 示例

```bash
BASE=http://localhost:3000

# 登录
curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo12345"}'

TOKEN='<登录返回的 token>'

# 家庭概览
curl -s "$BASE/api/families/me" -H "Authorization: Bearer $TOKEN"

# 注册设备（deviceToken 只出现这一次，请保存）
curl -s -X POST "$BASE/api/devices" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"我的手机","platform":"android","model":"Pixel 8","osVersion":"Android 15","appVersion":"0.1.0"}'

DEVICE_TOKEN='<返回的 deviceToken>'

# 位置上报（设备 Token）
curl -s -X POST "$BASE/api/locations" \
  -H "Authorization: Bearer $DEVICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"points":[{"lat":30.2741,"lng":120.1551,"accuracy":12,"battery":82,"provider":"amap","recordedAt":"2026-11-26T12:04:00.000Z"}]}'

# 最新位置与历史
curl -s "$BASE/api/families/me/latest" -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/api/devices/<deviceId>/locations" -H "Authorization: Bearer $TOKEN"

# 立即定位（设备需在线，即 WS 已连接）
curl -s -X POST "$BASE/api/devices/<deviceId>/locate" -H "Authorization: Bearer $TOKEN"
```

设备 WebSocket：连接 `ws://<host>/ws`，握手必须带 `Authorization: Bearer <deviceToken>`。
服务端每 30 秒发送 `{"type":"ping","ts":"..."}`；设备需回 `{"type":"pong","ts":"..."}`，90 秒无任何消息则服务端关闭连接（code 4002）。收到 `{"type":"locate","commandId":"...","deadlineAt":"..."}` 后做一次高精度定位并调用 `POST /api/locations` 带上 `commandId`。

## 测试

```bash
npm test
```

- vitest，每个测试文件在 `prisma/` 下创建独立的临时 SQLite 数据库并在结束后删除。
- 覆盖：注册/登录/JWT 形状与限流、JWT_SECRET 占位符/长度校验、邀请码加入/过期/重置与 `inviteExpiresAt`、跨家庭 404、管理员权限、共享暂停、设备软解绑（历史保留/旧 Token 失效/列表隐藏/WS 拒绝）、位置批量校验/未来时间上限/历史/最新位置、假 WebSocket 指令下发、并发去重、旧点不完成、超时清扫、真实 WS 握手鉴权/在线状态/心跳关闭。

## Docker

```bash
docker build -t where-are-u-server .
docker run --rm -p 3000:3000 \
  -e JWT_SECRET="<至少 32 位随机值>" \
  -e CORS_ORIGIN="https://your-domain.example" \
  -e TRUST_PROXY=false \
  -v where-are-u-data:/data \
  where-are-u-server
```

- 镜像基于 `node:24-alpine`，多阶段构建，非 root（`node`）运行。
- 容器启动时自动执行 `prisma db push --skip-generate`，再启动 `dist/index.js`。
- 默认 `DATABASE_URL=file:/data/where-are-u.db`；请挂载 `/data`（镜像内已 chown 给 node）。bind mount 需确保宿主目录对 UID 1000 可写。
- 镜像默认 `TRUST_PROXY=loopback,uniquelocal`，适配 Caddy/nginx 等私有网络代理；若直接把 3000 暴露到公网，请设 `TRUST_PROXY=false`，避免伪造 `X-Forwarded-For` 绕过限流。
- SQLite 单文件数据库：同一时刻只应运行一个写实例；多副本前请先迁移到 PostgreSQL。

## 实现说明与契约偏差

以下为契约未明确、实现时做出的选择，均保持向后兼容：

1. **设备 DTO 的 `userId`**：已随契约更新明确写入 `GET /api/devices`，实现字段一致；Web 用它显示设备归属。
2. **设备软解绑**：`Device` 增加 `active`、`revokedAt`。`DELETE /api/devices/:id`、移除成员、退出家庭只把设备置为 inactive 并吊销 Token，设备行、Location 历史与指令记录全部保留。`GET /api/devices`、`GET /api/me`、家庭最新位置只包含 active 设备。
3. **解绑后历史仍可查**：当前家庭成员仍可按家庭权限读取 inactive 设备的 `GET /api/devices/:id/locations`；已移除/退出的用户因失去成员关系返回 404；跨家庭始终 404。
4. **inactive 设备拒绝访问**：设备 Token 认证与 WS 握手返回 401；重命名、解绑、立即定位返回 404。历史查询是唯一允许访问 inactive 设备的家庭内路径。
5. **设备重命名/解绑权限**：设备本人或家庭管理员；同家庭普通成员操作他人设备返回 `403 FORBIDDEN`，跨家庭统一 `404 NOT_FOUND`。
6. **无家庭时的行为**：`GET /api/families/me`、`GET /api/devices`、`GET /api/families/me/latest` 返回 `404 NOT_FOUND`；`POST /api/devices` 返回 `409 CONFLICT`（契约未规定）。
7. **500 错误码**：契约错误码未包含服务端内部错误，使用 `INTERNAL`，响应结构保持一致。
8. **限流参数**：契约只要求基础限流；实现为全局 600 次/分钟/IP，登录/注册 20 次/分钟/IP，加入家庭 30 次/分钟/IP，超限返回 `429 RATE_LIMITED`。
9. **共享暂停**：`PATCH /api/families/me/sharing` 只更新当前成员的 `sharingPaused`；按契约，服务端仍接受设备补传历史位置，不因暂停拒绝写入。
10. **家庭人数上限**：原型书待确认项，契约未要求，未强制限制。
11. **定位精度与历史**：`accuracy` 落库为整数米（四舍五入），其余浮点字段保持浮点。历史查询默认 `from=今天 00:00 UTC`、`to=now`，按 `recordedAt` 升序返回。
12. **立即定位去重**：进程内 `Mutex` 保护「查 pending + 创建指令」的临界区，避免并发重复下发；30 秒内复用 pending 指令（`200`），否则新建（`202`）。离线判断为设备 DB `online` 或当前内存 WS 连接任一为真；若下发时 socket 已消失则指令标记 `failed` 并返回 `409 DEVICE_OFFLINE`。该互斥依赖 M1 单实例部署。
13. **WS 参数**：生产固定 30s ping / 90s 静默关闭；测试可通过 build 选项缩短，不读取环境变量。
14. **管理员离开家庭**：按契约返回 `403 FORBIDDEN`；M1 无转让/解散能力。
15. **注册时邀请码失败**：若填写的邀请码无效/过期，整个注册事务失败并返回 400（不会创建“无家庭账号”）；契约未明确该原子性选择。
16. **邀请码与过期时间**：非管理员响应中省略 `inviteCode`；管理员在 `GET /api/families/me` 与创建家庭响应中额外返回 `inviteExpiresAt`（新增契约要求，`docs/api-contract.md` 示例尚未列出该字段）。
17. **JWT_SECRET 校验**：至少 32 字节；空值、已知示例/占位值与低熵值会导致启动失败。`.env.example` 故意留空，避免复制即用弱密钥。
18. **recordedAt 未来上限**：上报点的时间戳若超过服务器时间 +5 分钟，整批返回 `400 VALIDATION` 并记录告警（仅设备 ID 与时间，不含坐标）；历史补传不受该上限影响。
19. **立即定位完成条件**：只有批次中存在 `recordedAt >= command.createdAt` 的点，才会把 pending 指令置为 completed；全部为旧点则保持 pending，等待新点或超时清扫。

## 安全要点

- 密码仅存 argon2id 哈希；响应、日志均不含密码哈希。
- 设备 Token 为 32 字节随机数（base64url），数据库只存 SHA-256；创建接口只返回一次。
- 解绑/移除成员后设备立即 inactive，旧 Token 认证与 WS 握手均被拒绝；为保留轨迹不删除历史数据。
- 用户 JWT HS256、7 天过期，密钥必须为至少 32 字节随机值；设备 Token 与用户 JWT 使用独立鉴权入口，不能互相冒用。
- 所有家庭内查询由服务端根据成员关系推导 `familyId`；跨家庭访问统一 404，避免泄露资源存在性。
- 管理员专属：重置邀请码、移除成员（以及本实现中的管理他人设备）。
- 日志脱敏 `Authorization`/`Cookie`，不记录请求体、密码或明文设备 Token。
- 生产环境请由反向代理（Caddy/Nginx 等）终止 TLS，并只在内网暴露本服务；本服务自身不处理 HTTPS。
- 反向代理后通过 `TRUST_PROXY` 信任代理来源，使限流按真实客户端 IP 生效；不要在直接公网暴露时信任任意代理头。
