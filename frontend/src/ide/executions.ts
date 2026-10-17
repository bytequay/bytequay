/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* eslint-disable import/no-unresolved -- This adapter targets the pinned VS Code services. */
import { getService, ITestService, ITestResultService, IDebugService, ITerminalService, ICommandService } from '@codingame/monaco-vscode-api';
import { ITaskService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/tasks/common/taskService.service';
import { ITerminalInstanceService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/terminal/browser/terminal.service';
import type { ITerminalInstance } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/terminal/browser/terminal';
import type { ITaskResolver, ITaskSummary, ITaskSystem, ITaskExecuteResult } from '@codingame/monaco-vscode-task-service-override/vscode/vs/workbench/contrib/tasks/common/taskSystem';
import type { Task, TaskRunSource } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/tasks/common/tasks';
import type { ITestResult, ITaskRawOutput } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/testing/common/testResult';
import type { IDebugAdapter, IAdapterManager } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/debug/common/debug';
import type * as vscode from 'vscode';
import type { LocalRunDto } from '../pulls/code-tests/contracts';
import type { ExecutionOutput, ExecutionResult, ExecutionStart } from './executionProtocol';
import { testEvidence } from './testEvidence';

// Assignment updates the service instance; defineProperty also replaces delayed proxies' cached bound methods.
function replace<T, K extends keyof T>(service: T, key: K, value: T[K]) {
  service[key] = value;
  Object.defineProperty(service, key, { value, configurable: true, writable: true });
}

type Live = {
  run: LocalRunDto; calls: number; sessions: Set<string>; tasks: Set<string>; results: Set<ITestResult>;
  result: ExecutionResult; output: string; truncated: boolean; sequence: number; pending?: ExecutionOutput;
  sending: boolean; finishing: boolean; terminals: Set<ITerminalInstance>; logs: Map<ITaskRawOutput, { offset: number; decoder: TextDecoder }>;
};

/** The persistent IDE owns discovery; each explicit execution owns one existing ByteQuay LOCAL_RUN claim. */
export async function coordinateExecutions(api: typeof vscode, root: string, authority: string) {
  const tests = await getService(ITestService);
  const results = await getService(ITestResultService);
  const debug = await getService(IDebugService);
  const terminals = await getService(ITerminalService);
  const terminalInstances = await getService(ITerminalInstanceService);
  const tasks = await getService(ITaskService);
  const commands = await getService(ICommandService);
  const changed = new api.EventEmitter<{ run: LocalRunDto | null; error: string | null }>();
  let current: Live | null = null;
  let starting = false;
  let error: string | null = null;
  const publish = () => changed.fire({ run: current?.run ?? null, error });
  const failed = (cause: unknown) => { error = String(cause); publish(); };
  const append = (live: Live, text: string) => {
    if (live.finishing) return;
    live.output += text;
    if (live.output.length > 2 * 1024 * 1024) { live.output = live.output.slice(-2 * 1024 * 1024); live.truncated = true; }
  };
  const begin = async (scope: ExecutionStart['scope'], label: string): Promise<Live> => {
    if (starting) throw new Error('An execution is already starting');
    if (current) {
      if (current.finishing || scope === 'TESTS') throw new Error('Stop the current execution before starting another');
      current.calls++;
      return current;
    }
    starting = true;
    try {
      // Saving happens before the claim and before extensions receive any launch request.
      if (!await api.workspace.saveAll(false)) throw new Error('Save or resolve the changed files before running');
      // Warm the shared PTY host before the process baseline; its shells still belong to individual executions.
      await (await terminalInstances.getBackend(authority))?.getEnvironment();
      const uri = api.window.activeTextEditor?.document.uri;
      const path = uri?.scheme === 'vscode-remote' && uri.path.startsWith(root + '/') ? uri.path.slice(root.length + 1) : null;
      const run = await window.ideWorkbench.startExecution({ scope, label: label.slice(0, 2000), path });
      current = { run, calls: 1, sessions: new Set(), tasks: new Set(), results: new Set(), logs: new Map(), terminals: new Set(),
        result: { cancelled: false, failed: false, incomplete: false, exitCode: null, tests: [] }, output: '', truncated: false, sequence: 0, sending: false, finishing: false };
      error = null; publish();
      return current;
    } finally { starting = false; }
  };
  const collect = (live: Live) => {
    for (const result of live.results) for (const task of result.tasks) {
      const state = live.logs.get(task.output) ?? { offset: 0, decoder: new TextDecoder() };
      while (state.offset < task.output.length) {
        const size = Math.min(65_536, task.output.length - state.offset);
        append(live, state.decoder.decode(task.output.getRange(state.offset, size).buffer, { stream: true }));
        state.offset += size;
      }
      if (result.completedAt !== undefined) append(live, state.decoder.decode());
      live.logs.set(task.output, state);
    }
  };
  const tick = async () => {
    const live = current;
    if (!live || live.sending) return;
    collect(live);
    live.sending = true;
    try {
      if (!live.calls && !live.sessions.size && !live.tasks.size) {
        for (const terminal of live.terminals) terminal.dispose();
        live.terminals.clear();
      }
      if (!live.pending && (live.output || live.truncated)) {
        live.pending = { sequence: ++live.sequence, text: live.output.slice(0, 65_536), truncated: live.truncated };
        live.output = live.output.slice(live.pending.text.length); live.truncated = false;
      }
      if (live.pending) {
        await window.ideWorkbench.outputExecution(live.run.id, live.pending);
        live.pending = undefined;
      }
      if (live.calls || live.sessions.size || live.tasks.size || live.output || live.pending) return;
      live.finishing = true;
      if (live.run.scope === 'TESTS') {
        const evidence = testEvidence(live.results);
        live.result.tests = evidence.tests;
        live.result.incomplete ||= evidence.incomplete;
      }
      live.run = await window.ideWorkbench.finishExecution(live.run.id, live.result);
      // A protocol completion is not process proof. Keep polling until the backend observes quiescence.
      if (!live.run.treeUnverified) { current = null; error = null; }
      publish();
    } catch (cause) { failed(cause); }
    finally { live.sending = false; }
  };
  const within = async <T,>(scope: ExecutionStart['scope'], label: string, operation: (live: Live) => Promise<T>): Promise<T> => {
    const live = await begin(scope, label);
    try { return await operation(live); }
    catch (cause) { live.result.failed = true; append(live, `${String(cause)}\n`); throw cause; }
    finally { live.calls--; void tick(); }
  };

  const runTests = tests.runResolvedTests.bind(tests);
  replace(tests, 'runResolvedTests', (request, token) => within('TESTS', request.group === 4 ? 'Debug tests' : 'Run tests', async live => {
    const known = new Set(results.results.map(result => result.id));
    const track = () => { for (const result of results.results) if (!known.has(result.id)) live.results.add(result); };
    const listener = results.onResultsChanged(track);
    const controllers = [...new Set(request.targets.map(target => target.controllerId))].flatMap(id => {
      const controller = tests.getTestController(id);
      if (!controller) return [];
      const original = controller.runTests;
      // VS Code shows controller launch errors as notifications, without putting them in test results.
      replace(controller, 'runTests', async (...args) => {
        const replies = await original.apply(controller, args);
        for (const reply of replies) if (reply.error) { live.result.failed = true; append(live, `${reply.error}\n`); }
        return replies;
      });
      return [{ controller, original }];
    });
    const cancellation = token?.onCancellationRequested(() => { live.result.cancelled = true; });
    try { const result = await runTests(request, token); live.results.add(result); return result; }
    finally { track(); listener.dispose(); cancellation?.dispose(); for (const { controller, original } of controllers) replace(controller, 'runTests', original); }
  }));
  // Continuous profiles bypass runResolvedTests and can outlive the user action. Explicit runs remain available.
  replace(tests, 'startContinuousRun', async () => { throw new Error('Use Run tests or Debug tests; continuous execution is not enabled'); });
  tests.onDidCancelTestRun(() => { if (current?.run.scope === 'TESTS') current.result.cancelled = true; });

  const startDebug = debug.startDebugging.bind(debug);
  replace(debug, 'startDebugging', (launch, configuration, options) => within(options?.noDebug || (typeof configuration === 'object' && configuration.noDebug) ? 'RUN' : 'DEBUG',
    typeof configuration === 'string' ? configuration : configuration?.name ?? 'Debug configuration', async live => {
      const launched = await startDebug(launch, configuration, options, false);
      if (!launched) live.result.failed = true;
      return launched;
    }));
  debug.onWillNewSession(session => { if (current) current.sessions.add(session.getId()); });
  debug.onDidEndSession(({ session, restart }) => { if (current && !restart) { current.sessions.delete(session.getId()); void tick(); } });
  const stopSession = debug.stopSession.bind(debug);
  replace(debug, 'stopSession', async (...args) => {
    if (current && current.run.scope !== 'TESTS') current.result.cancelled = true;
    return stopSession(...args);
  });
  api.debug.registerDebugConfigurationProvider('*', { resolveDebugConfigurationWithSubstitutedVariables: (_folder, configuration) => {
    if (configuration.request === 'attach') throw new Error('Choose a launch configuration; attaching to external processes is not supported');
    if (!current) throw new Error('Start debugging from this task’s Tears of time editor');
    return configuration;
  } });
  // Observe the workbench adapter boundary: local-host API trackers do not see remote Java/Go/Rust adapters.
  const adapters: IAdapterManager = debug.getAdapterManager();
  const createAdapter = adapters.createDebugAdapter.bind(adapters);
  replace(adapters, 'createDebugAdapter', session => {
    const live = current;
    if (!live || session.configuration.request === 'attach') throw new Error('Start a launch configuration in Tears of time');
    const adapter: IDebugAdapter | undefined = createAdapter(session);
    if (!adapter) return adapter;
    const onEvent = adapter.onEvent.bind(adapter);
    replace(adapter, 'onEvent', (callback: Parameters<IDebugAdapter['onEvent']>[0]) => onEvent((event: Parameters<Parameters<IDebugAdapter['onEvent']>[0]>[0]) => {
      if (current === live) {
        const body = event.body as { output?: unknown; exitCode?: unknown } | undefined;
        if (event.event === 'output' && typeof body?.output === 'string') append(live, body.output);
        if (event.event === 'exited' && typeof body?.exitCode === 'number') {
          live.result.exitCode = body.exitCode; live.result.failed ||= body.exitCode !== 0;
        }
      }
      callback(event);
    }));
    adapter.onError((cause: Error) => { if (current === live) { live.result.failed = true; append(live, `${cause.message}\n`); } });
    return adapter;
  });

  // The public task.run omits menu/shortcut launches and reruns. These are the two dispatch points in the pinned task service.
  const taskDispatch = tasks as typeof tasks & {
    _executeTask(task: Task, resolver: ITaskResolver, source: TaskRunSource): Promise<ITaskSummary>;
    _reRunTaskCommand(onlyRerun: boolean): unknown;
    _getTaskSystem(): ITaskSystem;
    _handleExecuteResult(result: ITaskExecuteResult): Promise<ITaskSummary>;
  };
  if (typeof taskDispatch._executeTask !== 'function' || typeof taskDispatch._reRunTaskCommand !== 'function') throw new Error('Unsupported IDE task service');
  const executeTask = taskDispatch._executeTask.bind(taskDispatch);
  replace(taskDispatch, '_executeTask', (task, resolver, source) => within('RUN', task._label || 'Run task', async live => {
    const result = await executeTask(task, resolver, source);
    if (result.exitCode !== undefined) { live.result.exitCode = result.exitCode; live.result.failed ||= result.exitCode !== 0; }
    return result;
  }));
  replace(taskDispatch, '_reRunTaskCommand', (onlyRerun) => within('RUN', 'Rerun task', async () => {
    const result = taskDispatch._getTaskSystem().rerun();
    if (!result) {
      if (!onlyRerun) await commands.executeCommand('workbench.action.tasks.runTask');
      return;
    }
    await taskDispatch._handleExecuteResult(result);
  }).catch(failed));
  tasks.onDidStateChange(event => {
    if (!current || event.kind === 'changed') return;
    if (event.kind === 'start') current.tasks.add(event.taskId);
    if (event.kind === 'processEnded' && event.exitCode !== undefined) {
      current.result.exitCode = event.exitCode; current.result.failed ||= event.exitCode !== 0;
    }
    if (event.kind === 'terminated') current.result.cancelled = true;
    if (event.kind === 'end') { current.tasks.delete(event.taskId); void tick(); }
  });
  const captureTerminal = (terminal: (typeof terminals.instances)[number]) => {
    const output = terminal.onData(text => { if (current) append(current, text); });
    terminal.onExit(() => output.dispose());
  };
  terminals.instances.forEach(captureTerminal);
  terminals.onDidCreateInstance(terminal => { current?.terminals.add(terminal); captureTerminal(terminal); });
  const timer = setInterval(() => { void tick(); }, 250);
  return {
    onChange: changed.event,
    get run() { return current?.run ?? null; },
    get error() { return error; },
    stop: async (force = false) => {
      const live = current;
      if (!live) return;
      if (force) {
        live.run = await window.ideWorkbench.stopExecution(live.run.id);
        if (!live.run.treeUnverified) current = null;
        publish(); return;
      }
      live.result.cancelled = true;
      tests.cancelTestRun();
      await debug.stopSession(undefined);
      for (const task of await tasks.getActiveTasks()) await tasks.terminate(task);
      void tick();
    },
    dispose: () => { clearInterval(timer); changed.dispose(); },
  };
}
