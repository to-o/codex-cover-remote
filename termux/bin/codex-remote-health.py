#!/data/data/com.termux/files/usr/bin/python3
import json
import os
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROXY = ("127.0.0.1", 7890)
URL = "https://chatgpt.com/backend-api/wham/remote/control/server/refresh"

def check(snapshot):
    try:
        with socket.create_connection(PROXY, timeout=1):
            pass
    except ConnectionRefusedError:
        return {"proxy": "closed", "endpoint": "unchecked", "codex": snapshot}
    except OSError:
        return {"proxy": "unreachable", "endpoint": "unchecked", "codex": snapshot}

    result = {"proxy": "ready", "endpoint": "error", "codex": snapshot}
    proxy = "http://%s:%s" % PROXY
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy}))
    request = urllib.request.Request(URL, data=b"{}", headers={"Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with opener.open(request, timeout=4) as response:
            result.update(endpoint="reachable", http=response.status)
    except urllib.error.HTTPError as error:
        try:
            body = error.read(8192).lower()
        except (OSError, ValueError):
            body = b''
        if b"unsupported_country_region_territory" in body:
            endpoint = "region"
        elif error.code == 407:
            endpoint = "proxy_auth"
        elif error.code == 429:
            endpoint = "rate_limited"
        elif error.code >= 500:
            endpoint = "server_error"
        elif error.code == 403:
            endpoint = "forbidden"
        elif error.code == 401:
            endpoint = "reachable"
        else:
            endpoint = "error"
        result.update(endpoint=endpoint, http=error.code)
    except (OSError, urllib.error.URLError) as error:
        reason = getattr(error, "reason", error)
        if isinstance(reason, TimeoutError):
            result["endpoint"] = "timeout"
        elif isinstance(reason, ssl.SSLError):
            result["endpoint"] = "tls"
        elif "407" in str(reason):
            result["endpoint"] = "proxy_auth"
    result["elapsedMs"] = round((time.monotonic() - started) * 1000)

    if result["endpoint"] == "reachable" and snapshot.get("status") == "errored":
        try:
            login = subprocess.run([os.path.join(os.environ["PREFIX"], "bin/codex"), "login", "status"],
                                   capture_output=True, text=True, timeout=3)
            if "not logged in" in (login.stdout + login.stderr).lower():
                result["login"] = "required"
        except (OSError, subprocess.TimeoutExpired):
            pass
    return result

print(json.dumps(check(json.loads(sys.argv[1]))))
