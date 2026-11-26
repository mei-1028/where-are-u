# 你在哪 · where-are-u

自建的家庭位置共享应用：家人的 Android 手机把位置上报到**自己家里**的服务器，家人通过 App 或 Web 家庭端查看当前位置与历史轨迹。

- **数据自持**：位置数据保存在家中设备的 SQLite 中；地图展示与定位能力由高德提供。
- **家庭内可见**：服务端按成员关系确定家庭范围，成员只能看到同一家庭的数据。
- **知情共享**：开启共享时显示常驻通知，可随时暂停；App 提供权限与保活自检。
- **弱网可用**：断网时本地缓存位置，恢复后批量补传。

> **项目状态**：M1 并行开发阶段。`server/`、`web/`、`android/` 由各端负责人实现，本 README 不表示功能已完成；下文命令是目标约定，最终以各端实际配置为准。接口以 [API 契约](docs/api-contract.md) 为唯一事实来源。

## 仓库结构

| 路径 | 内容 | 说明 |
|---|---|---|
| `android/` | Android App（Kotlin + Compose + 高德定位 SDK） | 位置上报、查看家人、接收立即定位指令 |
| `web/` | Web 家庭端（Vue 3 + Vite + 高德 JS API） | 大屏查看、轨迹、家庭与设备管理 |
| `server/` | 服务端（Node.js + Fastify + Prisma + SQLite） | 账号、家庭、设备、位置、指令与设备 WebSocket |
| `deploy/` | 部署模板（Docker Compose + Caddy + frp 示例） | 家中设备 + 隧道 + 自动 HTTPS |
| `docs/` | 原型书、接口契约、架构与部署文档 | 见下表 |
| `design/` | 原型设计文件 | `design.pen` |

## 文档

| 文档 | 用途 |
|---|---|
| [docs/prototype-book.md](docs/prototype-book.md) | 产品范围、页面清单、交互与 M1 原型 |
| [docs/api-contract.md](docs/api-contract.md) | M1 接口唯一事实来源（三端共同遵守） |
| [docs/architecture.md](docs/architecture.md) | 系统组成、数据流、信任边界、技术决策与失败模式 |
| [docs/setup.md](docs/setup.md) | 高德 Key、环境变量、局域网开发、隧道、HTTPS、备份与排障 |
| [deploy/README.md](deploy/README.md) | 生产部署拓扑与操作步骤 |

## 快速开始（占位）

| 端 | 预期命令 |
|---|---|
| 服务端 | `cd server` → `npm install` → `npm run dev` |
| Web | `cd web` → `npm install` → `npm run dev` |
| Android | 用 Android Studio 打开 `android/` 目录并运行 `app` |

约定端口：服务端 `3000`，Web 开发服务器 `5173`（以各端实际配置为准）。首次运行前需按 [docs/setup.md](docs/setup.md) 准备高德 Key、服务端 `.env` 与 Android 的 `local.properties`。服务端首次启动需初始化数据库（`server/package.json` 提供 `npm run db:push`）；Web 默认运行在 mock 模式，连接真实服务端需关闭 `VITE_USE_MOCK`。

## M1 范围

包含：注册登录、创建/加入家庭、邀请成员、设备绑定与管理、周期位置上报、家庭地图与成员详情、历史轨迹、立即定位、上报频率与权限自检。

M1 非目标：iOS、聊天、地理围栏、SOS、多家庭、位置导出、深色模式、推送通知、轨迹回放（P1）、修改密码（P1）、部署者管理后台（P2）。

完整清单见 [原型书](docs/prototype-book.md) 与 [API 契约](docs/api-contract.md)。

## 安全提示

- 生产环境必须使用 HTTPS（Android 客户端与 Web 均如此）；本地开发允许 HTTP。
- 不要提交 `.env`、`local.properties`、签名证书、frp token 或数据库文件；请从示例文件复制后本地填写。
- 公网入口只应暴露 Caddy 的 80/443，不要暴露服务端 3000 端口。
- 家庭位置属于敏感数据，备份文件同样需要加密并控制访问。

## 许可证

尚未选择许可证。在添加 `LICENSE` 前，本仓库默认保留所有权利。
