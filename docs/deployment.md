# 构建、安装与维护

## 环境

当前部署设备为 Samsung SM-F7310，Android API 36。APK 包名为 `local.codex.cover`，版本为 `1.0.0-live`，最低 API 34。

手机端需要 Termux、Python 3、tmux、flock、PRoot-Distro，以及名为 `codexdebian` 的 Debian 容器。Codex 包装器默认指向容器内 `/opt/codex/aarch64-unknown-linux-musl/bin/codex`。使用其他路径时先调整包装器。

语音和原远程服务共用现有 Codex 登录。先在容器中执行 `codex login` 完成登录。代理默认位于 `127.0.0.1:7890`；改变代理端口时同步检查远程主机、健康检查和语音桥的配置。

## 安装 Termux 文件

在 Termux 中从项目目录执行以下命令。安装到已有环境前，先备份将要替换的同名文件。

```sh
mkdir -p "$HOME/bin"
cp termux/bin/codex-remote-* "$HOME/bin/"
cp termux/bin/codex-voice-start termux/bin/codex-voice-bridge.py \
   termux/bin/codex-voice.html "$HOME/bin/"
chmod 700 "$HOME"/bin/codex-remote-* "$HOME/bin/codex-voice-start" \
          "$HOME/bin/codex-voice-bridge.py"
chmod 600 "$HOME/bin/codex-voice.html"
```

确认容器名和路径后，安装 Codex 包装器：

```sh
cp termux/bin/codex "$PREFIX/bin/codex"
chmod 700 "$PREFIX/bin/codex"
```

在 `~/.termux/termux.properties` 中启用 `allow-external-apps = true`，然后执行 `termux-reload-settings`。语音桥由应用按需启动，不需要加入开机脚本。原远程服务的开机/守护入口使用 `codex-remote-switch ensure`，尊重手动关闭标记。

## Linux 或手机本地构建

需要 JDK 21、aapt、zipalign、apksigner、Android API 34 的 `android.jar` 和 R8/D8 JAR。Debian 构建工具可通过以下方式安装：

```sh
apt-get install --no-install-recommends openjdk-21-jdk-headless aapt zipalign apksigner
```

准备位于仓库外的签名密钥及密码文件，执行：

```sh
python3 build-linux.py \
  --android-jar /path/to/android.jar \
  --r8-jar /path/to/r8.jar \
  --keystore /private/codex-cover.p12 \
  --password-file /private/codex-cover.password
```

产物为 `out/codex-cover.apk`。构建器使用临时目录编译，避免带入旧 class。密码通过子进程环境传给签名工具，不作为命令行参数。`--debug` 仅用于本机 WebView 检查，正式发布时省略。

## Windows 构建

需要 PowerShell 7.4、JDK 17、Android SDK Platform 34 和 Build Tools 34：

```powershell
pwsh ./build.ps1 -JdkBin 'C:/jdk-17/bin' `
  -AndroidJar 'C:/Android/Sdk/platforms/android-34/android.jar' `
  -BuildTools 'C:/Android/Sdk/build-tools/34.0.0' `
  -Keystore 'C:/private/codex-cover.p12'
```

提供密钥时生成签名 APK；省略密钥时生成 `out/codex-cover-unsigned.apk`。Windows 构建流程本次仅更新了源码文件发现和清理逻辑，未在 Windows 机器实测。

## 安装 APK

相同签名可直接升级：

```sh
adb -s 127.0.0.1:5555 install -r out/codex-cover.apk
```

如果更换签名，先备份旧 APK 和需要保留的应用数据，再卸载旧包后安装。卸载 `local.codex.cover` 不会删除 Termux 中的 Codex 登录和任务。当前设备的重签名迁移已完成，后续继续使用本机保存的新密钥即可直接升级。

打开应用并允许 Termux 命令权限；首次进入实时语音时允许麦克风权限。在三星 MultiStar 的应用启动器中添加「Codex 远程」。

## 日常使用与排查

- 启停、连接诊断和重试使用控制页原有按钮。
- 「实时语音 → 开始语音」连接 Live；「关闭麦克风」暂时静音，「结束」关闭通话。
- 若显示播放按钮，点击它允许音频播放，并检查手机媒体音量。
- 启动失败时，确认 Termux 可运行、外部命令开关已启用、脚本已复制并具有执行权限。
- 连接失败时，检查代理、网络、Codex 登录和版本。升级 Codex 后应重新验证 V3 WebRTC 协议。
- 语音启动日志位于 `~/.termux/codex-voice-bridge.launcher.log`，app-server 错误日志位于 `~/.termux/codex-voice-bridge.stderr.log`。分享日志前检查其中是否含有私人任务信息。
- 正在通话或执行任务时，先结束通话/等待任务完成再重启语音桥。替换磁盘上的 Python 文件不会热更新运行中的进程。

签名密钥、密码、登录资料、配对数据和运行状态应仅保存在本机，不提交仓库。恢复旧版本时需同时考虑 APK 签名和桥接脚本版本。
