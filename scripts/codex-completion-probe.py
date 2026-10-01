#!/usr/bin/env python3
"""Probe installed Codex completion APIs using a local fake Responses provider.

Run: python3 scripts/codex-completion-probe.py --codex /opt/homebrew/bin/codex
Requires Python 3.9+, the installed CLI, and permission to bind a loopback socket.
No login or real model is used. All provider state and logs use a fresh temporary
directory, printed on startup. The fixture emits text only, never tool calls.
This checks standalone thread APIs, not recursive child or approval behavior.
"""

import argparse
import http.server
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import tempfile
import threading
import time


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


class LocalProvider:
    """Hold each local request until the probe has inspected its active turn."""

    def __init__(self, output):
        self.output = output
        self.seen = threading.Event()
        self.release = threading.Event()
        self.requests = []
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.requests.append({"path": self.path, "authorizationPresent": "Authorization" in self.headers, "body": payload})
                write_json(output / "provider-requests.json", fixture.requests)
                if self.path != "/v1/responses" or "Authorization" in self.headers or len(fixture.requests) > 4:
                    self.send_error(400)
                    return
                fixture.seen.set()
                if not fixture.release.wait(25):
                    self.send_error(408)
                    return
                index = len(fixture.requests)
                part = {"type": "output_text", "text": "Fixture completed.", "annotations": []}
                item = {"id": "msg_fixture_" + str(index), "type": "message", "role": "assistant", "status": "completed", "content": [part]}
                response = {"id": "resp_fixture_" + str(index), "object": "response", "created_at": int(time.time()),
                            "status": "completed", "model": "fixture-model", "output": [item],
                            "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3,
                                      "input_tokens_details": {"cached_tokens": 0}, "output_tokens_details": {"reasoning_tokens": 0}}}
                item_ref = {"item_id": item["id"], "output_index": 0, "content_index": 0}
                events = [
                    {"type": "response.created", "response": {**response, "status": "in_progress", "output": []}},
                    {"type": "response.output_item.added", "output_index": 0, "item": {**item, "status": "in_progress", "content": []}},
                    {"type": "response.content_part.added", **item_ref, "part": {**part, "text": ""}},
                    {"type": "response.output_text.delta", **item_ref, "delta": part["text"]},
                    {"type": "response.output_text.done", **item_ref, "text": part["text"]},
                    {"type": "response.content_part.done", **item_ref, "part": part},
                    {"type": "response.output_item.done", "output_index": 0, "item": item},
                    {"type": "response.completed", "response": response},
                ]
                data = "".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class AppServer:
    def __init__(self, arguments, environment, output, name):
        self.output = output
        self.name = name
        self.incoming = queue.Queue()
        self.notifications = []
        self.next_id = 0
        self.responses = []
        self.log = (output / (name + "-rpc.jsonl")).open("w")
        self.lock = threading.Lock()
        self.stderr = (output / (name + "-stderr.log")).open("w")
        self.child = subprocess.Popen(arguments, cwd=output / "workspace", env=environment, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=self.stderr, text=True, bufsize=1, start_new_session=True)
        self.reader = threading.Thread(target=self.receive, daemon=True)
        self.reader.start()

    def record(self, direction, payload):
        with self.lock:
            self.log.write(json.dumps({"direction": direction, "payload": payload}) + "\n")
            self.log.flush()

    def receive(self):
        try:
            for line in self.child.stdout:
                payload = json.loads(line)
                self.record("in", payload)
                if "method" in payload:
                    self.notifications.append(payload)
                self.incoming.put(payload)
        finally:
            self.incoming.put({"closed": True})

    def send(self, payload):
        self.record("out", payload)
        self.child.stdin.write(json.dumps(payload) + "\n")
        self.child.stdin.flush()

    def request(self, method, params, phase, allow_error=False):
        self.next_id += 1
        self.send({"id": self.next_id, "method": method, "params": params})
        deadline = time.monotonic() + 20
        while True:
            response = self.incoming.get(timeout=max(0.01, deadline - time.monotonic()))
            require(not response.get("closed"), "App Server closed during " + method)
            if response.get("id") == self.next_id:
                self.responses.append({"phase": phase, "method": method, "params": params, "response": response})
                write_json(self.output / (self.name + "-responses.json"), self.responses)
                require(allow_error or "error" not in response, phase + " " + method + ": " + json.dumps(response))
                return response
            require("method" not in response or "id" not in response, "Unexpected provider request: " + json.dumps(response))

    def __enter__(self):
        try:
            initialized = self.request("initialize", {"clientInfo": {"name": "bytequay-completion-probe", "version": "1"},
                                                       "capabilities": {"experimentalApi": True, "requestAttestation": False}}, "initialize")
            print(initialized["result"]["userAgent"], flush=True)
            self.send({"method": "initialized", "params": {}})
            return self
        except BaseException:
            self.__exit__()
            raise

    def __exit__(self, *args):
        self.child.stdin.close()
        try:
            self.child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(self.child.pid, signal.SIGTERM)
            try:
                self.child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.child.pid, signal.SIGKILL)
                self.child.wait(timeout=5)
        self.reader.join(timeout=2)
        self.child.stdout.close()
        self.log.close()
        self.stderr.close()
        write_json(self.output / (self.name + "-process.json"), {"pid": self.child.pid, "exitCode": self.child.returncode})


def inspect(app, thread_id, mode, phase, status, turn_ids, active=False):
    metadata = app.request("thread/read", {"threadId": thread_id, "includeTurns": False}, phase)["result"]["thread"]
    require(metadata["status"]["type"] == status and metadata["turns"] == [], phase + ": unexpected metadata")
    history = app.request("thread/read", {"threadId": thread_id, "includeTurns": True}, phase)["result"]["thread"]
    require(history["status"]["type"] == status, phase + ": inconsistent history status")
    require([turn["id"] for turn in history["turns"]] == turn_ids, phase + ": incomplete turn history")
    for index, turn in enumerate(history["turns"]):
        expected = "inProgress" if active and index == len(turn_ids) - 1 else "completed"
        require(turn["status"] == expected, phase + ": unexpected turn status")
        require([item["type"] for item in turn["items"]] == (["userMessage"] if expected == "inProgress" else ["userMessage", "agentMessage"]),
                phase + ": incomplete message history")
    turns = []
    params = {"threadId": thread_id, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"}
    while True:
        page = app.request("thread/turns/list", params, phase)["result"]
        turns.extend(turn["id"] for turn in page["data"])
        if page["nextCursor"] is None:
            break
        require(len(turns) <= len(turn_ids), phase + ": repeated turns pagination")
        params = {**params, "cursor": page["nextCursor"]}
    require(turns == list(reversed(turn_ids)), phase + ": turns pagination lost history")
    items = app.request("thread/items/list", {"threadId": thread_id, "limit": 10}, phase, allow_error=True)
    if mode == "legacy":
        require(items.get("error", {}).get("code") == -32601, phase + ": legacy items/list behavior changed; review fixture")
    else:
        require(len(items["result"]["data"]) == 2 * len(turn_ids) - int(active), phase + ": items/list lost messages")
    terminals = app.request("thread/backgroundTerminals/list", {"threadId": thread_id}, phase, allow_error=True)
    if status == "notLoaded":
        require(terminals.get("error", {}).get("code") == -32600, phase + ": unloaded inventory behavior changed; review fixture")
    else:
        require(terminals.get("result") == {"data": [], "nextCursor": None}, phase + ": unexpected background terminal")
    print(phase + ": status=" + status + ", historyTurns=" + str(len(turn_ids)) + ", background=" + ("thread not found" if status == "notLoaded" else "empty"), flush=True)


def run_turn(app, provider, thread_id, mode, turn_ids):
    provider.seen.clear()
    provider.release.clear()
    phase = mode + "-turn-" + str(len(turn_ids) + 1)
    result = app.request("turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": "Return the fixed fixture response. Do not call tools."}]}, phase)["result"]
    turn_ids.append(result["turn"]["id"])
    require(provider.seen.wait(10), "Local provider received no request")
    inspect(app, thread_id, mode, phase + "-active", "active", turn_ids, active=True)
    provider.release.set()
    deadline = time.monotonic() + 15
    while not any(item.get("method") == "turn/completed" and item.get("params", {}).get("turn", {}).get("id") == turn_ids[-1] for item in app.notifications):
        require(time.monotonic() < deadline, "No turn/completed notification")
        time.sleep(0.01)
    inspect(app, thread_id, mode, phase + "-completed", "idle", turn_ids)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default=shutil.which("codex") or "/opt/homebrew/bin/codex")
    parser.add_argument("--output-parent", type=Path, help="Parent for a new isolated run directory (defaults to system temporary directory)")
    options = parser.parse_args()
    output = Path(tempfile.mkdtemp(prefix="bytequay-codex-completion-", dir=options.output_parent)).resolve()
    print("Probe artifacts: " + str(output), flush=True)
    for name in ("home", "codex-home", "sqlite", "tmp", "workspace"):
        (output / name).mkdir(mode=0o700)
    environment = {"HOME": str(output / "home"), "CODEX_HOME": str(output / "codex-home"), "CODEX_SQLITE_HOME": str(output / "sqlite"),
                   "TMPDIR": str(output / "tmp"), "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/sh", "LANG": "en_US.UTF-8"}
    with LocalProvider(output) as provider:
        arguments = [str(Path(options.codex).absolute()), "app-server", "--listen", "stdio://", "--strict-config",
                     "--enable", "skip_host_skill_discovery", "--disable", "multi_agent", "--disable", "multi_agent_v2"]
        config = {"model_provider": "fixture", "model": "fixture-model", "model_providers.fixture.name": "ByteQuay local fixture",
                  "model_providers.fixture.base_url": "http://127.0.0.1:" + str(provider.server.server_port) + "/v1",
                  "model_providers.fixture.wire_api": "responses", "model_providers.fixture.requires_openai_auth": False,
                  "model_providers.fixture.supports_websockets": False, "model_providers.fixture.request_max_retries": 0,
                  "web_search": "disabled", "shell_environment_policy.inherit": "none"}
        for key, value in config.items():
            arguments.extend(["-c", key + "=" + json.dumps(value)])
        write_json(output / "launch.json", {"arguments": arguments, "environment": environment})
        saved_threads = []
        thread_config = {"cwd": str(output / "workspace"), "model": "fixture-model", "modelProvider": "fixture", "approvalPolicy": "never", "sandbox": "read-only"}
        with AppServer(arguments, environment, output, "first") as app:
            for mode in ("legacy", "paginated"):
                thread = app.request("thread/start", {**thread_config, "historyMode": mode, "ephemeral": False, "dynamicTools": [],
                                                      "selectedCapabilityRoots": [], "environments": []}, mode + "-start")["result"]["thread"]
                turn_ids = []
                saved_threads.append((mode, thread["id"], turn_ids))
                run_turn(app, provider, thread["id"], mode, turn_ids)
                result = app.request("thread/unsubscribe", {"threadId": thread["id"]}, mode + "-unsubscribe")["result"]
                require(result["status"] == "unsubscribed", "Unexpected unsubscribe result")
                inspect(app, thread["id"], mode, mode + "-unsubscribed", "idle", turn_ids)
                loaded = app.request("thread/loaded/list", {}, mode + "-unsubscribed")["result"]["data"]
                require(thread["id"] in loaded, "Unsubscribe now unloads thread; review fixture")
                resumed = app.request("thread/resume", {**thread_config, "threadId": thread["id"], "excludeTurns": True}, mode + "-resume")["result"]
                require(resumed["thread"]["turns"] == [], "Resume returned history despite excludeTurns")
                run_turn(app, provider, thread["id"], mode, turn_ids)
        with AppServer(arguments, environment, output, "restarted") as app:
            require(app.request("thread/loaded/list", {}, "restart")["result"]["data"] == [], "Fresh App Server loaded unexpected threads")
            for mode, thread_id, turn_ids in saved_threads:
                inspect(app, thread_id, mode, mode + "-before-resume", "notLoaded", turn_ids)
                resumed = app.request("thread/resume", {**thread_config, "threadId": thread_id, "excludeTurns": True}, mode + "-restart-resume")["result"]
                require(resumed["thread"]["turns"] == [], "Restart resume returned history despite excludeTurns")
                inspect(app, thread_id, mode, mode + "-after-resume", "idle", turn_ids)
        require(len(provider.requests) == 4, "Expected exactly four requests to the local fake provider")
        require(all(item["body"]["model"] == "fixture-model" and not item["authorizationPresent"] for item in provider.requests), "Unexpected provider request")
        for index in (1, 3):
            require("Fixture completed." in json.dumps(provider.requests[index]["body"]["input"]), "Resume discarded the previous answer from model context")
    print("PASS: legacy and paginated active/completed history, pagination, unsubscribe, same-process resume, and fresh-process resume; four local fixture requests.", flush=True)
    print("notLoaded is load state; its background inventory returned an error, not a successful empty list.", flush=True)


if __name__ == "__main__":
    main()
