#!/usr/bin/env python3
"""Check native Codex CI permissions with disposable Git data and a local fake provider.

Run: python3 scripts/codex-ci-sandbox-probe.py --run-native
Requires macOS, Python 3.9+, Git, Codex, and permission to run the native sandbox
and bind loopback. Never contacts a real model or Git remote, copies credentials,
or changes HOME. All modified files belong to the printed temporary directory.
This is an opt-in native contract check, separate from ByteQuay supervisor tests.
"""

import argparse
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("completion_probe", Path(__file__).with_name("codex-completion-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

DEPENDENCY_POM = b"<project><modelVersion>4.0.0</modelVersion></project>\n"


class LocalProvider:
    def __init__(self, output):
        self.requests = []
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.path != "/maven/fixture.pom":
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Content-Length", str(len(DEPENDENCY_POM)))
                self.end_headers()
                self.wfile.write(DEPENDENCY_POM)

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.requests.append({"path": self.path, "authorizationPresent": "Authorization" in self.headers, "body": payload})
                probe.write_json(output / "provider-requests.json", fixture.requests)
                if self.path != "/v1/responses" or "Authorization" in self.headers or len(fixture.requests) > 3:
                    self.send_error(400)
                    return
                index = len(fixture.requests)
                if index == 1:
                    name = "exec_command"
                    arguments = {"cmd": "printf unexpected > " + shlex.quote(str(output / "escaped.txt")),
                                 "sandbox_permissions": "require_escalated", "justification": "Disposable native denial fixture"}
                elif index == 2:
                    name = "request_user_input"
                    arguments = {"questions": [{"id": "fixture", "header": "Fixture", "question": "Continue the fixture?",
                                                "options": [{"label": "Continue", "description": "Continue."},
                                                            {"label": "Stop", "description": "Stop."}]}]}
                if index < 3:
                    item = {"id": "fc_" + str(index), "type": "function_call", "call_id": "call_" + str(index),
                            "name": name, "arguments": json.dumps(arguments)}
                else:
                    item = {"id": "msg_fixture", "type": "message", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "Local fixture completed.", "annotations": []}]}
                response = {"id": "resp_fixture_" + str(index), "object": "response", "created_at": int(time.time()),
                            "status": "completed", "model": "fixture-model", "output": [item],
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

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def verify_config(app, workspace, roots, phase):
    result = app.request("config/read", {"cwd": str(workspace), "includeLayers": True}, phase)["result"]
    flags = [layer["config"] for layer in result["layers"] if layer["name"]["type"] == "sessionFlags"]
    probe.require(len(flags) == 1, "Expected one sessionFlags config layer")
    for config in (result["config"], flags[0]):
        probe.require(config["approval_policy"] == "on-request" and config["approvals_reviewer"] == "user", "Unexpected approval policy")
        probe.require(config["web_search"] == "disabled", "Web search became available")
        for name in ("guardian_approval", "multi_agent", "multi_agent_v2"):
            probe.require(config["features"][name] is False, "Unexpected enabled feature: " + name)
        proxy = config["features"]["network_proxy"]
        probe.require((proxy["enabled"] if isinstance(proxy, dict) else proxy) is False,
                      "Managed network proxy would block JVM builds")
        sandbox = config["sandbox_workspace_write"]
        probe.require(sandbox["writable_roots"] == roots, "Config roots changed")
        probe.require(sandbox["network_access"] is True, "CI build network permission changed")
        probe.require(sandbox["exclude_tmpdir_env_var"] and sandbox["exclude_slash_tmp"], "Temporary directories became writable")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-native", action="store_true", help="Explicitly run the installed CLI and native sandbox")
    parser.add_argument("--codex", default=shutil.which("codex") or "/opt/homebrew/bin/codex")
    parser.add_argument("--output-parent", type=Path, help="Parent for a new isolated fixture directory")
    options = parser.parse_args()
    probe.require(options.run_native, "Pass --run-native to run this opt-in fixture")
    probe.require(sys.platform == "darwin", "This fixture verifies the macOS native sandbox")
    output = Path(tempfile.mkdtemp(prefix="bytequay-codex-ci-", dir=options.output_parent)).resolve()
    print("Probe artifacts: " + str(output), flush=True)
    repository = output / "repository"
    workspace = output / "workspace"
    for directory in (repository, output / "profile", output / "sqlite", output / "ci-logs", output / "empty-hooks"):
        directory.mkdir(mode=0o700)
    log = output / "ci-logs/build.log"
    log.write_text("Read-only CI evidence.\n")
    environment = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TMPDIR")}
    environment.update({"CODEX_HOME": str(output / "profile"), "CODEX_SQLITE_HOME": str(output / "sqlite"),
                        "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"})

    def git(*arguments, cwd=repository):
        return subprocess.check_output(["git", *arguments], cwd=cwd, env=environment, text=True, stderr=subprocess.STDOUT).strip()

    git("init", "-b", "main")
    for key, value in {"user.name": "Local Fixture", "user.email": "fixture@example.invalid", "gc.auto": "0",
                       "commit.gpgsign": "false", "core.hooksPath": str(output / "empty-hooks")}.items():
        git("config", key, value)
    (repository / "base.txt").write_text("Base fixture.\n")
    git("add", "base.txt")
    git("commit", "-m", "Seed local fixture")
    git("worktree", "add", "-b", "ci/smoke", str(workspace))
    gitdir = Path(git("rev-parse", "--absolute-git-dir", cwd=workspace))
    common = repository / ".git"
    roots = [str(path) for path in (workspace, gitdir, common / "objects", common / "refs/heads/ci/smoke",
                                   common / "refs/heads/ci/smoke.lock", common / "logs/refs/heads/ci/smoke", common / "logs/refs/heads/ci/smoke.lock")]
    protected = [common / "refs/heads/ci/sibling", common / "config", common / "hooks/ci-probe",
                 common / "refs/heads/main", log, output / "outside.txt"]
    before = {path: path.read_bytes() if path.exists() else None for path in protected}
    policy = {"type": "workspaceWrite", "writableRoots": roots, "networkAccess": True,
              "excludeTmpdirEnvVar": True, "excludeSlashTmp": True}
    thread_config = {"web_search": "disabled", "features": {"guardian_approval": False, "multi_agent": False,
                                                              "multi_agent_v2": False, "network_proxy": False},
                     "sandbox_workspace_write": {"writable_roots": roots, "network_access": True,
                                                 "exclude_tmpdir_env_var": True, "exclude_slash_tmp": True}}
    with LocalProvider(output) as provider:
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config",
                     "--enable", "skip_host_skill_discovery", "--disable", "guardian_approval", "--disable", "multi_agent",
                     "--disable", "multi_agent_v2", "--disable", "network_proxy"]
        config = {"model_provider": "fixture", "model": "fixture-model", "model_providers.fixture.name": "Local CI fixture",
                  "model_providers.fixture.base_url": "http://127.0.0.1:" + str(provider.server.server_port) + "/v1",
                  "model_providers.fixture.wire_api": "responses", "model_providers.fixture.requires_openai_auth": False,
                  "model_providers.fixture.supports_websockets": False, "model_providers.fixture.request_max_retries": 0,
                  "approval_policy": "on-request", "approvals_reviewer": "user", "sandbox_mode": "workspace-write",
                  "shell_environment_policy.inherit": "none", "shell_environment_policy.set.PATH": environment["PATH"],
                  "shell_environment_policy.set.GIT_CONFIG_GLOBAL": "/dev/null", "shell_environment_policy.set.GIT_CONFIG_NOSYSTEM": "1",
                  **thread_config}
        for key, value in config.items():
            # Nested values need TOML inline tables, so flatten these known sections.
            for name, setting in value.items() if isinstance(value, dict) else [(None, value)]:
                arguments.extend(["-c", key + ("." + name if name else "") + "=" + json.dumps(setting)])
        probe.write_json(output / "launch.json", {"arguments": arguments, "environment": environment})
        with probe.AppServer(arguments, environment, output, "ci") as app:
            verify_config(app, workspace, roots, "process-config")

            def command(script, phase, writable_roots=roots):
                return app.request("command/exec", {"cwd": str(workspace), "command": ["/bin/sh", "-c", script],
                                                     "sandboxPolicy": {**policy, "writableRoots": writable_roots},
                                                     "timeoutMs": 15000, "outputBytesCap": 5000}, phase)["result"]

            result = command("printf changed > changed.txt; cat " + shlex.quote(str(log)) + "; git add changed.txt", "worktree-only", [str(workspace)])
            probe.require((workspace / "changed.txt").read_text() == "changed", "Worktree edits failed")
            probe.require("Read-only CI evidence." in result["stdout"], "CI logs are not readable")
            probe.require(result["exitCode"] != 0 and "index.lock" in result["stderr"], "Worktree-only sandbox unexpectedly allowed Git writes")
            result = command("git add changed.txt && git commit -m 'Commit local CI fixture'", "local-commit")
            probe.require(result["exitCode"] == 0 and git("status", "--porcelain", cwd=workspace) == "", "Exact Git roots did not permit a clean commit")
            probe.require(git("log", "-1", "--format=%s", cwd=workspace) == "Commit local CI fixture", "Expected commit is missing")
            for index, path in enumerate(protected):
                result = command("printf forbidden > " + shlex.quote(str(path)), "protected-path-" + str(index))
                probe.require(result["exitCode"] != 0, "Protected path accepted writes: " + str(path))
                probe.require((path.read_bytes() if path.exists() else None) == before[path], "Protected path changed: " + str(path))
            artifact_url = "http://127.0.0.1:" + str(provider.server.server_port) + "/maven/fixture.pom"
            result = command("/usr/bin/curl -fsS " + artifact_url + " -o dependency.pom", "dependency-download")
            probe.require(result["exitCode"] == 0 and (workspace / "dependency.pom").read_bytes() == DEPENDENCY_POM,
                          "CI sandbox could not fetch a local dependency artifact")
            java = shutil.which("java")
            javac = shutil.which("javac")
            probe.require(java is not None and javac is not None, "A JDK is needed for the JVM network fixture")
            source = output / "FetchArtifact.java"
            source.write_text("""
                    import java.net.URL;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class FetchArtifact {
                        public static void main(String[] args) throws Exception {
                            try (var input = new URL(args[0]).openStream()) {
                                Files.write(Path.of(args[1]), input.readAllBytes());
                            }
                        }
                    }
                    """)
            subprocess.run([javac, str(source)], cwd=output, check=True, capture_output=True, text=True)
            result = command(" ".join(shlex.quote(value) for value in
                                      (java, "-cp", str(output), "FetchArtifact", artifact_url, "dependency-java.pom")),
                             "jvm-dependency-download")
            probe.require(result["exitCode"] == 0 and (workspace / "dependency-java.pom").read_bytes() == DEPENDENCY_POM,
                          "CI sandbox could not fetch a local dependency artifact through the JVM")
            restricted = app.request("command/exec", {"cwd": str(workspace), "command": ["/usr/bin/curl", "-fsS", artifact_url],
                                                      "sandboxPolicy": {"type": "readOnly", "networkAccess": False},
                                                      "timeoutMs": 15000, "outputBytesCap": 5000}, "restricted-network")["result"]
            probe.require(restricted["exitCode"] != 0, "Restricted sandbox unexpectedly fetched a dependency artifact")
            start = {"cwd": str(workspace), "model": "fixture-model", "modelProvider": "fixture", "approvalPolicy": "on-request",
                     "approvalsReviewer": "user", "sandbox": "workspace-write", "config": thread_config}
            started = app.request("thread/start", {**start, "ephemeral": False}, "thread-start")["result"]
            thread_id = started["thread"]["id"]
            turn = app.request("turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": "Run the local denial fixture."}],
                                               "cwd": str(workspace), "approvalPolicy": "on-request", "approvalsReviewer": "user",
                                               "sandboxPolicy": policy}, "turn-start")["result"]["turn"]["id"]
            deadline = time.monotonic() + 15
            approvals = []
            while True:
                probe.require(time.monotonic() < deadline, "Fixture turn did not complete after approval response")
                event = app.incoming.get(timeout=max(0.01, deadline - time.monotonic()))
                probe.require(not event.get("closed"), "App Server closed during fixture turn")
                if event.get("method") == "item/commandExecution/requestApproval":
                    probe.require(event["params"]["turnId"] == turn, "Approval belongs to another turn")
                    approvals.append(event)
                    app.send({"id": event["id"], "result": {"decision": "decline"}})
                elif event.get("method") == "turn/completed" and event["params"]["turn"]["id"] == turn:
                    completed = event["params"]["turn"]
                    break
                else:
                    probe.require("method" not in event or "id" not in event, "Unexpected server request: " + json.dumps(event))
            probe.require(completed["status"] == "completed", "Fixture turn failed")
            resumed = app.request("thread/resume", {**start, "threadId": thread_id}, "thread-resume")["result"]
            for response in (started, resumed):
                probe.require(response["approvalPolicy"] == "on-request" and response["approvalsReviewer"] == "user", "Thread approval binding changed")
                probe.require(response["sandbox"] == {**policy, "writableRoots": roots[1:]}, "Thread sandbox binding changed")
            verify_config(app, workspace, roots, "resumed-config")
            probe.require(len(approvals) == 1, "Expected one ByteQuay-routed command approval")
            probe.require(len(provider.requests) == 3, "Expected three local provider requests")
            tool_names = {tool.get("name") for tool in provider.requests[0]["body"]["tools"]}
            probe.require(not tool_names.intersection({"spawn_agent", "web_search", "web"}), "Child agents or web tools became available")
            outputs = {item["call_id"]: item["output"] for item in provider.requests[-1]["body"]["input"] if item.get("type") == "function_call_output"}
            probe.require("rejected by user" in outputs["call_1"], "Escalated command was not denied by the approval response")
            probe.require("request_user_input is unavailable in Default mode" in outputs["call_2"], "Human input was not rejected locally")
            probe.require(not (output / "escaped.txt").exists(), "Escalated command executed")

        (workspace / "base.txt").write_text("Task change.\n")
        git("add", "base.txt", cwd=workspace)
        git("commit", "-m", "Create task conflict", cwd=workspace)
        (repository / "base.txt").write_text("Base branch change.\n")
        git("add", "base.txt")
        git("commit", "-m", "Create base conflict")
        try:
            git("rebase", "main", cwd=workspace)
            raise RuntimeError("Expected a real rebase conflict")
        except subprocess.CalledProcessError as failure:
            probe.require("CONFLICT" in failure.output, "Rebase failed without the expected conflict")
        rebase = gitdir / "rebase-merge"
        probe.require(rebase.is_dir() and (rebase / "head-name").read_text().strip() == "refs/heads/ci/smoke", "Expected task rebase metadata")
        detached_head = git("rev-parse", "HEAD", cwd=workspace)
        rebase_roots = roots[:3]
        rebase_policy = {**policy, "writableRoots": rebase_roots}
        rebase_config = {**thread_config, "sandbox_workspace_write": {**thread_config["sandbox_workspace_write"], "writable_roots": rebase_roots}}
        rebase_arguments = ["sandbox_workspace_write.writable_roots=" + json.dumps(rebase_roots)
                            if value.startswith("sandbox_workspace_write.writable_roots=") else value for value in arguments]
        protected.extend([common / "refs/heads/ci/smoke", common / "refs/heads/ci/smoke.lock"])
        before = {path: path.read_bytes() if path.exists() else None for path in protected}
        with probe.AppServer(rebase_arguments, environment, output, "rebase") as app:
            verify_config(app, workspace, rebase_roots, "rebase-config")
            started = app.request("thread/start", {**start, "config": rebase_config, "ephemeral": True}, "rebase-thread-start")["result"]
            probe.require(started["sandbox"] == {**rebase_policy, "writableRoots": rebase_roots[1:]}, "Rebase thread sandbox binding changed")

            def rebase_command(script, phase):
                return app.request("command/exec", {"cwd": str(workspace), "command": ["/bin/sh", "-c", script], "sandboxPolicy": rebase_policy,
                                                     "timeoutMs": 15000, "outputBytesCap": 5000}, phase)["result"]

            result = rebase_command("printf 'Resolved task and base changes.\\n' > base.txt && git add base.txt", "stage-conflict")
            probe.require(result["exitCode"] == 0 and git("diff", "--name-only", "--diff-filter=U", cwd=workspace) == "", "Conflict was not staged")
            probe.require(git("diff", "--cached", "--name-only", cwd=workspace) == "base.txt", "Expected staged resolution is missing")
            probe.require(rebase.is_dir() and git("rev-parse", "HEAD", cwd=workspace) == detached_head, "Agent-side staging advanced the rebase")
            for index, path in enumerate(protected):
                result = rebase_command("printf forbidden > " + shlex.quote(str(path)), "rebase-protected-path-" + str(index))
                probe.require(result["exitCode"] != 0, "Rebase sandbox accepted a protected write: " + str(path))
                probe.require((path.read_bytes() if path.exists() else None) == before[path], "Rebase changed protected metadata: " + str(path))
    print("PASS: exact Git roots permit edits and commits; shared refs/config/hooks, CI logs and outside writes stay protected; "
          "curl and JVM fetch a local dependency inside CI while a restricted command remains offline; "
          "on-request/user survives start/turn/resume, escalation is routed and denied, human input is unavailable, and child agents are disabled; "
          "a detached rebase conflict can be staged with no writable branch refs and remains open for ByteQuay.", flush=True)


if __name__ == "__main__":
    main()
