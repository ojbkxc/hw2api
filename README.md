# hw2api —— 手机上的华为云 CodeArts OpenAI 兼容 API 网关

**hw2api** 是一个 Android App：登录华为云账号后，手机本身变成一台 OpenAI 兼容 API 服务器，把 CodeArts 官方模型通道（GLM-5.2 / GLM-5.3 / OpenPangu 2.0）以标准 `GET /v1/models` + `POST /v1/chat/completions` 接口暴露给局域网内任意设备。凭证自动续期（refresh_token rotation），登一次长期有效。

| 项 | 值 |
|---|---|
| App 显示名 / 包名 | 「HW API」/ `com.lxseek.hw2api` |
| 目标设备 | vivo X60（V2055A，Android 12）；任何 Android 12+（minSdk 31）设备均可 |
| 技术栈 | Kotlin + Jetpack Compose + NanoHTTPD + OkHttp + WebView（OAuth） |
| 服务端口 | 默认 `8788`（可在设置页修改） |
| 模型 | GLM-5.2 / glm-5.2-sft-harmony / glm-5.3（仅流式）/ openpangu-2.0-pro / openpangu-2.0-flash |
| 工程结构 | 双模块：`core/`（纯 JVM 协议层，122 个单元测试）+ `app/`（Android 壳） |

> **仓库产出说明（诚实声明）**：本仓库代码在无 JDK / Android SDK / Gradle 的环境中编写完成，**未在产出机器上做过编译与运行验证**。协议层语义已通过 122 个纯 JVM 单元测试（含与桌面版 Python 实现的 golden 向量对拍）固化。构建、安装与真机验证请按下文步骤在你的机器上完成。
>
> 工程目录下另有 [README-GRADLE-WRAPPER.txt](README-GRADLE-WRAPPER.txt)（仓库不含 gradlew / gradle-wrapper.jar 的原因与补全方法），[scripts/](scripts/) 下有真机验证与日志取证脚本。

---

## 目录

1. [项目简介](#1-项目简介)
2. [功能列表](#2-功能列表)
3. [环境要求](#3-环境要求)
4. [构建步骤](#4-构建步骤)
5. [安装到手机](#5-安装到手机)
6. [使用指南](#6-使用指南)
7. [运行单元测试](#7-运行单元测试)
8. [常见问题](#8-常见问题)
9. [安全说明](#9-安全说明)
10. [已知限制](#10-已知限制)

---

## 1. 项目简介

### 1.1 这是什么

hw2api 是桌面版代理 [`codearts_proxy.py`](../llm-proxy/codearts_proxy.py) 的 **Android 原生移植**：把「华为云 CodeArts 官方模型通道」转换为标准 OpenAI 兼容 API。装在手机上运行后：

- 局域网内的电脑 / 平板 / 任何支持「自定义 OpenAI API 地址」的 App，填 `http://<手机IP>:8788/v1` 即可使用 GLM-5.2、GLM-5.3、OpenPangu 2.0 等模型；
- 凭证（AK/SK/securityToken）通过华为云 OAuth（PKCE + DPoP）在 App 内登录获取，存于加密存储，**每 2 小时自然过期、提前 1 小时自动续期**（refresh_token 轮换链约 30 天有效），正常情况登录一次即可长期使用；
- 手机变成一台低功耗常驻 API 网关，桌面机无需再跑 Python 进程。

### 1.2 与桌面版 codearts_proxy.py 的关系

同一套协议的两种载体：桌面版是 Python 脚本（从 CodeArts Agent 桌面客户端接管 / 建立登录态），hw2api 是 Android App（在手机上独立登录）。协议层（OAuth PKCE + DPoP ES256、STS token 交换与 refresh 轮换、SDK-HMAC-SHA256 上游签名、模型表）**语义一致**，核心逻辑已用 golden 向量单元测试对拍。

功能裁剪（手机场景做减法）：

| 桌面版 codearts_proxy.py | hw2api App | 说明 |
|---|---|---|
| `python codearts_proxy.py` 启动代理 | 主界面打开「API 服务」开关 | 端口默认同为 8788 |
| `--login` 浏览器建立独立登录链 | App 内「登录华为云」（WebView 授权页） | 同一 portal 授权流程，等价能力 |
| `--capture` 抓取桌面客户端登录态 | **不支持** | 手机上没有 CodeArts Agent 桌面客户端（DPAPI 加密库）可抓，无意义 |
| `--list` / `--remove` 账号池管理 UI | **不支持** | 数据层保留多账号结构，UI 仅单账号；「退出」即删除当前账号 |
| `--export` / `--import` 账号池迁移 | **不支持** | 凭证经 Android Keystore 加密存储，无法跨设备迁移（安全特性） |
| `--sync-models` 从内核日志同步模型 | **不支持** | 模型表写死于 `core` 的 `ModelRegistry`（5 个模型 + 5 别名） |
| Anthropic 协议（`/v1/messages`） | **不支持** | 仅 OpenAI 兼容（`/v1/models`、`/v1/chat/completions`） |
| `/v1/responses`（Responses 协议） | **不支持** | 同上 |
| 环境变量 `CODEARTS_PROXY_PORT` | 设置页「监听端口」 | 默认均 8788 |
| 环境变量 `CODEARTS_PROXY_API_KEY` | 设置页「API Key」+ 鉴权开关 | 同款 `Authorization: Bearer` / `X-Api-Key` 语义 |
| `GET /health` | `GET /health` | 字段集不同，见 [已知限制](#10-已知限制) |

---

## 2. 功能列表

- **OAuth 登录（App 内 WebView）**：加载华为云 CodeArts portal 授权页（PKCE + DPoP ES256），完成后凭证自动交换入库，全程不离开 App、不占用浏览器。
- **`GET /v1/models`**：OpenAI 格式模型列表，含 5 个上游模型 ID 与 5 个别名（共 10 个条目）。
- **`POST /v1/chat/completions`**：
  - `stream: true` → SSE 流式**直通**（上游分块原样转发，逐 token 推送）；
  - `stream: false` → 非流式聚合返回完整 JSON；
  - glm-5.3 上游仅支持流式：非流式请求由 App 内部转流式再聚合，对客户端透明；
  - 模型别名自动归一（如 `pangu-pro` → `openpangu-2.0-pro`）；`max_tokens` 超上限自动钳制。
- **凭证自动续期（refresh rotation）**：临期 1 小时内自动刷新，refresh_token 单向轮换（旧 token 用后即废）；上游 401 时强制续期并重试一次；并发刷新单飞去重。续期链彻底失效时通过通知栏 + App 内横幅引导重新登录。
- **API Key 局域网鉴权（可选）**：设置页配置 Key 后，`/v1/*` 请求需携带 `Authorization: Bearer <Key>` 或 `X-Api-Key: <Key>`（`/health` 不鉴权）；默认不鉴权。
- **`GET /health`**：账号、凭证到期时间与倒计时、上游最近一次调用结果、监听地址、鉴权状态。
- **前台服务常驻**：通知栏显示监听端口 + 凭证到期倒计时；锁屏后台持续服务。
- **模型别名**：`glm-5.2`、`glm-5.2-harmony`、`GLM-5.3`、`pangu-pro`、`pangu-flash`。

模型表（大小写敏感）：

| 上游模型 ID | 别名 | 上下文 | max_tokens | 流式支持 |
|---|---|---|---|---|
| `GLM-5.2` | `glm-5.2` | 202752 | 65536 | 流式 + 非流式 |
| `glm-5.2-sft-harmony` | `glm-5.2-harmony` | 202752 | 65536 | 流式 + 非流式 |
| `glm-5.3` | `GLM-5.3` | 202752 | 65536 | **仅流式**（非流式自动转聚合） |
| `openpangu-2.0-pro` | `pangu-pro` | 524288 | 65536 | 流式 + 非流式 |
| `openpangu-2.0-flash` | `pangu-flash` | 524288 | 65536 | 流式 + 非流式 |

---

## 3. 环境要求

| 组件 | 要求 | 说明 |
|---|---|---|
| 操作系统 | Windows 7 及以上 | 本 README 命令均为 Windows PowerShell 形态 |
| Android Studio | **Ladybug 2024.1.1+**（或更新版本） | 自带 JDK 17、Gradle、SDK 管理器，**无需手工安装任何 JDK / SDK / Gradle** |
| JDK | 17 | Android Studio 自带（Embedded JDK），不用单独装 |
| Android SDK | Platform 35 | 首次 Gradle Sync 时 AS 自动下载（compileSdk / targetSdk = 35） |
| Gradle | 8.9 | wrapper 自动下载（见 [4.1](#41-关于-gradle-wrapper首次必读)） |
| 构建工具链 | AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.00 | 均由 version catalog 声明，Sync 自动获取 |
| 真机 | Android 12+（minSdk 31） | 验证机型 vivo X60（V2055A / OriginOS）；需开启开发者选项与 USB 调试 |
| adb（可选） | 任意较新版本 | Android Studio 自带（`platform-tools`）；本机若使用独立 adb，示例路径为 `C:\adb\adb.exe` |
| 手机网络 | 需能访问华为云（codearts / sts / snap-access 域名） | 登录与上游转发均走 HTTPS |

---

## 4. 构建步骤

> 本章为**用户侧首次构建**指引（产出机器未执行过这些步骤）。日常使用构建 debug 包即可。
>
> **手头没有 Android Studio？** 直接看 [4.5 GitHub Actions 云端构建](#45-github-actions-云端构建免装-android-studio)：把仓库推到 GitHub 即可云端出 APK。

### 4.1 关于 Gradle Wrapper（首次必读）

本仓库**不含** `gradlew` / `gradlew.bat` 脚本与 `gradle/wrapper/gradle-wrapper.jar` 二进制，仅含 `gradle/wrapper/gradle-wrapper.properties`（指向 Gradle 8.9）。原因与两种补全方式详见 [README-GRADLE-WRAPPER.txt](README-GRADLE-WRAPPER.txt)：

- **方式一（推荐）**：用 Android Studio 打开工程，Gradle Sync 时按提示使用 AS 内置 Gradle 或自动补全 wrapper；
- **方式二（命令行）**：在 AS Terminal（或任何装有 Gradle 8.x 的终端）执行 `gradle wrapper --gradle-version 8.9` 生成 `gradlew.bat` 与 `gradle-wrapper.jar`，之后即可命令行构建。

### 4.2 Android Studio 图形界面构建（推荐）

1. **打开工程**：Android Studio → **Open** → 选择项目根目录 `C:\GitHub\hw2api`（注意是根目录，不是 `app/` 子目录）。
2. **等待 Gradle Sync**：右下角进度条走完。首次 Sync 会联网下载 Gradle 8.9、AGP、Kotlin 插件、依赖库与 SDK Platform 35，耗时取决于网络（国内网络慢见 [4.4](#44-国内网络-gradle-加速可选)）。期间若弹出「Gradle wrapper not found」等提示，按提示选用 AS 内置 Gradle 或自动补全（见 4.1）。
3. **构建 APK**：菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。右下角通知出现「locate」链接即成功。
4. **产物路径**：
   ```
   app\build\outputs\apk\debug\app-debug.apk
   ```
   debug 包未混淆压缩，体积较大（含 Compose 调试符号），日常真机使用没有问题；若需 release 包（R8 压缩，体积明显更小），执行 **Build → Generate Signed App Bundle / APK** 自行配置签名（本项目未内置签名材料，release 需自建 keystore）。

### 4.3 命令行构建（备选）

wrapper 补全后（见 4.1），在项目根目录的 Android Studio Terminal 中：

```powershell
# debug 包（真机日常使用）
.\gradlew.bat assembleDebug

# 产物：app\build\outputs\apk\debug\app-debug.apk
```

### 4.4 国内网络 Gradle 加速（可选）

国内直连 `dl.google.com` / `repo.maven.apache.org` 较慢时，可编辑根目录 `settings.gradle.kts`，在两处 `repositories` 块的**最前面**插入阿里云镜像：

```kotlin
pluginManagement {
    repositories {
        // 阿里云镜像（加速，插在最前）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google { /* 原有内容不动 */ }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像（加速，插在最前）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
```

> 提示：本项目依赖均在 google / mavenCentral 仓库；镜像缺失个别构件时会自动回落到原仓库。Gradle 发行包（8.9 zip 本身）下载慢，可在 AS 设置 **Settings → Build Tools → Gradle → Distribution URL** 或编辑 `gradle/wrapper/gradle-wrapper.properties` 指向国内镜像分发地址。

### 4.5 GitHub Actions 云端构建（免装 Android Studio）

手头没有 Android Studio / Android SDK 环境时，可把仓库推到 GitHub 用云端流水线直接出 APK。本仓库已内置工作流 `.github/workflows/build-apk.yml`：

1. **建仓推送**：在 GitHub 新建仓库（公开或私有均可），把本项目推上去。仓库自带 `.gitignore`，`build/`、`.gradle/`、`local.properties`、`.codeartsdoer/` 等本地与内部文件不会被提交（若此前已提交过这类文件，`.gitignore` 不会自动移除已跟踪文件，需先 `git rm -r --cached <路径>` 再提交）。
2. **自动触发**：推送完成后，仓库 **Actions** 页会自动运行 **Build Debug APK** 工作流；也可在 **Actions → Build Debug APK → Run workflow** 手动触发。
3. **下载产物**：首次构建约 5–10 分钟（需下载 Gradle、AGP 与全部依赖），缓存生效后约 3–5 分钟。进入运行详情页，在底部 **Artifacts** 区域下载 `hw2api-debug-apk`，zip 解压得到 `app-debug.apk`。
4. **云端回归**：工作流先跑完 core 模块全部单元测试再打包；测试失败则构建中止，并可下载 `core-test-reports` 排查——相当于每次构建自带一轮回归验证。

注意事项：

- **签名差异**：CI 产出的 debug APK 使用云端生成的调试签名，与本机 Android Studio 构建的签名不一致。两者相互覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先执行 `adb uninstall com.lxseek.hw2api` 再安装。
- **额度**：私有仓库消耗 Actions 免费额度（约 2000 分钟/月，单次构建约 5 分钟）；公开仓库不占用额度。
- **网络**：构建机在海外，依赖直接走 google / mavenCentral 官方源，无需配置 [4.4](#44-国内网络-gradle-加速可选) 的镜像加速（那节只影响本机构建）。

---

## 5. 安装到手机

以 vivo X60（OriginOS）为例，其他 Android 12+ 机型路径类似：

1. **开启开发者选项**：手机 **设置 → 关于手机 → 版本信息 → 连续点击「软件版本号」7 次**（提示已进入开发者模式）。
2. **开启 USB 调试与 USB 安装**：**设置 → 系统管理 → 开发者选项**，打开：
   - 「USB 调试」；
   - 「USB 安装」（vivo 需要此开关才允许 adb 安装 APK，部分版本还要求手机联网校验）。
3. **连接电脑**：USB 线连接，手机弹「是否允许 USB 调试」→ 勾选「一律允许」→ 确定。
4. **安装**（任选其一）：
   - **Android Studio**：直接点 **Run ▶**（自动安装并启动，最省事）；
   - **adb 命令**：
     ```powershell
     C:\adb\adb.exe install -r C:\GitHub\hw2api\app\build\outputs\apk\debug\app-debug.apk
     ```
     （`-r` 覆盖安装保留数据；若 adb 不在 `C:\adb`，替换为你的 adb 路径，或使用 AS 自带 `platform-tools\adb.exe`。安装期间手机可能弹出「允许 USB 安装」确认框，请留意手机屏幕。）

安装成功后桌面出现 **HW API** 图标。

---

## 6. 使用指南

### 6.1 首次启动与登录

1. 打开 **HW API**，主界面账号卡显示「未登录」，点击 **登录华为云**。
2. App 内打开华为云 CodeArts 授权页（WebView），输入华为云账号密码并完成授权。
3. 授权成功后自动返回主界面，账号卡显示账号名与**凭证剩余倒计时**（初始约 2 小时，服务开启期间自动续期，无需关心）。

> 登录页若白屏或无法交互，见 [常见问题 8.3](#83-登录页白屏无法输入)。

### 6.2 启动服务与接入地址

1. 主界面顶部「**API 服务**」卡，打开开关 → 通知栏出现常驻服务通知（显示端口与凭证倒计时）。
2. 默认**仅本机监听**（`127.0.0.1:8788`）。要给局域网其他设备用：
   - 右上角 **设置** → 打开「**局域网接入**」开关；
   - 返回主界面，若提示「设置已变更」，点「**重启服务**」（或关闭再打开服务开关）；
   - 主界面「**接入地址**」卡会显示 `http://<手机IP>:8788/v1`（一键复制）。
3. 局域网设备与手机须在**同一 WiFi / 热点**下。

### 6.3 接口调用示例（Windows PowerShell）

以下示例经 **adb 端口转发**在本机验证（`adb forward` 把电脑 127.0.0.1:8788 转到手机，无需打开局域网开关；若已开局域网监听，把 `127.0.0.1` 换成手机 IP 即可）：

```powershell
# 0. 端口转发（电脑侧执行；手机服务已开启）
C:\adb\adb.exe forward tcp:8788 tcp:8788

# 1. 健康检查（不鉴权）
Invoke-RestMethod http://127.0.0.1:8788/health

# 2. 模型列表（启用鉴权时需带 Key，见下）
Invoke-RestMethod http://127.0.0.1:8788/v1/models

# 3. 非流式对话（PowerShell 原生方式，中文无编码问题）
$body = @'
{"model":"openpangu-2.0-pro","messages":[{"role":"user","content":"你好"}]}
'@
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8788/v1/chat/completions `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

# 4. 流式对话（SSE，用 Windows 自带 curl.exe；PowerShell 下 JSON 内层双引号需转义为 \"）
curl.exe -N -X POST http://127.0.0.1:8788/v1/chat/completions -H "Content-Type: application/json" -d '{\"model\":\"openpangu-2.0-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],\"stream\":true}'
```

说明：

- 若在 **cmd** 中执行 curl.exe，JSON 转义用标准形式 `-d "{\"model\":...}"`；**Git Bash** 中直接单引号包 JSON。
- **启用 API Key 后**所有 `/v1/*` 请求需加鉴权头。PowerShell 下：
  ```powershell
  $h = @{ Authorization = "Bearer 你的Key" }
  Invoke-RestMethod http://127.0.0.1:8788/v1/models -Headers $h
  ```
- `model` 可填 5 个上游 ID 或 5 个别名中的任意一个；`glm-5.3`（或别名 `GLM-5.3`）仅支持流式，非流式请求由 App 自动转流式再聚合。

### 6.4 第三方客户端接入

任何支持「自定义 OpenAI API 地址（Base URL）」的客户端都能用：

| 客户端类型 | 配置 |
|---|---|
| OpenAI SDK（Python 等） | `base_url = "http://<手机IP>:8788/v1"`，`api_key` 填你设置的 Key（未启用鉴权时任意非空字符串） |
| ChatBox / Cherry Studio 等桌面 App | 接口类型选 OpenAI，API 地址 `http://<手机IP>:8788/v1`，Key 同上 |
| 任意 HTTP 客户端 | 直接调 `GET /v1/models`、`POST /v1/chat/completions`（OpenAI 规范负载） |

### 6.5 设置页说明

设置项（右上角齿轮进入）：

- **监听端口**：默认 8788，合法区间 1024–65535；改动后需**重启服务**生效（页面会提示并提供「重启服务」按钮）。
- **局域网接入**：默认关闭（仅 `127.0.0.1`）；开启后监听 `0.0.0.0`，同网段设备可访问。
- **API Key + 鉴权开关**：开关开启且 Key 非空时启用鉴权；Key 留空 = 不鉴权。
- **后台保活说明**：页内附电池白名单引导文案（见 [8.6](#86-锁屏后服务被杀断流)）。

### 6.6 真机自动验证脚本

`scripts/verify_device.ps1` 提供一键式真机验证（安装 → 启动 → 登录引导 → 起服务 → 端口转发 → 依次请求 `/health`、`/v1/models`、可选 chat 冒烟），用法见 [scripts/README.txt](scripts/README.txt)：

```powershell
# 基础验证（不消耗上游配额）
powershell -ExecutionPolicy Bypass -File .\scripts\verify_device.ps1

# 完整验证（含非流式 + 流式 chat 冒烟，消耗少量上游配额）
powershell -ExecutionPolicy Bypass -File .\scripts\verify_device.ps1 -WithChat
```

---

## 7. 运行单元测试

`core` 模块含 **122 个纯 JVM 单元测试**（11 个测试文件），覆盖 PKCE、DPoP ES256 签名、AKSK SDK-HMAC-SHA256 签名、STS token 交换 / refresh 轮换、模型表、SSE 聚合、TokenRefresher 并发单飞等；签名与协议向量与桌面版 `codearts_proxy.py` 的同输入输出**逐字符对拍**（golden 向量由 Python 侧离线推导后写死在测试中，测试不依赖真网络）。

在项目根目录（wrapper 补全后）执行：

```powershell
.\gradlew.bat :core:test
```

- 测试在纯 JVM 环境运行，**不需要** Android 设备 / 模拟器 / Robolectric；
- HTML 报告：`core\build\reports\tests\test\index.html`；
- 增量重跑：`.\gradlew.bat :core:test --rerun-tasks`。

---

## 8. 常见问题

### 8.1 Gradle Sync 失败 / 依赖下载慢

- 现象：Sync 卡在 `Downloading ...` 或报 `Could not resolve ...`。
- 处理：按 [4.4](#44-国内网络-gradle-加速可选) 配置阿里云镜像；公司网络需配置 HTTP 代理（AS **Settings → Appearance & Behavior → System Settings → HTTP Proxy**）。
- 报「Gradle wrapper not found」：正常现象，见 [4.1](#41-关于-gradle-wrapper首次必读)。

### 8.2 adb 安装失败（INSTALL FAILED / 未知来源）

- vivo：确认「开发者选项 → USB 安装」已开启；安装时留意手机弹窗并确认。
- 通用：确认 USB 调试已授权当前电脑（`adb devices` 状态为 `device` 而非 `unauthorized`）。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：先卸载旧版再装（`adb uninstall com.lxseek.hw2api`）。

### 8.3 登录页白屏 / 无法输入

App 的登录 WebView 出于安全基线**默认关闭 JavaScript**（portal 授权页是前端应用，无 JS 可能白屏）。若实机出现白屏 / 无法交互：

1. 编辑 `app\src\main\java\com\lxseek\hw2api\login\LoginWebViewActivity.kt`；
2. 将伴生对象中的开关改为开启：
   ```kotlin
   private const val ENABLE_JS = true
   ```
3. 重新构建安装。开启后仍无任何 JS 桥 / 文件访问（安全边界不受影响，仅放开页面脚本执行）。

### 8.4 登录回调相关说明

与桌面版不同，本 App 的 OAuth 回调在 **WebView 内部拦截**（不真发请求、**不占用 8789 端口**、不依赖外部浏览器），因此：

- 不存在桌面版「本地 8789 回调端口被占用导致登录失败」的问题；
- 不会弹出外部浏览器 / 系统「选择打开方式」弹窗（未知 scheme 已被 App 吞掉）；
- 若授权后长时间停在「正在交换凭证」：多为手机到 STS 端点（`sts.cn-north-4.myhuaweicloud.com`）网络不通，换网络重试。

### 8.5 API 返回 401 / relogin_required

- **带 Key 的 401 invalid_api_key**：请求未带 / 带错 API Key，核对设置页的 Key 与请求头（`Authorization: Bearer <Key>` 或 `X-Api-Key: <Key>`）。
- **relogin_required**：refresh 续期链已彻底失效（约 30 天未活跃、或在别处登录踢下线等）。表现：接口 401 + 通知栏提示 + App 内红色横幅。处理：打开 App 点「重新登录」即可。
- 上游偶发 401 会由 App 自动强制续期并重试一次，无需人工干预。

### 8.6 锁屏后服务被杀 / 断流

OriginOS 省电策略激进，可能杀后台前台服务。处理（vivo 路径，不同版本略有差异）：

- **设置 → 电池 → 后台耗电管理 → HW API → 允许后台高耗电**；
- 或 **设置 → 应用 → 应用管理 → HW API → 电池 → 选择「不限制」**；
- 下拉通知栏给 HW API 服务通知**加锁**（最近任务界面上拉锁定）。

服务声明为 `START_STICKY`，被系统回收后会尝试自动拉起，但电池白名单才是根治手段。

### 8.7 局域网设备连不上

依次排查：

1. 手机「局域网接入」开关是否已开，且改动后**重启过服务**（主界面接入地址显示的应是手机 IP 而非 `127.0.0.1`）；
2. 客户端与手机是否同一网段（同一 WiFi / 手机热点直连最稳）；路由器开了「AP 隔离 / 访客网络隔离」会互 ping 不通；
3. 电脑防火墙 / 安全软件是否拦截了对 `http://<手机IP>:8788` 的访问；
4. 手机侧确认：`adb shell "dumpsys wifi | grep -i ip"` 或看 App 接入地址卡显示的 IP 是否为当前网络 IP（切换 WiFi 后 IP 会变）；
5. 快速自检：电脑 PowerShell 执行 `Test-NetConnection <手机IP> -Port 8788` 看 TcpTestSucceeded。

### 8.8 凭证过期的表现

- **正常状态**：凭证 2 小时有效，剩余不足 1 小时时主界面倒计时变红，服务内自动续期后恢复；
- **续期失败**（refresh 链失效）：通知栏「凭证已失效，请重新登录」+ App 内横幅，`/health` 返回 `status: credentials_expired`，chat 接口返回 401 `relogin_required`。

### 8.9 如何取日志排查崩溃

App 遵循零日志原则（不写日志文件、不打凭证日志），排查崩溃用系统 logcat。一键取证：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\pull_logs.ps1
```

输出到 `scripts\logs\`（进程日志 + crash 缓冲区，自动扫描 FATAL / AndroidRuntime），详见 [scripts/README.txt](scripts/README.txt)。

---

## 9. 安全说明

- **凭证加密存储**：AK / SK / securityToken / refresh_token / DPoP 私钥等敏感字段全部存于 **EncryptedSharedPreferences**（Android Keystore 托管 AES-256 主密钥，per-entry AES-GCM）；API Key 同样加密存储。
- **凭证不出设备**：无导出功能；Keystore 密钥不可迁移，换手机需重新登录。App 不写任何日志文件、无遥测上报。
- **API Key 建议开启**：开启局域网监听后，同网段任何设备都可访问你的模型额度。建议：设置页填写强 Key 并保持鉴权开关开启。
- **明文 HTTP 的边界**：本地 API 服务是明文 HTTP（OpenAI 兼容客户端惯例），但**默认仅监听本机回环 `127.0.0.1`**；打开局域网监听属于用户显式操作。App 自身作为客户端的所有外联（portal / STS / 上游 chat）**全部 HTTPS**（network security config 全局禁明文外发）。
- **登录 WebView 安全基线**：无 JS 桥（`addJavascriptInterface`）、file / content access 全关，仅拦截 OAuth 回调；JavaScript 默认关闭（见 [8.3](#83-登录页白屏无法输入)）。
- **使用建议**：不要在不可信公共 WiFi 下打开局域网监听；长期不用时关闭服务开关。

---

## 10. 已知限制

1. **不支持 chunked 请求体**：本地 server 按 `Content-Length` 读取请求体，`Transfer-Encoding: chunked` 的请求无法处理。curl（默认带 content-length）、OkHttp、各 OpenAI SDK 均不受影响；个别手写 `Transfer-Encoding: chunked` 的客户端需调整。
2. **`/health` 字段与桌面版不同**。hw2api 返回：
   ```json
   {
     "status": "ok | no_account | credentials_expired",
     "account": "账号标签",
     "credentials_expire_at": 1791234567890,
     "credentials_expire_at_iso": "2026-10-16T07:27:35.696Z",
     "expires_in_seconds": 4321,
     "upstream": { "last_call_ok": true, "last_call_at": 1791230000000, "last_error": null },
     "listen": "0.0.0.0:8788",
     "auth_enabled": false
   }
   ```
   桌面版的 `ok` / `mode` / `accounts[]` 字段不在此提供，依赖桌面版 `/health` 结构的脚本需适配。
3. **单账号 UI**：数据层是多账号结构（`account_{id}` 条目 + active 切换），但 UI 只展示当前账号，无账号池管理界面；「退出」即删除当前账号凭证。
4. **无模型同步**：模型表写死于 `core` 的 `ModelRegistry`（5 模型 + 5 别名）。上游新增 / 下架模型需改代码重新构建。
5. **无凭证导出 / 导入 / 迁移**：安全取舍（见第 9 章）。
6. **不支持 Anthropic 协议与 `/v1/responses`**：仅 OpenAI 兼容三端点（`/v1/models`、`/v1/chat/completions`、`/health`）。
7. **请求体上限 8MB**（超限返回 413）；SSE 并发长连接受线程池上限保护（64 并发 + 64 排队，局域网自用规模足够）。
8. **构建未在本机验证**：如第 4 章开头所述，本仓库在无 JDK / Android SDK 环境产出，首次构建如遇问题优先按 [8.1](#81-gradle-sync-失败--依赖下载慢) 排查 Sync 环节。

---

*hw2api · 华为云 CodeArts OpenAI 兼容 API 手机网关 · Kotlin + Compose 实现*