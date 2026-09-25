#!/usr/bin/env python3
"""Launch a disposable ByteQuay backend against the local closed-issue fixture."""

import argparse
import os
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    parser.add_argument("schema", choices=("create", "update"))
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    executable = shutil.which("claude")
    if executable is None:
        parser.error("Claude CLI is unavailable")
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
                 "--spring.jpa.hibernate.ddl-auto=" + args.schema,
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


if __name__ == "__main__":
    main()
