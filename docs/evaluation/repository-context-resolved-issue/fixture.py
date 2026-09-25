#!/usr/bin/env python3
"""Reproduce the isolated closed-issue learning and reuse case."""

import argparse
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import socketserver
import sqlite3
import ssl
import subprocess
import sys
import tempfile
from threading import Thread
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


FILES = {
    "README.md": "# Sensor windows\n\n`contains(start, end, sample)` chooses the dashboard windows that show a reading.\nRun `python3 -B test_window.py` for the ordinary checks.\n",
    "window.py": "def contains(start, end, sample):\n    return start <= sample <= end\n",
    "test_window.py": (
        "import unittest\nfrom window import contains\n\n\nclass WindowTest(unittest.TestCase):\n"
        "    def test_common_positions(self):\n        self.assertTrue(contains(10, 20, 10))\n"
        "        self.assertTrue(contains(10, 20, 15))\n        self.assertFalse(contains(10, 20, 21))\n\n\n"
        "if __name__ == '__main__':\n    unittest.main()\n"
    ),
}

ISSUE_UPDATED = "2026-01-15T10:00:00Z"
ISSUE_REFRESHED = "2026-01-15T10:01:00Z"
DISCUSSION = (
    "Outcome after comparing adjacent dashboard panels: close this request as documented behavior, "
    "with no code fix. Keep the end inclusive so a sample at the shared boundary appears in both panels. "
    "A future product decision could change this; closure alone does not prove the implementation is correct."
)
ISSUE = {
    "number": 12,
    "title": "Should adjacent dashboard windows stop sharing the end sample?",
    "body": "Operators asked whether the shared end sample should disappear from the earlier panel. The maintainer closed this as documented behavior with no code change; keep the inclusive end until a new product decision.",
    "state": "closed",
    "updated_at": ISSUE_UPDATED,
    "user": {"login": "fixture-maintainer"},
    "labels": [],
}

# Git's dumb HTTP transport needs only these static files from the bare fixture.
GIT_ROUTE = re.compile(r"/remote\.git/(?:HEAD|info/refs|objects/(?:[0-9a-f]{2}/[0-9a-f]{38}|info/packs|pack/pack-[0-9a-f]{40}\.(?:pack|idx)))")


def environment():
    return {
        "PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_NO_REPLACE_OBJECTS": "1", "GIT_NO_LAZY_FETCH": "1", "GIT_TERMINAL_PROMPT": "0",
        "GIT_AUTHOR_NAME": "ByteQuay fixture", "GIT_COMMITTER_NAME": "ByteQuay fixture",
        "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        "GIT_AUTHOR_DATE": "@0 +0000", "GIT_COMMITTER_DATE": "@0 +0000",
    }


def git(repository, *args):
    return subprocess.run(
        ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false",
         "-c", "core.fsmonitor=false", "-C", str(repository), *args],
        env=environment(), capture_output=True, check=True, timeout=10).stdout


def manifest(repository):
    commit = git(repository, "rev-parse", "HEAD").decode().strip()
    if git(repository, "ls-tree", "-r", "--name-only", commit).decode().splitlines() != sorted(FILES):
        raise ValueError("Unexpected frozen source paths")
    files = {}
    for path, text in FILES.items():
        data = git(repository, "show", commit + ":" + path)
        if data != text.encode():
            raise ValueError("Frozen source changed: " + path)
        files[path] = {"blobId": git(repository, "rev-parse", commit + ":" + path).decode().strip(),
                       "sha256": hashlib.sha256(data).hexdigest()}
    return {"schemaVersion": 1, "repository": "contextfixture/issue-case", "repositoryId": 207,
            "workspaceId": "issue-case", "baselineSha": commit, "issueNumber": 12, "files": files}


def verify(output):
    repository = output / "repository"
    if repository.is_symlink() or not (repository / ".git").is_dir():
        raise ValueError("Expected a regular local fixture repository")
    expected = manifest(repository)
    if json.loads((output / "manifest.json").read_text()) != expected:
        raise ValueError("Fixture manifest changed")
    if git(repository, "remote").strip() or git(repository, "status", "--porcelain", "--untracked-files=all").strip():
        raise ValueError("Fixture repository must have no remote or changes")
    with tempfile.TemporaryDirectory(prefix="bytequay-issue-oracle-") as temporary:
        directory = Path(temporary)
        for path, text in FILES.items():
            (directory / path).write_text(text)
        suite = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import runpy,sys;sys.path.insert(0,'.');runpy.run_path('test_window.py',run_name='__main__')"],
                               cwd=directory, env=environment(), capture_output=True, text=True, timeout=10)
        probe = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import sys;sys.path.insert(0,'.');from window import contains;print(contains(10,20,20))"],
                               cwd=directory, env=environment(), capture_output=True, text=True, timeout=10)
    if suite.returncode or probe.returncode or probe.stdout.strip() != "True":
        raise ValueError("Frozen behavior check failed")
    return {"scope": "Fixture integrity and code behavior only", "manifest": expected,
            "ordinarySuiteExit": suite.returncode, "sharedEndIncluded": True}


def create(output):
    output.mkdir()
    repository = output / "repository"
    repository.mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    git(repository, "init", "-q", "--initial-branch=main", "--object-format=sha1", "--template=" + str(template))
    for path, text in FILES.items():
        (repository / path).write_text(text)
    git(repository, "add", "--", *sorted(FILES))
    git(repository, "commit", "-qm", "Add sensor window grouping")
    (output / "manifest.json").write_text(json.dumps(manifest(repository), indent=2) + "\n")
    return verify(output)


def seed(database_path, corpus, checkout, remote):
    database_path = database_path.resolve(strict=True)
    if not database_path.is_relative_to(Path("/private/tmp").resolve()):
        raise ValueError("Only a disposable database under /private/tmp may be seeded")
    expected = verify(corpus.resolve(strict=True))["manifest"]
    checkout = checkout.resolve(strict=True)
    if not remote.startswith("https://127.0.0.1:") or not remote.endswith("/remote.git"):
        raise ValueError("Only the local HTTPS Git fixture remote is accepted")
    if git(checkout, "rev-parse", "HEAD").decode().strip() != expected["baselineSha"] or git(checkout, "remote", "get-url", "origin").decode().strip() != remote:
        raise ValueError("Managed checkout does not match the frozen baseline and origin")
    marker = checkout.parent / ".bytequay-workspace"
    if marker.exists():
        raise ValueError("The managed workspace marker already exists")
    timeline = [{"githubId": 1201, "event": "commented", "actor": "fixture-maintainer",
                 "timestamp": "2026-01-15T09:55:00Z", "body": DISCUSSION,
                 "payload": {"reactions": [], "comments": []}, "localEventKey": None},
                {"githubId": 1202, "event": "closed", "actor": "fixture-maintainer",
                 "timestamp": ISSUE_UPDATED, "body": "Closed as documented behavior; no code fix was merged.",
                 "payload": {"reactions": [], "comments": []}, "localEventKey": None}]
    database = sqlite3.connect(f"file:{database_path}?mode=rw", uri=True, timeout=30)
    try:
        if database.execute("SELECT COUNT(*) FROM watched_repo_workspaces").fetchone()[0]:
            raise ValueError("The fixture database already has workspaces")
        with database:
            database.execute("""INSERT INTO watched_repo_workspaces
                (display_order, watched, created_at, last_code_sync_at, last_metadata_sync_at,
                 monitored_repository_id, selected_repository_id, updated_at, default_branch, id,
                 local_repository_path, monitored_owner, monitored_repo, name, origin_clone_url,
                 selected_owner, selected_repo, topology)
                VALUES (0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        207, 207, CURRENT_TIMESTAMP, 'main', 'issue-case', ?, 'contextfixture',
                        'issue-case', 'Closed issue fixture', ?, 'contextfixture', 'issue-case', 'DIRECT')""",
                             (str(checkout), remote))
            database.execute("""INSERT INTO repository_issue_details
                (id, repository_id, issue_number, timeline, current_type, available_types, refreshed_at)
                VALUES ('207:12', 207, 12, ?, NULL, '[]', ?)""", (json.dumps(timeline), ISSUE_REFRESHED))
    finally:
        database.close()
    marker.write_text("issue-case\n207\n")
    return {"workspaceId": "issue-case", "repository": expected["repository"],
            "repositoryId": 207, "issueNumber": 12, "activityItems": len(timeline),
            "baselineSha": expected["baselineSha"]}


def git_handler(runtime):
    if (runtime / "remote.git").is_symlink():
        raise ValueError("The disposable Git remote must not be a symlink")
    bare = (runtime / "remote.git").resolve(strict=True)
    if not bare.is_dir() or bare.parent != runtime:
        raise ValueError("Expected a regular remote.git directory inside the disposable runtime")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *handler_args, **kwargs):
            super().__init__(*handler_args, directory=str(bare), **kwargs)

        def serve_git(self, method):
            route = urlsplit(self.path).path
            if not GIT_ROUTE.fullmatch(route):
                self.send_error(404)
                return
            target = (bare / route.removeprefix("/remote.git/")).resolve()
            if not target.is_relative_to(bare):
                self.send_error(404)
                return
            self.path = route.removeprefix("/remote.git")
            method()

        def do_GET(self):
            self.serve_git(super().do_GET)

        def do_HEAD(self):
            self.serve_git(super().do_HEAD)

        def do_POST(self):
            self.send_error(405)

        def log_message(self, format, *values):
            with (runtime / "git-requests.txt").open("a") as log:
                log.write(format % values + "\n")

    return Handler


def serve_git(runtime):
    runtime = runtime.resolve(strict=True)
    with ThreadingHTTPServer(("127.0.0.1", 0), git_handler(runtime)) as server:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(runtime / "git-cert.pem"), str(runtime / "git-key.pem"))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        (runtime / "git-port").write_text(str(server.server_port))
        server.serve_forever()


def check_git_server():
    with tempfile.TemporaryDirectory(prefix="bytequay-issue-server-") as temporary:
        runtime = Path(temporary).resolve()
        (runtime / "remote.git" / "info").mkdir(parents=True)
        (runtime / "remote.git" / "info" / "refs").write_text("frozen refs\n")
        (runtime / "remote.git" / "config").write_text("private bare config\n")
        (runtime / "remote.git" / "objects" / "aa").mkdir(parents=True)
        (runtime / "session.token").write_text("private session\n")
        (runtime / "remote.git" / "HEAD").symlink_to(runtime / "session.token")
        (runtime / "remote.git" / "objects" / "aa" / ("b" * 38)).symlink_to(runtime / "session.token")
        with ThreadingHTTPServer(("127.0.0.1", 0), git_handler(runtime)) as server:
            worker = Thread(target=server.serve_forever, daemon=True)
            worker.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with urlopen(base + "/remote.git/info/refs", timeout=2) as response:
                    assert response.status == 200 and response.read() == b"frozen refs\n"
                for path in ("/session.token", "/remote.git/../session.token", "/remote.git/%2e%2e/session.token",
                             "/remote.git/config", "/remote.git/info/", "/remote.git/HEAD",
                             "/remote.git/objects/aa/" + "b" * 38):
                    try:
                        with urlopen(base + path, timeout=2) as response:
                            raise AssertionError(f"Unexpectedly served {path}: {response.status}")
                    except HTTPError as error:
                        assert error.code == 404, (path, error.code)
                try:
                    urlopen(Request(base + "/remote.git/info/refs", data=b"write", method="POST"), timeout=2)
                    raise AssertionError("Git fixture accepted POST")
                except HTTPError as error:
                    assert error.code == 405, error.code
            finally:
                server.shutdown()
                worker.join(timeout=2)
    return {"gitInfoRefs": "readable", "runtimeSecrets": "inaccessible", "writes": "denied"}


def serve_github(runtime):
    runtime = runtime.resolve(strict=True)
    git_url = "https://127.0.0.1:" + (runtime / "git-port").read_text().strip() + "/remote.git"
    repository = {
        "id": 207, "name": "issue-case", "full_name": "contextfixture/issue-case",
        "owner": {"login": "contextfixture"}, "description": "Local issue fixture", "fork": False,
        "visibility": "public", "permissions": {"push": False}, "default_branch": "main",
        "clone_url": git_url,
    }
    user = {"login": "fixture-reviewer", "name": "Fixture Reviewer", "avatar_url": "",
            "html_url": "https://github.com/fixture-reviewer", "public_repos": 0,
            "followers": 0, "following": 0, "bio": "", "location": "", "company": "", "email": None}
    routes = {"/user": user, "/repositories/207": repository,
              "/repos/contextfixture/issue-case": repository,
              "/repos/contextfixture/issue-case/issues/12": ISSUE,
              "/repos/contextfixture/issue-case/languages": {},
              "/repos/contextfixture/issue-case/contributors": [],
              "/repos/contextfixture/issue-case/assignees": [],
              "/repos/contextfixture/issue-case/labels": []}

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            line = self.rfile.readline(4096).decode("ascii", "replace").strip()
            if line != "CONNECT api.github.com:443 HTTP/1.1":
                return
            while self.rfile.readline(4096) not in (b"\r\n", b"\n", b""):
                pass
            self.wfile.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            self.wfile.flush()
            with self.server.context.wrap_socket(self.connection, server_side=True) as tls:
                request_file = tls.makefile("rb")
                request = request_file.readline(4096).decode("ascii", "replace").strip()
                while request_file.readline(4096) not in (b"\r\n", b"\n", b""):
                    pass
                parts = request.split(" ")
                path = parts[1] if len(parts) == 3 and parts[0] == "GET" else ""
                route = path.split("?", 1)[0]
                body = json.dumps(routes[route] if route in routes else {}).encode()
                status = b"200 OK" if route in routes else b"404 Not Found"
                tls.sendall(b"HTTP/1.1 " + status + b"\r\nContent-Type: application/json\r\nContent-Length: "
                            + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
                with (runtime / "github-requests.txt").open("a") as log:
                    log.write(request + "\n")

    class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
        allow_reuse_address = True
        daemon_threads = True

    with Server(("127.0.0.1", 0), Handler) as server:
        server.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server.context.load_cert_chain(str(runtime / "github-cert.pem"), str(runtime / "github-key.pem"))
        (runtime / "proxy-port").write_text(str(server.server_address[1]))
        server.serve_forever()


def start_backend(runtime, schema):
    runtime = runtime.resolve(strict=True)
    executable = shutil.which("claude")
    if executable is None:
        raise ValueError("Claude CLI is unavailable")
    proxy_port = (runtime / "proxy-port").read_text().strip()
    private_home = runtime / "private-home"
    private_home.mkdir(exist_ok=True)
    arguments = ["java", "--enable-native-access=ALL-UNNAMED",
                 "-Duser.home=" + str(private_home),
                 "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=" + proxy_port,
                 "-Dhttp.nonProxyHosts=localhost|127.*",
                 "-Djavax.net.ssl.trustStore=" + str(runtime / "truststore.p12"),
                 "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStorePassword=changeit",
                 "-jar", str(runtime / "backend.jar"), "--server.port=0", "--server.shutdown=immediate",
                 "--spring.datasource.url=jdbc:sqlite:" + str(runtime / "fixture.db")
                 + "?journal_mode=WAL&busy_timeout=30000&foreign_keys=ON",
                 "--spring.jpa.hibernate.ddl-auto=" + schema,
                 "--spring.datasource.hikari.maximum-pool-size=1", "--spring.datasource.hikari.minimum-idle=1",
                 "--bytequay.scheduling.enabled=false", "--bytequay.agent.runtime-root=" + str(runtime / "agent-runtime"),
                 "--bytequay.workspace.root=" + str(runtime / "workspaces"),
                 "--bytequay.agent.codex-enabled=false", "--bytequay.agent.claude-enabled=true",
                 "--bytequay.agent.claude-executable=" + executable,
                 "--bytequay.agent.claude-home=" + str(Path.home() / "Library/Application Support/ByteQuay/claude-home")]
    environment = dict(os.environ)
    environment["BYTEQUAY_DESKTOP_SESSION_TOKEN"] = (runtime / "session.token").read_text().strip()
    environment["GIT_SSL_CAINFO"] = str(runtime / "git-cert.pem")
    os.execvpe("java", arguments, environment)


def api(runtime, method, path, output, body_path):
    runtime = runtime.resolve(strict=True)
    if not path.startswith("/") or path.startswith("//"):
        raise ValueError("A local absolute API path is required")
    port = (runtime / "backend-port").read_text().strip()
    if not port.isascii() or not port.isdecimal():
        raise ValueError("Invalid local backend port")
    output = output.resolve()
    if not output.is_relative_to(runtime) or output.exists():
        raise ValueError("Output must be a new file within the disposable runtime")
    body = body_path.read_bytes() if body_path else None
    headers = {"X-ByteQuay-Session": (runtime / "session.token").read_text().strip()}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request("http://127.0.0.1:" + port + path, data=body, method=method, headers=headers)
    try:
        with urlopen(request, timeout=120) as response:
            status, content = response.status, response.read()
    except HTTPError as error:
        status, content = error.code, error.read()
    output.write_bytes(content)
    print(status)
    if status >= 400:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for operation in ("create", "verify"):
        commands.add_parser(operation).add_argument("output", type=Path)
    seed_command = commands.add_parser("seed")
    seed_command.add_argument("database", type=Path)
    seed_command.add_argument("corpus", type=Path)
    seed_command.add_argument("checkout", type=Path)
    seed_command.add_argument("remote")
    for operation in ("serve-git", "serve-github"):
        commands.add_parser(operation).add_argument("runtime", type=Path)
    backend_command = commands.add_parser("start-backend")
    backend_command.add_argument("runtime", type=Path)
    backend_command.add_argument("schema", choices=("create", "update"))
    api_command = commands.add_parser("api")
    api_command.add_argument("runtime", type=Path)
    api_command.add_argument("method", choices=("GET", "POST", "PUT", "DELETE"))
    api_command.add_argument("path")
    api_command.add_argument("output", type=Path)
    api_command.add_argument("--body", type=Path)
    commands.add_parser("check-git-server")
    args = parser.parse_args()
    try:
        if args.operation in ("create", "verify"):
            result = create(args.output.resolve()) if args.operation == "create" else verify(args.output.resolve())
        elif args.operation == "seed":
            result = seed(args.database, args.corpus, args.checkout, args.remote)
        elif args.operation == "serve-git":
            serve_git(args.runtime)
            return
        elif args.operation == "serve-github":
            serve_github(args.runtime)
            return
        elif args.operation == "start-backend":
            start_backend(args.runtime, args.schema)
            return
        elif args.operation == "api":
            api(args.runtime, args.method, args.path, args.output, args.body)
            return
        else:
            result = check_git_server()
    except (OSError, ValueError, subprocess.SubprocessError, sqlite3.Error) as error:
        parser.exit(1, str(error) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
