#!/usr/bin/env python3
"""Serve one disposable bare Git repository over read-only localhost HTTPS."""

import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *handler_args, **kwargs):
            super().__init__(*handler_args, directory=str(runtime), **kwargs)

        def do_POST(self):
            self.send_error(405)

        def log_message(self, format, *values):
            with (runtime / "git-requests.txt").open("a") as log:
                log.write(format % values + "\n")

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(runtime / "git-cert.pem"), str(runtime / "git-key.pem"))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        (runtime / "git-port").write_text(str(server.server_port))
        server.serve_forever()


if __name__ == "__main__":
    main()
