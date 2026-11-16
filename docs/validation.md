# 实机验证记录

验证日期：2026-11-16。

| 项目 | 环境或结果 |
| --- | --- |
| 设备 | Samsung SM-F7310，Android API 36，外屏 748 × 720 |
| Codex | CLI 0.165.0，PRoot Debian 容器 |
| 登录 | 已有 ChatGPT Plus 登录 |
| 应用 | `local.codex.cover`，`1.0.0-live`，最低/目标 API 34 |
| 实时协议 | app-server 实验 API，V3，WebRTC |

## 已验证

- `account/read` 识别现有订阅登录；查询过程中未输出登录凭据。
- 默认 WebSocket 语音路径返回 `realtime conversation requires API key auth`。
- V3 WebRTC 路径使用现有订阅登录取得 SDP answer。
- Termux 语音桥通过本机页面加载、令牌校验和跨 Origin 请求拒绝检查。
- 外屏应用能通过 Termux 命令回调启动语音页。
- 实际通话中收到用户语音字幕、Codex 回复字幕和任务状态。
- WebRTC 统计显示双向音频：一次取样中发送音频 325,673 字节、5,019 个包，接收音频 446,693 字节、4,793 个包。
- 结束通话后确认麦克风轨道已释放，WebRTC peer 已关闭。
- 合并 APK 的正式构建通过签名校验，安装后未启用 `DEBUGGABLE`。
- 原远程服务在应用迁移前后均保持 `running / connected`。
- JavaScript 语法检查、Python 编译检查和 `git diff --check` 通过。

## 未覆盖

其他设备或账号、Windows 构建实测、长时间后台运行、锁屏持续语音、蓝牙音频路由，以及所有任务交互类型均未完整验证。本记录不代表其他订阅账号一定具备相同服务端权限。

后续升级重点复查：账号登录、V3 WebRTC 建连、双向音频、字幕、结束/离开页面后的麦克风释放，以及原远程服务是否仍正常。
