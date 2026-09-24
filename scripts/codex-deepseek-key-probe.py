#!/usr/bin/env python3
"""Exercise the DeepSeek Codex profile against a local fake Responses server.

Run: python3 scripts/codex-deepseek-key-probe.py --run-native
The installed Codex CLI and macOS sandbox are required. The provider is a loopback
fixture, the API key is fake, and all state is kept in a new temporary directory.
"""

import argparse
import http.server
import importlib.util
import json
from pathlib import Path
import secrets
import shlex
import shutil
import sys
import tempfile
import threading
import time
import urllib.request

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("completion_probe", Path(__file__).with_name("codex-completion-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

FAKE_KEY = "bytequay-fixture-secret"
SHELL_ENV = ["HOME", "LANG", "LC_ALL", "LOGNAME", "PATH", "SHELL", "TERM", "TMPDIR", "USER"]


class FakeResponses:
    def __init__(self, output, workspace, escalation=None):
        self.requests = []
        self.proxy_requests = 0
        self.escalation = escalation
        self.proxy_base = "/" + secrets.token_hex(16) + "/"
        self.proxy_path = self.proxy_base + "responses"
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.requests.append(payload)
                if (self.path != "/responses" or self.headers.get("Authorization") != "Bearer " + FAKE_KEY
                        or len(fixture.requests) > 2):
                    self.send_error(400)
                    return
                index = len(fixture.requests)
                if index == 1:
                    names = {tool.get("name") for tool in payload.get("tools", [])}
                    probe.require("exec_command" in names, "Expected Codex model-facing shell tool")
                    protected = shlex.quote(str(output / "home/Library/Application Support/ByteQuay/master.key"))
                    changed = shlex.quote(str(workspace / "changed.txt"))
                    command = ("for key in DEEPSEEK_API_KEY OPENAI_API_KEY GH_TOKEN; do "
                               "if printenv \"$key\" >/dev/null; then printf 'LEAKED:%s\\n' \"$key\"; fi; done; "
                               "if cat " + protected + " >/dev/null 2>&1; then printf 'READ_ALLOWED\\n'; "
                               "else printf 'READ_DENIED\\n'; fi; "
                               "printf changed > " + changed + "; printf 'WRITE_OK\\n'")
                    arguments = {"cmd": command}
                    if fixture.escalation:
                        arguments.update({"sandbox_permissions": "require_escalated", "justification": "Read a disposable fake key"})
                    item = {"id": "fc_fixture", "type": "function_call", "call_id": "call_fixture",
                            "name": "exec_command", "arguments": json.dumps(arguments)}
                else:
                    item = {"id": "msg_fixture", "type": "message", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "Local fixture completed.", "annotations": []}]}
                response = {"id": "resp_fixture_" + str(index), "object": "response", "created_at": int(time.time()),
                            "status": "completed", "model": "deepseek-flash", "output": [item],
                            "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
                events = [{"type": "response.output_item.done", "output_index": 0, "item": item},
                          {"type": "response.completed", "response": response}]
                body = "".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

        class ProxyHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                fixture.proxy_requests += 1
                if self.path != fixture.proxy_path or "Authorization" in self.headers:
                    self.send_error(400)
                    return
                body = self.rfile.read(int(self.headers["Content-Length"]))
                request = urllib.request.Request(
                    "http://127.0.0.1:" + str(fixture.server.server_port) + "/responses", body,
                    {"Authorization": "Bearer " + FAKE_KEY, "Content-Type": self.headers.get("Content-Type", "application/json")})
                with urllib.request.urlopen(request, timeout=10) as upstream:
                    response = upstream.read()
                    self.send_response(upstream.status)
                    self.send_header("Content-Type", upstream.headers["Content-Type"])
                    self.send_header("Content-Length", str(len(response)))
                    self.end_headers()
                    self.wfile.write(response)

        self.proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ProxyHandler)
        self.proxy_thread = threading.Thread(target=self.proxy.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        self.proxy_thread.start()
        return self

    def __exit__(self, *args):
        self.proxy.shutdown()
        self.proxy.server_close()
        self.proxy_thread.join(timeout=2)
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-native", action="store_true")
    parser.add_argument("--codex", default=shutil.which("codex") or "/opt/homebrew/bin/codex")
    parser.add_argument("--escalate-read", choices=("accept", "decline"))
    options = parser.parse_args()
    probe.require(options.run_native, "Pass --run-native to run the installed CLI fixture")
    probe.require(sys.platform == "darwin", "This fixture verifies the macOS native shell")
    output = Path(tempfile.mkdtemp(prefix="bytequay-deepseek-key-")).resolve()
    print("Probe artifacts: " + str(output), flush=True)
    for name in ("home", "codex-home", "sqlite", "tmp"):
        (output / name).mkdir(mode=0o700)
    app_data = output / "home/Library/Application Support/ByteQuay"
    app_data.mkdir(parents=True, mode=0o700)
    appserver_output = app_data / "workspaces"
    workspace = appserver_output / "workspace"
    workspace.mkdir(parents=True, mode=0o700)
    (app_data / "master.key").write_text("fake-encryption-key")
    (app_data / "master.key").chmod(0o600)
    catalog = output / "codex-home/deepseek-models.json"
    catalog.write_bytes((Path(__file__).resolve().parents[1] / "backend/agent-cli/src/main/resources/com/bytequay/agent/cli/codex/deepseek-models.json").read_bytes())
    (output / "codex-home/config.toml").touch(mode=0o600)
    environment = {"HOME": str(output / "home"), "CODEX_HOME": str(output / "codex-home"),
                   "CODEX_SQLITE_HOME": str(output / "sqlite"), "TMPDIR": str(output / "tmp"),
                   "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/sh",
                   "LANG": "en_US.UTF-8"}
    probe.require(not {"DEEPSEEK_API_KEY", "OPENAI_API_KEY", "GH_TOKEN"}.intersection(environment),
                  "Codex process environment contains a credential")
    with FakeResponses(output, workspace, options.escalate_read) as provider:
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config",
                     "--enable", "skip_host_skill_discovery", "--disable", "guardian_approval",
                     "--disable", "multi_agent", "--disable", "multi_agent_v2"]
        config = {"cli_auth_credentials_store": "ephemeral", "model_provider": "deepseek", "model": "deepseek-flash",
                  "model_catalog_json": str(catalog), "model_providers.deepseek.name": "DeepSeek",
                  "model_providers.deepseek.base_url": "http://127.0.0.1:" + str(provider.proxy.server_port) + provider.proxy_base,
                  "model_providers.deepseek.wire_api": "responses",
                  "model_providers.deepseek.requires_openai_auth": False,
                  "model_providers.deepseek.supports_websockets": False,
                  "model_providers.deepseek.request_max_retries": 0,
                  "approval_policy": "on-request", "approvals_reviewer": "user", "web_search": "disabled",
                  "default_permissions": "bytequay-deepseek",
                  "permissions.bytequay-deepseek.extends": ":workspace",
                  "permissions.bytequay-deepseek.network.enabled": False,
                  "shell_environment_policy.inherit": "core", "shell_environment_policy.ignore_default_excludes": False,
                  "shell_environment_policy.experimental_use_profile": False,
                  "shell_environment_policy.include_only": SHELL_ENV}
        for key, value in config.items():
            arguments.extend(["-c", key + "=" + json.dumps(value)])
        filesystem = '{":root"="deny",":minimal"="read",' + json.dumps(str(app_data)) + '="deny"}'
        arguments.extend(["-c", "permissions.bytequay-deepseek.filesystem=" + filesystem])
        probe.require(FAKE_KEY not in json.dumps(arguments), "Codex arguments contain the DeepSeek key")
        with probe.AppServer(arguments, environment, appserver_output, "deepseek") as app:
            effective = app.request("config/read", {"cwd": str(workspace), "includeLayers": True}, "config")["result"]["config"]
            probe.require(effective["model_provider"] == "deepseek" and effective["approvals_reviewer"] == "user",
                          "DeepSeek provider or user reviewer changed")
            probe.require(effective["sandbox_mode"] is None and effective["sandbox_workspace_write"] is None
                          and effective["auto_review"] is None, "Legacy sandbox or automatic review was enabled")
            probe.require(effective["default_permissions"] == "bytequay-deepseek", "Named permission profile changed")
            profile = effective["permissions"]["bytequay-deepseek"]
            probe.require(profile["extends"] == ":workspace" and profile["network"]["enabled"] is False,
                          "Worktree or network permission changed")
            filesystem_rules = {key: value for key, value in profile["filesystem"].items() if value is not None}
            probe.require(filesystem_rules == {":root": "deny", ":minimal": "read", str(app_data): "deny"},
                          "Protected app data permission changed")
            probe.require(effective["model_providers"]["deepseek"].get("env_key") is None, "Codex provider references a secret")
            shell = effective["shell_environment_policy"]
            probe.require(set(shell["include_only"]) == set(SHELL_ENV), "Shell environment allowlist changed")
            thread_config = {"model_provider": "deepseek", "model_catalog_json": str(catalog),
                             "model_providers": {"deepseek": {"name": "DeepSeek",
                                                               "base_url": config["model_providers.deepseek.base_url"],
                                                               "wire_api": "responses",
                                                               "requires_openai_auth": False, "supports_websockets": False,
                                                               "request_max_retries": 0}},
                             "web_search": "disabled", "approval_policy": "on-request", "approvals_reviewer": "user",
                             "features": {"skip_host_skill_discovery": True, "guardian_approval": False,
                                          "multi_agent": False, "multi_agent_v2": False},
                             "shell_environment_policy": {"inherit": "core", "include_only": SHELL_ENV,
                                                          "ignore_default_excludes": False,
                                                          "experimental_use_profile": False}}
            start = {"cwd": str(workspace), "model": "deepseek-flash", "modelProvider": "deepseek",
                     "approvalPolicy": "on-request", "approvalsReviewer": "user", "config": thread_config,
                     "ephemeral": False, "permissions": "bytequay-deepseek"}
            started = app.request("thread/start", start, "thread-start")["result"]
            probe.require(started["activePermissionProfile"]["id"] == "bytequay-deepseek",
                          "Thread did not activate DeepSeek named permission profile")
            thread_id = started["thread"]["id"]
            turn_params = {"threadId": thread_id, "input": [{"type": "text", "text": "Run the local shell fixture."}],
                           "cwd": str(workspace), "approvalPolicy": "on-request", "approvalsReviewer": "user",
                           "permissions": "bytequay-deepseek"}
            turn = app.request("turn/start", turn_params, "turn-start")["result"]["turn"]["id"]
            deadline = time.monotonic() + 20
            approvals = set()
            while not any(item.get("method") == "turn/completed" and item["params"]["turn"]["id"] == turn for item in app.notifications):
                for item in app.notifications:
                    if item.get("method") == "item/commandExecution/requestApproval" and item.get("id") not in approvals:
                        approvals.add(item["id"])
                        app.send({"id": item["id"], "result": {"decision": options.escalate_read or "decline"}})
                probe.require(time.monotonic() < deadline, "Fixture turn did not complete")
                time.sleep(0.01)
            completed = next(item["params"]["turn"] for item in app.notifications if item.get("method") == "turn/completed" and item["params"]["turn"]["id"] == turn)
            probe.require(completed["status"] == "completed", "Fixture turn failed")
            probe.require(len(provider.requests) == 2 and provider.proxy_requests == 2,
                          "Expected two proxied Responses requests")
            outputs = {item["call_id"]: item["output"] for item in provider.requests[1]["input"]
                       if item.get("type") == "function_call_output"}
            if options.escalate_read:
                probe.require(len(approvals) == 1, "Escalated command bypassed the user reviewer")
            if options.escalate_read != "decline":
                probe.require("READ_DENIED" in outputs["call_fixture"] and "READ_ALLOWED" not in outputs["call_fixture"],
                              "Model-facing shell read ByteQuay app data")
                probe.require("WRITE_OK" in outputs["call_fixture"] and (workspace / "changed.txt").read_text() == "changed",
                              "Model-facing shell could not edit the worktree")
                probe.require("LEAKED:" not in outputs["call_fixture"], "Model-facing shell inherited a secret")
            if not options.escalate_read:
                probe.require(not approvals, "App Server unexpectedly requested approval for the local shell")
    if options.escalate_read == "decline":
        print("PASS: native DeepSeek fixture routed the escalated command to the user reviewer, who declined it", flush=True)
    else:
        print("PASS: native DeepSeek fixture proxied two authenticated Responses requests; model shell saw no secrets, "
              "could not read sibling ByteQuay data, and could edit the nested worktree", flush=True)


if __name__ == "__main__":
    main()
