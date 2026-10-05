#!/usr/bin/env python3
"""Check actual Codex child state against a local fake Responses provider.

Examples:
  python3 scripts/codex-child-completion-probe.py --mode v1 --background
  python3 scripts/codex-child-completion-probe.py --mode v1 --grandchild
  python3 scripts/codex-child-completion-probe.py --mode v2 --history-mode paginated
  python3 scripts/codex-child-completion-probe.py --mode none --model gpt-6-astra

Requires Python 3.9+, Codex, and permission to bind a loopback socket. Uses a
fresh keyless profile and never contacts a real model. --background additionally
runs one owned bash/sleep command to check V1 close_agent cleanup. That experiment
does not prove cleanup of arbitrary detached processes or every notLoaded cause.
"""

import argparse
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("completion_probe", Path(__file__).with_name("codex-completion-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def wait_for(predicate, message):
    deadline = time.monotonic() + 15
    while not predicate():
        probe.require(time.monotonic() < deadline, message)
        time.sleep(0.01)


def process_identity(pid):
    return subprocess.run(["/bin/ps", "-p", str(pid), "-o", "lstart=,comm="], capture_output=True, text=True, check=False).stdout.strip()


class ChildProvider:
    def __init__(self, output, mode, background, grandchild=False):
        self.output = output
        self.mode = mode
        self.background = background
        self.grandchild = grandchild
        self.requests = []
        self.spawn_next = True
        self.child_seen = threading.Event()
        self.release_child = threading.Event()
        self.close_target = None
        self.owned_pid = None
        self.owned_identity = None
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.requests.append({"path": self.path, "authorizationPresent": "Authorization" in self.headers, "body": payload})
                probe.write_json(output / "provider-requests.json", fixture.requests)
                if self.path != "/v1/responses" or "Authorization" in self.headers or len(fixture.requests) > (8 if grandchild else 6):
                    self.send_error(400)
                    return
                item = fixture.reply(payload)
                index = len(fixture.requests)
                response = {"id": "resp_fixture_" + str(index), "object": "response", "created_at": int(time.time()), "status": "completed",
                            "model": "fixture-model", "output": [item], "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3,
                            "input_tokens_details": {"cached_tokens": 0}, "output_tokens_details": {"reasoning_tokens": 0}}}
                events = [{"type": "response.created", "response": {**response, "status": "in_progress", "output": []}},
                          {"type": "response.output_item.added", "output_index": 0, "item": item},
                          {"type": "response.output_item.done", "output_index": 0, "item": item},
                          {"type": "response.completed", "response": response}]
                data = "".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def call(self, name, arguments, namespace=None):
        item = {"id": "fc_" + name, "type": "function_call", "name": name, "call_id": name + "_fixture", "arguments": json.dumps(arguments)}
        if namespace:
            item["namespace"] = namespace
        return item

    def reply(self, payload):
        outputs = {item["call_id"]: item["output"] for item in payload["input"] if item.get("type") == "function_call_output"}
        namespace = "multi_agent_v1" if self.mode == "v1" else "collaboration"
        if self.spawn_next:
            self.spawn_next = False
            arguments = {"message": "ByteQuay child fixture: return the fixed response. Use only the fixture's requested tool."}
            if self.mode != "v1":
                arguments.update({"task_name": "fixture_child", "fork_turns": "none"})
            return self.call("spawn_agent", arguments, namespace)
        if self.close_target and "close_agent_fixture" not in outputs:
            return self.call("close_agent", {"target": self.close_target}, namespace)
        if "spawn_agent_fixture" in outputs or "close_agent_fixture" in outputs:
            text = "Root fixture completed."
        elif "grandchild_spawn_fixture" in outputs:
            text = "Child fixture completed with a live grandchild."
        else:
            if self.grandchild and any("ByteQuay child fixture:" in str(item.get("content", "")) for item in payload["input"] if item.get("role") == "user"):
                item = self.call("spawn_agent", {"message": "Grandchild fixture: return the fixed response."}, namespace)
                item["call_id"] = "grandchild_spawn_fixture"
                return item
            if self.background and "exec_command_fixture" not in outputs:
                return self.call("exec_command", {"cmd": "echo BYTEQUAY_OWNED_SLEEP_PID=$$; exec /bin/sleep 30", "shell": "/bin/bash",
                                                  "login": False, "yield_time_ms": 250, "max_output_tokens": 100})
            if self.background:
                match = re.search(r"BYTEQUAY_OWNED_SLEEP_PID=(\d+)", str(outputs["exec_command_fixture"]))
                probe.require(match is not None, "Owned command did not report its PID")
                self.owned_pid = int(match.group(1))
                self.owned_identity = process_identity(self.owned_pid)
                probe.require(self.owned_identity.endswith("/bin/sleep"), "Unexpected owned command identity")
            self.child_seen.set()
            probe.require(self.release_child.wait(25), "Child response was never released")
            if self.mode == "v2" and "send_message_fixture" not in outputs:
                return self.call("send_message", {"target": "/root", "message": "Child fixture reporting to its parent."}, namespace)
            text = "Child fixture completed."
        return {"id": "msg_fixture_" + str(len(self.requests)), "type": "message", "role": "assistant", "status": "completed",
                "content": [{"type": "output_text", "text": text, "annotations": []}]}

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.release_child.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        if self.owned_pid and process_identity(self.owned_pid) == self.owned_identity:
            os.kill(self.owned_pid, signal.SIGTERM)
            wait_for(lambda: process_identity(self.owned_pid) != self.owned_identity, "Owned sleep survived fixture cleanup")


def inspect(app, thread_id, phase, status, turn_status, background=False):
    thread = app.request("thread/read", {"threadId": thread_id, "includeTurns": True}, phase)["result"]["thread"]
    probe.require(thread["status"]["type"] == status, phase + ": unexpected thread status")
    probe.require(thread["turns"][-1]["status"] == turn_status, phase + ": unexpected child turn status")
    inventory = app.request("thread/backgroundTerminals/list", {"threadId": thread_id}, phase, allow_error=True)
    if status == "notLoaded":
        probe.require(inventory.get("error", {}).get("code") == -32600, phase + ": unloaded inventory behavior changed")
    else:
        items = inventory["result"]["data"]
        probe.require(len(items) == int(background), phase + ": unexpected background inventory")
        if background:
            probe.require(items[0]["itemId"] == "exec_command_fixture", phase + ": unexpected background item")
    print(phase + ": thread=" + status + ", turn=" + turn_status + ", background=" + ("unavailable" if status == "notLoaded" else str(int(background))), flush=True)
    return thread


def children(app, parent_id, child_id, phase, status, grandchild_id=None, grandchild_status=None):
    for key in ("parentThreadId", "ancestorThreadId"):
        result = app.request("thread/list", {key: parent_id, "sourceKinds": ["subAgent"], "modelProviders": []}, phase)["result"]
        expected = {child_id: (parent_id, status)}
        if grandchild_id and key == "ancestorThreadId":
            expected[grandchild_id] = (child_id, grandchild_status)
        actual = {child["id"]: (child["parentThreadId"], child["status"]["type"]) for child in result["data"]}
        probe.require(result["nextCursor"] is None and actual == expected, phase + ": incorrect relationship or status")
    result = app.request("thread/list", {"parentThreadId": child_id, "sourceKinds": ["subAgent"], "modelProviders": []}, phase)["result"]
    expected = {grandchild_id: (child_id, grandchild_status)} if grandchild_id else {}
    actual = {child["id"]: (child["parentThreadId"], child["status"]["type"]) for child in result["data"]}
    probe.require(actual == expected, phase + ": unexpected grandchild inventory")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default=shutil.which("codex") or "/opt/homebrew/bin/codex")
    parser.add_argument("--mode", choices=("none", "v1", "v2"), default="v1")
    parser.add_argument("--model", help="Catalog model name sent only to the local fake provider (V2 defaults to gpt-6-astra)")
    parser.add_argument("--history-mode", choices=("legacy", "paginated"), default="legacy")
    parser.add_argument("--background", action="store_true", help="Check V1 close_agent with a completed child's yielded sleep command")
    parser.add_argument("--grandchild", action="store_true", help="Check V1 recursive close while a completed child has a live grandchild")
    parser.add_argument("--output-parent", type=Path)
    options = parser.parse_args()
    if options.model is None:
        # Child tool selection needs a catalog model that advertises V2 messaging.
        options.model = "gpt-6-astra" if options.mode == "v2" else "fixture-model"
    if (options.background or options.grandchild) and options.mode != "v1":
        parser.error("--background and --grandchild require --mode v1 (V2 advertises no close_agent)")
    if options.background and options.grandchild:
        parser.error("Run --background and --grandchild as separate scenarios")
    output = Path(tempfile.mkdtemp(prefix="bytequay-codex-child-", dir=options.output_parent)).resolve()
    print("Probe artifacts: " + str(output), flush=True)
    for name in ("home", "codex-home", "sqlite", "tmp", "workspace"):
        (output / name).mkdir(mode=0o700)
    environment = {"HOME": str(output / "home"), "CODEX_HOME": str(output / "codex-home"), "CODEX_SQLITE_HOME": str(output / "sqlite"),
                   "TMPDIR": str(output / "tmp"), "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/sh", "LANG": "en_US.UTF-8"}
    with ChildProvider(output, options.mode, options.background, options.grandchild) as provider:
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config", "--enable", "skip_host_skill_discovery",
                     "--enable" if options.mode == "v1" else "--disable", "multi_agent", "--enable" if options.mode == "v2" else "--disable", "multi_agent_v2"]
        config = {"model_provider": "fixture", "model": options.model, "model_providers.fixture.name": "ByteQuay local child fixture",
                  "model_providers.fixture.base_url": "http://127.0.0.1:" + str(provider.server.server_port) + "/v1",
                  "model_providers.fixture.wire_api": "responses", "model_providers.fixture.requires_openai_auth": False,
                  "model_providers.fixture.supports_websockets": False, "model_providers.fixture.request_max_retries": 0,
                  "web_search": "disabled", "shell_environment_policy.inherit": "none", "agents.enabled": options.mode != "none"}
        if options.grandchild:
            config["agents.max_depth"] = 2
        for key, value in config.items():
            arguments.extend(["-c", key + "=" + json.dumps(value)])
        probe.write_json(output / "launch.json", {"arguments": arguments, "environment": environment})
        with probe.AppServer(arguments, environment, output, "child") as app:
            effective = app.request("config/read", {"includeLayers": True}, "config")["result"]
            probe.require(effective["config"]["agents"]["enabled"] == (options.mode != "none"), "Incorrect effective agent enablement")
            flags = [layer["config"] for layer in effective["layers"] if layer["name"]["type"] == "sessionFlags"]
            probe.require(len(flags) == 1 and flags[0]["agents"]["enabled"] == (options.mode != "none"), "Incorrect session agent enablement")
            thread_config = {"cwd": str(output / "workspace"), "historyMode": options.history_mode, "model": options.model,
                             "modelProvider": "fixture", "approvalPolicy": "never", "sandbox": "read-only", "ephemeral": False,
                             "config": {"agents": {"enabled": options.mode != "none"}}}
            root = app.request("thread/start", thread_config, "root-start")["result"]["thread"]
            turn = app.request("turn/start", {"threadId": root["id"], "input": [{"type": "text", "text": "Spawn one child for this authorized fixture, then return the fixed response."}]}, "root-turn")["result"]["turn"]
            if options.mode == "none":
                wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("turn", {}).get("id") == turn["id"] for item in app.notifications), "Root did not complete")
                probe.require(len(provider.requests) == 2 and not provider.child_seen.is_set(), "Disabled profile spawned a child")
                probe.require(all(item["body"]["model"] == options.model and not item["authorizationPresent"] for item in provider.requests), "Unexpected provider request")
                probe.require("spawn_agent" not in json.dumps(provider.requests[0]["body"]), "Disabled profile advertised spawn_agent")
                outputs = [item["output"] for item in provider.requests[1]["body"]["input"] if item.get("call_id") == "spawn_agent_fixture" and item.get("type") == "function_call_output"]
                probe.require(outputs == ["unsupported call: collaborationspawn_agent"], "Disabled spawn was not rejected")
                result = app.request("thread/list", {"parentThreadId": root["id"], "sourceKinds": ["subAgent"], "modelProviders": []}, "disabled-children")["result"]
                probe.require(result["data"] == [], "Disabled profile created a child thread")
                inspect(app, root["id"], "disabled-spawn-completed", "idle", "completed")
                resume = {key: value for key, value in thread_config.items() if key not in ("historyMode", "ephemeral")}
                resume.update(threadId=root["id"], excludeTurns=True)
                restored = app.request("thread/resume", resume, "disabled-resume")["result"]["thread"]
                probe.require(restored["id"] == root["id"], "Resume changed the root identity")
                provider.spawn_next = True
                turn = app.request("turn/start", {"threadId": root["id"], "input": [{"type": "text", "text": "Try the same fixture spawn again."}]}, "resumed-turn")["result"]["turn"]
                wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("turn", {}).get("id") == turn["id"] for item in app.notifications), "Resumed root did not complete")
                probe.require(len(provider.requests) == 4 and not provider.child_seen.is_set(), "Resumed disabled profile spawned a child")
                outputs = [item["output"] for item in provider.requests[-1]["body"]["input"] if item.get("call_id") == "spawn_agent_fixture" and item.get("type") == "function_call_output"]
                probe.require(outputs[-1:] == ["unsupported call: collaborationspawn_agent"], "Resumed disabled spawn was not rejected")
                inspect(app, root["id"], "resumed-disabled-spawn-completed", "idle", "completed")
                print("PASS: agents.enabled=false hides and rejects spawn_agent on start/resume for " + options.model + "; 4 local requests.", flush=True)
                return
            probe.require(provider.child_seen.wait(10), "Child made no local fixture request")
            wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("turn", {}).get("id") == turn["id"] for item in app.notifications), "Root did not complete")
            relationships = [item["params"]["item"] for item in app.notifications if item.get("method") == "item/completed"]
            child_ids = [child_id for item in relationships if item.get("tool") == "spawnAgent" and item["senderThreadId"] == root["id"] for child_id in item["receiverThreadIds"]] if options.mode == "v1" else [item["agentThreadId"] for item in relationships if item["type"] == "subAgentActivity" and item["kind"] == "started"]
            probe.require(len(child_ids) == 1, "Expected exactly one provider-created child")
            child_id = child_ids[0]
            grandchild_id = None
            if options.grandchild:
                grandchild_ids = [child for item in relationships if item.get("id") == "grandchild_spawn_fixture" for child in item["receiverThreadIds"]]
                probe.require(len(grandchild_ids) == 1, "Expected one grandchild")
                grandchild_id = grandchild_ids[0]
                wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("threadId") == child_id for item in app.notifications), "Child did not complete")
            root_history = inspect(app, root["id"], "root-completed-child-held", "idle", "completed")
            if grandchild_id:
                inspect(app, child_id, "child-with-live-grandchild", "idle", "completed")
                inspect(app, grandchild_id, "grandchild-held", "active", "inProgress")
                children(app, root["id"], child_id, "grandchild-held", "idle", grandchild_id, "active")
            else:
                inspect(app, child_id, "child-held", "active", "inProgress", options.background)
                children(app, root["id"], child_id, "child-held", "active")
            relationship_type = "collabAgentToolCall" if options.mode == "v1" else "subAgentActivity"
            retained = any(item["type"] == relationship_type for item in root_history["turns"][-1]["items"])
            probe.require(retained == (options.history_mode == "paginated" or options.mode == "v2"), "Relationship hydration behavior changed; inspect evidence")
            if not grandchild_id:
                provider.release_child.set()
                wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("threadId") == child_id for item in app.notifications), "Child did not complete")
                inspect(app, child_id, "child-completed", "idle", "completed", options.background)
                children(app, root["id"], child_id, "child-completed", "idle")
                if options.mode == "v2":
                    activities = [item["params"]["item"] for item in app.notifications
                                  if item.get("method") == "item/completed" and item["params"]["item"]["type"] == "subAgentActivity"]
                    probe.require(any(item["agentThreadId"] == child_id and item["agentPath"] == "/root/fixture_child"
                                      and item["kind"] == "started" for item in activities), "Unexpected V2 child path")
                    probe.require(any(item["agentThreadId"] == root["id"] and item["agentPath"] == "/root"
                                      and item["kind"] == "interacted" for item in activities), "Missing V2 message to the canonical root path")
            if options.mode == "v1":
                if options.background:
                    probe.require(process_identity(provider.owned_pid) == provider.owned_identity, "Owned sleep ended before close_agent")
                provider.close_target = child_id
                turn = app.request("turn/start", {"threadId": root["id"], "input": [{"type": "text", "text": "Close the completed child with close_agent for this authorized fixture."}]}, "close-turn")["result"]["turn"]
                wait_for(lambda: any(item.get("method") == "turn/completed" and item.get("params", {}).get("turn", {}).get("id") == turn["id"] for item in app.notifications), "Close turn did not complete")
                inspect(app, child_id, "child-closed", "notLoaded", "completed")
                children(app, root["id"], child_id, "child-closed", "notLoaded", grandchild_id, "notLoaded" if grandchild_id else None)
                if grandchild_id:
                    inspect(app, grandchild_id, "grandchild-closed", "notLoaded", "interrupted")
                    provider.release_child.set()
                if options.background:
                    probe.require(process_identity(provider.owned_pid) != provider.owned_identity, "close_agent left the owned sleep alive")
                    print("close_agent stopped owned sleep PID " + str(provider.owned_pid), flush=True)
            unrelated = app.request("thread/start", thread_config, "unrelated-start")["result"]["thread"]
            result = app.request("thread/list", {"parentThreadId": unrelated["id"], "sourceKinds": ["subAgent"], "modelProviders": []}, "unrelated-children")["result"]
            probe.require(result["data"] == [], "Child inventory crossed root boundaries")
        expected_requests = (5 if options.mode == "v1" else 4) + int(options.background) + 2 * int(options.grandchild)
        probe.require(len(provider.requests) == expected_requests, "Unexpected local provider request count")
        probe.require(all(item["body"]["model"] == options.model and not item["authorizationPresent"] for item in provider.requests), "Unexpected provider request")
    print("PASS: " + options.mode + "/" + options.history_mode + " child active/completed state, direct/ancestor inventory, and unrelated-root exclusion; " + str(expected_requests) + " local requests.", flush=True)


if __name__ == "__main__":
    main()
