import json, os, subprocess, time, select
os.umask(0o077)
home=os.path.expanduser('~')
state=home+'/.termux/codex-remote-status.json'
# This Debian guest can reach FlClash without relying on the Wi-Fi router for DNS.
server_env = dict(os.environ, CODEX_FLCLASH_PROXY='http://127.0.0.1:7890')
p=subprocess.Popen([os.environ['PREFIX']+'/bin/codex','--no-daemon','app-server','--stdio'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=open(home+'/.termux/codex-server-stderr.log','a'),text=True,bufsize=1,env=server_env)
def send(i,m,params):
 p.stdin.write(json.dumps({'id':i,'method':m,'params':params})+'\n'); p.stdin.flush()
send(1,'initialize',{'clientInfo':{'name':'termux_remote_host','version':'1.0'},'capabilities':{'experimentalApi':True}})
last=time.monotonic(); ready=False; last_status=None
buffer=b""
last_change=None
confirmed_at=None
retry_request=home+'/.termux/codex-retry.request'
retry_in_progress=False
retry_deadline=0
retry_error_code=None

def publish(result, confirmed=False):
 global last_status, last_change, confirmed_at
 now=int(time.time())
 state_key=('status','serviceState','rpcErrorCode','appServerExitCode')
 different=(last_status is None or any(last_status.get(key)!=result.get(key) for key in state_key))
 if different: last_change=now
 if confirmed: confirmed_at=now
 snapshot=dict(result, changedAt=last_change, confirmedAt=confirmed_at, retrying=retry_in_progress)
 if retry_error_code is not None: snapshot['retryErrorCode']=retry_error_code
 with open(state+'.tmp','w') as f: json.dump(snapshot,f)
 os.replace(state+'.tmp',state)
 if different:
  print(time.strftime('%Y-%m-%d %H:%M:%S'),json.dumps(result),flush=True)
 last_status=result

publish({'status': None, 'serviceState': 'starting'})
while p.poll() is None:
 control=home+"/.termux/codex-control-request.json"
 if ready and os.path.exists(control):
  with open(control) as f: req=json.load(f)
  os.unlink(control)
  send(900,req["method"],req["params"])
 if retry_in_progress and time.monotonic()>=retry_deadline:
  retry_in_progress=False
  retry_error_code='timeout'
  publish(last_status)
 if ready and os.path.exists(retry_request) and not retry_in_progress:
  try:
   age=time.time()-os.stat(retry_request).st_mtime
   os.unlink(retry_request)
  except FileNotFoundError:
   age=60
  if age<15:
   retry_in_progress=True
   retry_deadline=time.monotonic()+10
   retry_error_code=None
   publish(last_status)
   send(910,'remoteControl/disable',{'ephemeral':True})
 readable,_,_=select.select([p.stdout],[],[],0.25)
 if readable:
  chunk=os.read(p.stdout.fileno(),65536)
  if not chunk: break
  lines=(buffer+chunk).split(b'\n')
  buffer=lines.pop()
  for line in lines:
   try: msg=json.loads(line)
   except ValueError: continue
   ident=msg.get('id')
   if ident==1:
    if 'error' in msg:
     publish({'status': None, 'serviceState': 'errored', 'rpcErrorCode': msg['error'].get('code')})
     p.terminate()
     break
    p.stdin.write('{"method":"initialized"}\n'); p.stdin.flush()
    send(2,'remoteControl/enable',{'ephemeral':False}); ready=True
   elif ident in (2,3):
    if 'result' in msg:
     publish(msg['result'], confirmed=True)
    elif 'error' in msg:
     publish({'status': None, 'serviceState': 'errored', 'rpcErrorCode': msg['error'].get('code')})
   elif msg.get('method')=='remoteControl/status/changed':
    publish(msg['params'], confirmed=True)
   elif ident in (910,911):
    if ident==910:
     send(911,'remoteControl/enable',{'ephemeral':True})
    else:
     retry_in_progress=False
     retry_error_code=msg.get('error',{}).get('code')
    if 'result' in msg:
     publish(msg['result'], confirmed=True)
    else:
     publish(last_status)
   elif ident==900:
    resultfile=home+"/.termux/codex-control-result.json"
    with open(resultfile,"w") as f: json.dump(msg,f)
    os.chmod(resultfile,0o600)
    print("CONTROL_REQUEST_COMPLETE",flush=True)
   elif ident==4:
    with open(home+'/.termux/codex-pairing.json','w') as f: json.dump(msg,f)
    os.chmod(home+'/.termux/codex-pairing.json',0o600)
    print('PAIRING_READY',flush=True)
 if ready and time.monotonic()-last>10:
  send(3,'remoteControl/status/read',{}); last=time.monotonic()

exit_code=p.wait()
publish({'status': None, 'serviceState': 'restarting', 'appServerExitCode': exit_code})
print('APP_SERVER_EXIT',exit_code,flush=True)
