#!/usr/bin/env python3
"""Offline completed-receipt proof through the real pinned harness entry point."""
import subprocess,json,time,uuid
from pathlib import Path
name='usage-fixture-'+uuid.uuid4().hex[:8]
def docker(*a,input=None,check=True):
 return subprocess.run(['docker',*a],input=input,text=True,capture_output=True,check=check,timeout=90)
def api(route,body=None):
 args=['exec',name,'curl','-fsS','--max-time','10','-u','opencode:fixture','localhost:4096/api/'+route]
 if body is not None:args+=['-H','Content-Type: application/json','-d',json.dumps(body)]
 output=docker(*args).stdout
 return json.loads(output) if output else None
try:
 docker('run','-d','--name',name,'--platform','linux/amd64','--network','none','--read-only','--tmpfs','/tmp:exec','--tmpfs','/home/workspace:uid=1000,gid=1000','-e','WORKSPACED_AI_PROVIDER=openai','-e','OPENAI_API_KEY=fixture-only','-e','OPENCODE_SERVER_PASSWORD=fixture','workspaced-harness:provider-profiles')
 for _ in range(45):
  try:
   if api('info')['version']=='2.2.5':break
  except Exception:time.sleep(1)
 docker('exec','-i',name,'sh','-c','cat > /tmp/provider.py',input=Path(__file__).with_name('fixtures').joinpath('completed-provider.py').read_text())
 docker('exec','-d',name,'python3','/tmp/provider.py')
 config={'providers':{'openai':{'settings':{'baseURL':'http://127.0.0.1:5005/v1'}}}}
 docker('exec','-i',name,'sh','-c','cat > /home/workspace/project/opencode.json',input=json.dumps(config))
 session=api('session',{'location':{'directory':'/home/workspace/project'},'title':'Fixture only'})['data']['id']
 api('session/'+session+'/model',{'model':{'providerID':'openai','id':'gpt-6.1-sol'}})
 api('session/'+session+'/prompt',{'id':'msg_workspaced_fixture','text':'Reply WORKSPACED_OK'})
 for _ in range(40):
  messages=api('session/'+session+'/message?order=asc')['data']
  completed=[m for m in messages if m['type']=='assistant' and m.get('time',{}).get('completed') and m.get('tokens')]
  if completed:break
  time.sleep(1)
 print(json.dumps(messages,indent=2))
 assert next(m for m in messages if m['type']=='user')['id']=='msg_workspaced_fixture'
 assert completed, 'No measured completion'
 assert completed[0]['tokens']['input']==9, completed[0]['tokens'] # OpenCode subtracts cached input from raw input.
 assert completed[0]['tokens']['output']==5 # Reasoning is reported separately by the pinned harness.
 print('PASS pinned harness: explicit dispatch ID, model, completed receipt, measured token fields; no external network')
finally:
 docker('rm','-f',name,check=False)
