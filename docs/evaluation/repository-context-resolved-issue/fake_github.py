#!/usr/bin/env python3
"""Local HTTPS CONNECT fixture: one exact closed-issue preview, GET only."""

import argparse
import json
from pathlib import Path
import socketserver
import ssl


ISSUE = {
    "number": 12,
    "title": "Should adjacent dashboard windows stop sharing the end sample?",
    "body": "Operators asked whether the shared end sample should disappear from the earlier panel. The maintainer closed this as documented behavior with no code change; keep the inclusive end until a new product decision.",
    "state": "closed",
    "updated_at": "2026-01-15T10:00:00Z",
    "user": {"login": "fixture-maintainer"},
    "labels": [],
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path, help="Disposable directory containing github-cert.pem and github-key.pem")
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
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


if __name__ == "__main__":
    main()
