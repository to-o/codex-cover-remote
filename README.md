# Codex 外屏远程控制

在 Samsung Galaxy Z Flip 的外屏上开启、关闭及检查 Termux 中的 Codex 远程服务。界面显示本地服务状态、Codex 连接状态码、状态变更时间和上次确认时间。异常时可以检查 FlClash 代理和 OpenAI 接口，也可以让当前进程重试连接。

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
