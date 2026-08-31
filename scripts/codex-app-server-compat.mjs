#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const contract = {
  version: "0.151.0",
  schemaCommand:
    "codex app-server generate-json-schema --experimental --out <temporary-directory>",
  experimentalBundleSha256:
    "31ae67beb2c94cc9509f6a71968600062dc8c6d7fe45437ed3a9129838f4d2d9",
  experimentalV2BundleSha256:
    "a586cdc50f84c56c7654387e869b470a689796fa1c57678dcafb5921bb2d5255",
  stableBundleSha256:
    "424b204943b18e5ffa52667a2aa397c9950730ec1e49ad767e2a016743990541",
  stableV2BundleSha256:
    "2442b15801bc019ad55987ad03e0f0ae60c51417825b9b6d708db640e6c2651c",
};

const experimentalMethods = [
  "environment/add",
  "environment/info",
  "thread/backgroundTerminals/list",
  "thread/backgroundTerminals/clean",
  "thread/backgroundTerminals/terminate",
];

const stableMethods = [
  "model/list",
  "thread/start",
  "turn/start",
  "thread/read",
  "thread/resume",
];

const commandApprovalMethod = "item/commandExecution/requestApproval";

const disabledFeatures = [
  "apps",
  "browser_use",
  "browser_use_external",
  "computer_use",
  "hooks",
  "image_generation",
  "in_app_browser",
  "multi_agent",
  "multi_agent_v2",
  "plugins",
  "remote_plugin",
  "skill_mcp_dependency_install",
  "skill_search",
  "tool_call_mcp_elicitation",
  "tool_suggest",
  "workspace_dependencies",
];

const passiveTurnItemTypes = new Set([
  "agentMessage",
  "contextCompaction",
  "plan",
  "reasoning",
  "userMessage",
]);

const proofFileName = "bytequay-cp1-proof.txt";
const proofFileContents = "bytequay-cp1-proof\n";
const environmentEvidenceCommand =
  `printf 'bytequay-cp1-proof\\n' > ${proofFileName}; ` +
  'if [ -z "${CODEX_API_KEY+x}" ]; then key_state=absent; ' +
  "else key_state=present; fi; " +
  "printf 'CODEX_API_KEY_VISIBLE=%s\\nAPP_SERVER_SENTINEL=%s\\n" +
  "CODEX_HOME=%s\\nHOME=%s\\nPWD=%s\\n' \"$key_state\" " +
  '"${BYTEQUAY_CP1_APP_SERVER_SENTINEL-<unset>}" ' +
  '"${CODEX_HOME-<unset>}" "$HOME" "$PWD"';

const environmentEvidencePrompt =
  `Use the shell exactly once to run this exact command: ${environmentEvidenceCommand}. ` +
  "Then reply with that exact output. Do not read files or use any other tool.";
const backgroundEvidenceCommand =
  `${environmentEvidenceCommand}; ` +
  "printf 'BYTEQUAY_CP1_BACKGROUND_READY=1\\n'; exec /bin/sleep 300";
const backgroundEvidencePrompt =
  `Use the shell exactly once to run this exact command: ${backgroundEvidenceCommand}. ` +
  "Set yield_time_ms to 250. When it yields a running process, immediately reply exactly " +
  "bytequay-background-yielded. Do not call write_stdin or use another tool.";

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    killSignal: "SIGKILL",
    maxBuffer: 16 * 1024 * 1024,
    timeout: 30_000,
    ...options,
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed (${result.status}):\n${result.stderr || result.stdout}`,
    );
  }
  return result.stdout.trim();
}

function minimalProcessEnvironment(home, temporaryDirectory) {
  return {
    HOME: home,
    LANG: process.env.LANG ?? "en_US.UTF-8",
    LOGNAME: process.env.LOGNAME ?? process.env.USER ?? "bytequay",
    PATH: process.env.PATH ?? "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    SHELL: "/bin/zsh",
    TMPDIR: temporaryDirectory,
    USER: process.env.USER ?? "bytequay",
  };
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function requestMethods(schema) {
  return new Set(
    schema.oneOf.flatMap((request) => request.properties?.method?.enum ?? []),
  );
}

function decisionVariants(schema, definition) {
  return new Set(
    schema.definitions[definition].oneOf.flatMap(
      (variant) => variant.enum ?? Object.keys(variant.properties ?? {}),
    ),
  );
}

function requireProperties(schema, names) {
  for (const name of names) {
    assert.ok(schema.properties?.[name], `${schema.title} lacks property ${name}`);
  }
}

function requireFields(schema, names) {
  for (const name of names) {
    assert.ok(schema.required?.includes(name), `${schema.title} does not require ${name}`);
  }
}

function checkSchema(directory, experimental) {
  const requests = requestMethods(readJson(join(directory, "ClientRequest.json")));
  const serverRequests = requestMethods(readJson(join(directory, "ServerRequest.json")));
  for (const method of stableMethods) {
    assert.ok(requests.has(method), `generated schema lacks ${method}`);
  }
  assert.ok(
    serverRequests.has(commandApprovalMethod),
    `generated schema lacks ${commandApprovalMethod}`,
  );
  for (const method of experimentalMethods) {
    assert.equal(
      requests.has(method),
      experimental,
      `${method} experimental gating changed`,
    );
  }

  const initialize = readJson(join(directory, "v1", "InitializeParams.json"));
  requireProperties(initialize.definitions.InitializeCapabilities, [
    "experimentalApi",
    "requestAttestation",
  ]);

  const modelList = readJson(join(directory, "v2", "ModelListResponse.json"));
  requireProperties(modelList.definitions.Model, [
    "defaultReasoningEffort",
    "id",
    "isDefault",
    "inputModalities",
    "supportedReasoningEfforts",
    "multiAgentVersion",
  ]);
  requireFields(modelList.definitions.Model, ["defaultReasoningEffort"]);
  requireProperties(modelList.definitions.ReasoningEffortOption, [
    "description",
    "reasoningEffort",
  ]);
  assert.deepEqual(modelList.definitions.MultiAgentVersion.enum, ["disabled", "v1", "v2"]);

  const threadRead = readJson(join(directory, "v2", "ThreadReadParams.json"));
  requireProperties(threadRead, ["threadId", "includeTurns"]);
  requireFields(threadRead, ["threadId"]);

  const threadResume = readJson(join(directory, "v2", "ThreadResumeParams.json"));
  requireProperties(threadResume, ["threadId", "cwd", "approvalPolicy", "sandbox"]);
  requireFields(threadResume, ["threadId"]);
  assert.equal(
    threadResume.properties.environments,
    undefined,
    "thread/resume unexpectedly gained an environments field",
  );

  if (!experimental) return;

  const commandApprovalParams = readJson(
    join(directory, "CommandExecutionRequestApprovalParams.json"),
  );
  requireProperties(commandApprovalParams, [
    "approvalId",
    "availableDecisions",
    "command",
    "cwd",
    "environmentId",
    "itemId",
    "kind",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
  ]);
  requireFields(commandApprovalParams, ["itemId", "startedAtMs", "threadId", "turnId"]);
  assert.deepEqual(
    commandApprovalParams.definitions.CommandExecutionApprovalKind.enum,
    ["command", "writeStdin"],
  );

  const commandApprovalResponse = readJson(
    join(directory, "CommandExecutionRequestApprovalResponse.json"),
  );
  requireProperties(commandApprovalResponse, ["decision"]);
  requireFields(commandApprovalResponse, ["decision"]);
  const commandDecisions = decisionVariants(
    commandApprovalResponse,
    "CommandExecutionApprovalDecision",
  );
  for (const decision of [
    "accept",
    "acceptForSession",
    "acceptWithExecpolicyAmendment",
    "applyNetworkPolicyAmendment",
    "decline",
    "cancel",
  ]) {
    assert.ok(commandDecisions.has(decision), `command approval lacks ${decision}`);
  }

  const fileApprovalResponse = readJson(
    join(directory, "FileChangeRequestApprovalResponse.json"),
  );
  const fileDecisions = decisionVariants(fileApprovalResponse, "FileChangeApprovalDecision");
  for (const decision of ["accept", "acceptForSession", "decline", "cancel"]) {
    assert.ok(fileDecisions.has(decision), `file approval lacks ${decision}`);
  }
  requireFields(readJson(join(directory, "PermissionsRequestApprovalResponse.json")), [
    "permissions",
  ]);

  const environmentAdd = readJson(join(directory, "v2", "EnvironmentAddParams.json"));
  requireProperties(environmentAdd, ["environmentId", "execServerUrl", "connectTimeoutMs"]);
  requireFields(environmentAdd, ["environmentId", "execServerUrl"]);

  const environmentInfo = readJson(join(directory, "v2", "EnvironmentInfoResponse.json"));
  requireProperties(environmentInfo, ["cwd", "shell"]);
  requireFields(environmentInfo, ["shell"]);
  requireFields(environmentInfo.definitions.EnvironmentShellInfo, ["name", "path"]);

  const threadStart = readJson(join(directory, "v2", "ThreadStartParams.json"));
  const turnStart = readJson(join(directory, "v2", "TurnStartParams.json"));
  requireProperties(threadStart, ["environments", "selectedCapabilityRoots"]);
  requireProperties(turnStart, ["environments", "threadId", "input"]);
  requireFields(threadStart.definitions.TurnEnvironmentParams, ["environmentId", "cwd"]);
  requireFields(turnStart.definitions.TurnEnvironmentParams, ["environmentId", "cwd"]);

  const backgroundList = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsListResponse.json"),
  );
  requireProperties(backgroundList, ["data", "nextCursor"]);
  requireFields(backgroundList, ["data"]);
  requireProperties(backgroundList.definitions.ThreadBackgroundTerminal, [
    "processId",
    "itemId",
    "command",
    "cwd",
  ]);
  requireFields(backgroundList.definitions.ThreadBackgroundTerminal, [
    "processId",
    "itemId",
    "command",
    "cwd",
  ]);

  const backgroundListParams = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsListParams.json"),
  );
  requireProperties(backgroundListParams, ["threadId", "cursor", "limit"]);
  requireFields(backgroundListParams, ["threadId"]);

  const backgroundTerminateParams = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsTerminateParams.json"),
  );
  requireProperties(backgroundTerminateParams, ["threadId", "processId"]);
  requireFields(backgroundTerminateParams, ["threadId", "processId"]);
  const backgroundTerminateResponse = readJson(
    join(directory, "v2", "ThreadBackgroundTerminalsTerminateResponse.json"),
  );
  requireProperties(backgroundTerminateResponse, ["terminated"]);
  requireFields(backgroundTerminateResponse, ["terminated"]);

  const threadResponse = readJson(join(directory, "v2", "ThreadReadResponse.json"));
  requireProperties(threadResponse.definitions.Thread, ["parentThreadId", "turns"]);
  const commandItem = threadResponse.definitions.ThreadItem.oneOf.find(
    (item) => item.properties?.type?.enum?.includes("commandExecution"),
  );
  assert.ok(commandItem, "ThreadItem lacks commandExecution");
  requireProperties(commandItem, [
    "aggregatedOutput",
    "command",
    "commandActions",
    "cwd",
    "exitCode",
    "id",
    "processId",
    "status",
    "type",
  ]);
  requireFields(commandItem, ["command", "commandActions", "cwd", "id", "status", "type"]);
  assert.deepEqual(
    threadResponse.definitions.CommandExecutionStatus.enum,
    ["inProgress", "completed", "failed", "declined"],
  );
}

function staticCompatibilityCheck() {
  const root = mkdtempSync(join(tmpdir(), "bytequay-codex-schema-"));
  const codexHome = join(root, "codex-home");
  const temporaryDirectory = join(root, "tmp");
  const experimental = join(root, "experimental");
  const stable = join(root, "stable");
  mkdirSync(codexHome, { mode: 0o700 });
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  const environment = {
    ...minimalProcessEnvironment(root, temporaryDirectory),
    CODEX_HOME: codexHome,
    CODEX_SQLITE_HOME: codexHome,
  };
  try {
    const versionOutput = run("codex", ["--version"], { env: environment });
    assert.equal(
      versionOutput,
      `codex-cli ${contract.version}`,
      `expected codex-cli ${contract.version}, got ${versionOutput}`,
    );

    run("codex", [
      "app-server",
      "generate-json-schema",
      "--experimental",
      "--out",
      experimental,
    ], { env: environment });
    run("codex", ["app-server", "generate-json-schema", "--out", stable], {
      env: environment,
    });

    assert.equal(
      sha256(join(experimental, "codex_app_server_protocol.schemas.json")),
      contract.experimentalBundleSha256,
      "experimental protocol bundle changed",
    );
    assert.equal(
      sha256(join(experimental, "codex_app_server_protocol.v2.schemas.json")),
      contract.experimentalV2BundleSha256,
      "experimental v2 protocol bundle changed",
    );
    assert.equal(
      sha256(join(stable, "codex_app_server_protocol.schemas.json")),
      contract.stableBundleSha256,
      "stable protocol bundle changed",
    );
    assert.equal(
      sha256(join(stable, "codex_app_server_protocol.v2.schemas.json")),
      contract.stableV2BundleSha256,
      "stable v2 protocol bundle changed",
    );

    checkSchema(experimental, true);
    checkSchema(stable, false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  console.log(`PASS exact Codex version ${contract.version}`);
  console.log(`PASS generated stable and experimental schema (${contract.schemaCommand})`);
}

class JsonRpcProcess {
  constructor(child) {
    this.child = child;
    this.nextId = 1;
    this.pending = new Map();
    this.notifications = [];
    this.serverRequestHandler = null;
    this.serverRequests = [];
    this.waiters = [];
    this.stderr = "";
    this.failure = null;

    createInterface({ input: child.stdout }).on("line", (line) => {
      try {
        this.#receive(line);
      } catch (error) {
        this.#disconnect(error);
      }
    });
    child.stdin.on("error", (error) => {
      this.#disconnect(new Error(`app-server input failed: ${error.message}`));
    });
    child.stdout.on("error", (error) => {
      this.#disconnect(new Error(`app-server output failed: ${error.message}`));
    });
    child.stderr.on("data", (chunk) => {
      this.stderr = `${this.stderr}${chunk}`.slice(-16_384);
    });
    child.stderr.on("error", (error) => {
      this.#disconnect(new Error(`app-server diagnostics failed: ${error.message}`));
    });
    child.on("error", (error) => {
      this.#disconnect(new Error(`unable to start app-server: ${error.message}`));
    });
    child.on("exit", (code, signal) => {
      this.#disconnect(new Error(
        `app-server exited (${code ?? signal})${this.stderr ? `:\n${this.stderr}` : ""}`,
      ));
    });
  }

  request(method, params = {}, timeoutMs = 30_000) {
    if (this.failure) return Promise.reject(this.failure);
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timeout });
      try {
        this.#send({ id, method, params });
      } catch (error) {
        clearTimeout(timeout);
        this.pending.delete(id);
        reject(error);
      }
    });
  }

  notify(method, params = {}) {
    this.#send({ method, params });
  }

  handleServerRequests(method, respond) {
    assert.equal(this.serverRequestHandler, null, "server request handler already installed");
    this.serverRequestHandler = { method, respond };
  }

  stopHandlingServerRequests() {
    this.serverRequestHandler = null;
  }

  waitFor(method, predicate = () => true, timeoutMs = 180_000) {
    if (this.failure) return Promise.reject(this.failure);
    const buffered = this.notifications.find(
      (notification) => notification.method === method && predicate(notification.params),
    );
    if (buffered) return Promise.resolve(buffered.params);

    return new Promise((resolve, reject) => {
      const waiter = { method, predicate, resolve, reject };
      waiter.timeout = setTimeout(() => {
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        reject(new Error(`${method} notification timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  #send(message) {
    if (this.failure) throw this.failure;
    if (!this.child.stdin.writable) throw new Error("app-server input is closed");
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #receive(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      throw new Error(`app-server emitted non-JSON stdout: ${line}`);
    }

    if (message.id !== undefined && message.method) {
      this.#handleServerRequest(message);
      return;
    }
    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timeout);
      this.pending.delete(message.id);
      if (message.error) {
        pending.reject(
          new Error(`${pending.method} failed: ${JSON.stringify(message.error)}`),
        );
      } else {
        pending.resolve(message.result);
      }
      return;
    }
    if (!message.method) return;

    this.notifications.push(message);
    if (this.notifications.length > 2_000) this.notifications.shift();
    for (const waiter of [...this.waiters]) {
      if (waiter.method === message.method && waiter.predicate(message.params)) {
        clearTimeout(waiter.timeout);
        this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
        waiter.resolve(message.params);
      }
    }
  }

  #handleServerRequest(message) {
    const handler = this.serverRequestHandler;
    if (!handler || handler.method !== message.method) {
      this.#send({
        id: message.id,
        error: { code: -32601, message: `unexpected CP1 request: ${message.method}` },
      });
      throw new Error(`unexpected App Server request: ${message.method}`);
    }
    const result = handler.respond(message.params);
    this.serverRequests.push(message);
    this.#send({ id: message.id, result });
  }

  #disconnect(error) {
    if (this.failure) return;
    this.failure = error;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timeout);
      waiter.reject(error);
    }
    this.waiters = [];
  }
}

function processGroupExists(pid) {
  if (!pid) return false;
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

function signalProcessGroup(pid, signal) {
  if (!pid) return;
  try {
    process.kill(-pid, signal);
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForProcessGroupExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (processGroupExists(pid) && Date.now() < deadline) {
    await delay(50);
  }
  return !processGroupExists(pid);
}

async function stopProcess(child) {
  const pid = child?.pid;
  if (!pid || !processGroupExists(pid)) return;
  signalProcessGroup(pid, "SIGTERM");
  if (await waitForProcessGroupExit(pid, 5_000)) return;
  signalProcessGroup(pid, "SIGKILL");
  assert.ok(
    await waitForProcessGroupExit(pid, 2_000),
    `process group ${pid} remained after SIGKILL`,
  );
}

function startExecServer(cwd, environment, registerChild) {
  const child = spawn("codex", ["exec-server", "--listen", "ws://127.0.0.1:0"], {
    cwd,
    detached: true,
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
  });
  registerChild(child);
  const server = { child, failure: null, url: null };
  return new Promise((resolve, reject) => {
    let stderr = "";
    let settled = false;
    let timeout;
    const fail = async (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      try {
        await stopProcess(child);
        reject(error);
      } catch (cleanupError) {
        reject(new AggregateError([error, cleanupError], "exec-server startup and cleanup failed"));
      }
    };
    const reportFailure = (error) => {
      if (settled) {
        server.failure ??= error;
      } else {
        void fail(error);
      }
    };
    timeout = setTimeout(() => {
      void fail(new Error("exec-server startup timed out"));
    }, 15_000);
    createInterface({ input: child.stdout }).once("line", (line) => {
      if (settled) return;
      clearTimeout(timeout);
      if (!/^ws:\/\/127\.0\.0\.1:\d+$/.test(line)) {
        void fail(new Error(`unexpected exec-server endpoint: ${line}`));
        return;
      }
      settled = true;
      server.url = line;
      resolve(server);
    });
    child.stderr.on("data", (chunk) => {
      stderr = `${stderr}${chunk}`.slice(-8_192);
    });
    child.stdout.on("error", (error) => {
      reportFailure(new Error(`exec-server output failed: ${error.message}`));
    });
    child.stderr.on("error", (error) => {
      reportFailure(new Error(`exec-server diagnostics failed: ${error.message}`));
    });
    child.once("error", (error) => {
      reportFailure(new Error(`unable to start exec-server: ${error.message}`));
    });
    child.once("exit", (code, signal) => {
      reportFailure(new Error(`exec-server exited (${code ?? signal}): ${stderr}`));
    });
  });
}

async function startAppServer(cwd, environment, registerChild) {
  const child = spawn("codex", appServerArguments(), {
    cwd,
    detached: true,
    env: environment,
    stdio: ["pipe", "pipe", "pipe"],
  });
  registerChild(child);
  const rpc = new JsonRpcProcess(child);
  await rpc.request("initialize", {
    clientInfo: { name: "bytequay-cp1-compat", version: "1" },
    capabilities: { experimentalApi: true, requestAttestation: false },
  });
  rpc.notify("initialized");
  return { child, rpc };
}

function assertRuntimeHealthy(rpc, execServers) {
  assert.ifError(rpc.failure);
  assert.ok(processGroupExists(rpc.child.pid), "App Server exited before PASS");
  for (const [name, server] of execServers) {
    assert.ifError(server.failure);
    assert.ok(
      processGroupExists(server.child.pid),
      `${name} exec-server exited before PASS`,
    );
  }
}

function canonicalPath(uri) {
  assert.equal(new URL(uri).protocol, "file:", `expected canonical file URI, got ${uri}`);
  return realpathSync(fileURLToPath(uri));
}

async function completeTurn(rpc, params) {
  const started = await rpc.request("turn/start", params);
  const completed = await rpc.waitFor(
    "turn/completed",
    (notification) => notification.threadId === params.threadId &&
      notification.turn.id === started.turn.id,
  );
  assert.equal(completed.turn.status, "completed", JSON.stringify(completed.turn.error));
  return completed.turn;
}

async function waitForNoBackgroundTerminals(rpc, threadId, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const response = await rpc.request("thread/backgroundTerminals/list", {
      threadId,
      limit: 100,
    });
    if (response.data.length === 0) return response;
    assert.ok(Date.now() < deadline, `background terminals remained after ${timeoutMs} ms`);
    await delay(50);
  }
}

function createIsolatedCodexHome(root) {
  const isolatedHome = join(root, "codex-home");
  mkdirSync(isolatedHome, { mode: 0o700 });
  return isolatedHome;
}

function requiredLiveApiKey() {
  const apiKey = process.env.BYTEQUAY_CODEX_COMPAT_API_KEY;
  assert.ok(
    apiKey?.trim(),
    "live gate requires BYTEQUAY_CODEX_COMPAT_API_KEY; " +
      "it never copies rotating ChatGPT credentials",
  );
  return apiKey.trim();
}

function appServerProcessEnvironment(root, codexHome, apiKey) {
  const temporaryDirectory = join(root, "app-server-tmp");
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  const environment = {
    ...minimalProcessEnvironment(root, temporaryDirectory),
    BYTEQUAY_CP1_APP_SERVER_SENTINEL: "must-not-reach-tools",
    CODEX_API_KEY: apiKey,
    CODEX_HOME: codexHome,
    CODEX_SQLITE_HOME: codexHome,
  };
  for (const name of [
    "ALL_PROXY",
    "HTTPS_PROXY",
    "HTTP_PROXY",
    "NO_PROXY",
    "all_proxy",
    "https_proxy",
    "http_proxy",
    "no_proxy",
  ]) {
    if (process.env[name]) environment[name] = process.env[name];
  }
  return environment;
}

function executorProcessEnvironment(root, name, cwd) {
  const codexHome = join(root, `${name}-codex-home`);
  const temporaryDirectory = join(root, `${name}-tmp`);
  mkdirSync(codexHome, { mode: 0o700 });
  mkdirSync(temporaryDirectory, { mode: 0o700 });
  return {
    environment: {
      ...minimalProcessEnvironment(cwd, temporaryDirectory),
      CODEX_HOME: codexHome,
      CODEX_SQLITE_HOME: codexHome,
    },
    codexHome,
  };
}

function initializeDisposableGitWorktree(cwd, environment) {
  run("git", ["init", "--quiet"], { cwd, env: environment });
  assert.equal(
    run("git", ["status", "--short", "--untracked-files=all"], {
      cwd,
      env: environment,
    }),
    "",
    `new disposable Git worktree is not clean: ${cwd}`,
  );
}

function assertProofFile(cwd, environment) {
  assert.equal(
    readFileSync(join(cwd, proofFileName), "utf8"),
    proofFileContents,
    `unexpected proof file content in ${cwd}`,
  );
  assert.equal(
    run("git", ["status", "--short", "--untracked-files=all"], {
      cwd,
      env: environment,
    }),
    `?? ${proofFileName}`,
    `bounded proof file was not the only Git worktree mutation in ${cwd}`,
  );
}

function appServerArguments() {
  const args = [
    "app-server",
    "--listen",
    "stdio://",
    "--strict-config",
    "--enable",
    "skip_host_skill_discovery",
    "-c",
    'web_search="disabled"',
    "-c",
    'shell_environment_policy.inherit="core"',
    "-c",
    "shell_environment_policy.ignore_default_excludes=false",
    "-c",
    'shell_environment_policy.include_only=["HOME","LANG","LOGNAME","PATH","SHELL","TMPDIR","USER"]',
    "-c",
    "shell_environment_policy.experimental_use_profile=false",
  ];
  for (const feature of disabledFeatures) args.push("--disable", feature);
  return args;
}

async function verifyCapabilityIsolation(rpc, threadId, codexHome, cwds) {
  const [mcpStatus, hooks, config] = await Promise.all([
    rpc.request("mcpServerStatus/list", {
      threadId,
      limit: 100,
      detail: "toolsAndAuthOnly",
    }),
    rpc.request("hooks/list", { cwds }),
    rpc.request("config/read", { cwd: cwds[0], includeLayers: true }),
  ]);
  assert.deepEqual(mcpStatus.data, [], "isolated thread inherited MCP servers");
  assert.ok(
    hooks.data.length === cwds.length &&
      hooks.data.every(
        (entry) =>
          entry.hooks.length === 0 &&
          entry.errors.length === 0 &&
          entry.warnings.length === 0,
      ),
    "isolated thread inherited lifecycle hooks",
  );
  assert.equal(config.config.web_search, "disabled", "web search was not disabled");
  for (const layer of config.layers ?? []) {
    if (layer.name.type === "project") {
      assert.fail(`temporary environment loaded project config ${layer.name.dotCodexFolder}`);
    }
    if (layer.name.type === "user") {
      assert.equal(
        realpathSync(dirname(layer.name.file)),
        realpathSync(codexHome),
        `temporary environment loaded user config ${layer.name.file}`,
      );
    }
  }
}

function commandRanIn(command, cwd, expectedCommand) {
  const apiKeyVisibility = command.aggregatedOutput
    ?.match(/^CODEX_API_KEY_VISIBLE=(.+)$/m)?.[1].trim();
  const appServerSentinel = command.aggregatedOutput
    ?.match(/^APP_SERVER_SENTINEL=(.+)$/m)?.[1].trim();
  const codexHome = command.aggregatedOutput?.match(/^CODEX_HOME=(.+)$/m)?.[1].trim();
  const home = command.aggregatedOutput?.match(/^HOME=(.+)$/m)?.[1].trim();
  const workingDirectory = command.aggregatedOutput?.match(/^PWD=(.+)$/m)?.[1].trim();
  const exactCommandRecorded = command.commandActions?.some(
    (action) => action.command === expectedCommand,
  );
  return command.command === expectedCommand &&
    exactCommandRecorded &&
    realpathSync(command.cwd) === realpathSync(cwd) &&
    apiKeyVisibility === "absent" &&
    appServerSentinel === "<unset>" &&
    codexHome === "<unset>" &&
    home &&
    workingDirectory &&
    realpathSync(home) === realpathSync(cwd) &&
    realpathSync(workingDirectory) === realpathSync(cwd);
}

function commandItemDiagnostics(items) {
  return JSON.stringify(
    items.map((item) => ({
      actionTypes: item.commandActions?.map((action) => action.type) ?? [],
      cwd: item.cwd,
      exitCode: item.exitCode,
      id: item.id,
      status: item.status,
      type: item.type,
    })),
  );
}

function assertSingleCommand(
  items,
  cwd,
  label,
  expectedCommand = environmentEvidenceCommand,
  expectedStatus = "completed",
  expectedExitCode = 0,
) {
  const activeItems = items.filter((item) => !passiveTurnItemTypes.has(item.type));
  assert.equal(
    activeItems.length,
    1,
    `${label} executed unexpected active items: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(activeItems[0].type, "commandExecution", `${label} did not use the shell`);
  assert.ok(activeItems[0].id, `${label} command had no item id`);
  assert.ok(
    commandRanIn(activeItems[0], cwd, expectedCommand),
    `${label} lacked environment evidence: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(activeItems[0].status, expectedStatus, `${label} had unexpected status`);
  if (expectedExitCode === null) {
    assert.ok(activeItems[0].exitCode == null, `${label} exited before it was terminated`);
  } else if (expectedExitCode !== undefined) {
    assert.equal(activeItems[0].exitCode, expectedExitCode, `${label} had unexpected exit code`);
  }
  return activeItems[0];
}

function assertSingleCommandStart(items, label) {
  const activeItems = items.filter((item) => !passiveTurnItemTypes.has(item.type));
  assert.equal(
    activeItems.length,
    1,
    `${label} started unexpected active items: ${commandItemDiagnostics(activeItems)}`,
  );
  assert.equal(
    activeItems[0].type,
    "commandExecution",
    `${label} did not start with the shell`,
  );
  assert.ok(activeItems[0].id, `${label} command had no item id`);
  return activeItems[0];
}

async function liveCompatibilityCheck() {
  const root = mkdtempSync(join(tmpdir(), "bytequay-codex-live-"));
  const cwdA = join(root, "environment-a");
  const cwdB = join(root, "environment-b");

  let appServer;
  let execA;
  let execB;
  let rpc;
  let failure;
  let interruptedSignal;
  let rejectInterruption;
  const signalErrors = [];
  const children = new Set();
  const interruption = new Promise((_, reject) => {
    rejectInterruption = reject;
  });
  void interruption.catch(() => {});
  const interruptible = (promise) => Promise.race([promise, interruption]);
  const registerChild = (child) => {
    children.add(child);
    if (interruptedSignal) {
      try {
        signalProcessGroup(child.pid, "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const stopRegisteredChild = async (child) => {
    await stopProcess(child);
    children.delete(child);
  };
  const handleSignal = (signal) => {
    const firstSignal = !interruptedSignal;
    interruptedSignal ??= signal;
    process.exitCode = signal === "SIGINT" ? 130 : 143;
    if (firstSignal) rejectInterruption(new Error(`received ${signal}`));
    for (const child of children) {
      try {
        signalProcessGroup(child.pid, firstSignal ? "SIGTERM" : "SIGKILL");
      } catch (error) {
        signalErrors.push(error);
      }
    }
  };
  const handleSigint = () => handleSignal("SIGINT");
  const handleSigterm = () => handleSignal("SIGTERM");
  process.on("SIGINT", handleSigint);
  process.on("SIGTERM", handleSigterm);
  try {
    const apiKey = requiredLiveApiKey();
    mkdirSync(cwdA);
    mkdirSync(cwdB);
    const codexHome = createIsolatedCodexHome(root);
    const appServerEnvironment = appServerProcessEnvironment(root, codexHome, apiKey);
    const executorA = executorProcessEnvironment(root, "exec-a", cwdA);
    const executorB = executorProcessEnvironment(root, "exec-b", cwdB);
    initializeDisposableGitWorktree(cwdA, executorA.environment);
    initializeDisposableGitWorktree(cwdB, executorB.environment);
    assert.equal(existsSync(join(codexHome, "auth.json")), false);
    assert.equal(existsSync(join(executorA.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(executorB.codexHome, "auth.json")), false);
    execA = await interruptible(
      startExecServer(cwdA, executorA.environment, registerChild),
    );
    execB = await interruptible(
      startExecServer(cwdB, executorB.environment, registerChild),
    );
    ({ child: appServer, rpc } = await interruptible(
      startAppServer(root, appServerEnvironment, registerChild),
    ));

    const models = await interruptible(rpc.request("model/list", {}));
    const model = models.data.find((candidate) => candidate.isDefault) ?? models.data[0];
    assert.ok(model, "model/list returned no model");
    assert.ok(model.supportedReasoningEfforts.length > 0);
    assert.ok(
      model.supportedReasoningEfforts.some(
        (option) => option.reasoningEffort === model.defaultReasoningEffort,
      ),
      "model default reasoning effort is not in its advertised choices",
    );
    assert.ok(model.inputModalities.includes("text"));
    assert.ok(
      model.multiAgentVersion === null ||
        ["disabled", "v1", "v2"].includes(model.multiAgentVersion),
    );

    await interruptible(rpc.request("environment/add", {
      environmentId: "bytequay-cp1-a",
      execServerUrl: execA.url,
      connectTimeoutMs: 10_000,
    }));
    await interruptible(rpc.request("environment/add", {
      environmentId: "bytequay-cp1-b",
      execServerUrl: execB.url,
      connectTimeoutMs: 10_000,
    }));
    const [infoA, infoB] = await interruptible(Promise.all([
      rpc.request("environment/info", { environmentId: "bytequay-cp1-a" }),
      rpc.request("environment/info", { environmentId: "bytequay-cp1-b" }),
    ]));
    assert.equal(canonicalPath(infoA.cwd), realpathSync(cwdA));
    assert.equal(canonicalPath(infoB.cwd), realpathSync(cwdB));
    assert.notEqual(infoA.cwd, infoB.cwd);
    assert.ok(infoA.shell.name && infoA.shell.path);
    assert.ok(infoB.shell.name && infoB.shell.path);

    const environmentA = {
      environmentId: "bytequay-cp1-a",
      cwd: cwdA,
      runtimeWorkspaceRoots: [cwdA],
    };
    const environmentB = {
      environmentId: "bytequay-cp1-b",
      cwd: cwdB,
      runtimeWorkspaceRoots: [cwdB],
    };
    const started = await interruptible(rpc.request("thread/start", {
      cwd: cwdA,
      model: model.model,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      ephemeral: false,
      serviceName: "ByteQuay CP1 compatibility fixture",
      dynamicTools: [],
      selectedCapabilityRoots: [],
      environments: [environmentA],
    }));
    const threadId = started.thread.id;

    await interruptible(
      verifyCapabilityIsolation(rpc, threadId, codexHome, [cwdA, cwdB]),
    );

    let approvalItemId;
    let approvalTurnId;
    const parentCommandApprovals = [];
    rpc.handleServerRequests("item/commandExecution/requestApproval", (params) => {
      assert.equal(params.threadId, threadId);
      assert.ok(params.turnId, "command approval omitted turnId");
      assert.ok(params.itemId, "command approval omitted itemId");
      assert.equal(realpathSync(params.cwd), realpathSync(cwdA));
      assert.equal(params.environmentId, environmentA.environmentId);
      assert.equal(params.kind ?? "command", "command");
      approvalItemId ??= params.itemId;
      approvalTurnId ??= params.turnId;
      assert.equal(params.itemId, approvalItemId, "approval crossed command items");
      assert.equal(params.turnId, approvalTurnId, "approval crossed turns");
      if (params.availableDecisions) {
        assert.ok(
          params.availableDecisions.includes("accept"),
          "command approval did not offer accept",
        );
      }
      if (params.approvalId == null) {
        assert.ok(
          params.command?.includes(proofFileName) &&
            params.command.includes("CODEX_API_KEY_VISIBLE") &&
            params.command.includes("APP_SERVER_SENTINEL") &&
            params.command.includes("CODEX_HOME=%s"),
          "parent approval did not contain the expected evidence command",
        );
        assert.equal(
          existsSync(join(cwdA, proofFileName)),
          false,
          "proof file existed before parent command approval",
        );
        parentCommandApprovals.push(params);
      }
      return { decision: "accept" };
    });
    const firstTurnNotificationStart = rpc.notifications.length;
    const firstCompletedTurn = await interruptible(completeTurn(rpc, {
      threadId,
      input: [
        {
          type: "text",
          text: environmentEvidencePrompt,
          text_elements: [],
        },
      ],
      model: model.model,
      effort: model.defaultReasoningEffort,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      cwd: cwdA,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwdA],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environmentA],
    }));
    rpc.stopHandlingServerRequests();

    const firstTurnNotifications = rpc.notifications.slice(firstTurnNotificationStart);
    const firstStartedItems = firstTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/started" &&
          notification.params.threadId === threadId,
      )
      .map((notification) => notification.params.item);
    const firstStartedCommand = assertSingleCommandStart(firstStartedItems, "first turn");
    const firstCompletedItems = firstTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadId,
      )
      .map((notification) => notification.params.item);
    const firstCompletedCommand = assertSingleCommand(
      firstCompletedItems,
      cwdA,
      "first turn completion",
    );
    assert.equal(firstCompletedCommand.id, firstStartedCommand.id);
    assert.ok(rpc.serverRequests.length >= 1, "first turn did not request approval");
    assert.equal(
      parentCommandApprovals.length,
      1,
      "first turn did not request exactly one parent command approval",
    );
    assert.equal(parentCommandApprovals[0].itemId, firstStartedCommand.id);
    assert.equal(parentCommandApprovals[0].turnId, firstCompletedTurn.id);
    assertProofFile(cwdA, executorA.environment);
    assert.equal(
      existsSync(join(cwdB, proofFileName)),
      false,
      "environment A wrote its proof file into environment B",
    );
    assert.ok(
      firstTurnNotifications.some(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadId &&
          notification.params.item?.type === "agentMessage",
      ),
      "final agent message was not streamed",
    );

    const firstRead = await interruptible(
      rpc.request("thread/read", { threadId, includeTurns: true }),
    );
    assert.equal(firstRead.thread.turns.length, 1, "thread/read returned extra turns");
    assert.equal(firstRead.thread.turns[0].id, firstCompletedTurn.id);
    const firstHistoryCommand = assertSingleCommand(
      firstRead.thread.turns[0].items,
      cwdA,
      "first turn history",
    );
    assert.equal(firstHistoryCommand.id, firstCompletedCommand.id);

    const resumed = await interruptible(rpc.request("thread/resume", {
      threadId,
      cwd: cwdB,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      excludeTurns: false,
    }));
    assert.equal(resumed.thread.id, threadId);

    const secondTurnNotificationStart = rpc.notifications.length;
    const secondCompletedTurn = await interruptible(completeTurn(rpc, {
      threadId,
      input: [
        {
          type: "text",
          text: backgroundEvidencePrompt,
          text_elements: [],
        },
      ],
      model: model.model,
      effort: model.defaultReasoningEffort,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      cwd: cwdB,
      sandboxPolicy: {
        type: "workspaceWrite",
        writableRoots: [cwdB],
        networkAccess: false,
        excludeTmpdirEnvVar: true,
        excludeSlashTmp: true,
      },
      environments: [environmentB],
    }));

    const secondTurnNotifications = rpc.notifications.slice(secondTurnNotificationStart);
    const secondStartedItems = secondTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/started" &&
          notification.params.threadId === threadId,
      )
      .map((notification) => notification.params.item);
    const secondStartedCommand = assertSingleCommandStart(
      secondStartedItems,
      "second turn",
    );
    assert.equal(secondStartedCommand.command, backgroundEvidenceCommand);
    assert.equal(secondStartedCommand.status, "inProgress");
    assert.equal(realpathSync(secondStartedCommand.cwd), realpathSync(cwdB));
    const secondTurnCompletedItems = secondTurnNotifications
      .filter(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadId,
      )
      .map((notification) => notification.params.item);
    assert.deepEqual(
      secondTurnCompletedItems.filter((item) => !passiveTurnItemTypes.has(item.type)),
      [],
      "yielded command completed before targeted termination",
    );
    assertProofFile(cwdB, executorB.environment);
    assertProofFile(cwdA, executorA.environment);
    assert.ok(
      secondTurnNotifications.some(
        (notification) =>
          notification.method === "item/completed" &&
          notification.params.threadId === threadId &&
          notification.params.item?.type === "agentMessage" &&
          notification.params.item.text.trim(),
      ),
      "executor B final agent message was not streamed",
    );

    const secondRead = await interruptible(
      rpc.request("thread/read", { threadId, includeTurns: true }),
    );
    assert.equal(secondRead.thread.turns.length, 2, "thread/read did not return two turns");
    assert.equal(secondRead.thread.id, threadId);
    assert.equal(secondRead.thread.turns[0].id, firstCompletedTurn.id);
    assert.equal(secondRead.thread.turns[1].id, secondCompletedTurn.id);
    const firstReplayedCommand = assertSingleCommand(
      secondRead.thread.turns[0].items,
      cwdA,
      "first replayed turn",
    );
    const secondHistoryCommand = assertSingleCommand(
      secondRead.thread.turns[1].items,
      cwdB,
      "second turn history",
      backgroundEvidenceCommand,
      "inProgress",
      null,
    );
    assert.equal(firstReplayedCommand.id, firstCompletedCommand.id);
    assert.equal(secondHistoryCommand.id, secondStartedCommand.id);
    assert.ok(
      secondRead.thread.turns.at(-1).items.some(
        (item) => item.type === "agentMessage" && item.text.trim(),
      ),
      "resumed thread did not return a final response",
    );
    assert.ok(
      !rpc.notifications.some(
        (notification) =>
          notification.method === "mcpServer/startupStatus/updated" &&
          notification.params.threadId === threadId,
      ),
      "isolated thread started an inherited MCP server",
    );

    const beforeCleanup = await interruptible(rpc.request("thread/backgroundTerminals/list", {
      threadId,
      limit: 100,
    }));
    assert.equal(beforeCleanup.nextCursor ?? null, null);
    assert.equal(beforeCleanup.data.length, 1, "yielded command was not listed exactly once");
    const backgroundTerminal = beforeCleanup.data[0];
    assert.equal(backgroundTerminal.itemId, secondStartedCommand.id);
    assert.ok(
      typeof backgroundTerminal.processId === "string" && backgroundTerminal.processId.trim(),
      "background terminal omitted processId",
    );
    assert.equal(backgroundTerminal.command, backgroundEvidenceCommand);
    assert.equal(realpathSync(backgroundTerminal.cwd), realpathSync(cwdB));
    const backgroundCompletion = rpc.waitFor(
      "item/completed",
      (notification) =>
        notification.threadId === threadId &&
        notification.turnId === secondCompletedTurn.id &&
        notification.item?.id === secondStartedCommand.id,
    );
    const termination = await interruptible(rpc.request(
      "thread/backgroundTerminals/terminate",
      { threadId, processId: backgroundTerminal.processId },
    ));
    assert.deepEqual(termination, { terminated: true });
    const terminatedItem = await interruptible(backgroundCompletion);
    const terminatedSecondCommand = assertSingleCommand(
      [terminatedItem.item],
      cwdB,
      "targeted background termination",
      backgroundEvidenceCommand,
      "failed",
      undefined,
    );
    assert.notEqual(terminatedSecondCommand.exitCode, 0);
    assert.ok(
      terminatedSecondCommand.aggregatedOutput?.includes(
        "BYTEQUAY_CP1_BACKGROUND_READY=1",
      ),
      "terminated command omitted its pre-yield output",
    );
    const afterTermination = await interruptible(
      waitForNoBackgroundTerminals(rpc, threadId),
    );
    assert.equal(afterTermination.nextCursor ?? null, null);
    await interruptible(rpc.request("thread/backgroundTerminals/clean", { threadId }));
    const afterCleanup = await interruptible(rpc.request("thread/backgroundTerminals/list", {
      threadId,
      limit: 100,
    }));
    assert.deepEqual(afterCleanup.data, []);
    const terminatedRead = await interruptible(
      rpc.request("thread/read", { threadId, includeTurns: true }),
    );
    const terminatedHistoryCommand = assertSingleCommand(
      terminatedRead.thread.turns[1].items,
      cwdB,
      "terminated second turn history",
      backgroundEvidenceCommand,
      "failed",
      undefined,
    );
    assert.equal(terminatedHistoryCommand.id, terminatedSecondCommand.id);

    assertRuntimeHealthy(rpc, [
      ["environment A", execA],
      ["environment B", execB],
    ]);
    const oldAppServerPid = appServer.pid;
    const oldExecAPid = execA.child.pid;
    const oldExecBPid = execB.child.pid;
    await interruptible(stopRegisteredChild(appServer));
    await interruptible(Promise.all([
      stopRegisteredChild(execA.child),
      stopRegisteredChild(execB.child),
    ]));
    assert.equal(processGroupExists(oldAppServerPid), false);
    assert.equal(processGroupExists(oldExecAPid), false);
    assert.equal(processGroupExists(oldExecBPid), false);

    const replacementExecutorB = executorProcessEnvironment(root, "exec-b-restarted", cwdB);
    assert.equal(existsSync(join(replacementExecutorB.codexHome, "auth.json")), false);
    execB = await interruptible(
      startExecServer(cwdB, replacementExecutorB.environment, registerChild),
    );
    ({ child: appServer, rpc } = await interruptible(
      startAppServer(root, appServerEnvironment, registerChild),
    ));
    await interruptible(rpc.request("environment/add", {
      environmentId: environmentB.environmentId,
      execServerUrl: execB.url,
      connectTimeoutMs: 10_000,
    }));
    const replacementInfoB = await interruptible(rpc.request("environment/info", {
      environmentId: environmentB.environmentId,
    }));
    assert.equal(canonicalPath(replacementInfoB.cwd), realpathSync(cwdB));
    assert.ok(replacementInfoB.shell.name && replacementInfoB.shell.path);

    const restartNotificationStart = rpc.notifications.length;
    const recovered = await interruptible(rpc.request("thread/resume", {
      threadId,
      cwd: cwdB,
      approvalPolicy: "never",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
      excludeTurns: false,
    }));
    assert.equal(recovered.thread.id, threadId);
    await interruptible(
      verifyCapabilityIsolation(rpc, threadId, codexHome, [cwdA, cwdB]),
    );
    const recoveredRead = await interruptible(
      rpc.request("thread/read", { threadId, includeTurns: true }),
    );
    assert.equal(recoveredRead.thread.id, threadId);
    assert.equal(recoveredRead.thread.turns.length, 2);
    assert.equal(recoveredRead.thread.turns[0].id, firstCompletedTurn.id);
    assert.equal(recoveredRead.thread.turns[1].id, secondCompletedTurn.id);
    const recoveredFirstCommand = assertSingleCommand(
      recoveredRead.thread.turns[0].items,
      cwdA,
      "recovered first turn",
    );
    const recoveredSecondCommand = assertSingleCommand(
      recoveredRead.thread.turns[1].items,
      cwdB,
      "recovered second turn",
      backgroundEvidenceCommand,
      "failed",
      undefined,
    );
    assert.equal(recoveredFirstCommand.id, firstCompletedCommand.id);
    assert.equal(recoveredSecondCommand.id, terminatedSecondCommand.id);
    const recoveredBackground = await interruptible(
      rpc.request("thread/backgroundTerminals/list", { threadId, limit: 100 }),
    );
    assert.deepEqual(recoveredBackground.data, []);
    assertProofFile(cwdA, executorA.environment);
    assertProofFile(cwdB, replacementExecutorB.environment);

    await interruptible(delay(100));
    const settledRecoveredRead = await interruptible(
      rpc.request("thread/read", { threadId, includeTurns: true }),
    );
    assert.deepEqual(
      settledRecoveredRead.thread.turns.map((turn) => turn.id),
      [firstCompletedTurn.id, secondCompletedTurn.id],
      "App Server restart changed durable history without a new turn",
    );
    assert.ok(
      !rpc.notifications.slice(restartNotificationStart).some(
        (notification) =>
          notification.method === "turn/started" ||
          notification.method === "item/started",
      ),
      "App Server restart started provider work without a new turn",
    );
    assertRuntimeHealthy(rpc, [
      ["replacement environment B", execB],
    ]);
    assert.equal(
      existsSync(join(codexHome, "auth.json")),
      false,
      "App Server persisted the dedicated credential to auth.json",
    );
    assert.equal(existsSync(join(executorA.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(executorB.codexHome, "auth.json")), false);
    assert.equal(existsSync(join(replacementExecutorB.codexHome, "auth.json")), false);

    console.log(`PASS live initialize and model/list (${model.model})`);
    console.log("PASS dedicated API-key auth and credential-free executor processes");
    console.log("PASS empty MCP/hooks and disabled optional capabilities");
    console.log("PASS environment A and B command routing without ambient fallback");
    console.log("PASS command events, thread/read, resume, and two turns");
    console.log("PASS yielded background-terminal list, targeted termination, and cleanup");
    console.log("PASS App Server restart and disk-backed thread recovery without resend");
  } catch (error) {
    failure = error;
  } finally {
    const cleanupErrors = [];
    try {
      if (appServer?.stdin.writable) appServer.stdin.end();
    } catch (error) {
      cleanupErrors.push(error);
    }
    const processResults = await Promise.allSettled(
      [...children].map((child) => stopProcess(child)),
    );
    let everyProcessGroupStopped = true;
    for (const result of processResults) {
      if (result.status === "rejected") {
        everyProcessGroupStopped = false;
        cleanupErrors.push(result.reason);
      }
    }
    if (everyProcessGroupStopped) {
      try {
        rmSync(root, { recursive: true, force: true });
      } catch (error) {
        cleanupErrors.push(error);
      }
    } else {
      cleanupErrors.push(
        new Error(`retained ${root} because a process group did not stop`),
      );
    }
    if (interruptedSignal) {
      cleanupErrors.push(new Error(`received ${interruptedSignal}`), ...signalErrors);
    }
    if (cleanupErrors.length > 0) {
      failure = new AggregateError(
        failure ? [failure, ...cleanupErrors] : cleanupErrors,
        interruptedSignal
          ? "live compatibility check interrupted"
          : "live compatibility check cleanup failed",
      );
    }
    process.off("SIGINT", handleSigint);
    process.off("SIGTERM", handleSigterm);
  }
  if (failure) throw failure;
}

async function main() {
  const flags = new Set(process.argv.slice(2));
  for (const flag of flags) {
    assert.equal(flag, "--live", `unknown option ${flag}`);
  }
  staticCompatibilityCheck();
  if (flags.has("--live")) await liveCompatibilityCheck();
}

main().catch((error) => {
  console.error(`FAIL ${error.stack ?? error}`);
  process.exitCode ||= 1;
});
