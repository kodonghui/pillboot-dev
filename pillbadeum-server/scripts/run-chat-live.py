#!/usr/bin/env python3
"""전용 DB/벡터 환경과 .env를 사용하는 실제 AI 검증 실행기임. 앱 저장 설정·키는 수정하지 않음."""
import argparse
import collections
import http.server
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid

# 실제 유료 요청은 명시적 실행에서만 수행하며, 운영 DB를 시작할 수 없도록 전용 주소를 검사함.
parser = argparse.ArgumentParser(description='CHAT-005 실제 AI 30대화/5가드레일 실행. 공공데이터만 합성 자료를 사용함.')
parser.add_argument('--run-live', action='store_true')
parser.add_argument('--docker-host')
parser.add_argument('--mysql-container', default='pill-chatlive-mysql')
args = parser.parse_args()
if not args.run_live:
    parser.error('--run-live is required; real AI requests are billed')
root = Path(__file__).resolve().parents[1]
mysql_url = os.environ.get('MYSQL_TEST_URL', '')
parsed_db = urllib.parse.urlsplit(mysql_url.removeprefix('jdbc:'))
if parsed_db.scheme != 'mysql' or parsed_db.hostname not in {'127.0.0.1', 'localhost'} or parsed_db.path != '/pill_test':
    parser.error('MYSQL_TEST_URL must explicitly select local pill_test')
mysql_user = os.environ.get('MYSQL_TEST_USER', 'pill')
mysql_password = os.environ.get('MYSQL_TEST_PASSWORD', '')
if not mysql_password or not os.environ.get('QDRANT_TEST_PORT'):
    parser.error('MYSQL_TEST_PASSWORD and QDRANT_TEST_PORT are required')
java = str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else shutil.which('java')
if not java:
    parser.error('Java 21 is required')
output_dir = root/'build/local-probes'
output_dir.mkdir(parents=True, exist_ok=True)
prefix = 'live_verify_' + uuid.uuid4().hex[:10]
calls = collections.Counter()
class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        calls[path] += 1
        if 'DrbEasyDrugInfoService' in path:
            items = [{'itemSeq':'199900001','efcyQesitm':'가상 약의 테스트용 자료임. 실제 의약품의 효능이나 용법 정보는 없음. 사용자가 앱에 등록한 일정과 메모만 설명하고 의학적 판단은 하지 않음.','updateDe':'2026-10-20'}]
        elif 'DrugPrdtPrmsnInfoService08' in path:
            items = [{'ITEM_SEQ':'199900001','EE_DOC_DATA':'테스트용 가상 약의 합성 자료임. 의학적 효능·용법·안전성 정보는 제공하지 않음.','CHANGE_DATE':'20261020','CANCEL_NAME':'정상'}]
        else:
            items = []
        raw = json.dumps({'header':{'resultCode':'00'},'body':{'totalCount':len(items),'items':items}},ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type','application/json;charset=UTF-8')
        self.send_header('Content-Length',str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

fixture = http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture)
threading.Thread(target=fixture.serve_forever,daemon=True).start()
proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or '')
flags = ['-Dhttp.nonProxyHosts=localhost|127.*|[::1]']
if Path('/etc/ssl/certs/java/cacerts').is_file():
    flags += ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
if proxy.hostname:
    for protocol in ['http','https']:
        flags += [f'-D{protocol}.proxyHost={proxy.hostname}',f'-D{protocol}.proxyPort={proxy.port or 80}']
jar = next((root/'build/libs').glob('*SNAPSHOT.jar'))
arguments = [
    '--spring.profiles.active=local,test','--server.address=127.0.0.1','--server.port=18082',
    f'--spring.datasource.url={mysql_url}',
    f'--spring.datasource.username={mysql_user}',f'--spring.datasource.password={mysql_password}',
    '--jwt.secret=cGlsbGJhZGV1bS10ZXN0LW9ubHktc2lnbmluZy1rZXktMzItYnl0ZXM',
    '--qdrant.enabled=true','--qdrant.host=127.0.0.1',f'--qdrant.port={os.environ["QDRANT_TEST_PORT"]}',f'--qdrant.collection-prefix={prefix}','--qdrant.vector-size=1536',
    f'--public-data.base-url=http://127.0.0.1:{fixture.server_port}','--public-data.service-key=synthetic-public-fixture',
    '--springdoc.api-docs.enabled=true','--springdoc.swagger-ui.enabled=true','--web-push.scheduler.enabled=false',
    '--web-push.vapid.public-key=','--web-push.vapid.private-key=','--web-push.vapid.subject=',
    '--logging.level.org.hibernate.SQL=info','--logging.level.org.semiproject.pillbadeumserver=info',
]
log = (output_dir/'live-chat-server.log').open('w',encoding='utf-8')
process = subprocess.Popen([java,*flags,'-jar',str(jar),*arguments],cwd=root,stdout=log,stderr=subprocess.STDOUT)
(output_dir/'live-chat-java.pid').write_text(str(process.pid))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
exit_code = 2
try:
    for _ in range(60):
        if process.poll() is not None:
            raise RuntimeError('test server startup failed')
        try:
            with opener.open('http://127.0.0.1:18082/actuator/health',timeout=1) as response:
                if response.status == 200:
                    break
        except Exception:
            time.sleep(1)
    else:
        raise RuntimeError('test server readiness timed out')
    print(json.dumps({'server':'READY','ai':'LIVE','public_data':'SYNTHETIC_FIXTURE','prefix':prefix}),flush=True)
    runtime_env = os.environ.copy()
    command = [sys.executable,'scripts/verify-chat-live.py','--run-live','--mysql-container',args.mysql_container,
               '--collection-prefix',prefix,'--public-data-mode','synthetic-fixture']
    if args.docker_host:
        command += ['--docker-host',args.docker_host]
    exit_code = subprocess.call(command,cwd=root,env=runtime_env)
finally:
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    fixture.shutdown()
    log.close()
    (output_dir/'live-chat-public-fixture-calls.json').write_text(json.dumps(dict(calls),indent=2)+'\n')
raise SystemExit(exit_code)
