# Codex 外屏远程控制

在 Samsung Galaxy Z Flip 的外屏上开启、关闭及检查 Termux 中的 Codex 远程服务。界面显示本地服务状态、Codex 连接状态码、状态变更时间和上次确认时间。异常时可以检查 FlClash 代理和 OpenAI 接口，也可以让当前进程重试连接。

代码开发版本：`1.1.1`（正式发布需创建对应 Tag；手机当前已安装版本不一定同步）。完整说明见 [构建与部署](docs/deployment.md)、[架构与接口](docs/architecture.md) 和 [实机验证记录](docs/validation.md)。

## GitHub 自动构建与外屏 OTA

项目支持 GitHub Actions 无密钥编译测试，并通过版本 Tag 签名发布 GitHub Releases。检查更新直接读取公开 Release 清单，不受 GitHub REST API 匿名请求的 60 次/小时限额影响。外屏主界面新增「检查更新」按钮，可以读取公开 Release、验证 APK 的 SHA-256 和签名后使用 Android 安装器升级；Termux 脚本可以通过一次性安装的升级助手单独更新，不覆盖用户的 Codex 登录或设备专用包装器。

完整说明：**[GitHub CI/CD 与 OTA 更新](docs/update.md)**。

注意：仓库需先改为 **Public** 并发布正式 Release，客户端才能匿名检查更新。APK 签名需要配置与已安装版本一致的密钥；不会将密钥提交仓库。

## 外屏实时语音

控制页的「实时语音」打开独立语音页面。允许麦克风和 Termux 命令权限后，点击「开始语音」即可连续交谈、听取回复和查看字幕。支持麦克风开关、结束语音和任务权限确认；离开语音页面会停止录音。返回页面后可以继续当前语音任务。

语音通过 Codex app-server 的实验性 `thread/realtime/start`、V3 和 WebRTC 使用当前 Codex 登录。本设备的 Codex 0.165.0 / ChatGPT Plus 登录已通过 SDP、双向音频、字幕和麦克风释放的实机验证。账号和服务端开放情况仍可能不同；默认 WebSocket 语音路径要求 API key，不能替代这里的 WebRTC 路径。

安装 `termux/bin/codex-voice-start`、`codex-voice-bridge.py`、`codex-voice.html` 到 Termux 的 `~/bin/`。脚本设为可执行，HTML 仅需可读。语音桥只监听随机的 `127.0.0.1` 端口，每次启动生成本地访问令牌，经 Termux 回调交给应用。令牌不放入 APK 或仓库。桥接服务在五分钟无请求且没有执行中的任务时退出，失去页面心跳三十秒后关闭语音。麦克风音频使用 WebRTC 直接传到语音服务。

第一版会新建一个名为「外屏实时语音」的 Codex 任务，工作目录为 `/root/android`，使用本设备所需的完整文件访问及 `on-request` 审批策略。它不接管原来远程服务正在执行的任务。语音使用实验接口，升级 Codex 后应重新验证协议。

Android 最低版本统一为 API 34。Linux/本机构建可安装 JDK 21、aapt、zipalign、apksigner，然后执行：

```sh
python3 build-linux.py --android-jar /path/android.jar --r8-jar /path/r8.jar \
  --keystore /private/codex-cover.p12 --password-file /private/password
```

密钥和密码文件应保存在仓库外并仅允许当前用户读取。更换签名需要卸载旧 APK 后重装；此操作不影响 Termux 中的登录资料。

## 源码和构建

`android/` 保存 Android Java 源码、清单和图标。`termux/` 保存开关、代理诊断和后台服务脚本。Windows 端构建需要 PowerShell 7.4、JDK 17、Android SDK Platform 34 和 Build Tools 34。

```powershell
pwsh ./build.ps1 -JdkBin 'C:/jdk-17/bin' -AndroidJar 'C:/Android/Sdk/platforms/android-34/android.jar' -BuildTools 'C:/Android/Sdk/build-tools/34.0.0'
```

默认产物是 `out/codex-cover-unsigned.apk`。传入 `-Keystore <路径>` 可以签名，`-Alias` 默认是 `codex-cover`。升级手机上的现有应用时，使用原来的签名密钥。应用包名是 `local.codex.cover`。

## 手机端配置

1. 在 Termux 中准备 Python、tmux、flock 和 PRoot-Distro，并在 `codexdebian` Debian 容器里安装 Codex CLI、登录 ChatGPT。
2. 把 `termux/bin/codex-remote-*` 放到 Termux 的 `~/bin/`，赋予执行权限。在安装脚本前备份已有配置。`termux/bin/codex` 是设备使用的包装脚本。按容器名和 Codex 安装路径调整后放到 Termux 的 `$PREFIX/bin`。它会将远程服务指定的代理传给 Debian。
3. 手机上的 FlClash 本机代理为 `127.0.0.1:7890`。如果端口不同，同步调整 `codex-remote-host.py` 和 `codex-remote-health.py`。
4. 在 Termux 的 `~/.termux/termux.properties` 启用 `allow-external-apps = true`，再运行 `termux-reload-settings`。安装已签名的 APK，授予应用 Termux 的 `RUN_COMMAND` 权限，然后在 MultiStar 的「应用启动器」里添加「Codex 远程」。
5. 如果使用开机启动或守护任务，让它调用 `~/bin/codex-remote-switch ensure`，也可以使用 `termux/start-codex-remote.sh` 作为入口。`ensure` 会尊重手动关闭标记。

```sh
~/bin/codex-remote-switch on
~/bin/codex-remote-switch status-json
~/bin/codex-remote-switch diagnose
~/bin/codex-remote-switch retry
~/bin/codex-remote-switch off
```

连接检查通过代理发出无凭据探测。接口返回 HTTP 401 表示该接口可达，实际连接状态由 Codex 服务单独报告。登录资料、配对数据、运行日志、APK 和签名密钥由本地保管。
