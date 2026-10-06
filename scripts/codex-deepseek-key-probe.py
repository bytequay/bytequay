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
import subprocess
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
MCP_TOOL = "report_development_result"
DEPENDENCY_POM = b"<project><modelVersion>4.0.0</modelVersion></project>\n"
SHELL_ENV = ["HOME", "LANG", "LC_ALL", "LOGNAME", "PATH", "SHELL", "TERM", "TMPDIR", "USER"]


class FakeResponses:
    def __init__(self, output, workspace, escalation=None, command=None, mcp=False):
        self.requests = []
        self.main_requests = []
        self.review_requests = []
        self.proxy_requests = 0
        self.mcp_calls = []
        self.escalation = escalation
        self.command = command
        self.command_suffix = ""
        self.mcp = mcp
        self.proxy_base = "/" + secrets.token_hex(16) + "/"
        self.proxy_path = self.proxy_base + "responses"
        self.mcp_path = self.proxy_base + "mcp"
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.requests.append(payload)
                probe.write_json(output / "provider-requests.json", fixture.requests)
                if (self.path != "/responses" or self.headers.get("Authorization") != "Bearer " + FAKE_KEY
                        or len(fixture.requests) > 8):
                    self.send_error(400)
                    return
                review = payload.get("text", {}).get("format", {}).get("type") == "json_schema"
                if review:
                    probe.require(payload.get("model") == "deepseek-flash", "Auto-review did not use the DeepSeek model")
                    fixture.review_requests.append(payload)
                    item = {"id": "msg_review", "type": "message", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": json.dumps({"outcome": "allow"}), "annotations": []}]}
                else:
                    fixture.main_requests.append(payload)
                index = len(fixture.main_requests)
                if not review and index == 1:
                    names = {tool.get("name") for tool in payload.get("tools", [])}
                    probe.require("exec_command" in names, "Expected Codex model-facing shell tool")
                    changed = shlex.quote(str(workspace / "changed.txt"))
                    command = fixture.command or ("for key in DEEPSEEK_API_KEY OPENAI_API_KEY GH_TOKEN BYTEQUAY_TOOL_TOKEN; do "
                               "if printenv \"$key\" >/dev/null; then printf 'LEAKED:%s\\n' \"$key\"; fi; done; "
                               "printf changed > " + changed + "; printf 'WRITE_OK\\n'")
                    arguments = {"cmd": command + fixture.command_suffix}
                    if fixture.escalation:
                        arguments.update({"sandbox_permissions": "require_escalated", "justification": "Run a disposable fixture command"})
                    item = {"id": "fc_fixture", "type": "function_call", "call_id": "call_fixture",
                            "name": "exec_command", "arguments": json.dumps(arguments)}
                elif not review and index == 2 and fixture.mcp:
                    namespace = next((tool for tool in payload.get("tools", []) if tool.get("name") == "mcp__bytequay"), None)
                    probe.require(namespace is not None and MCP_TOOL in json.dumps(namespace),
                                  "Expected model-facing ByteQuay development tool; got " + repr(namespace))
                    item = {"id": "fc_mcp", "type": "function_call", "call_id": "call_mcp",
                            "namespace": "mcp__bytequay", "name": MCP_TOOL,
                            "arguments": json.dumps({"readyForDelivery": False})}
                elif not review:
                    item = {"id": "msg_fixture", "type": "message", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "Local fixture completed.", "annotations": []}]}
                response = {"id": "resp_fixture_" + str(len(fixture.requests)), "object": "response", "created_at": int(time.time()),
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

            def do_GET(self):
                if self.path != fixture.proxy_base + "maven/fixture.pom" or "Authorization" in self.headers:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Content-Length", str(len(DEPENDENCY_POM)))
                self.end_headers()
                self.wfile.write(DEPENDENCY_POM)

            def do_POST(self):
                if "Authorization" in self.headers:
                    self.send_error(400)
                    return
                body = self.rfile.read(int(self.headers["Content-Length"]))
                if self.path == fixture.mcp_path and fixture.mcp:
                    payload = json.loads(body)
                    if "id" not in payload:
                        self.send_response(202)
                        self.end_headers()
                        return
                    method = payload.get("method")
                    if method == "initialize":
                        result = {"protocolVersion": payload["params"]["protocolVersion"], "capabilities": {"tools": {}},
                                  "serverInfo": {"name": "bytequay-fixture", "version": "1"}}
                    elif method == "tools/list":
                        result = {"tools": [{"name": MCP_TOOL, "description": "Report local development readiness",
                                             "inputSchema": {"type": "object", "properties": {"readyForDelivery": {"type": "boolean"}},
                                                             "required": ["readyForDelivery"], "additionalProperties": False}}]}
                    elif method == "tools/call":
                        params = payload["params"]
                        probe.require(params.get("name") == MCP_TOOL and params.get("arguments") == {"readyForDelivery": False},
                                      "Unexpected ByteQuay tool call: " + repr(params))
                        fixture.mcp_calls.append({"name": params["name"], "arguments": params["arguments"]})
                        result = {"content": [{"type": "text", "text": "Development report accepted"}], "isError": False}
                    else:
                        self.send_error(400)
                        return
                    response = json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": result}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(response)))
                    self.end_headers()
                    self.wfile.write(response)
                    return
                fixture.proxy_requests += 1
                if self.path != fixture.proxy_path:
                    self.send_error(400)
                    return
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
    parser.add_argument("--mode", choices=("local", "commit", "ci"), default="local")
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
    appserver_output.mkdir(parents=True, mode=0o700)
    if options.mode == "local":
        workspace.mkdir(mode=0o700)
    else:
        repository = app_data / "repository"
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "config", "user.name", "Fixture"], check=True)
        subprocess.run(["git", "-C", str(repository), "config", "user.email", "fixture@example.test"], check=True)
        (repository / "initial.txt").write_text("initial\n")
        subprocess.run(["git", "-C", str(repository), "add", "initial.txt"], check=True)
        subprocess.run(["git", "-C", str(repository), "commit", "-qm", "Initial fixture"], check=True)
        subprocess.run(["git", "-C", str(repository), "branch", "other"], check=True)
        subprocess.run(["git", "-C", str(repository), "worktree", "add", "-qb", "task", str(workspace)], check=True)
        (output / "home/.gitconfig").write_text("[user]\n\tname = Fixture\n\temail = fixture@example.test\n")
        global_ignore = output / "home/.config/git/ignore"
        global_ignore.parent.mkdir(parents=True)
        global_ignore.write_text("ignored-global.txt\n")
        (repository / ".git/info/exclude").write_text("ignored.txt\n")
        (workspace / "ignored.txt").write_text("ignored\n")
    catalog = output / "codex-home/deepseek-models.json"
    catalog.write_bytes((Path(__file__).resolve().parents[1] / "backend/agent-cli/src/main/resources/com/bytequay/agent/cli/codex/deepseek-models.json").read_bytes())
    (output / "codex-home/config.toml").write_text('web_search = "disabled"\n')
    environment = {"HOME": str(output / "home"), "CODEX_HOME": str(output / "codex-home"),
                   "CODEX_SQLITE_HOME": str(output / "sqlite"), "TMPDIR": str(output / "tmp"),
                   "XDG_CONFIG_HOME": str(output / "home/.config"),
                   "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/sh",
                   "LANG": "en_US.UTF-8"}
    probe.require(not {"DEEPSEEK_API_KEY", "OPENAI_API_KEY", "GH_TOKEN", "BYTEQUAY_TOOL_TOKEN"}.intersection(environment),
                  "Codex process environment contains a credential")
    command = None
    roots = []
    if options.mode != "local":
        git_directory = Path(subprocess.check_output(["git", "-C", str(workspace), "rev-parse", "--absolute-git-dir"], text=True).strip())
        common_directory = git_directory.parent.parent
        branch = common_directory / "refs/heads/task"
        reflog = common_directory / "logs/refs/heads/task"
        runtime = app_data / "task-runtime"
        runtime.mkdir(mode=0o700)
        (runtime / "ci.log").write_text("fixture CI log\n")
        if options.mode == "ci":
            (runtime / "tmp").mkdir(mode=0o700)
            environment["TMPDIR"] = str(runtime / "tmp")
            roots = [str(path) for path in (workspace, git_directory, common_directory / "objects", branch,
                                            Path(str(branch) + ".lock"), reflog, Path(str(reflog) + ".lock"))]
        other_ref = shlex.quote(str(common_directory / "refs/heads/other"))
        sandbox_git = "git"
        git_command = (sandbox_git + " rev-parse --verify HEAD >/dev/null && "
                       + sandbox_git + " status --porcelain >/dev/null 2>&1 && printf 'GIT_READ_OK\\n' && "
                       + sandbox_git + " check-ignore -q ignored.txt && "
                       "printf changed > changed.txt && " + sandbox_git + " add changed.txt && "
                       + sandbox_git + " commit -qm 'Fixture change' && "
                       + "printf 'COMMIT_OK\\n'")
        command = ("for key in DEEPSEEK_API_KEY OPENAI_API_KEY GH_TOKEN BYTEQUAY_TOOL_TOKEN; do "
                   "if printenv \"$key\" >/dev/null; then printf 'LEAKED:%s\\n' \"$key\"; fi; done; "
                   "printf 'GIT_CONFIG_GLOBAL=%s GIT_CONFIG_NOSYSTEM=%s\\n' "
                   "\"${GIT_CONFIG_GLOBAL-unset}\" \"${GIT_CONFIG_NOSYSTEM-unset}\"; " + git_command)
        if options.mode == "ci":
            log = shlex.quote(str(runtime / "ci.log"))
            host_tmp = shlex.quote("/tmp/bytequay-deepseek-probe-" + output.name)
            command += ("; if cat " + log + " >/dev/null 2>&1; then printf 'LOG_OK\\n'; fi; "
                        "if temp_file=$(mktemp \"${TMPDIR%/}/probe.XXXXXX\") && printf temp > \"$temp_file\" && "
                        "test \"$(cat \"$temp_file\")\" = temp; then printf 'TEMP_OK\\n'; "
                        "else printf 'TEMP_DENIED\\n'; fi; "
                        "if printf temp > " + host_tmp + " 2>/dev/null; then printf 'HOST_TMP_ALLOWED\\n'; "
                        "else printf 'HOST_TMP_DENIED\\n'; fi; "
                        "if cat " + other_ref + " >/dev/null 2>&1; then printf 'OTHER_REF_READ_ALLOWED\\n'; "
                        "else printf 'OTHER_REF_READ_DENIED\\n'; fi; "
                        "if printf damaged > " + other_ref + " 2>/dev/null; then printf 'OTHER_REF_ALLOWED\\n'; "
                        "else printf 'OTHER_REF_DENIED\\n'; fi")
    with FakeResponses(output, workspace, options.mode == "commit", command, options.mode != "local") as provider:
        ci = options.mode == "ci"
        if ci:
            provider.command_suffix = ("; /usr/bin/curl -fsS http://127.0.0.1:" + str(provider.proxy.server_port)
                                       + provider.proxy_base + "maven/fixture.pom -o dependency.pom"
                                       + " && printf 'FETCH_OK\\n'")
        reviewer = "auto_review"
        shell_env = SHELL_ENV
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config",
                     "--enable", "skip_host_skill_discovery", "--enable", "guardian_approval",
                     "--disable", "multi_agent", "--disable", "multi_agent_v2", "--disable", "network_proxy"]
        config = {"cli_auth_credentials_store": "ephemeral", "model_provider": "deepseek", "model": "deepseek-flash",
                  "model_catalog_json": str(catalog), "model_providers.deepseek.name": "DeepSeek",
                  "model_providers.deepseek.base_url": "http://127.0.0.1:" + str(provider.proxy.server_port) + provider.proxy_base,
                  "model_providers.deepseek.wire_api": "responses",
                  "model_providers.deepseek.requires_openai_auth": False,
                  "model_providers.deepseek.supports_websockets": False,
                  "model_providers.deepseek.request_max_retries": 0,
                  "web_search": "disabled" if ci else "live",
                  "shell_environment_policy.inherit": "core",
                  "shell_environment_policy.ignore_default_excludes": False,
                  "shell_environment_policy.experimental_use_profile": False,
                  "shell_environment_policy.include_only": shell_env,
                  "sandbox_workspace_write.network_access": True,
                  "approval_policy": "on-request", "approvals_reviewer": reviewer}
        if ci:
            config.update({"sandbox_workspace_write.writable_roots": roots,
                           "sandbox_workspace_write.exclude_tmpdir_env_var": True,
                           "sandbox_workspace_write.exclude_slash_tmp": True})
        if provider.mcp:
            config["mcp_servers.bytequay.url"] = "http://127.0.0.1:" + str(provider.proxy.server_port) + provider.mcp_path
            config["mcp_servers.bytequay.default_tools_approval_mode"] = "approve"
        for key, value in config.items():
            arguments.extend(["-c", key + "=" + json.dumps(value)])
        probe.require(FAKE_KEY not in json.dumps(arguments), "Codex arguments contain the DeepSeek key")
        probe.require(not any("bearer_token_env_var" in argument for argument in arguments),
                      "Codex arguments reference a task MCP bearer credential")
        with probe.AppServer(arguments, environment, appserver_output, "deepseek") as app:
            effective = app.request("config/read", {"cwd": str(workspace), "includeLayers": True}, "config")["result"]["config"]
            probe.require(effective["model_provider"] == "deepseek", "DeepSeek provider changed")
            proxy_feature = effective["features"]["network_proxy"]
            probe.require((proxy_feature["enabled"] if isinstance(proxy_feature, dict) else proxy_feature) is False,
                          "Managed network proxy would block JVM builds")
            probe.require(effective["default_permissions"] is None and not any(
                name.startswith("bytequay-deepseek") for name in (effective["permissions"] or {})),
                "DeepSeek inherited a separate permission profile")
            probe.require(effective["approval_policy"] == "on-request" and effective["approvals_reviewer"] == reviewer
                          and effective["features"]["guardian_approval"] is True,
                          "DeepSeek did not enable native automatic review")
            if ci:
                sandbox = effective["sandbox_workspace_write"]
                probe.require(sandbox["writable_roots"] == roots and sandbox["network_access"] is True
                              and sandbox["exclude_tmpdir_env_var"] and sandbox["exclude_slash_tmp"],
                              "DeepSeek CI permission configuration differs from Codex CI")
            else:
                probe.require(effective["sandbox_workspace_write"]["network_access"] is True,
                              "DeepSeek attended permission configuration differs from Codex development")
            probe.require(effective["model_providers"]["deepseek"].get("env_key") is None, "Codex provider references a secret")
            if provider.mcp:
                servers = effective["mcp_servers"]
                probe.require(set(servers) == {"bytequay"} and servers["bytequay"]["url"] == config["mcp_servers.bytequay.url"]
                              and servers["bytequay"].get("bearer_token_env_var") is None,
                              "DeepSeek task MCP inherited a bearer credential or another server")
            shell = effective["shell_environment_policy"]
            probe.require(shell["include_only"] == shell_env and shell["inherit"] == "core"
                          and shell["ignore_default_excludes"] is False and shell["set"] is None,
                          "DeepSeek shell environment policy differs from Codex")
            thread_config = {"model_provider": "deepseek", "model_catalog_json": str(catalog),
                             "model_providers": {"deepseek": {"name": "DeepSeek",
                                                               "base_url": config["model_providers.deepseek.base_url"],
                                                               "wire_api": "responses",
                                                               "requires_openai_auth": False, "supports_websockets": False,
                                                               "request_max_retries": 0}},
                             "web_search": "disabled" if ci else "live",
                             "features": {"skip_host_skill_discovery": True, "guardian_approval": True,
                                          "multi_agent": False, "multi_agent_v2": False, "network_proxy": False},
                             "shell_environment_policy": {"inherit": "core", "include_only": shell_env,
                                                          "ignore_default_excludes": False,
                                                          "experimental_use_profile": False},
                             "sandbox_workspace_write": {"network_access": True}}
            if ci:
                thread_config.update({"approval_policy": "on-request", "approvals_reviewer": reviewer,
                                      "sandbox_workspace_write": {"writable_roots": roots, "network_access": True,
                                                                  "exclude_tmpdir_env_var": True, "exclude_slash_tmp": True}})
            sandbox_policy = {"type": "workspaceWrite", "writableRoots": roots if ci else [str(workspace)],
                              "networkAccess": True, "excludeTmpdirEnvVar": ci, "excludeSlashTmp": ci}
            start = {"cwd": str(workspace), "model": "deepseek-flash", "modelProvider": "deepseek",
                     "approvalPolicy": "on-request", "approvalsReviewer": reviewer, "config": thread_config,
                     "ephemeral": False, "sandbox": "workspace-write"}
            started = app.request("thread/start", start, "thread-start")["result"]
            probe.require(started["approvalPolicy"] == start["approvalPolicy"]
                          and started["approvalsReviewer"] == reviewer
                          and started["activePermissionProfile"] is None
                          and started["sandbox"]["type"] == "workspaceWrite"
                          and started["sandbox"]["writableRoots"] == (roots[1:] if ci else [])
                          and started["sandbox"]["networkAccess"] is True,
                          "Thread did not activate the Codex approval and sandbox policy")
            thread_id = started["thread"]["id"]
            if provider.mcp:
                status = app.request("mcpServerStatus/list", {"threadId": thread_id, "limit": 100,
                                                              "detail": "toolsAndAuthOnly"}, "tools")["result"]
                probe.require(len(status["data"]) == 1 and status["data"][0]["name"] == "bytequay",
                              "ByteQuay task MCP did not connect")
            turn_params = {"threadId": thread_id, "input": [{"type": "text", "text": "Run the local shell fixture."}],
                           "cwd": str(workspace), "approvalPolicy": start["approvalPolicy"], "approvalsReviewer": reviewer,
                           "sandboxPolicy": sandbox_policy}
            turn = app.request("turn/start", turn_params, "turn-start")["result"]["turn"]["id"]
            deadline = time.monotonic() + 20
            while not any(item.get("method") == "turn/completed" and item["params"]["turn"]["id"] == turn for item in app.notifications):
                probe.require(not any(item.get("method") == "item/commandExecution/requestApproval" for item in app.notifications),
                              "Native automatic review sent a command approval to the user")
                probe.require(time.monotonic() < deadline, "Fixture turn did not complete")
                time.sleep(0.01)
            probe.require(not any(item.get("method") == "item/commandExecution/requestApproval" for item in app.notifications)
                          and len(provider.review_requests) == (1 if options.mode == "commit" else 0),
                          "Native automatic review did not handle the fixture escalation")
            completed = next(item["params"]["turn"] for item in app.notifications if item.get("method") == "turn/completed" and item["params"]["turn"]["id"] == turn)
            probe.require(completed["status"] == "completed", "Fixture turn failed")
            expected_requests = 3 if provider.mcp else 2
            probe.require(len(provider.main_requests) == expected_requests and provider.proxy_requests == len(provider.requests),
                          "Expected authenticated Responses requests")
            outputs = {item["call_id"]: item["output"] for item in provider.main_requests[1]["input"]
                       if item.get("type") == "function_call_output"}
            if options.mode == "local":
                probe.require("WRITE_OK" in outputs["call_fixture"] and (workspace / "changed.txt").read_text() == "changed",
                              "Model-facing shell could not edit the worktree")
            else:
                probe.require("GIT_CONFIG_GLOBAL=unset GIT_CONFIG_NOSYSTEM=unset" in outputs["call_fixture"]
                              and "GIT_READ_OK" in outputs["call_fixture"] and "COMMIT_OK" in outputs["call_fixture"]
                              and (workspace / "changed.txt").read_text() == "changed",
                              "Model-facing shell could not commit in the linked worktree")
                subject = subprocess.check_output(["git", "-C", str(workspace), "log", "-1", "--format=%s"], text=True).strip()
                probe.require(subject == "Fixture change", "Linked worktree HEAD did not advance")
                if options.mode == "ci":
                    probe.require("LOG_OK" in outputs["call_fixture"] and "FETCH_OK" in outputs["call_fixture"]
                                  and (workspace / "dependency.pom").read_bytes() == DEPENDENCY_POM
                                  and "TEMP_DENIED" in outputs["call_fixture"]
                                  and "TEMP_OK" not in outputs["call_fixture"]
                                  and "HOST_TMP_DENIED" in outputs["call_fixture"]
                                  and "HOST_TMP_ALLOWED" not in outputs["call_fixture"]
                                  and "OTHER_REF_DENIED" in outputs["call_fixture"]
                                  and "OTHER_REF_ALLOWED" not in outputs["call_fixture"]
                                  and "OTHER_REF_READ_ALLOWED" in outputs["call_fixture"]
                                  and "OTHER_REF_READ_DENIED" not in outputs["call_fixture"],
                                  "CI sandbox did not match Codex's temporary and Git scope")
                mcp_items = [item["params"]["item"] for item in app.notifications if item.get("method") == "item/completed"
                             and item.get("params", {}).get("item", {}).get("type") == "mcpToolCall"]
                probe.require(provider.mcp_calls == [{"name": MCP_TOOL, "arguments": {"readyForDelivery": False}}]
                              and len(mcp_items) == 1 and mcp_items[0]["tool"] == MCP_TOOL
                              and mcp_items[0]["status"] == "completed",
                              "ByteQuay task MCP call did not complete")
                mcp_outputs = {item["call_id"]: item["output"] for item in provider.main_requests[2]["input"]
                               if item.get("type") == "function_call_output"}
                probe.require("Development report accepted" in str(mcp_outputs.get("call_mcp", "")),
                              "ByteQuay task MCP result did not return to the model")
            probe.require("LEAKED:" not in outputs["call_fixture"], "Model-facing shell inherited a secret")
        if options.mode != "local":
            expected_trust = "[projects." + json.dumps(str(repository)) + "]\ntrust_level = \"trusted\"\n"
            saved_config = (output / "codex-home/config.toml").read_text()
            probe.require('web_search = "disabled"' in saved_config and expected_trust in saved_config,
                          "Codex did not preserve user configuration and project trust")
            with probe.AppServer(arguments, environment, appserver_output, "deepseek-restart") as restarted:
                reloaded = restarted.request("config/read", {"cwd": str(workspace), "includeLayers": True}, "restart-config")["result"]
                probe.require(reloaded["config"]["model_provider"] == "deepseek"
                              and reloaded["config"]["default_permissions"] is None
                              and not any(layer["name"]["type"] == "project" for layer in reloaded["layers"]),
                              "Codex restart inherited an unexpected provider, permission, or project config")
                resumed = restarted.request("thread/resume", {**start, "threadId": thread_id}, "restart-resume")["result"]
                probe.require(resumed["approvalPolicy"] == start["approvalPolicy"]
                              and resumed["approvalsReviewer"] == reviewer
                              and resumed["activePermissionProfile"] is None
                              and resumed["sandbox"]["type"] == "workspaceWrite"
                              and resumed["sandbox"]["writableRoots"] == (roots[1:] if ci else [])
                              and resumed["sandbox"]["networkAccess"] is True,
                              "Codex restart did not resume the shared approval and sandbox policy")
    if options.mode != "local":
        print("PASS: native DeepSeek " + options.mode + " profile committed in a linked worktree with fake provider credentials, "
              "called ByteQuay task MCP, used Codex sandbox permissions, and resumed after Codex saved project trust", flush=True)
    else:
        print("PASS: native DeepSeek fixture proxied two authenticated Responses requests; model shell saw no secrets, "
              "and could edit the worktree under Codex sandbox permissions", flush=True)


if __name__ == "__main__":
    main()
