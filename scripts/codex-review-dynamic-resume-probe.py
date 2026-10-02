#!/usr/bin/env python3
"""Check review child read/write boundaries and root tools across a native resume.

Run: python3 scripts/codex-review-dynamic-resume-probe.py
Requires macOS, Python 3.9+, Codex 0.156.1+, and permission to bind a loopback socket.
Uses the production child gate with a keyless fake model and MCP server in a fresh profile.
"""

import argparse
import http.server
import importlib.util
import json
from pathlib import Path
import shlex
import shutil
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("completion_probe", Path(__file__).with_name("codex-completion-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

TOOL = "review_save_finding"


def child_request(request):
    return any(item.get("type") == "agent_message" and any(
        isinstance(part, dict) and part.get("text", "").startswith("Message Type: NEW_TASK")
        for part in item.get("content", [])) for item in request.get("input", []))


def rejected_child_call(requests):
    return any(child_request(request) and any(
        item.get("type") == "function_call_output" and item.get("call_id") == "child_0" and
        TOOL in item.get("output", "") and
        any(word in item.get("output", "").lower() for word in ("unsupported", "unknown"))
        for item in request.get("input", [])) for request in requests)


class Fixture:
    def __init__(self, output):
        self.output = output
        self.requests = []
        self.mcp_calls = []
        self.resumed = False
        self.lock = threading.Lock()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, payload, content_type="application/json"):
                data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/mcp":
                    if "id" not in body:
                        self.send_response(202)
                        self.end_headers()
                        return
                    method = body.get("method")
                    if method == "initialize":
                        result = {"protocolVersion": body["params"]["protocolVersion"], "capabilities": {"tools": {}},
                                  "serverInfo": {"name": "bytequay-fixture", "version": "1"}}
                    elif method == "tools/list":
                        result = {"tools": [{"name": "review_get_context", "description": "Read fixture context",
                                             "inputSchema": {"type": "object", "properties": {}}}]}
                    elif method == "tools/call":
                        with fixture.lock:
                            fixture.mcp_calls.append(body["params"]["name"])
                        result = {"content": [{"type": "text", "text": "Fixture read"}], "isError": False}
                    elif method == "resources/list":
                        result = {"resources": []}
                    else:
                        result = {"resourceTemplates": []}
                    self.reply({"jsonrpc": "2.0", "id": body["id"], "result": result})
                    return
                if self.path != "/v1/responses":
                    self.send_error(404)
                    return
                with fixture.lock:
                    fixture.requests.append(body)
                    index = len(fixture.requests)
                    resumed = fixture.resumed
                child = child_request(body)
                calls = [item for item in body.get("input", []) if item.get("type") == "function_call"]
                if resumed:
                    calls = [item for item in calls if item.get("call_id", "").startswith("resumed_")]
                    sequence = [] if child else [(TOOL, {"finding": "resumed root"}, None)]
                elif child:
                    sequence = [(TOOL, {"finding": "child attempt"}, None),
                                ("exec_command", {"cmd": "touch child-wrote", "workdir": str(fixture.output / "workspace")}, None),
                                ("review_get_context", {}, "mcp__bytequay")]
                else:
                    sequence = [("spawn_agent", {"message": "Inspect the fixture", "task_name": "fixture_child",
                                                  "fork_turns": "none"}, "collaboration"),
                                (TOOL, {"finding": "initial root"}, None)]
                stage = len(calls)
                if stage < len(sequence):
                    name, arguments, namespace = sequence[stage]
                    call_id = ("resumed_" if resumed else "child_" if child else "root_") + str(stage)
                    item = {"id": "fc_" + call_id, "type": "function_call", "name": name,
                            "call_id": call_id, "arguments": json.dumps(arguments)}
                    if namespace:
                        item["namespace"] = namespace
                else:
                    item = {"id": "msg_done", "type": "message", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "Fixture done.", "annotations": []}]}
                response = {"id": "resp_fixture_" + str(index), "object": "response", "created_at": int(time.time()),
                            "status": "completed", "model": "fixture-model", "output": [item],
                            "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3,
                                      "input_tokens_details": {"cached_tokens": 0},
                                      "output_tokens_details": {"reasoning_tokens": 0}}}
                events = [{"type": "response.created", "response": {**response, "status": "in_progress", "output": []}},
                          {"type": "response.output_item.added", "output_index": 0, "item": item},
                          {"type": "response.output_item.done", "output_index": 0, "item": item},
                          {"type": "response.completed", "response": response}]
                self.reply("".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n"
                                   for event in events).encode(), "text/event-stream")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        probe.write_json(self.output / "provider-requests.json", self.requests)
        probe.write_json(self.output / "mcp-calls.json", self.mcp_calls)


def run_turn(app, thread_id, text, request_id, callback_text):
    turn = app.request("turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": text}]}, request_id)["result"]["turn"]
    answered = set()
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        for event in list(app.notifications):
            if event.get("method") != "item/tool/call" or event.get("id") in answered:
                continue
            answered.add(event["id"])
            params = event["params"]
            exact_root = params["threadId"] == thread_id and params["turnId"] == turn["id"] and params["tool"] == TOOL
            app.send({"id": event["id"], "result": {"contentItems": [{"type": "inputText", "text": callback_text}],
                                                   "success": exact_root}})
        completed = any(event.get("method") == "turn/completed" and
                        event.get("params", {}).get("threadId") == thread_id and
                        event["params"]["turn"]["id"] == turn["id"] for event in app.notifications)
        if completed and len(answered) == 1:
            return turn["id"]
        time.sleep(0.02)
    raise RuntimeError("Root dynamic tool call or turn did not complete")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default=shutil.which("codex"))
    parser.add_argument("--output-parent", type=Path)
    options = parser.parse_args()
    probe.require(options.codex, "Codex CLI is unavailable")
    output = Path(tempfile.mkdtemp(prefix="bytequay-codex-review-dynamic-", dir=options.output_parent)).resolve()
    print("Probe artifacts: " + str(output), flush=True)
    for name in ("home", "profile", "sqlite", "tmp", "workspace"):
        (output / name).mkdir(mode=0o700)
    gate = output / "profile" / "review-child-gate.sh"
    shutil.copyfile(Path(__file__).resolve().parents[1] / "backend/agent-cli/src/main/resources/com/bytequay/agent/cli/codex/review-child-gate.sh", gate)
    gate.chmod(0o700)
    environment = {"HOME": str(output / "home"), "CODEX_HOME": str(output / "profile"),
                   "CODEX_SQLITE_HOME": str(output / "sqlite"), "TMPDIR": str(output / "tmp"),
                   "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/sh", "LANG": "en_US.UTF-8"}
    fixture = Fixture(output)
    try:
        base = "http://127.0.0.1:" + str(fixture.server.server_port)
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config",
                     "--enable", "skip_host_skill_discovery", "--enable", "multi_agent_v2", "--disable", "multi_agent",
                     "--enable", "hooks", "--disable", "tool_call_mcp_elicitation", "--disable", "code_mode",
                     "--disable", "tool_search"]
        config = {"model_provider": "fixture", "model": "fixture-model", "model_providers.fixture.name": "Local fixture",
                  "model_providers.fixture.base_url": base + "/v1", "model_providers.fixture.wire_api": "responses",
                  "model_providers.fixture.requires_openai_auth": False, "model_providers.fixture.supports_websockets": False,
                  "model_providers.fixture.request_max_retries": 0, "approval_policy": "on-request",
                  "approvals_reviewer": "auto_review", "sandbox_mode": "workspace-write", "mcp_servers.bytequay.url": base + "/mcp",
                  "mcp_servers.bytequay.default_tools_approval_mode": "approve", "web_search": "disabled",
                  "shell_environment_policy.inherit": "none"}
        for key, value in config.items():
            arguments.extend(["-c", key + "=" + json.dumps(value)])
        arguments.extend(["-c", 'hooks.PreToolUse=[{matcher=".*",hooks=[{type="command",command='
                          + json.dumps(shlex.quote(str(gate))) + ',timeout=5}]}]'])
        probe.write_json(output / "launch.json", {"arguments": arguments, "environment": environment})
        initial = {"cwd": str(output / "workspace"), "model": "fixture-model", "modelProvider": "fixture",
                   "approvalPolicy": "on-request", "approvalsReviewer": "auto_review", "sandbox": "workspace-write",
                   "ephemeral": False, "config": {"bypass_hook_trust": True,
                                                  "features": {"hooks": True, "multi_agent_v2": True, "multi_agent": False}},
                   "dynamicTools": [{"type": "function", "name": TOOL, "description": "Save a fixture finding",
                                     "inputSchema": {"type": "object", "properties": {"finding": {"type": "string"}}}}]}
        with probe.AppServer(arguments, environment, output, "initial") as app:
            root = app.request("thread/start", initial, "start")["result"]["thread"]
            app.request("mcpServerStatus/list", {"threadId": root["id"]}, "mcp")
            first_turn = run_turn(app, root["id"], "Exercise root and child tool visibility.", "first-turn", "initial root accepted")
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                with fixture.lock:
                    child_rejected = rejected_child_call(fixture.requests) and "review_get_context" in fixture.mcp_calls
                if child_rejected:
                    break
                time.sleep(0.02)
            probe.require(child_rejected, "Child's unsupported dynamic tool call was not processed")
            probe.write_json(output / "initial-notifications.json", app.notifications)
        with fixture.lock:
            fixture.resumed = True
        with probe.AppServer(arguments, environment, output, "resumed") as app:
            resume = {key: value for key, value in initial.items() if key not in ("dynamicTools", "ephemeral")}
            resume["threadId"] = root["id"]
            resume["developerInstructions"] = ""
            restored = app.request("thread/resume", resume, "resume")["result"]["thread"]
            probe.require(restored["id"] == root["id"], "Resume changed root thread identity")
            second_turn = run_turn(app, root["id"], "Exercise restored dynamic tool.", "second-turn", "resumed root accepted")
            probe.write_json(output / "resumed-notifications.json", app.notifications)
        with fixture.lock:
            requests = list(fixture.requests)
            mcp_calls = list(fixture.mcp_calls)
        probe.require(any(child_request(request) for request in requests), "No native child provider request was observed")
        probe.require(rejected_child_call(requests), "Child's attempted root tool call had no rejection output")
        probe.require(all(TOOL not in [tool.get("name") for tool in request.get("tools", [])]
                          for request in requests if child_request(request)), "Child inherited the root dynamic tool")
        probe.require(any(TOOL in [tool.get("name") for tool in request.get("tools", [])]
                          for request in requests if not child_request(request) and
                          any(part.get("text") == "Exercise restored dynamic tool." for item in request.get("input", [])
                              for part in item.get("content", []) if isinstance(part, dict))),
                      "Resumed root lost its saved dynamic tool")
        for name, turn_id in (("initial", first_turn), ("resumed", second_turn)):
            notifications = json.loads((output / (name + "-notifications.json")).read_text())
            callbacks = [event["params"] for event in notifications if event.get("method") == "item/tool/call"]
            probe.require(len(callbacks) == 1 and callbacks[0]["threadId"] == root["id"] and
                          callbacks[0]["turnId"] == turn_id and callbacks[0]["tool"] == TOOL,
                          name + " callback was not bound to the exact root thread and turn")
        probe.require(mcp_calls == ["review_get_context"], "Child read was denied or another tool reached the shared MCP endpoint")
        probe.require(not (output / "workspace" / "child-wrote").exists(), "Child executed a shell write")
        probe.require(any(child_request(request) and any(
            item.get("type") == "function_call_output" and item.get("call_id") == "child_1" and
            "PreToolUse" in item.get("output", "") for item in request.get("input", [])) for request in requests),
            "Child shell call was not rejected by the production hook")
        print("Native child read succeeded; root tool and child shell write were denied; resumed root retained its exact-turn write tool.", flush=True)
    finally:
        fixture.close()


if __name__ == "__main__":
    main()
