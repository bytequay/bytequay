#!/usr/bin/env python3
"""Check review MCP permissions against the installed Codex, without a real model.

Run: python3 scripts/codex-review-mcp-probe.py --codex /opt/homebrew/bin/codex
Uses isolated temporary homes and loopback fake Responses/MCP servers. The
default profile must reproduce the cancellation; ByteQuay's explicit task-tool
approval must allow context and finish calls without a user-input request.
"""

import argparse
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("completion_probe", Path(__file__).with_name("codex-completion-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
TOOLS = ("review_get_context", "review_finish")


def check(codex, approved, previous=None):
    output = previous[0] if previous else Path(tempfile.mkdtemp(prefix="bytequay-review-mcp-")).resolve()
    if not previous:
        for name in ("home", "workspace", "profile", "sqlite", "tmp"):
            (output / name).mkdir(mode=0o700)
    calls = []
    model_requests = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, body, content_type):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.send_error(405)

        def do_POST(self):
            if "Authorization" in self.headers:
                self.send_error(400)
                return
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/mcp":
                method = payload.get("method")
                if "id" not in payload:
                    self.send_response(202)
                    self.end_headers()
                    return
                if method == "initialize":
                    result = {"protocolVersion": payload["params"]["protocolVersion"], "capabilities": {"tools": {}},
                              "serverInfo": {"name": "bytequay-fixture", "version": "1"}}
                elif method == "tools/list":
                    result = {"tools": [{"name": name, "description": "Private review fixture " + name,
                                          "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}} for name in TOOLS]}
                elif method == "tools/call":
                    calls.append(payload["params"]["name"])
                    result = {"content": [{"type": "text", "text": "Private report accepted"}], "isError": False}
                elif method == "resources/list":
                    result = {"resources": []}
                else:
                    result = {"resourceTemplates": []}
                self.reply(json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": result}).encode(), "application/json")
                return
            if self.path != "/v1/responses" or len(model_requests) > len(TOOLS):
                self.send_error(400)
                return
            model_requests.append(payload)
            index = len(model_requests) - 1
            if index < len(TOOLS):
                item = {"id": "fc_" + str(index), "type": "function_call", "call_id": "call_" + str(index),
                        "namespace": "mcp__bytequay", "name": TOOLS[index], "arguments": "{}"}
            else:
                item = {"id": "msg_fixture", "type": "message", "role": "assistant", "status": "completed",
                        "content": [{"type": "output_text", "text": "Fixture complete.", "annotations": []}]}
            response = {"id": "resp_" + str(index), "object": "response", "created_at": int(time.time()),
                        "status": "completed", "model": "fixture-model", "output": [item],
                        "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
            events = [{"type": "response.output_item.done", "output_index": 0, "item": item},
                      {"type": "response.completed", "response": response}]
            self.reply("".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n" for event in events).encode(), "text/event-stream")

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = "http://127.0.0.1:" + str(server.server_port)
    environment = {key: value for key, value in os.environ.items() if key in ("PATH", "SHELL", "LANG")}
    environment.update(HOME=str(output / "home"), CODEX_HOME=str(output / "profile"),
                       CODEX_SQLITE_HOME=str(output / "sqlite"), TMPDIR=str(output / "tmp"))
    arguments = [codex, "app-server", "--listen", "stdio://", "--strict-config", "--enable", "skip_host_skill_discovery",
                 "--disable", "tool_call_mcp_elicitation", "--disable", "multi_agent", "--disable", "multi_agent_v2",
                 "--disable", "code_mode", "--disable", "tool_search"]
    config = {"model_provider": "fixture", "model": "fixture-model", "model_providers.fixture.name": "Local fixture",
              "model_providers.fixture.base_url": base + "/v1", "model_providers.fixture.wire_api": "responses",
              "model_providers.fixture.requires_openai_auth": False, "model_providers.fixture.supports_websockets": False,
              "model_providers.fixture.request_max_retries": 0, "approval_policy": "on-request", "approvals_reviewer": "auto_review",
              "sandbox_mode": "workspace-write", "mcp_servers.bytequay.url": base + "/mcp"}
    if approved:
        config["mcp_servers.bytequay.default_tools_approval_mode"] = "approve"
    for key, value in config.items():
        arguments.extend(["-c", key + "=" + json.dumps(value)])
    label = "approved" if approved else "default"
    print(label + " artifacts: " + str(output), flush=True)
    try:
        with probe.AppServer(arguments, environment, output, label) as app:
            params = {"cwd": str(output / "workspace"), "model": "fixture-model",
                      "approvalPolicy": "on-request", "approvalsReviewer": "auto_review", "sandbox": "workspace-write"}
            if previous:
                params["threadId"] = previous[1]
            started = app.request("thread/resume" if previous else "thread/start", params, "start")
            thread_id = started["result"]["thread"]["id"]
            app.request("mcpServerStatus/list", {"threadId": thread_id}, "tools")
            app.request("turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": "Read and finish the private fixture review."}]}, "turn")
            rejected = set()
            deadline = time.monotonic() + 20
            while not any(event.get("method") == "turn/completed" for event in app.notifications):
                for event in list(app.notifications):
                    if "method" in event and "id" in event and event["id"] not in rejected:
                        probe.require(event["method"] == "item/tool/requestUserInput", "Unexpected server request")
                        rejected.add(event["id"])
                        # The adapter rejects this unsupported request; Codex reports it as user cancellation.
                        app.send({"id": event["id"], "error": {"code": -32603, "message": "Unsupported Codex request"}})
                probe.require(time.monotonic() < deadline, "No turn/completed notification")
                time.sleep(0.01)
            items = [event["params"]["item"] for event in app.notifications if event.get("method") == "item/completed"
                     and event.get("params", {}).get("item", {}).get("type") == "mcpToolCall"]
            probe.require([item["tool"] for item in items] == list(TOOLS), "Expected both context and finish calls")
            if approved:
                probe.require(calls == list(TOOLS) and not rejected, "Task tools must run without requesting approval")
                probe.require(all(item["status"] == "completed" for item in items), "Approved tools did not complete")
            else:
                probe.require(not calls and len(rejected) == len(TOOLS), "Default must request approval before reaching the broker")
                probe.require(all(item["error"]["message"] == "user cancelled MCP tool call" for item in items), "Cancellation reproduction changed")
            print(label + ": " + ("resumed context and finish succeeded" if approved else "reproduced cancelled tool calls"), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
    return output, thread_id


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default=shutil.which("codex") or "/opt/homebrew/bin/codex")
    options = parser.parse_args()
    previous = check(options.codex, False)
    check(options.codex, True, previous)
