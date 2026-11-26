# 任务编排 · M1

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-26 |
| 角色 | 主控（Leader） |
| 目标 | 完成 M1 原型、架构、服务端、Web、Android 五条工作流，并进入联调 |
| 接口事实来源 | `docs/api-contract.md` |
| 产品事实来源 | `docs/prototype-book.md` |

## 1. 工作流与文件所有权

| # | 工作流 | 交付物 | 文件范围 | 状态 |
|---|---|---|---|---|
| 1 | 设计（Miro 重做） | 19 屏原型 + 封面 + 原型书第 9 节 | `design/design.pen`、`docs/prototype-book.md` | 完成 · 待评审 |
| 2 | 架构与部署 | 架构文档、环境说明、部署编排 | `README.md`、`.gitignore`、`docs/architecture.md`、`docs/setup.md`、`deploy/**` | 完成 · 已部署（HTTP） |
| 3 | 服务端 | Fastify + Prisma + SQLite 可运行服务 | `server/**` | 完成 · 36 测试 · 软解绑已部署 |
| 4 | Web | Vue 3 + Vite 家庭地图与管理系统 | `web/**` | 完成 · 15 测试 · 公网已验证 |
| 5 | Android | Kotlin + Compose 客户端骨架 | `android/**` | 完成 · 56 测试 · 真机+模拟器 + 公网 locate 验证通过 |

**隔离规则**：任一工作流不得修改其它工作流的文件。接口变更必须先改 `docs/api-contract.md`，再通知三端。

## 2. 接口与依赖

```
docs/api-contract.md
        │
        ├── server/     实现 REST + /ws
        ├── web/        REST 客户端 + 轮询
        └── android/    REST 客户端 + /ws + 前台定位服务
```

- 三端均以 `docs/api-contract.md` 为准，不自行扩展字段。
- 设计线与工程线并行，工程端直接按 `design/DESIGN.md`（Miro）实现视觉令牌。

## 3. 验收标准

| 工作流 | 验收 |
|---|---|
| 设计 | 19 屏 + 封面齐全；Miro 令牌一致；无裁切；`design.pen` 已保存；原型书第 9 节同步 |
| 架构 | 文档链接有效；部署文件无密钥；拓扑与 M1 范围一致 |
| 服务端 | `npm install`、`db:push`、`npm test`、`npm run build` 全部通过；`/health` 可访问 |
| Web | `npm run build` 通过；mock 模式可浏览全部页面；控制台无错误 |
| Android | 项目结构完整；单元测试通过；有 SDK 则 `assembleDebug` 通过，无 SDK 则明确标注未编译 |

## 4. 第二波：集成验收

五条工作流完成后启动集成验收：

1. 服务端跑通注册 → 建家庭 → 绑设备 → 上报位置 → Web 显示。
2. Web 连接真实服务端，关闭 mock，验证登录、地图、立即定位、轨迹、家庭/设备管理。
3. Android 静态核对 API 字段与鉴权头；有设备时真机联调。
4. 修复跨端问题（CORS、时间格式、字段命名、错误码）。
5. 输出 `docs/verification.md`。

## 5. 风险与待确认

| 风险 | 影响 | 处理 |
|---|---|---|
| 无 Android SDK / adb | 无法本地编译与真机验证 | 先交付可导入 Android Studio 的工程；用户在 Android Studio 中构建 |
| 高德 Key 未申请 | 定位与地图不可用 | 先使用 Fake 定位与示意地图；Key 就绪后替换 |
| 域名 / 公网中转未定 | 无法端到端外网联调 | 先局域网 HTTP 联调；部署文档同时给 frp 与 Cloudflare Tunnel 方案 |
| 家人手机品牌未知 | 保活引导默认项不准 | 引导页覆盖主流品牌，后续按实际机型调整 |
| pen.dev 单画布 | 多代理同时编辑会冲突 | 仅设计线持有 `design.pen` |

## 6. 架构线提出的跨端问题

| 问题 | 归属 | 处理 |
|---|---|---|
| `server/Dockerfile` | 服务端 | 已补齐；本机无 Docker，未实际构建验证 |
| Fastify `trustProxy` | 服务端 | 已用 `TRUST_PROXY` 解决；Docker 默认信任 loopback/uniquelocal |
| 暂停/恢复共享缺少写接口 | 服务端 + 三端 | 契约已补 `PATCH /api/families/me/sharing`；服务端已实现并测试，Android 接入中 |
| Web 生产构建默认 `VITE_USE_MOCK=true` 的风险 | Web | 生产构建显式关闭；集成阶段核对 |
| Android `usesCleartextTraffic="true"` 且未配置 release signing | Android | 上线前关闭 cleartext、配置签名、更新高德 SHA1 |
| 历史保留天数 / 清空历史未实现 | 产品 + 三端 | M1 先在 UI 标注 P1/禁用，评审后补契约与实现 |
| 批量幂等、邀请码限流、人数上限、`failed` 状态 | 产品 + 三端 | 评审确认后补入契约 |

## 7. 部署与验收现状

| 项 | 现状 |
|---|---|
| 公网入口 | `http://<VPS_IP>:18080`（临时 HTTP，无域名） |
| 拓扑 | VPS frps `7012` → NAS 独立 frpc `whereareu-web` → Caddy `18080` → server `3000` |
| 后端 | 公网 `/health` 返回 200；SQLite 持久化在 NAS |
| Web | 生产构建已部署，登录/家庭地图/成员/定位在公网验证通过 |
| Android | 真机 Redmi K100 Pro（Android 17）+ Orca 模拟器（API 35）均完成登录、引导、地图与位置上报 |
| 立即定位 | 真机与 Web 双侧验证，指令状态 `completed`，精度与时间刷新 |
| 临时数据 | 公网服务端存在一个 `acceptance` 验收账号/家庭（用于部署验证，可删除） |

## 8. 推送前检查

- 仓库已 `git init -b main`，远程 `origin` 指向 `mei-1028/where-are-u`。
- `.env`、`.env.*`、SQLite、`local.properties`、keystore、APK、`dist/`、日志与 Playwright 产物均已忽略。
- 模板中的真实 VPS/NAS IP 已替换为 `<VPS_IP>` / `<NAS_IP>`；`JWT_SECRET` 示例为占位符。
- 真实地址与密钥只保留在 NAS 的 `.env`、`web/.env.local`、`web/.env.production.local`、`android/local.properties` 等未跟踪文件中。
- 待 Android sharing 同步完成并重新构建后提交、推送。
