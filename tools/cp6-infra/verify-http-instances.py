"""Two actual application JVMs against explicitly disposable loopback PG/Redis.

Uses separate fresh working directories and one random schema. No application .env,
developer credentials, provider calls, Redis flush, or production service is used.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    if not __debug__:
        raise RuntimeError('Optimized Python disables assertions; this acceptance probe refuses to run.')
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'jar', 'psql'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    # The PowerShell runner owns the single strict file parser and temporarily
    # installs its selected CP6 settings. This entrypoint never reads any .env.
    settings = {key: value for key, value in os.environ.items() if key.startswith('CP6_IT_')}
    assert settings.get('CP6_IT_ENABLE') == 'true'
    assert settings.get('CP6_IT_PERMISSION') == 'DISPOSABLE_CP6_TEST_ONLY'
    target = urllib.parse.urlparse(settings['CP6_IT_PG_URL'].removeprefix('jdbc:'))
    assert target.scheme == 'postgresql' and target.hostname == '127.0.0.1'
    assert target.path == '/' + settings['CP6_IT_PG_DATABASE'] and target.port
    assert not (target.query or target.fragment or target.username or target.password)
    assert settings['CP6_IT_REDIS_HOST'] == '127.0.0.1'
    # This probe accepts only the dedicated local services provisioned for this run.
    assert settings.get('CP6_IT_REDIS_TLS', 'false') == 'false'
    directory = Path(tempfile.mkdtemp(prefix='cp6-http-instances-'))
    schema = 'cp6_' + uuid.uuid4().hex
    minimal = {k: v for k, v in os.environ.items()
               if k.upper() in {'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH'}}
    pg_env = {**minimal, 'PGPASSWORD': settings['CP6_IT_PG_PASSWORD'], 'PGCONNECT_TIMEOUT': '5'}
    def sql(statement):
        result = subprocess.run([args.psql, '-X', '-v', 'ON_ERROR_STOP=1', '-h', target.hostname,
            '-p', str(target.port), '-U', settings['CP6_IT_PG_USER'], '-d', settings['CP6_IT_PG_DATABASE'],
            '-Atqc', statement], env=pg_env, capture_output=True, timeout=20)
        if result.returncode:
            (directory / 'sql-error.log').write_bytes(result.stderr)
            raise RuntimeError('Isolated SQL failed (details retained only in test directory)')
        return result.stdout.decode().strip()
    sql('CREATE SCHEMA ' + schema)
    processes, logs = [], []
    token = None
    checks = []
    try:
        ports = [free_port(), free_port()]
        while ports[1] == ports[0]:
            ports[1] = free_port()
        closed = free_port()
        for index, port in enumerate(ports):
            cwd = directory / str(index)
            cwd.mkdir()
            env = {**minimal, 'JWT_SECRET': 'isolated-http-jwt-secret-0123456789-abcdef',
                'DEEPSEEK_API_KEY': 'isolated-non-provider-key', 'DEEPSEEK_BASE_URL': f'http://127.0.0.1:{closed}',
                'DASHSCOPE_API_KEY': '', 'AMAP_WEB_SERVICE_KEY': '',
                'AMAP_WEB_SERVICE_BASE_URL': f'http://127.0.0.1:{closed}',
                'QDRANT_HOST': '127.0.0.1', 'QDRANT_PORT': str(closed), 'QDRANT_USE_TLS': 'false',
                'QDRANT_PRODUCTION_V2_AUTO_CREATE_INDEXES': 'false', 'APP_KNOWLEDGE_SYNC_ENABLED': 'false',
                'ADMIN_BOOTSTRAP_MODE': 'disabled', 'KNOWLEDGE_DB_TYPE': 'postgresql',
                'KNOWLEDGE_DB_URL': settings['CP6_IT_PG_URL'] + '?currentSchema=' + schema,
                'KNOWLEDGE_DB_USERNAME': settings['CP6_IT_PG_USER'],
                'KNOWLEDGE_DB_PASSWORD': settings['CP6_IT_PG_PASSWORD'],
                'YUYOUZHICE_REDIS_HOST': '127.0.0.1', 'YUYOUZHICE_REDIS_PORT': settings['CP6_IT_REDIS_PORT'],
                'YUYOUZHICE_REDIS_DATABASE': '15', 'JWT_REVOCATION_REDIS_ENABLED': 'true',
                'YUYOUZHICE_REDIS_PASSWORD': settings.get('CP6_IT_REDIS_PASSWORD', '')}
            log = (cwd / 'java.log').open('wb')
            logs.append(log)
            processes.append(subprocess.Popen([args.java, '-jar', str(Path(args.jar).resolve()),
                '--server.address=127.0.0.1', '--server.port=' + str(port)], cwd=cwd, env=env,
                stdout=log, stderr=subprocess.STDOUT))
        def request(index, method, path, body=None, authenticated=True):
            headers = {'Content-Type': 'application/json'}
            if authenticated and token:
                headers['Authorization'] = 'Bearer ' + token
            data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(f'http://127.0.0.1:{ports[index]}' + path, data, headers, method=method)
            try:
                response = urllib.request.urlopen(req, timeout=25)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, json.loads(response.read())
        deadline = time.monotonic() + 75
        for index in range(2):
            while time.monotonic() < deadline:
                if processes[index].poll() is not None:
                    raise AssertionError('Application exited; see isolated java.log')
                try:
                    if request(index, 'GET', '/actuator/health/readiness', authenticated=False)[0] == 200:
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(0.2)
            else:
                raise AssertionError('Application readiness timed out')
        credentials = {'username': 'cp6_' + uuid.uuid4().hex[:15], 'password': 'Test-only-password-012345!'}
        status, registered = request(0, 'POST', '/ai/auth/register', credentials, False)
        assert status == 200 and registered['code'] == 200
        token = registered['data']['token']
        assert request(1, 'GET', '/ai/trips')[0] == 200
        checks.append('shared-user-authority-across-instances')
        def pair(method, path, bodies):
            barrier = threading.Barrier(2)
            def call(index):
                barrier.wait(timeout=5)
                return request(index, method, path, bodies[index])
            with ThreadPoolExecutor(2) as executor:
                return list(executor.map(call, range(2)))
        plan = {'title': '隔离行程', 'days': [{'day': 1, 'stops': [{'id': 'one', 'name': '测试站'}]}]}
        body = {'plan': plan, 'idempotencyKey': uuid.uuid4().hex}
        created = pair('POST', '/ai/trips', [body, body])
        assert sorted(code for code, _ in created) == [200, 201], [code for code, _ in created]
        trip_id = created[0][1]['data']['id']
        assert created[1][1]['data']['id'] == trip_id
        assert sql(f'SELECT COUNT(*) FROM {schema}.trip') == '1'
        assert sql(f'SELECT COUNT(*) FROM {schema}.trip_version') == '1'
        checks.append('same-key-create-one-trip-one-version')
        bodies = [{'plan': {**plan, 'title': title}, 'title': title, 'expectedVersion': 1,
                   'idempotencyKey': uuid.uuid4().hex} for title in ('编辑甲', '编辑乙')]
        updated = pair('PUT', '/ai/trips/' + trip_id, bodies)
        assert sorted(code for code, _ in updated) == [200, 409], [code for code, _ in updated]
        assert sql(f'SELECT current_version FROM {schema}.trip') == '2'
        assert sql(f'SELECT COUNT(*) FROM {schema}.trip_version') == '2'
        checks.append('same-version-overlapping-edit-one-winner-one-conflict')
        # Kill one actual HTTP backend; the other must still read committed formal state.
        processes[0].kill()
        processes[0].wait(timeout=15)
        status, recovered = request(1, 'GET', '/ai/trips/' + trip_id)
        assert status == 200 and recovered['data']['currentVersion'] == 2
        checks.append('surviving-http-instance-reads-committed-trip-after-peer-kill')
        result = {'passed': checks, 'instances': 2, 'schema': schema, 'directory': str(directory),
                  'scope': 'Actual HTTP backends; not BFF, provider, or database-service crash recovery'}
        (directory / 'result-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=20)
        for log in logs:
            log.close()
        sql('DROP SCHEMA ' + schema + ' CASCADE')


if __name__ == '__main__':
    main()
