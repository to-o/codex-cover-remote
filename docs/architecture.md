# 架构与接口

## 组件

| 文件 | 作用 |
| --- | --- |
| `android/src/local/codex/cover/MainActivity.java` | 原生外屏控制页：启停、查询、诊断、重试和语音入口 |
| `android/src/local/codex/cover/VoiceActivity.java` | 请求麦克风和 Termux 权限，启动语音桥，用 WebView 加载语音页面 |
| `android/res/xml/network_security_config.xml` | 仅对本机 `127.0.0.1` 允许明文 HTTP |
| `termux/bin/codex-remote-switch` | 用文件锁串行处理远程服务操作，维护手动关闭标记 |
| `termux/bin/codex-remote-loop` | 在独立 tmux 会话中运行远程服务进程并轮换日志 |
| `termux/bin/codex-remote-host.py` | 驱动原远程服务的 app-server，发布连接状态 |
| `termux/bin/codex-remote-health.py` | 检查本机代理、接口可达性和登录状态 |
| `termux/bin/codex` | 在 Debian 容器中执行 Codex，并传入语音/远程服务所需代理 |
| `termux/bin/codex-voice-start` | Android 应用通过 Termux 调用的语音启动入口 |
| `termux/bin/codex-voice-bridge.py` | 本机 HTTP 服务、app-server JSON-RPC、SDP、事件和权限确认 |
| `termux/bin/codex-voice.html` | WebRTC 音频、字幕、开始/结束、静音和权限确认页面 |

## 语音连接过程

1. 用户进入实时语音页，Android 请求 `RECORD_AUDIO` 和 Termux `RUN_COMMAND` 权限。
2. `VoiceActivity` 用 Termux 后台命令执行 `codex-voice-start`。
3. 启动器检查已有桥接服务；必要时启动一个新进程，监听随机的本机端口。
4. 桥接服务生成随机访问令牌，以 Termux 私有状态文件保存地址和令牌，通过命令回调传给 Android。应用不读取 Codex 登录凭据。
5. WebView 加载本机页面。用户按「开始语音」后，页面取得麦克风音轨，创建 WebRTC offer，并把 SDP 交给桥接服务。
6. 桥接服务创建或继续本进程拥有的语音任务，调用 `thread/realtime/start`，使用 `version: v3`、`outputModality: audio` 和 `transport.type: webrtc`。
7. app-server 使用已有 Codex 登录创建语音连接，桥接服务返回 `thread/realtime/sdp` 中的 answer。
8. 页面设置 answer 后直接通过 WebRTC 收发音频，桥接服务转发字幕、任务状态和权限请求。

## 本机 HTTP 接口

`GET /` 只返回静态页面。下面的 POST 请求必须带本次启动生成的 `Authorization: Bearer ...`，并满足本机 Host 和 Origin 校验。请求体最大为 64 KiB。

| 路径 | 请求 | 响应/行为 |
| --- | --- | --- |
| `/api/health` | `{}` | app-server 进程是否仍存活 |
| `/api/start` | `{"sdp":"..."}` | 创建语音连接，返回 answer SDP |
| `/api/stop` | `{}` | 停止语音，拒绝尚未处理的权限请求 |
| `/api/events` | `{"since":0}` | 返回有序事件、最新游标和语音活动状态 |
| `/api/approval` | `{"id":"...","allow":false}` | 对对应的待处理请求作出选择 |

事件缓冲最多保留 256 条。页面使用游标轮询；音频不经过这个轮询接口。

## 会话和进程生命周期

- 原远程服务和语音桥使用独立 app-server 进程。语音桥创建名为「外屏实时语音」的新任务，不接管另一个进程正在执行的任务。
- 同一桥接进程内，再次开始语音会继续它已创建的任务。进程退出后再次启动会创建新语音任务；本版没有跨进程自动续接或任务选择器。
- 离开 Android 语音页时请求停止语音，释放页面的麦克风音轨和 WebRTC 连接。
- 桥接服务失去心跳超过 30 秒后停止语音；超过五分钟没有请求、且没有执行中的任务时退出。
- 语音任务使用 `/root/android` 作为工作目录，采用本机 PRoot 所需的完整访问和 `on-request` 审批策略。命令、文件修改、额外权限请求可在页面确认；其他交互类型暂不支持。
- 本机状态文件、令牌和日志位于 Termux 的 `~/.termux/` 中，默认仅当前用户可读写。

## 已知边界

实时语音依赖 Codex 的实验接口和账号服务端权限。已验证的组合见 [实机验证](validation.md)。正常路径使用订阅登录；默认 WebSocket 语音路径要求 API key，不能作为订阅版 WebRTC 的直接替换。

页面不提供后台持续录音、锁屏语音、蓝牙耳机专项适配或跨进程恢复。遇到未支持的任务交互，应在完整 Codex 客户端继续处理。
