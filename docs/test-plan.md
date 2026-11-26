# M1 测试计划与结果

| 项目 | 内容 |
|---|---|
| 版本 | v1.0.0（M1） |
| 日期 | 2026-11-26 |
| 目标 | 验证家庭位置共享核心链路、异常场景与发布可用性 |

## 1. 测试环境

| 环境 | 说明 |
|---|---|
| 真机 | Redmi K100 Pro（Xiaomi M511CD），Android 17 |
| 模拟器 | Orca AVD `whereareu_api35`，Android 15（API 35），x86_64 |
| 服务端（本地） | Fastify + Prisma/SQLite，`http://<LAN_IP>:3000` |
| 服务端（公网） | NAS Docker（Caddy + server），`http://<PUBLIC_HOST>:18080` |
| Web | Vite 生产构建，经 Caddy 同源提供 |
| 定位/地图 | 高德 Android 3D 地图 10.0.600 + 高德定位；高德 JS API 2.0 |

## 2. 自动化测试

| 层 | 命令 | 结果 |
|---|---|---|
| 服务端 | `npm test`（36 个集成测试） | 通过 |
| 服务端构建 | `npm run build` | 通过 |
| Android 单测 | `.\gradlew.bat testDebugUnitTest` | 56/56 通过（位置上报/离线队列/设备生命周期/SharingController） |
| Android 构建 | `.\gradlew.bat assembleDebug` | 通过 |
| Web 单测 | `npm test`（15 个：API base/401/store/登出） | 通过 |
| Web 类型检查/构建 | `npm run build` | 通过；`VITE_USE_MOCK=true` 构建被拒绝 |
| Web Mock | Playwright 1440×900 / 390×844 全页面 | 通过 |
| Web 真实服务端 | 登录、地图、抽屉、立即定位 | 通过 |
| Web 公网生产 | 新构建部署后登录、成员表、邀请码到期、AMap | 通过（1/2 在线，邀请码 11-27 23:16 到期） |

## 3. 端到端场景矩阵

| ID | 场景 | 端 | 结果 |
|---|---|---|---|
| E2E-01 | 安装、冷启动、会话恢复 | Android | 通过 |
| E2E-02 | 注册 / 登录 / 错误密码 | Android / Web | 通过 |
| E2E-03 | 创建家庭、邀请码加入 | Android / Web | 通过 |
| E2E-04 | 3 步权限引导（前台/后台/保活） | Android | 通过 |
| E2E-05 | 家庭地图、成员状态、电量、地址 | Android | 通过 |
| E2E-06 | 位置定时上报、服务端最新位置 | Android → Server | 通过 |
| E2E-07 | Web 查看地图、成员详情 | Web | 通过 |
| E2E-08 | 立即定位（Web 下发 → 手机回传） | Web → Android | 通过：真机 AMap one-shot；模拟器高德额度失败时自动降级系统 fused，命令 completed，Web 更新 |
| E2E-09 | 暂停 / 恢复共享、服务端 `sharingPaused` | Android → Server | 通过（真机 PATCH 200；恢复后 WS 连接、位置上报 202） |
| E2E-10 | 家庭页、设置页切换 | Android | 通过（升级高德 10.0.600 后不再闪退） |
| E2E-11 | 地图 → 家庭 → 设置 → 地图 | Android | 通过（真机返回地图正常渲染；headless 模拟器 GL 黑屏为环境限制） |
| E2E-12 | 历史轨迹（日期、折线、统计） | Android / Web | 通过（真机 78 个真实轨迹点；模拟器地图 GL 黑屏为 headless 限制） |
| E2E-13 | 设备管理、绑定/解绑 | Web / Android | 待最终复测 |
| E2E-14 | 断网、服务端不可达、离线队列 | Android | 通过：断网暂停本地生效并停服务，联网后 SharingSyncWorker 补发 PATCH 200；离线队列与并发 flush 有单测 |
| E2E-15 | 锁屏 / 后台 / 厂商保活 | Android | 部分：锁屏后 HyperOS 会清理，需自启动+电池白名单 |
| E2E-16 | 公网 Web 登录、地图、成员 | Web | 通过 |
| E2E-17 | 模拟器安装、登录、引导、地图 | Android 模拟器 | 通过 |
| E2E-18 | 最终 Release APK 安装与核心回归 | Android | 模拟器通过：v1.0.0、公网登录、家庭地图、立即定位 completed；Redmi 待安装最终 APK（手机带走） |

## 4. 已修复缺陷

| ID | 现象 | 根因 | 修复 |
|---|---|---|---|
| D-01 | 启动即崩溃 | ViewModel 工厂注册的是基类，未注册具体类 | `viewModelFactoryFor` 改为 reified 泛型 |
| D-02 | 定位服务启动后崩溃 | OkHttp 不接受 `ws://` scheme | WebSocket URL 使用 http/https，由 OkHttp 内部升级 |
| D-03 | 点「家庭 / 设置」闪退 | 高德 9.7.0 MapView 销毁时 `GLMapEngine_nativeDestroy` 在 Android 17 触发 SIGABRT | 升级高德 3D 地图 10.0.600 |
| D-04 | 切 Tab 返回地图灰屏 | MapView 销毁重建后未恢复渲染 | 持久化地图 Host；真机验证正常。headless 模拟器 GL 黑屏为 SwiftShader 限制 |
| D-05 | Android 17 16KB 页对齐警告 | 高德 9.7.0 .so 未对齐 | 升级 10.0.600 后 Redmi（4KB 页）不再告警。注意：10.0.600 .so 仍是 4KB 页对齐，16KB 页内核机型存在 dlopen 风险，需高德 16KB 版本 |
| D-06 | 共享暂停只停本机、家人端不同步 | 缺少写接口 | 契约新增 `PATCH /api/families/me/sharing`，Android 接入 |
| D-07 | 公网立即定位 30s 超时 | App 已收到 locate 指令，但高德 10.0.600 一次性定位回调未产出 | 已修复：AMap one-shot 6s → 系统 fused 8s 降级；模拟器/真机命令 completed |
| D-08 | 发布产物曾指向内网/0.1.0/debuggable | 早期 debug 产物；release 未签名 | 已配置 release signingConfig(debug cert)、versionName 1.0.0、公网 API；release APK 已验证签名/版本 |

## 5. 发布门槛

- P0/P1 缺陷清零；E2E-01～E2E-11、E2E-16、E2E-18 必须通过。
- Android `assembleDebug` / `testDebugUnitTest` 通过；服务端 `npm test` 通过；Web `npm run build` 通过。
- 最终 APK 在真机完成「登录 → 地图 → 家庭 → 设置 → 返回地图 → 暂停/恢复 → 立即定位」回归。
- APK 放入 `release/` 并记录 SHA-256；GitHub 无法创建 Release 时，随仓库推送。

## 6. 暂不覆盖 / 已知限制

- iOS 客户端、推送通知、地理围栏、SOS、多家庭。
- 正式签名与 HTTPS：v1.0.0 为侧载版本，使用调试证书 + HTTP。
- 长时间（>24h）后台保活与各厂商 ROM 全量适配，仅覆盖 Redmi K100 Pro。
- 高并发/多实例：SQLite 单写、单实例部署。
