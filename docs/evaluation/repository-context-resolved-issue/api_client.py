#!/usr/bin/env python3
"""Call only a disposable localhost fixture backend; save responses outside the repository."""

import argparse
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    parser.add_argument("method", choices=("GET", "POST", "PUT", "DELETE"))
    parser.add_argument("path")
    parser.add_argument("output", type=Path)
    parser.add_argument("--body", type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    if not args.path.startswith("/") or args.path.startswith("//"):
        parser.error("A local absolute API path is required")
    port = (runtime / "backend-port").read_text().strip()
    if not port.isascii() or not port.isdecimal():
        parser.error("Invalid local backend port")
    output = args.output.resolve()
    if not output.is_relative_to(runtime) or output.exists():
        parser.error("Output must be a new file within the disposable runtime")
    body = args.body.read_bytes() if args.body else None
    headers = {"X-ByteQuay-Session": (runtime / "session.token").read_text().strip()}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request("http://127.0.0.1:" + port + args.path, data=body, method=args.method, headers=headers)
    try:
        with urlopen(request, timeout=120) as response:
            status, content = response.status, response.read()
    except HTTPError as error:
        status, content = error.code, error.read()
    output.write_bytes(content)
    print(status)
    if status >= 400:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
