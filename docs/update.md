# GitHub CI/CD 与外屏 OTA 更新

## 当前实现

源代码和打包完全由 GitHub Actions 执行。PR / main 触发无密钥构建测试；
创建版本 Tag（例如 `v1.1.0`）后使用项目原有签名密钥签名并上传正式 Release。

正式 Release 资产：
- `codex-cover.apk`：外屏 Android 客户端
- `termux-scripts.zip`：Termux 脚本，除设备特有 `codex` 包装器外可更新
- `manifest.json`：版本、最低 Android SDK、文件大小和 SHA-256
- `SHA256SUMS`：二次人工检查用的校验和

公开仓库后，客户端通过 `https://github.com/to-o/codex-cover-remote/releases/latest/download/manifest.json` 读取公开 Release 版本清单，无需 PAT，不使用有匿名频率限制的 GitHub REST API。
**仓库仍为私有时，未认证的外屏客户端会得到 404；这不是升级程序错误。**

## 首次配置（维护者）

1. 公开前审查**全部提交历史、GitHub Actions 日志、Release 资产**，确认无账号、令牌、设备个人数据、签名密钥。
2. 仓库 Settings → Secrets and variables → Actions 新增：
   - `APK_KEYSTORE_BASE64`：当前 APK 使用的 PKCS#12 签名密钥内容 Base64（无换行）
   - `APK_KEYSTORE_PASSWORD`：当前密钥库密码
   - `APK_KEY_ALIAS`：原有别名（通常为 `codex-cover`）
3. 只允许可信维护者创建 `v*` Tag，建议启用受保护 Tag 的 ruleset。
4. 首次发布前比较旧 APK 与 CI 产物签名证书；使用不同签名会导致 Android 覆盖安装失败。
5. 将当前仓库公开后，GitHub Releases API 和公开 APK 下载地址才可匿名访问。

示例：从本地密钥生成 Secrets 所需的 Base64（**不要粘贴到 Issue、聊天或日志中**）：

```sh
base64 -w0 /private/codex-cover.p12 | gh secret set APK_KEYSTORE_BASE64
gh secret set APK_KEYSTORE_PASSWORD
gh secret set APK_KEY_ALIAS --body codex-cover
```

上面的密码建议在交互提示中输入，避免出现在命令历史。

## 首次引导 Termux 脚本升级器

新版客户端可以检测/安装 APK，但 Termux 脚本更新需要升级助手**先安装一次**：

```sh
install -m 700 termux/bin/codex-release-update.py "$HOME/bin/codex-release-update.py"
```

之后在外屏客户端的「检查更新」中选择「更新 Termux 脚本」，即可从公开 Release 下载、校验、替换脚本。
脚本升级程序不会修改 Codex 登录、配置、PRoot 容器或 `$PREFIX/bin/codex` 设备定制包装器；
运行中的远程服务和语音任务不会被强制杀死。请在任务结束后重启服务。
对于首次安装，应完整按照原 README 安装所需依赖和 Termux 脚本。

## 发布流程

1. 将版本号写入 `android/AndroidManifest.xml` 中的 `android:versionCode`（严格递增）和 `versionName`。
2. 创建 PR，待 Build and test 成功，合并到 `main`。
3. 在 `main` 的对应提交创建并推送 Tag `v<versionName>`。
4. Release workflow 对 Tag/manifest 一致性、Python 和 APK 构建进行验证。
5. 签名并发布正式 GitHub Release。发布之前不影响设备。
6. 外屏客户端检查最新 Release，下载 APK 后验证 SHA-256、包名、版本和签名，再通过 Android 系统安装器安装。

正式 APK 默认**需要用户在外屏确认安装**。升级程序不会自动 Root 静默安装；
这样可以降低外屏是唯一可用显示器时升级失败导致不可操作的风险。

Android 平台需要用户首次为「Codex 远程」授予「允许安装未知应用」权限。
在未授权前，客户端会进入系统设置，请授权后再点击检查更新并安装。

## 更新/回滚约束

- 已签名 APK 的 `versionCode` 必须大于已安装版本，否则 Android 通常拒绝正常升级。
- Termux 脚本会先验证 ZIP 的 SHA-256、大小、路径、重复项和符号链接，再替换；文件写入中出错会恢复本次已替换文件。
- 目前脚本升级成功后没有持久的「一键回滚到上一版本」入口，且不能保证跨版本脚本兼容；升级前保留备份或通过历史 Tag 手工恢复。
- 不自动更新 PRoot 内 Codex CLI，避免实验性 `thread/realtime/start` 兼容性变化。
- 首次切换签名或外屏安装器不可操作时，需要 ADB/Termux 手动恢复；建议保留本机 ADB 连接。

## 公开仓库与运行数据

仓库仅包含通用源码。不要提交 `auth.json`、GitHub Token、签名密钥、APK 私钥、配对信息或 `~/.termux/` 中的状态/日志。公开项目仅供用户用**自己的** ChatGPT/Codex 账号登录；实验性订阅语音功能不承诺所有账号可用。
