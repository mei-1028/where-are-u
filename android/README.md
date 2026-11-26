# 「你在哪」Android App · M1

家庭位置共享 App 的 Android 客户端（Kotlin + Jetpack Compose + 高德定位）。
代码位于本目录，遵循 `docs/api-contract.md` 与 `docs/prototype-book.md`，视觉采用
`design/DESIGN.md` 的 Miro 设计语言（主色 `#5B76FE`、白底、近黑文字、粉彩表面、
8/12/20 圆角、系统字体）。

> **构建状态**：在安装 Android SDK 35 并配置 `android/local.properties` 后，
> 已实际执行成功（2026-11-27）：
>
> ```text
> .\gradlew.bat assembleDebug --no-daemon                    BUILD SUCCESSFUL
> .\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon   BUILD SUCCESSFUL
> ```
>
> 产物：`app/build/outputs/apk/debug/app-debug.apk`（56,226,395 字节，约 54 MiB，
> SHA-256 `9139E33E5F0A8629919E441A2D98CAC08768C56EC090DB46DAB9CCA613E8AD24`）。
> 56 个单元测试全部通过（0 failures / 0 errors）。
>
> 已在真机 `e02f73ae`（Xiaomi M511CD / Android 17 / AMap 10.0.600，公网服务端）验证：
> - Web「立即定位」→ App one-shot 成功（provider=gps）→ `POST /api/locations` 202 →
>   服务端命令 completed，Web 显示「已获取最新位置」。
> - 地图 Tab、成员详情、历史轨迹往返后 MapView 未销毁/未黑屏。
> - 断网时暂停：PATCH 连接失败后本地立即暂停并停止前台服务；恢复网络后
>   `SharingSyncWorker` 自动补发 PATCH 并返回 200。
> - 尚未验证：非管理员「退出→加入新家庭」、通知栏暂停按钮、登出解绑、WS 401、
>   开机保活与 OEM 长时后台。
> - Android 17 对本 APK 弹出 **16 KB 对齐** 兼容性警告（AMap 10.0.600 等原生库）；
>   见第 8 节。
> Gradle Wrapper（8.9）脚本与 `gradle-wrapper.jar` 已包含在仓库中。

## 1. 环境要求

| 项 | 建议 |
|---|---|
| JDK | 17 或 21（当前机器为 Java 21） |
| Android Studio | Ladybug 或更新（内置 JDK 17/21） |
| Android SDK | compileSdk / targetSdk 35，minSdk 26 |
| Gradle | 使用仓库内 wrapper（8.9），无需单独安装 |
| AGP / Kotlin | 8.7.3 / 2.0.21 |

## 2. 首次打开

1. 用 Android Studio 打开 **`android/` 目录**（不是仓库根目录）。
2. 复制 `local.properties.example` 为 `local.properties`，按需填写：
   ```properties
   sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
   AMAP_KEY=
   API_BASE_URL=http://10.0.2.2:3000
   ```
   `local.properties` 已被 `.gitignore` 忽略，**不要提交真实 Key**。
3. 等待 Gradle Sync。也可命令行：
   ```powershell
   cd android
   .\gradlew.bat assembleDebug        # Windows
   .\gradlew.bat testDebugUnitTest    # 单元测试
   ```
   （Linux/macOS 用 `./gradlew`。）

## 3. 配置高德 Key 与服务器地址

### 3.1 配置项读取顺序

`android/local.properties` → `android/gradle.properties` / `-P` 参数 → 默认值：

| 字段 | 作用 | 默认 |
|---|---|---|
| `AMAP_KEY` | 高德 Android Key；为空时使用模拟定位 + 示意地图 | 空 |
| `API_BASE_URL` | 自建服务器根地址（不含 `/api`） | `http://10.0.2.2:3000` |

App 会自动在根地址后加 `/api` 调用 REST，连接 `/ws` 建立设备 WebSocket。
模拟器访问电脑用 `10.0.2.2`；真机请填局域网 IP 或 HTTPS 域名。登录页右上角齿轮
可在运行中修改服务器地址（保存在 DataStore）。生产环境必须使用 HTTPS，并移除
Manifest 中的 `android:usesCleartextTraffic="true"`。

### 3.2 获取高德 Android Key（包名 + SHA1）

1. 登录 <https://console.amap.com/> → 应用管理 → 创建应用。
2. 添加 Key：服务平台选 **Android 平台**。
3. 填包名：`com.whereareu.app`。
4. 填签名 SHA1：
   - 调试签名：
     ```powershell
     keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" `
       -alias androiddebugkey -storepass android -keypass android
     ```
     取输出中的 `SHA1:`。
   - 发布签名：用自己的 keystore 执行同样命令（`-keystore your.jks`）。
   - 调试与发布 SHA1 不同时，需要在控制台各加一个 Key（或切换 Key）。
5. 把 Key 写入 `local.properties` 或 `gradle.properties` 的 `AMAP_KEY=...`，
   重新 Sync / 构建。

Key 会通过 `manifestPlaceholders` 写入 `<meta-data
android:name="com.amap.api.v2.apikey">`，同时由代码调用
`AMapLocationClient.setApiKey` / `MapsInitializer.setApiKey`。

> 依赖说明：`com.amap.api:3dmap:10.0.600` 的 Maven JAR 已内置
> `com.amap.api.location` 相关类；如果再添加 `com.amap.api:location:6.4.3`
> 会触发 duplicate class 构建失败。因此工程只声明 3dmap，
> `AmapLocationProvider` 使用的仍是同一套 AMap Location API。

**没有 Key 时**：App 仍可完整运行——
- 地图：Compose Canvas 绘制的示意底图 + 家庭成员标记（可点击）、轨迹折线；
- 定位：`FakeLocationProvider` 在杭州西湖附近模拟移动，通知/设置中标注「模拟定位」；
- 地址：使用系统 Geocoder，失败时显示经纬度。

## 4. 权限与保活

Manifest 已声明：

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`（「始终允许」，Android 10+ 必须单独授权）
- `POST_NOTIFICATIONS`（Android 13+）
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
- `RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（自建部署使用；上架商店需说明用途）

已实现的内容：

- 3 步权限引导：前台定位 → 后台定位 + 通知 → 电池优化/OEM 自启动引导
  （小米/HyperOS、华为/EMUI/HarmonyOS、OPPO/一加/ColorOS、vivo/iQOO/OriginOS、原生）。
- 前台服务 `LocationTrackingService`：`foregroundServiceType="location"`，常驻通知，
  支持通知栏「暂停共享」。
- 自适应频率：静止时降频（最多 10 分钟），移动时按「省电 10 分钟 / 标准 3 分钟 /
  高频 1 分钟」档位调整，最快 1 分钟。
- 断网位置写入磁盘队列（最多 1000 点），恢复网络后用 WorkManager 重试补传。
- 开机恢复：`BootReceiver` + `TrackingRestartWorker` 兜底；失败时发通知请用户打开 App。

### Android 14/15 前台服务限制

- Android 12+ 从后台启动前台服务受限；Android 14 要求 location FGS 具备
  while-in-use 定位权限并声明 FGS 类型；Android 15 进一步限制部分 FGS 从
  `BOOT_COMPLETED` 启动。
- 因此开机路径是**尽力而为**：失败时通知用户打开 App，前台启动服务始终可靠。
- 国产 ROM 的「自启动 / 后台运行 / 电池白名单」无法通过代码自动开启，只能引导用户。

## 5. 已实现页面 / 能力

| 页面 | 说明 |
|---|---|
| 启动页 | 品牌 + 会话检查（过期自动回登录） |
| 登录 / 注册 | 校验、密码可见切换、服务器地址弹窗、邀请码注册 |
| 创建 / 加入家庭 | 管理员创建、6 位邀请码加入、已加入家庭自动兜底 |
| 权限引导 3 步 | 前台定位、后台定位+通知、电池/OEM 自启动 |
| 家庭地图 | 成员条、地图（高德或示意）、在线状态、我的共享卡、权限/断网横幅、刷新、回到我的位置 |
| 成员详情底部抽屉 | 地址、更新时间/精度/电量/速度、坐标复制、立即定位、历史轨迹、导航 |
| 历史轨迹 | 今天/昨天/日期选择、折线、起终点、里程/时长/点数、回放按钮置灰 |
| 家庭管理 | 家庭信息、成员列表、移除成员（管理员）、退出家庭（非管理员） |
| 邀请 | 6 位码、复制、分享、重置（管理员）、二维码占位 |
| 设置 | 账号、共享总开关（PATCH 同步服务端）、上报频率、五项权限自检、隐私说明、清本地队列、服务器地址、退出登录 |

数据层：

- `AuthRepository`：登录/注册，用户 JWT 存 DataStore；登录后自动 `POST /api/devices`
  注册设备，`deviceToken` **单独存储**，仅用于位置上报和设备 WebSocket。
- 设备生命周期：离开家庭/被移除/登出会停止前台服务并清空 `deviceToken + deviceId`
  （离开/被移除保留登录态）；登出前调用 `DELETE /api/devices/:id`；创建/加入家庭后
  `ensureRegistered(force=true)` 删除旧设备并签发新 token。设备上报返回 401 时保留
  队列并强制重新注册；用户 JWT 失效则登出。
- `ApiService`：覆盖 `docs/api-contract.md` 中的鉴权、家庭、设备、位置、立即定位接口；
  统一 `ApiException` + 中文错误映射。
- 共享开关：首页、设置、引导页与通知栏「暂停」统一走 `SharingController` →
  `PATCH /api/families/me/sharing`。恢复共享要求 PATCH 成功且前台服务在
  `startForeground` 后回执 `isRunning`；暂停遇网络失败时隐私优先：本地立即暂停并
  停止服务，PATCH 交给 `SharingSyncWorker` 联网补发；业务错误仍拒绝。
  `MapViewModel` 用服务端 `sharingPaused` 校准本地开关。
- 地图导航：地图页作为持久 Host 常驻，家庭/设置以及 **历史/邀请** 都是覆盖层；
  MapView 不会因页面导航/切换而 `onDestroy`/重建，隐藏时保持 resumed 且禁用输入，
  仅 Activity 后台时 `onPause`，Activity 销毁或登出/离开家庭时才释放。
- 轮询：地图每 10 秒刷新，仅在地图页可见且 Activity resumed 时运行；切 Tab、打开
  历史/邀请或退到后台会停止，避免后台请求与误登出。
- 主题：Activity 级显式设置 `isAppearanceLightStatusBars = true`（以及导航栏深色图标），
  避免系统深色模式下浅色/粉色页面上出现白色状态栏图标。
- 设备 WebSocket：`Authorization: Bearer <deviceToken>`，hello + 30 秒 pong、
  指数退避 + 抖动重连；握手 HTTP 401 或关闭码 4401 时停止无限重连并重新注册/提示。
- 立即定位：`{"type":"locate"}` → AMap 单次定位（6s）→ 失败改用系统
  `LocationManager.getCurrentLocation` / `requestSingleUpdate`（8s）→ 仍失败时允许使用
  “命令到达后”产生的连续定位新点；三者都没有则记录错误并发出「立即定位失败」通知。
  POST 点保证 `recordedAt >= 命令到达时间`，并携带 `commandId`。
- 地图：配置 `AMAP_KEY` 时使用 `MapView`（AndroidView + 生命周期绑定）；
  否则 Canvas 示意地图。

## 6. 单元测试（已执行通过：56/56）

- `BackoffPolicyTest`（4）：WebSocket 重连指数退避、抖动窗口、上限。
- `ServerSocketParserTest`（6）：locate 指令解析、缺失 commandId、ping、未知类型、坏帧。
- `AdaptiveIntervalPolicyTest`（8）：三档频率在静止/步行/快速移动下的区间与上下限。
- `SharingControllerTest`（9）：暂停/恢复成功、业务失败不改状态、断网暂停本地优先并
  排队重试、暂停请求 4s 超时仍本地优先、FGS 回执失败回滚服务端、并发串行化、
  服务端 sharingPaused 校准。
- `TrackingStartReceiptTest`（3）：startForeground 回执的立即成功、轮询等待、超时。
- `DeviceManagerTest`（5）：force 删除旧设备并重注册、复用 token、失败后无残留 token。
- `FamilySessionManagerTest`（2）：加入后强制重注册、离开/移除后停服并清 token/family。
- `LocationReporterTest`（5）：401 保留队列并触发重认证、5xx 保留、4xx 丢弃、
  并发 flush 不丢点/不重复。
- `OfflineLocationQueueTest`（3）：FIFO、只删除本次上传批次、clear。
- `LocateFixPolicyTest`（5）：one-shot 新鲜度、连续点回退与命令时间约束。
- `ServerConfigTest`（6）：scheme 保留、裸 IP/端口默认 http、443/域名默认 https。

运行：`.\gradlew.bat testDebugUnitTest --rerun-tasks`，本次结果为 **56/56 通过**。

## 7. 模拟 / 占位内容

- **定位**：未配置 `AMAP_KEY` 时使用 `FakeLocationProvider`（杭州西湖附近模拟走动）。
  模拟定位仍需要系统定位权限，因为 Android 对 location 前台服务有权限要求；
  同时地图数据仍来自你配置的服务端，服务端不可达时会显示「连接中断」。
- **地图**：未配置 Key 时使用 Canvas 示意地图；轨迹页标注「示意轨迹」。示意地图
  只能展示服务端返回的成员位置，不提供底图街道真实性。
- **二维码**：邀请页是确定性占位图形，**不可扫码**；M1 原型也仅要求占位。
- **地址**：服务端契约的 `lastLocation` 不含地址；客户端用系统 Geocoder 逆地理编码，
  不可用时回退经纬度。配置高德 Key 不会自动获得逆地理编码。注意高德定位返回
  GCJ-02 坐标，系统 Geocoder 通常按 WGS-84 解析，地址可能有偏移；后续可接高德
  逆地理编码服务。
- **通知**：无推送通道（M1 不做推送），仅有前台服务常驻通知。

## 8. 已知限制与差距

1. **运行态验证范围有限**：真机 `e02f73ae`（小米 M511CD / Android 17 /
   AMap 10.0.600，公网服务端）已验证：Tab/历史轨迹地图保活、Web 立即定位成功、
   断网暂停 + 联网补发、首页/设置暂停恢复。验收账号是管理员，按契约不能“退出家庭”，
   因此 **真实“退出→加入新家庭”**、通知栏暂停按钮、登出解绑、WS 401、开机保活、
   OEM 长时后台仍未在真机执行（对应逻辑由单元测试覆盖）。
2. **16 KB 页对齐**：Android 17 的兼容性检查会提示 APK 含未按 16 KB 对齐的原生库：
   `libAMapSDK_MAP_v10_0_600.so`、`libandroidx.graphics.path.so`、
   `libdatastore_shared_counter.so`。AMap 部分需等待/更换 SDK 版本，本仓库按指示
   **不替换 AMap SDK**；在 16 KB 页设备上这可能导致安装/运行受限，发布前必须解决。
3. **跨端共享状态**：Android 的暂停/恢复会 PATCH 服务端；Web 修改 `sharingPaused`
   后，Android 在地图可见并轮询时会校准开关/停服，但后台或未轮询时不是即时同步。
4. **成员邀请**：原型能力表写「成员可邀请」，但接口只允许管理员看到 `inviteCode`。
   Android 端按接口实现（管理员可见邀请码），非管理员看到说明页。
5. **家庭改名 / 转让管理员 / 清空云端历史**：契约未提供接口，UI 未开放。
6. **立即定位**：依赖 `/families/me/latest` 返回 `deviceId`；AMap one-shot 失败会降级
   到系统 LocationManager，再失败则只接受命令到达后的连续新点；全部失败时仅能
   本地告警（契约无失败回执接口），服务端仍会超时。
7. **开机保活**：Android 14/15 与 OEM 策略下不能保证自动恢复，只能兜底通知。
8. **设备 Token / JWT 存储**：使用普通 DataStore，未接入 Keystore 加密；M1 自建
   部署可接受，后续建议 EncryptedFile/Keystore。
9. **明文 HTTP**：为本地调试开启 `usesCleartextTraffic=true`，生产需关闭。
10. **地址与轨迹回放**：地址依赖设备 Geocoder；轨迹回放 M1 不做；GCJ-02/WGS-84
    偏移问题仍存在。
11. **国际化**：文案目前硬编码中文，未抽取 `strings.xml`（通知渠道/应用名除外）。
12. **无深色模式**：原型书说明深色模式 P1 再评估。
13. **无仪器测试 / 截图基线**；真机验证为手工抽查（见第 1 条），未覆盖全部页面与 OEM。
14. **Headless SwiftShader 模拟器**：AMap MapView 在该环境可能首屏即黑（不切换 Tab 也黑），
    属于高德 GL 渲染与软渲染器的兼容问题；真机 `e02f73ae` 正常。Tab/页面隐藏时保持
    resumed、仅禁用输入，避免额外的 `onPause`/`onResume`。
15. **轮询**：地图每 10 秒拉取 `/families/me/latest`，但仅页面可见且 Activity resumed
    时运行；省电性仍依赖服务端优化。

## 9. 目录结构

```
android/
├─ app/
│  ├─ build.gradle.kts            # AMAP_KEY / API_BASE_URL 注入 + 依赖
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ res/                  # 主题、图标、通知渠道文案
│     │  └─ java/com/whereareu/app/
│     │     ├─ MainActivity.kt / WhereAreUApp.kt
│     │     ├─ core/
│     │     │  ├─ auth/           # SessionStore、AuthRepository、DeviceManager
│     │     │  ├─ data/           # FamilyRepository、HistoryRepository
│     │     │  ├─ location/       # LocationProvider、前台服务、离线队列、策略
│     │     │  ├─ ws/             # DeviceSocket、重连策略、协议解析
│     │     │  ├─ net/            # ApiService、DTO、错误映射、运行时配置
│     │     │  ├─ permissions/    # 权限检查、OEM 引导
│     │     │  ├─ notifications/  # 渠道与通知
│     │     │  ├─ boot/           # 开机恢复 + WorkManager
│     │     │  └─ util/           # 地址、电量、时间、距离
│     │     ├─ ui/
│     │     │  ├─ auth/ family/ onboarding/ map/ history/ settings/
│     │     │  ├─ components/ theme/ nav/ splash/
│     │     └─ ...
│     └─ test/…/core/…            # 十一个纯 Kotlin 测试类（56 用例）
├─ gradle/libs.versions.toml
├─ gradle/wrapper/                # Gradle 8.9 wrapper（jar 已包含）
├─ gradle.properties
├─ local.properties.example
└─ README.md
```

## 10. 安全注意事项

- 不要提交 `local.properties`、keystore、真实 `AMAP_KEY`。
- 生产使用 HTTPS；服务器地址不是受信任输入的边界，App 仅做格式校验。
- App 不申请通讯录、短信、后台读文件等与功能无关的权限。
- 位置数据只发送到用户配置的服务器，M1 无第三方统计与广告。
