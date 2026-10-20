"""Exercise the packaged Java main with fixture configuration in disposable directories.

No developer env file is read. Every provider URL is loopback, and Redis/Qdrant
target a closed loopback port. Only the fixture SQLite database may be written.
"""
import argparse
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.error


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Provider(http.server.BaseHTTPRequestHandler):
    calls = 0

    def respond(self):
        type(self).calls += 1
        self.send_response(503)
        self.end_headers()
        self.wfile.write(b'{"error":"isolated fixture"}')

    do_GET = respond
    do_POST = respond

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--jar", required=True)
    args = parser.parse_args()
    java, jar = str(Path(args.java).resolve()), str(Path(args.jar).resolve())
    root = Path(tempfile.mkdtemp(prefix="cp6-java-config-"))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_port}"
    closed = free_port()
    results = []
    # No real value is inherited, including JAVA_TOOL_OPTIONS or Spring overrides.
    minimal_env = {key: os.environ[key] for key in ("SystemRoot", "WINDIR", "TEMP", "TMP") if key in os.environ}
    scenarios = [
        ("missing-jwt", "", "fixture-only-model-key", None, None, False),
        ("blank-provider", "fixture-jwt-secret-0123456789-abcdef", "", None, None, False),
        ("local-file", "fixture-jwt-secret-0123456789-abcdef", "fixture-only-model-key", None, None, False),
        ("process-wins", "invalid", "fixture-only-model-key", "fixture-jwt-process-0123456789-abcdef", None, False),
        ("jvm-wins", "invalid", "fixture-only-model-key", None, "fixture-jwt-jvm-0123456789-abcdef", False),
        ("parent-file", "fixture-jwt-secret-0123456789-abcdef", "fixture-only-model-key", None, None, True),
        ("process-only", "fixture-jwt-secret-0123456789-abcdef", "fixture-only-model-key", None, None, False),
    ]
    try:
        for name, secret, model_key, process_secret, jvm_secret, parent_file in scenarios:
            directory = root / name
            cwd = directory / "app"
            cwd.mkdir(parents=True)
            port = free_port()
            fixture = {
                "JWT_SECRET": secret, "DEEPSEEK_API_KEY": model_key,
                "DEEPSEEK_BASE_URL": base, "DASHSCOPE_API_KEY": "",
                "DASHSCOPE_EMBEDDING_URL": base + "/embedding", "BAILIAN_RERANK_URL": base + "/rerank",
                "AMAP_WEB_SERVICE_BASE_URL": base, "AMAP_WEB_SERVICE_KEY": "",
                "BAIDU_ASR_URL": base + "/asr", "BAIDU_TTS_URL": base + "/tts",
                "BAIDU_TOKEN_URL": base + "/token", "BAIDU_WEB_SEARCH_URL": base + "/search",
                "BAIDU_WEB_FAVICON_URL": base + "/icon", "QDRANT_HOST": "127.0.0.1",
                "QDRANT_PORT": str(closed), "QDRANT_USE_TLS": "false", "QDRANT_API_KEY": "",
                "QDRANT_PRODUCTION_V2_AUTO_CREATE_INDEXES": "false",
                "YUYOUZHICE_REDIS_HOST": "127.0.0.1", "YUYOUZHICE_REDIS_PORT": str(closed),
                "JWT_REVOCATION_REDIS_ENABLED": "false", "APP_KNOWLEDGE_SYNC_ENABLED": "false",
                "KNOWLEDGE_DB_TYPE": "sqlite", "KNOWLEDGE_DB_URL": "jdbc:sqlite:" + (directory / "fixture.db").as_posix(),
                "KNOWLEDGE_UPLOAD_DIR": (directory / "uploads").as_posix(), "ADMIN_BOOTSTRAP_MODE": "disabled",
            }
            env_file = (directory if parent_file else cwd) / ".env"
            if name != "process-only":
                env_file.write_text("\n".join(f"{key}={value}" for key, value in fixture.items()), encoding="utf-8")
            env = dict(minimal_env)
            if name == "process-only":
                env.update(fixture)
            if process_secret:
                env["JWT_SECRET"] = process_secret
            command = [java] + (["-DJWT_SECRET=" + jvm_secret] if jvm_secret else []) + [
                "-jar", jar, "--server.address=127.0.0.1", "--server.port=" + str(port),
            ]
            healthy = False
            with (directory / "java.log").open("wb") as log:
                proc = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 50
                    while time.monotonic() < deadline and proc.poll() is None:
                        try:
                            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health/readiness", timeout=0.5) as response:
                                healthy = response.status == 200
                            if healthy:
                                break
                        except Exception:
                            pass
                        time.sleep(0.15)
                    exited = proc.poll()
                    health_status = None
                    if healthy:
                        try:
                            with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=2) as response:
                                health_status = response.status
                        except urllib.error.HTTPError as error:
                            health_status = error.code
                finally:
                    if proc.poll() is None:
                        proc.terminate()
                    proc.wait(timeout=20)
            output = (directory / "java.log").read_text(encoding="utf-8", errors="replace")
            jwt_failure = "JWT_SECRET 必须" in output
            provider_failure = "OpenAI API key must be set" in output or "OpenAI API key" in output
            if name == "missing-jwt":
                assert not healthy and exited is not None and jwt_failure, name
            elif name == "blank-provider":
                assert healthy or (exited is not None and provider_failure), name + ": unexpected startup failure"
            else:
                assert healthy, name + ": health endpoint did not become ready"
            results.append({"scenario": name, "ready": healthy, "aggregateHealthStatus": health_status, "jwtRequiredFailure": jwt_failure,
                            "providerRequiredFailure": provider_failure, "exitBeforeCleanup": exited})
            print(json.dumps(results[-1], ensure_ascii=False), flush=True)
        summary = {"jar": jar, "results": results, "localProviderCalls": Provider.calls,
                   "fixtureDirectory": str(root)}
        (root / "result-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        print("result=" + str(root / "result-summary.json"), flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
