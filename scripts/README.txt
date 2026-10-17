hw2api 真机脚本说明（scripts/README.txt）
==========================================

本目录包含 2 个 PowerShell 脚本，用于在电脑侧对手机上的 hw2api 做
真机验证与日志取证。两个脚本均为纯 adb + HTTP 操作，不涉及任何
编译/构建命令。

前置条件（两个脚本通用）
------------------------
1. APK 已按仓库根 README.md 第 4 章构建完成（默认路径
   app\build\outputs\apk\debug\app-debug.apk）；
2. adb 位于 C:\adb\adb.exe（不在 PATH 中，脚本已按此写死；
   位置不同请修改脚本头部的 $adb 变量）；
3. 手机已开启「开发者选项 → USB 调试」并授权当前电脑；
4. vivo X60 序列号默认为 9587839428004US（不同设备请用 -Serial
   参数传入，或修改脚本头部 $Serial 默认值）。

脚本一：verify_device.ps1（真机验证）
-------------------------------------
分 10 步验证 App 安装、登录、服务启动与三个 API 端点，每步打印
[PASS]/[FAIL] 与中文说明；有失败项时退出码为 1，全过为 0。

步骤概览：
  1.  adb devices 确认设备在线（未授权/离线会给出中文提示）
  2.  adb install -r 安装 APK（不存在则提示先构建）
  3.  am start 启动 MainActivity
  4.  提示手动完成华为云登录（等回车继续）
  5.  am start-foreground-service 启动前台服务
      （Android 12 可能拒绝 adb 直启非导出服务，脚本会自动
       引导在 App 内手动打开服务开关，再复核服务状态）
  6.  adb forward tcp:8788 tcp:8788 端口转发
  7.  GET /health 健康检查（校验 status=ok）
  8.  GET /v1/models 模型列表非空（应返回 10 个条目）
  9.  POST /v1/chat/completions 非流式冒烟   ┐ 仅 -WithChat 时
  10. POST /v1/chat/completions 流式 SSE 冒烟 ┘ 执行（消耗上游配额）

用法示例（在本目录打开 PowerShell）：

  基础验证（不消耗上游配额）：
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1

  完整验证（含 chat 冒烟）：
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1 -WithChat

  自定义参数：
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1 `
        -WithChat -ApkPath C:\apk\app-debug.apk `
        -Serial 9587839428004US -Port 8788 -ApiKey "你的Key"

参数说明：
  -ApkPath  APK 路径，默认 ..\app\build\outputs\apk\debug\app-debug.apk
  -Serial   设备序列号，默认 9587839428004US
  -Port     App 监听端口，默认 8788（须与 App 设置页一致）
  -ApiKey   App 设置页配置的 API Key（启用鉴权时必须传入）
  -WithChat 开关，执行第 9/10 步 chat 冒烟（消耗少量上游配额）

说明：
- 脚本经 adb 端口转发访问 127.0.0.1:8788，因此 App 无需打开
  「局域网接入」开关（默认仅本机监听即可完成全部验证）；
- 脚本结束会自动清理端口转发（adb forward --remove）；
- 第 4 步登录是人工步骤：脚本会暂停等回车，请在手机上完成
  登录后再按回车继续。

脚本二：pull_logs.ps1（日志取证）
---------------------------------
真机验证失败 / App 闪退时取证用。拉取 hw2api 相关 logcat 并筛查
崩溃，输出到本目录 logs\ 子目录（文件名带时间戳）。

行为：
  1. App 进程在运行 → logcat -d --pid=<pid> 精确拉取进程日志；
  2. App 进程不在（被杀/崩溃退出）→ 全量 logcat 按包名
     com.lxseek.hw2api 过滤；
  3. 另存 crash 缓冲区（logcat -d -b crash）；
  4. 扫描 FATAL EXCEPTION / AndroidRuntime 关键字并打印摘要。

退出码：0 = 无崩溃迹象；1 = 执行错误（设备不在线/adb 缺失）；
        2 = 检测到崩溃记录。

用法示例：

    powershell -ExecutionPolicy Bypass -File .\pull_logs.ps1

    powershell -ExecutionPolicy Bypass -File .\pull_logs.ps1 -Serial <序列号>

输出文件：
  logs\hw2api_proc_<时间戳>.log      进程日志（进程在跑时）
  logs\hw2api_filtered_<时间戳>.log  包名过滤日志（进程不在时）
  logs\hw2api_crash_<时间戳>.log     崩溃缓冲区日志

说明：App 遵循零日志原则（不主动写日志、不打凭证日志），logcat
中相关内容以系统消息与崩溃栈为主；排查闪退请重点看
hw2api_crash_*.log 中的 FATAL EXCEPTION 段（含完整调用栈）。

其他
----
- 两个脚本均要求 Windows PowerShell 5.1+（Win10/11 自带），
  文件已保存为带 BOM 的 UTF-8，中文输出不会乱码；
- 脚本不修改手机数据（除安装 APK 外），端口转发结束即清理；
- 若 PowerShell 执行策略受限，务必按示例带上
  -ExecutionPolicy Bypass。