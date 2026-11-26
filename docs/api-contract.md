# API Contract · M1

| 项目 | 内容 |
|---|---|
| 版本 | v0.2（软解绑、邀请码到期、时间校验、locate 去重） |
| 日期 | 2026-11-26 |
| 状态 | 三端共同遵守的接口契约（server / web / android） |
| 依据 | `docs/prototype-book.md` |

> 本文件是 M1 的唯一接口事实来源。实现与本文件冲突时，以本文件为准；确需变更时先改本文件并通知三端。

## 1. 约定

- Base URL：`/api`
- 编码：JSON UTF-8；时间统一 ISO 8601 UTC，例如 `2026-11-26T12:04:00.000Z`
- ID：不透明字符串（cuid）
- 用户鉴权：`Authorization: Bearer <userJwt>`
- 设备鉴权：`Authorization: Bearer <deviceToken>`（仅用于位置上报与设备 WebSocket）
- 错误格式：HTTP 状态码 + `{ "error": { "code": "...", "message": "..." } }`
- 一个用户同一时间最多属于一个家庭
- 所有家庭内查询由服务端根据成员关系推导 `familyId`，不接受客户端传入的 `familyId`

### 错误码

`UNAUTHORIZED` `FORBIDDEN` `NOT_FOUND` `VALIDATION` `CONFLICT` `ALREADY_IN_FAMILY` `INVITE_INVALID` `INVITE_EXPIRED` `DEVICE_OFFLINE` `RATE_LIMITED`

## 2. 鉴权

### POST /api/auth/register

```json
{ "username": "chen", "nickname": "阿哲", "password": "至少 8 位", "inviteCode": "824137" }
```

- `inviteCode` 可选；有效则注册后直接加入家庭。
- `201`：

```json
{ "token": "<jwt>", "user": { "id": "u_1", "username": "chen", "nickname": "阿哲" }, "familyId": "f_1" }
```

### POST /api/auth/login

```json
{ "username": "chen", "password": "..." }
```

- `200`：`{ token, user, familyId }`

### GET /api/me

- `200`：`{ user, family: { id, name, role } | null, devices: [Device] }`
- `devices` 仅包含 active 设备。

## 3. 家庭

### POST /api/families

```json
{ "name": "陈家" }
```

- `201`：`{ family: { id, name, inviteCode, inviteExpiresAt, role: "admin" } }`
- 创建者自动成为管理员；`inviteExpiresAt` 为邀请码到期时间（ISO 8601）。

### POST /api/families/join

```json
{ "code": "824137" }
```

- `200`：`{ family: { id, name, role: "member" } }`
- 已加入家庭 → `409 ALREADY_IN_FAMILY`；码无效/过期 → `400 INVITE_INVALID` / `INVITE_EXPIRED`。

### GET /api/families/me

```json
{
  "family": { "id": "f_1", "name": "陈家", "inviteCode": "824137", "inviteExpiresAt": "2026-11-27T12:04:00.000Z", "role": "admin" },
  "members": [
    {
      "userId": "u_1", "username": "chen", "nickname": "阿哲", "role": "admin",
      "sharingPaused": false, "online": true,
      "lastLocation": { "lat": 30.2741, "lng": 120.1551, "accuracy": 12, "battery": 82, "recordedAt": "..." }
    }
  ]
}
```

- `inviteCode` / `inviteExpiresAt` 仅管理员可见；成员响应中省略。

### POST /api/families/invite/reset（管理员）

- `200`：`{ inviteCode, expiresAt }`，旧码立即失效，新码 24 小时有效。

### DELETE /api/families/members/:userId（管理员）

- `204`。不能移除自己；移除后对方设备软解绑（deviceToken 失效、设备列表不再显示），历史位置保留。

### POST /api/families/leave

- `204`。管理员离开前必须先转让或解散（M1 暂不支持转让，管理员不可离开）。成员退出后其设备软解绑，历史位置保留。

### PATCH /api/families/me/sharing

```json
{ "paused": true }
```

- `200`：`{ sharingPaused: true }`
- 修改当前用户的 `FamilyMember.sharingPaused`，家庭其他成员看到「已暂停」。
- 客户端暂停时应停止位置上报；服务端不拒绝历史位置补传。

## 4. 设备

### POST /api/devices（用户 JWT）

```json
{ "name": "妈妈的手机", "platform": "android", "model": "Pixel 8", "osVersion": "Android 15", "appVersion": "0.1.0" }
```

- `201`：`{ device: { id, name, ... }, deviceToken }`
- `deviceToken` 只返回一次，客户端持久化保存；服务端只存 SHA-256。

### GET /api/devices

- `200`：`{ devices: [{ id, userId, name, platform, model, osVersion, appVersion, online, lastSeenAt, lastLocation }] }`
- 家庭内所有成员可见；仅返回 active 设备。

### PATCH /api/devices/:id

```json
{ "name": "新名字" }
```

- `200`：`{ device }`

### DELETE /api/devices/:id

- `204`。软解绑：deviceToken 立即失效，设备从列表消失；历史位置保留（家庭内仍可按原权限查询）。

## 5. 位置

### POST /api/locations（设备 Token）

```json
{
  "commandId": "cmd_1",
  "points": [
    {
      "lat": 30.2741, "lng": 120.1551, "accuracy": 12,
      "speed": 0, "bearing": 0, "battery": 82,
      "provider": "amap", "recordedAt": "2026-11-26T12:04:00.000Z"
    }
  ]
}
```

- `points` 1–100 条；`commandId` 可选，用于完成「立即定位」。
- `recordedAt` 不得晚于服务器时间 +5 分钟，否则整批 `400 VALIDATION`。
- `202`：`{ accepted: 1 }`
- 服务端写入 `deviceId/userId/familyId`，更新设备 `lastSeenAt`。
- 仅当批次中存在 `recordedAt >= command.createdAt` 的点时，才把对应 `LocateCommand` 标记为 completed；仅旧点不会完成指令。

### GET /api/devices/:id/locations?from=&to=&limit=500

- 同家庭成员可查；`from` 默认今天 00:00 UTC，`to` 默认当前，`limit` 最大 5000。
- `200`：`{ points: [{ id, lat, lng, accuracy, speed, bearing, battery, provider, recordedAt }] }`

### GET /api/families/me/latest

- 家庭地图轮询接口（M1 Web 每 10 秒）。
- `200`：`{ members: [{ userId, deviceId, online, lastSeenAt, lastLocation }] }`
- 仅包含 active 设备；已解绑设备不再出现在最新位置中。

## 6. 立即定位

### POST /api/devices/:id/locate

- 同家庭成员可发起。
- 30 秒内同一设备重复请求：返回已有 pending 指令（`200`），不重复下发。
- 并发请求由服务端串行化，只会创建一条 pending 指令。
- `202`：`{ commandId, deadlineAt }`
- 设备离线 → `409 DEVICE_OFFLINE`

### GET /api/commands/:id

- `200`：`{ status: "pending" | "completed" | "timeout" | "failed", completedAt? }`
- 服务端定时任务把超过 `deadlineAt` 的 pending 指令标记为 timeout。
- 只有不早于指令创建时间的新位置点才会把指令标记 completed。

## 7. 设备 WebSocket

- 连接：`GET /ws`，Header `Authorization: Bearer <deviceToken>`
- 服务端 → 设备：

```json
{ "type": "locate", "commandId": "cmd_1", "deadlineAt": "..." }
{ "type": "ping", "ts": "..." }
```

- 设备 → 服务端：

```json
{ "type": "pong", "ts": "..." }
{ "type": "hello", "appVersion": "0.1.0", "battery": 82 }
```

- 心跳：设备每 30 秒发 `pong`；服务端 90 秒无消息则关闭。
- 连接建立 → `online=true`；断开 → `online=false` 并更新 `lastSeenAt`。

## 8. 数据模型（Prisma）

| 模型 | 关键字段 |
|---|---|
| User | id, username(unique), nickname, passwordHash, createdAt |
| Family | id, name, inviteCode(unique), inviteExpiresAt, createdAt |
| FamilyMember | id, familyId, userId, role(`admin`/`member`), sharingPaused, joinedAt; unique(familyId,userId) |
| Device | id, userId, familyId, name, platform, model, osVersion, appVersion, tokenHash(unique), active, revokedAt?, online, lastSeenAt, createdAt |
| Location | id, deviceId, userId, familyId, lat, lng, accuracy, speed?, bearing?, battery?, provider?, recordedAt, createdAt; index(deviceId, recordedAt); 设备解绑/成员移除不删除 |
| 说明 | 解绑与移除采用软删除：`Device.active=false` + `revokedAt`，保留 Location；认证与列表只认 active |
| LocateCommand | id, deviceId, familyId, requestedBy, status(`pending`/`completed`/`timeout`/`failed`), createdAt, deadlineAt, completedAt?; index(deviceId, createdAt) |

## 9. 安全规则

- 密码使用 argon2；用户 JWT HS256，有效期 7 天。
- 设备 Token：32 字节随机数（base64url），数据库只存 SHA-256；仅 active 设备可用，解绑/移除后立即失效。
- 所有位置查询按家庭成员关系鉴权；越权统一返回 `404`，不泄露资源存在性。
- 管理员专属：重置邀请码、移除成员。
- 传输必须 HTTPS（生产）；本地开发允许 HTTP。
- 服务端不记录明文密码、设备 Token、完整位置日志。

## 10. M1 不做

地理围栏、推送通知、iOS、部署者管理后台、聊天、位置导出。
