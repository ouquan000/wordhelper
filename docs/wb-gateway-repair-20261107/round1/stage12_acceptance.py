"""Independent round1 G1/G2. Production allowlisted GET only; key stays in memory.
Run: python docs/wb-gateway-repair-20261107/round1/stage12_acceptance.py
"""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

OUT = Path(__file__).resolve().parent
SPEC = OUT.parent / 'acceptance-spec.md'
LOCK = OUT.parent / 'acceptance-spec.lock'
STUB = Path('C:/temp/stub-rewrite.py')
RUN = Path('D:/deepseek_v4.1flash1017/workbuddy2api-panel/run')
EXPECTED = 'c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91'
EXPECTED_LOCK = '50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b'
KEY = ''
ENV = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONIOENCODING': 'utf-8'}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
report = {'started_at': datetime.now(timezone.utc).isoformat(), 'scope': 'G1/G2 only; G3-G6 and final K1-K6 pending candidate-ready', 'commands': [], 'checks': [], 'stub_runs': [], 'errors': []}

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def clean(v):
    if isinstance(v, dict):
        return {k: '[REDACTED]' if re.search(r'token|secret|password|authorization|api.?key|^key$|auths', k, re.I) else clean(x) for k, x in v.items()}
    if isinstance(v, list):
        return [clean(x) for x in v]
    return v.replace(KEY, '[REDACTED]') if isinstance(v, str) and KEY else v

def check(name, ok, details=None):
    report['checks'].append({'name': name, 'pass': bool(ok), 'details': clean(details)})

def command(args, timeout=20):
    r = subprocess.run(args, cwd=OUT, env=ENV, capture_output=True, encoding='utf-8', errors='replace', timeout=timeout)
    report['commands'].append({'argv': args, 'exit_code': r.returncode, 'stdout': clean(r.stdout.strip()), 'stderr': clean(r.stderr.strip())})
    return r

def snapshot():
    ps = '$ports = @(8788,8789,8790,18791,18792); $out = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$ports -contains $_.LocalPort} | ForEach-Object {$p = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $_.OwningProcess); [pscustomobject]@{port=$_.LocalPort;address=$_.LocalAddress;pid=$_.OwningProcess;exe=$p.ExecutablePath;created=$p.CreationDate.ToString("o");commandLine=$p.CommandLine}}); ConvertTo-Json -InputObject $out -Depth 4 -Compress'
    r = command(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', ps])
    if r.returncode:
        raise RuntimeError('Listener identity snapshot failed')
    x = json.loads(r.stdout)
    return x if isinstance(x, list) else [x]

def get(port, path, auth=False):
    if port in {8788, 8789, 8790} and path not in {'/healthz', '/v1/models', '/panel/', '/_router/status'}:
        raise RuntimeError('Production GET allowlist rejected path')
    headers = {'Authorization': 'Bearer ' + KEY} if auth else {}
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers=headers, method='GET')
    started = time.monotonic()
    try:
        response = OPENER.open(req, timeout=10)
    except urllib.error.HTTPError as e:
        response = e
    with response:
        raw = response.read()
        r = {'method': 'GET', 'port': port, 'path': path, 'authenticated': auth, 'status': response.status, 'content_type': response.headers.get('Content-Type'), 'bytes': len(raw), 'body_sha256': hashlib.sha256(raw).hexdigest(), 'elapsed_ms': round((time.monotonic()-started)*1000, 2)}
        try:
            body = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            body = None
        if path == '/v1/models':
            ids = [x.get('id') for x in body.get('data', [])] if isinstance(body, dict) else []
            r.update(model_ids=sorted(x for x in ids if isinstance(x, str)), model_count=len(ids), valid_nonempty_directory=bool(ids) and all(isinstance(x, str) and x for x in ids))
        elif path == '/panel/':
            title = re.search(rb'<title[^>]*>(.*?)</title>', raw, re.S | re.I)
            r['title'] = title.group(1).decode('utf-8', 'replace') if title else None
        elif path == '/_router/status':
            r.update(top_level_fields=sorted(body.keys()) if isinstance(body, dict) else [], mode=body.get('mode') if isinstance(body, dict) else None)
        else:
            r['json'] = clean(body)
    return r

def assert_port_free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        s.bind(('127.0.0.1', port))
    return True

def port_closed(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.25)
        return s.connect_ex(('127.0.0.1', port)) != 0

def run_stub(label, port, args):
    r = {'label': label, 'port': port, 'argv': [sys.executable, '-u', str(STUB), *args], 'preflight_free': assert_port_free(port)}
    report['stub_runs'].append(r)
    child = None
    log_path = OUT / (label + '-stdout.log')
    try:
        with log_path.open('x', encoding='utf-8') as log:
            child = subprocess.Popen(r['argv'], cwd=OUT, env=ENV, stdout=log, stderr=subprocess.STDOUT)
            r['owned_child_pid'] = child.pid
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError(f'Owned stub child exited early: {child.returncode}')
                try:
                    r['status_before'] = get(port, '/rewrite/status')
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(0.05)
            else:
                raise RuntimeError('Owned stub did not become available')
            r['status_after'] = get(port, '/rewrite/status')
            expected = {'queued': 0, 'done': 0, 'failed': 0}
            check('G1.' + label, all(r[k]['status'] == 200 and r[k]['json'] == expected for k in ('status_before', 'status_after')), r)
    finally:
        if child is not None:
            # Terminate only our Popen child using its retained Windows handle.
            r['was_running_before_cleanup'] = child.poll() is None
            if child.poll() is None:
                child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
            r.update(exit_code_after_cleanup=child.returncode, own_process_reaped=child.poll() is not None)
        r.update(port_closed_after_cleanup=port_closed(port), stdout_log=str(log_path))
        check('G1.' + label + '.cleanup', r.get('own_process_reaped') and r['port_closed_after_cleanup'], r)

try:
    report['hashes_begin'] = {'spec_sha256': sha(SPEC), 'lock_sha256': sha(LOCK), 'stub_sha256': sha(STUB), 'router_config_sha256': sha(RUN / 'router.config.json')}
    check('freeze.begin', report['hashes_begin']['spec_sha256'] == EXPECTED and report['hashes_begin']['lock_sha256'] == EXPECTED_LOCK and LOCK.read_text(encoding='utf-8-sig').split()[0] == EXPECTED, report['hashes_begin'])
    if not report['checks'][-1]['pass']:
        raise RuntimeError('Frozen specification or lock hash mismatch')
    before = snapshot()
    report['listeners_before'] = before
    ids = {x['port']: x for x in before}
    check('G1.main_listener_identity', ids.get(8790, {}).get('pid') == 38084 and str(ids.get(8790, {}).get('exe', '')).replace('\\', '/').lower() == str(RUN / 'wb2api.exe').replace('\\', '/').lower(), ids.get(8790))
    check('G2.aux_original_identity', ids.get(8789, {}).get('pid') == 29624, ids.get(8789))
    check('G1.default_port_initially_no_listener', 18791 not in ids, {'port': 18791, 'listener': ids.get(18791)})
    if 18791 in ids or 18792 in ids:
        raise RuntimeError('Planned isolated port occupied; refusing to stop its owner')
    c = json.loads((RUN / 'router.config.json').read_text(encoding='utf-8-sig'))
    KEY = c['main']['key']
    if not isinstance(KEY, str) or not KEY:
        raise RuntimeError('Existing main key missing')
    report['auth_source'] = 'Read-only run/router.config.json main.key; never logged; no auths read'
    for port in (8788, 8789, 8790):
        # Real argparse, but the server factory is replaced: no bind can occur.
        shim = "import http.server,runpy,sys; exec(\"def reject_bind(*a, **k):\\n print('BIND_GUARD_REACHED', flush=True)\\n raise SystemExit(91)\"); http.server.ThreadingHTTPServer=reject_bind; sys.argv=sys.argv[1:]; runpy.run_path(sys.argv[0],run_name='__main__')"
        r = command([sys.executable, '-c', shim, str(STUB), '--port', str(port)])
        check('G1.reject_gateway_port_' + str(port), r.returncode == 2 and 'reserved WB gateway ports' in r.stderr and 'BIND_GUARD_REACHED' not in r.stdout, {'exit_code': r.returncode, 'stderr': r.stderr.strip(), 'bind_attempted': 'BIND_GUARD_REACHED' in r.stdout})
    run_stub('stub-default', 18791, [])
    run_stub('stub-explicit', 18792, ['--host', '127.0.0.1', '--port', '18792'])
    live = {'main_health': get(8790, '/healthz'), 'entry_health': get(8788, '/healthz'), 'aux_health': get(8789, '/healthz'), 'main_models': get(8790, '/v1/models', True), 'entry_models': get(8788, '/v1/models', True), 'entry_panel': get(8788, '/panel/'), 'entry_status': get(8788, '/_router/status')}
    report['live_gets'] = live
    for name in ('main_health', 'entry_health'):
        r = live[name]
        check('G2.' + name, r['status'] == 200 and isinstance(r['json'], dict) and r['json'].get('service') == 'workbuddy2api' and r['json'].get('healthy') == 7, r)
    check('G1.main_service_identity', live['main_health']['status'] == 200 and live['main_health']['json'].get('service') == 'workbuddy2api', live['main_health'])
    r = live['aux_health']
    check('G2.aux_health', r['status'] == 200 and isinstance(r['json'], dict) and r['json'].get('service') == 'workbuddy2api' and r['json'].get('healthy') == 1, r)
    for name in ('main_models', 'entry_models'):
        check('G2.' + name, live[name]['status'] == 200 and live[name]['valid_nonempty_directory'], live[name])
    a, b = sorted(set(live['main_models']['model_ids'])), sorted(set(live['entry_models']['model_ids']))
    check('G2.model_id_sets_equal', bool(a) and a == b, {'main_count': len(a), 'entry_count': len(b), 'only_main': sorted(set(a)-set(b)), 'only_entry': sorted(set(b)-set(a)), 'count_is_observation_not_fixed_assertion': True})
    check('G2.entry_panel_200', live['entry_panel']['status'] == 200, live['entry_panel'])
    after = snapshot()
    report['listeners_after'] = after
    after_ids = {x['port']: x for x in after}
    for port in (8788, 8789, 8790):
        a, b = ids.get(port, {}), after_ids.get(port, {})
        check('G2.production_identity_unchanged_' + str(port), bool(a) and all(a.get(k) == b.get(k) for k in ('pid', 'exe', 'created', 'commandLine')), {'before': a, 'after': b})
    check('cleanup.no_test_listeners', all(x['port'] not in {18791, 18792} for x in after), {'test_ports': [18791, 18792]})
except Exception as e:
    report['errors'].append(clean(f'{type(e).__name__}: {e}'))
finally:
    report['hashes_end'] = {'spec_sha256': sha(SPEC), 'lock_sha256': sha(LOCK), 'stub_sha256': sha(STUB), 'router_config_sha256': sha(RUN / 'router.config.json')}
    check('freeze.end', report['hashes_end']['spec_sha256'] == EXPECTED and report['hashes_end']['lock_sha256'] == EXPECTED_LOCK, report['hashes_end'])
    check('readonly.router_config_unchanged', report.get('hashes_begin', {}).get('router_config_sha256') == report['hashes_end']['router_config_sha256'])
    check('readonly.stub_unchanged', report.get('hashes_begin', {}).get('stub_sha256') == report['hashes_end']['stub_sha256'])
    for group, points in (('G1', 15), ('G2', 15)):
        cs = [x for x in report['checks'] if x['name'].startswith(group + '.')]
        passed = cs and all(x['pass'] for x in cs) and not report['errors']
        report[group] = {'status': 'PASS' if passed else 'FAIL_OR_INCOMPLETE', 'score': points if passed else 0, 'max_score': points, 'check_count': len(cs)}
    report.update(ended_at=datetime.now(timezone.utc).isoformat(), post_count=0, production_stops=0)
    evidence = OUT / 'stage12-evidence.json'
    evidence.write_text(json.dumps(clean(report), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'G1': report['G1'], 'G2': report['G2'], 'failed_checks': [x for x in report['checks'] if not x['pass']], 'errors': report['errors'], 'hashes_begin': report.get('hashes_begin'), 'hashes_end': report['hashes_end'], 'evidence': str(evidence), 'pending': 'G3-G6 and complete K1-K6 independent assessment'}, ensure_ascii=False, indent=2))
sys.exit(0 if report['G1']['status'] == report['G2']['status'] == 'PASS' and all(x['pass'] for x in report['checks']) else 1)
