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

## 11. 关注与提醒（v1.0.4 新增）

### 11.1 概念

- 关注是**单向订阅**：A 关注 B，只影响 A 收到的提醒，不改变家庭内互相可见的位置权限。
- 关注无需对方同意；B 可以通过「关注者列表」看到谁在关注自己，但看不到对方的具体规则。
- 一个关注下可配置多条规则（最多 10 条）：`MOVEMENT`/`STALE`/`LOW_BATTERY` 每种最多一条，`GEOFENCE` 可按地点配置多条（同一地点只能一条）。
- 规则由**服务端**评估；`Alert` 是站内消息的事实来源，设备 WebSocket 只负责尽快送达。
- 被关注者暂停共享（`sharingPaused=true`）期间不评估任何规则，恢复共享时位置变动规则重新取基准。

### 11.2 规则类型

| type | config | 默认值 | 说明 |
|---|---|---|---|
| `MOVEMENT` | `{ thresholdMeters, cooldownMinutes }` | 500 / 30 | 与「上次通知时的位置」比较，移动超过阈值触发；冷却期内不重复，基准不移动 |
| `STALE` | `{ hours }` | 12 | 超过 N 小时没有新的位置点；规则创建后先等待一次新位置再开始监控 |
| `LOW_BATTERY` | `{ percent }` | 20 | 电量低于阈值触发；回升到 `percent + 5` 后重新武装 |
| `GEOFENCE` | `{ placeId, event, window? }` | BOTH / 全天 | 到达或离开某个家庭地点时触发；见 §12 |

范围校验：`thresholdMeters` 200–50000，`cooldownMinutes` 5–1440，`hours` 1–168，`percent` 1–50。

夜间静默：中国时区 23:00–07:00 触发的 `LOW_BATTERY` 告警 `silent=true`（仍入库、仍推送未读数，但客户端不弹系统通知）。`MOVEMENT` / `STALE` 不受影响。

### 11.3 端点（均为用户 JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/follows` | `{ follows: [Follow] }` |
| POST | `/api/follows` | `{ targetUserId, rules?: [{ type, config? }] }`；已关注 → `200` 返回原记录，新建 → `201` |
| DELETE | `/api/follows/:id` | `204`，级联删除规则，历史 Alert 保留 |
| GET | `/api/follows/followers` | `{ followers: [{ userId, nickname, since }] }` |
| POST | `/api/follows/:id/rules` | `201 { rule }`；非围栏同类型重复、同一地点的围栏重复 → `409 CONFLICT` |
| PATCH | `/api/follows/:id/rules/:ruleId` | `{ enabled?, config? }`，config 与现有值合并后整体校验 |
| DELETE | `/api/follows/:id/rules/:ruleId` | `204` |
| GET | `/api/alerts?limit=50&unread=1&after=&before=` | `{ alerts: [Alert], unreadCount }`，按 `createdAt` 倒序，limit 1–100 |
| POST | `/api/alerts/:id/read` | `204`，幂等 |
| POST | `/api/alerts/read-all` | `{ updated }` |

校验：不能关注自己（`400 VALIDATION`）；目标不是同家庭成员（`404 NOT_FOUND`，不泄露存在性）；规则不属于当前用户（`404`）。

`Follow`：

```json
{
  "id": "f_1",
  "target": { "userId": "u_2", "nickname": "妈妈" },
  "createdAt": "2026-11-28T12:00:00.000Z",
  "rules": [
    { "id": "r_1", "type": "MOVEMENT", "enabled": true,
      "config": { "thresholdMeters": 500, "cooldownMinutes": 30 },
      "lastFiredAt": "2026-11-28T12:30:00.000Z" }
  ]
}
```

`Alert`：

```json
{
  "id": "a_1", "type": "MOVEMENT",
  "target": { "userId": "u_2", "nickname": "妈妈" },
  "title": "妈妈 位置发生变化",
  "body": "已移动约 1.2 公里",
  "silent": false,
  "data": { "lat": 31.68, "lng": 120.79, "accuracy": 20,
            "distanceMeters": 1234, "recordedAt": "2026-11-28T12:29:00.000Z" },
  "occurredAt": "2026-11-28T12:29:00.000Z",
  "createdAt": "2026-11-28T12:30:02.000Z",
  "readAt": null
}
```

`data` 按类型不同：`STALE` 带 `lastRecordedAt/lat/lng/hours`，`LOW_BATTERY` 带 `battery/lat/lng/recordedAt`。

### 11.4 设备 WebSocket 新增下行消息

```json
{ "type": "alert", "alert": { /* 同 Alert */ } }
```

离线设备不补推；客户端重新连接后通过 `GET /api/alerts?unread=1` 补拉。

### 11.5 数据模型（Prisma）

| 模型 | 关键字段 |
|---|---|
| Follow | id, familyId, followerId, targetId, createdAt; unique(followerId,targetId); index(targetId) |
| AlertRule | id, followId, type(`MOVEMENT`/`STALE`/`LOW_BATTERY`/`GEOFENCE`), enabled, config(JSON), state(JSON), createdAt, updatedAt |
| Alert | id, userId(接收者), familyId, ruleId?, targetId, type, title, body, data(JSON), silent, dedupeKey(unique), occurredAt, createdAt, readAt?; index(userId, createdAt) |

告警保留 90 天，由服务端扫描任务清理。

## 12. 地点与地理围栏（v1.1.0 新增）

### 12.1 概念

- 地点是**家庭共享**的坐标 + 半径（家、学校、公司等），所有成员可见。
- 任何成员可创建；**仅创建者或家庭管理员**可编辑/删除。
- 坐标体系统一为 GCJ-02（与位置上报、高德地图一致）。
- 每个家庭最多 20 个地点，名称家庭内唯一（忽略大小写与全/半角）。
- 删除地点会一并删除引用它的 `GEOFENCE` 规则；历史 `Alert` 保留（`data.placeName` 仍可读）。

### 12.2 端点（均为用户 JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/places` | `{ places: [Place] }`，按创建时间升序；含 `usedByRules` |
| POST | `/api/places` | `{ name, type?, lat, lng, radiusMeters? }` → `201 { place }` |
| PATCH | `/api/places/:id` | `{ name?, type?, lat?, lng?, radiusMeters? }` → `200 { place }` |
| DELETE | `/api/places/:id` | `200 { removedRules }` |

`Place`：

```json
{
  "id": "p_1", "name": "学校", "type": "school",
  "lat": 31.6, "lng": 120.7, "radiusMeters": 200,
  "createdBy": "u_1", "createdAt": "2026-11-28T12:00:00.000Z",
  "usedByRules": 2
}
```

校验：`name` trim 1–20 字符；`type` ∈ `home|school|company|other`；`radiusMeters` 50–5000；每家庭 20 个；重名 → `409`；非创建者/非管理员编辑删除 → `403`；跨家庭 → `404`。

编辑坐标或半径会重置引用规则的围栏基线（下次位置重新判定，不补发进出）。

### 12.3 GEOFENCE 规则

```json
{
  "type": "GEOFENCE",
  "config": {
    "placeId": "p_1",
    "event": "BOTH",
    "window": { "days": [1, 2, 3, 4, 5], "start": "07:00", "end": "09:00" }
  }
}
```

- `event`：`ENTER`（到达）/ `LEAVE`（离开）/ `BOTH`（两者），默认 `BOTH`。
- `window`：可选；`null`/省略表示全天。`days` 为 1=周一…7=周日，空数组=每天；按**中国时区（UTC+8）**解释，`start` 含、`end` 不含；`start > end` 表示跨天，`days` 指窗口开始那天。
- 同一关注下同一地点只能有一条围栏规则；`POST /follows/:id/rules` 重复 → `409`。
- 规则 DTO 附带 `place: { id, name } | null`，方便客户端显示地点名。

判定语义：

- 第一个**可判定**的位置点只建立围栏内/外基线，不提醒。
- 迟滞：已在围栏内时，需 `距离 - 精度 > 半径` 才判定离开；已在围栏外时，需 `距离 + 精度 < 半径` 才判定进入；精度带按 `min(精度, 半径/2)` 截断，避免小围栏永远无法进入。
- 精度差于 `max(500m, 半径×3)` 的点直接忽略。
- 同一规则两次触发至少间隔 60 秒（按点的 `recordedAt`）。
- 规则创建/重置之后、以及重复或乱序的位置点不会触发提醒；补传中跨越多于 15 分钟的事件仍会入库，但 `silent=true`（不弹系统通知）。
- 生效时段按点的 `recordedAt` 判断；窗口外的进出只更新状态、不产生告警。
- 被关注者暂停共享期间不评估；恢复共享时围栏基线重置。

`Alert.data` 额外字段：`{ placeId, placeName, event: "ENTER"|"LEAVE", lat, lng, accuracy, distanceMeters, recordedAt }`。标题为「{昵称} 到{地点}了」/「{昵称} 离开{地点}了」。

### 12.4 数据模型（Prisma）

| 模型 | 关键字段 |
|---|---|
| Place | id, familyId, name, nameKey, type, lat, lng, radiusMeters, createdBy, createdAt, updatedAt; unique(familyId, nameKey) |
