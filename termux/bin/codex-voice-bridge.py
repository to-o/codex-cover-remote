#!/data/data/com.termux/files/usr/bin/python3
"""Local authenticated bridge for the cover-screen WebRTC voice client."""
import collections
import fcntl
import http.server
import json
import os
import pathlib
import queue
import secrets
import signal
import subprocess
import sys
import threading
import time
import urllib.request

os.umask(0o077)
STATE = pathlib.Path.home() / '.termux' / 'codex-voice-bridge.json'
ASSET = pathlib.Path(__file__).with_name('codex-voice.html')
PREFIX = os.environ.get('PREFIX', '/data/data/com.termux/files/usr')


class Voice:
    def __init__(self):
        self.lock = threading.RLock()
        self.operation = threading.Lock()
        self.pending = {}
        self.serial = 0
        self.events = collections.deque(maxlen=256)
        self.sequence = 0
        self.thread_id = None
        self.start_result = queue.Queue()
        self.active = False
        self.working = False
        self.last_seen = time.monotonic()
        self.requests = {}
        env = dict(os.environ, CODEX_FLCLASH_PROXY='http://127.0.0.1:7890')
        self.log = open(STATE.with_suffix('.stderr.log'), 'a')
        self.proc = subprocess.Popen([PREFIX + '/bin/codex', '--no-daemon', 'app-server', '--stdio'],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=self.log, text=True, env=env)
        threading.Thread(target=self.read, daemon=True).start()
        self.rpc('initialize', {'clientInfo': {'name': 'codex_cover_voice', 'version': '1.0.0'},
                               'capabilities': {'experimentalApi': True}})
        self.write({'method': 'initialized', 'params': {}})

    def write(self, value):
        with self.lock:
            self.proc.stdin.write(json.dumps(value) + '\n')
            self.proc.stdin.flush()

    def rpc(self, method, params, timeout=45):
        with self.lock:
            self.serial += 1
            ident = self.serial
            response = queue.Queue(maxsize=1)
            self.pending[ident] = response
        try:
            self.write({'id': ident, 'method': method, 'params': params})
            msg = response.get(timeout=timeout)
            if 'error' in msg:
                raise RuntimeError(msg['error'].get('message', 'Codex 请求失败'))
            return msg.get('result', {})
        finally:
            with self.lock:
                self.pending.pop(ident, None)

    def publish(self, kind, **fields):
        with self.lock:
            self.sequence += 1
            self.events.append(dict(seq=self.sequence, kind=kind, **fields))

    def read(self):
        try:
            for line in self.proc.stdout:
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if 'id' in msg and 'method' not in msg:
                    with self.lock:
                        response = self.pending.get(msg['id'])
                    if response:
                        response.put(msg)
                    continue
                method, params = msg.get('method', ''), msg.get('params', {})
                if 'id' in msg and method:
                    if method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
                                  'item/permissions/requestApproval'):
                        with self.lock:
                            self.requests[str(msg['id'])] = msg
                        self.publish('approval', id=str(msg['id']), method=method,
                                     text=params.get('command') or params.get('reason') or '任务请求额外权限')
                    else:
                        self.write({'id': msg['id'], 'error': {'code': -32601,
                                   'message': '此语音客户端暂不支持该交互，请在完整客户端处理。'}})
                    continue
                if method == 'thread/realtime/sdp':
                    self.start_result.put(('sdp', params['sdp']))
                elif method == 'thread/realtime/error':
                    self.active = False
                    self.start_result.put(('error', params.get('message', '语音连接失败')))
                    self.publish('error', text=params.get('message', '语音连接失败'))
                elif method == 'thread/realtime/closed':
                    self.active = False
                    self.start_result.put(('error', '语音连接已结束'))
                    self.publish('closed', text='语音连接已结束')
                elif method == 'thread/realtime/transcript/delta':
                    self.publish('transcript', role=params.get('role', ''), delta=params.get('delta', ''))
                elif method == 'thread/realtime/transcript/done':
                    self.publish('transcriptDone', role=params.get('role', ''), text=params.get('text', ''))
                elif method == 'item/agentMessage/delta':
                    self.publish('task', delta=params.get('delta', ''))
                elif method == 'turn/started':
                    self.working = True
                    self.publish('status', text='Codex 正在处理任务')
                elif method == 'turn/completed':
                    self.working = False
                    self.publish('status', text='任务已结束，可以继续说话')
        finally:
            self.active = False
            self.start_result.put(('error', 'Codex 进程已退出'))
            self.publish('error', text='Codex 进程已退出，请返回后重新打开语音')
            with self.lock:
                for response in self.pending.values():
                    try:
                        response.put_nowait({'error': {'message': 'Codex 进程已退出'}})
                    except queue.Full:
                        pass

    def start(self, sdp):
        with self.operation:
            if self.active:
                raise RuntimeError('已有语音连接，请先结束')
            if not self.thread_id:
                result = self.rpc('thread/start', {
                    'cwd': '/root/android', 'sandbox': 'danger-full-access',
                    'approvalPolicy': 'on-request',
                    'developerInstructions': '通过手机外屏与用户实时语音交流，默认使用中文。简短说明进展，遵循用户授权和 AGENTS.md。'})
                self.thread_id = result['thread']['id']
                self.rpc('thread/name/set', {'threadId': self.thread_id, 'name': '外屏实时语音'})
            while not self.start_result.empty():
                self.start_result.get_nowait()
            self.active = True
            try:
                self.rpc('thread/realtime/start', {'threadId': self.thread_id,
                    'version': 'v3', 'outputModality': 'audio',
                    'transport': {'type': 'webrtc', 'sdp': sdp},
                    'prompt': '默认用中文自然交流。回答简短。执行任务前确认理解用户意图，及时说明进展。'})
                kind, value = self.start_result.get(timeout=45)
                if kind != 'sdp':
                    raise RuntimeError(value)
                return {'sdp': value}
            except Exception:
                self.stop()
                raise

    def stop(self):
        if self.thread_id:
            try:
                self.rpc('thread/realtime/stop', {'threadId': self.thread_id}, timeout=8)
            except Exception:
                pass
        self.active = False
        with self.lock:
            requests = list(self.requests)
        for ident in requests:
            self.approve(ident, False)

    def approve(self, ident, allow):
        with self.lock:
            msg = self.requests.pop(str(ident), None)
        if msg is None:
            raise RuntimeError('这个权限请求已失效')
        if msg['method'] == 'item/permissions/requestApproval':
            result = {'permissions': msg['params'].get('permissions', {}) if allow else {}, 'scope': 'turn'}
        else:
            result = {'decision': 'accept' if allow else 'decline'}
        self.write({'id': msg['id'], 'result': result})

    def close(self):
        self.stop()
        if self.proc.poll() is None:
            self.proc.stdin.close()
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
        self.log.close()


def serve():
    token = secrets.token_urlsafe(32)
    voice = Voice()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, value, code=200, kind='application/json; charset=utf-8'):
            data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def authorized(self):
            origin = self.headers.get('Origin')
            expected = 'http://127.0.0.1:' + str(self.server.server_port)
            return (self.headers.get('Host') == expected.removeprefix('http://') and
                    origin in (None, expected) and
                    secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token))

        def do_GET(self):
            if self.path == '/' and self.headers.get('Host') == '127.0.0.1:' + str(self.server.server_port):
                self.reply(ASSET.read_bytes(), kind='text/html; charset=utf-8')
            else:
                self.reply({'error': 'Not found'}, 404)

        def do_POST(self):
            if not self.authorized():
                self.reply({'error': 'Unauthorized'}, 403)
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 65536:
                    self.reply({'error': 'Invalid request size'}, 413)
                    return
                value = json.loads(self.rfile.read(length))
                voice.last_seen = time.monotonic()
                if self.path == '/api/health':
                    result = {'ok': voice.proc.poll() is None}
                elif self.path == '/api/start':
                    sdp = value.get('sdp')
                    if not isinstance(sdp, str) or not sdp.startswith('v=0'):
                        raise ValueError('无效的语音连接请求')
                    result = voice.start(sdp)
                elif self.path == '/api/stop':
                    voice.stop()
                    result = {'ok': True}
                elif self.path == '/api/approval':
                    if type(value.get('allow')) is not bool:
                        raise ValueError('无效的权限选择')
                    voice.approve(str(value['id']), value['allow'])
                    result = {'ok': True}
                elif self.path == '/api/events':
                    since = int(value.get('since', 0))
                    with voice.lock:
                        result = {'events': [x for x in voice.events if x['seq'] > since],
                                  'cursor': voice.sequence, 'active': voice.active}
                else:
                    self.reply({'error': 'Not found'}, 404)
                    return
                self.reply(result)
            except queue.Empty:
                self.reply({'error': '连接超时，请重试'}, 504)
            except (RuntimeError, ValueError, KeyError, OSError) as error:
                self.reply({'error': str(error)[:600]}, 400)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    info = {'url': 'http://127.0.0.1:' + str(server.server_port) + '/', 'token': token}
    tmp = STATE.with_suffix('.tmp')
    tmp.write_text(json.dumps(info))
    os.replace(tmp, STATE)
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())

    def watchdog():
        while True:
            time.sleep(5)
            age = time.monotonic() - voice.last_seen
            if voice.active and age > 30:
                voice.stop()
            if age > 300 and not voice.working:
                server.shutdown()
                return

    threading.Thread(target=watchdog, daemon=True).start()
    try:
        server.serve_forever(poll_interval=.5)
    finally:
        server.server_close()
        voice.close()
        if STATE.exists():
            try:
                if json.loads(STATE.read_text()).get('token') == token:
                    STATE.unlink()
            except (OSError, ValueError):
                pass


def launch():
    STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(STATE.with_suffix('.lock'), 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)

        def healthy():
            try:
                info = json.loads(STATE.read_text())
                request = urllib.request.Request(info['url'] + 'api/health', data=b'{}',
                          headers={'Authorization': 'Bearer ' + info['token'], 'Content-Type': 'application/json'})
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=2) as response:
                    return info if json.load(response).get('ok') else None
            except (OSError, ValueError, KeyError):
                return None

        info = healthy()
        if not info:
            with open(STATE.with_suffix('.launcher.log'), 'a') as log:
                child = subprocess.Popen([sys.executable, str(pathlib.Path(__file__).resolve()), '--serve'],
                    stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
            for _ in range(100):
                time.sleep(.2)
                info = healthy()
                if info:
                    break
                if child.poll() is not None:
                    raise RuntimeError('语音服务启动失败，请检查本地语音服务日志')
        if not info:
            raise RuntimeError('语音服务启动超时')
        print(json.dumps(info))


if __name__ == '__main__':
    try:
        serve() if '--serve' in sys.argv else launch()
    except Exception as error:
        print('语音服务错误：' + str(error)[:300], file=sys.stderr)
        sys.exit(1)
