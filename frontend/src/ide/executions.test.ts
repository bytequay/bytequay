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
/* eslint-disable import/no-unresolved -- Pinned VS Code service types. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getService, ITestService, IDebugService } from '@codingame/monaco-vscode-api';
import type { IDebugSession } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/debug/common/debug';
import type * as vscode from 'vscode';
import type { ITestResult } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/testing/common/testResult';
import type { ResolvedTestRunRequest } from '@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/testing/common/testTypes';
import type { LocalRunDto } from '../pulls/code-tests/contracts';
import { coordinateExecutions } from './executions';
import { parseExecutionRequest } from './executionProtocol';
import { testEvidence } from './testEvidence';

vi.mock('@codingame/monaco-vscode-api/vscode/vs/base/browser/markdownRenderer', () => ({
  renderAsPlaintext: (message: string | { value: string }) => typeof message === 'string' ? message : message.value,
}));

const services = vi.hoisted(() => new Map<unknown, unknown>());
vi.mock('@codingame/monaco-vscode-api', () => ({ getService: async (id: unknown) => services.get(id),
  ITestService: 'tests', ITestResultService: 'results', IDebugService: 'debug', ITerminalService: 'terminals', ICommandService: 'commands' }));
vi.mock('@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/terminal/browser/terminal.service', () => ({ ITerminalInstanceService: 'terminalInstances' }));
vi.mock('@codingame/monaco-vscode-api/vscode/vs/workbench/contrib/tasks/common/taskService.service', () => ({ ITaskService: 'tasks' }));

class Event<T> {
  listeners = new Set<(value: T) => void>();
  event = (listener: (value: T) => void) => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; };
  fire = (value: T) => this.listeners.forEach(listener => listener(value));
  dispose = () => this.listeners.clear();
}
const noEvent = () => ({ dispose: () => {} });
const session = { getId: () => 'debug-session' };
let onSession: Event<typeof session>;
let onEndSession: Event<{ session: typeof session; restart: boolean }>;
let terminalCreated: Event<{ onData: typeof noEvent; onExit: typeof noEvent; dispose: ReturnType<typeof vi.fn> }>;
let adapterEvents: Event<{ type: string; event: string; body: { output?: string; exitCode?: number } }>;
let originalDebug: ReturnType<typeof vi.fn>;
let originalTests: ReturnType<typeof vi.fn>;
let originalTask: ReturnType<typeof vi.fn>;
let rawTask: { _executeTask: (...args: unknown[]) => unknown; _reRunTaskCommand: (only: boolean) => unknown };
let save: ReturnType<typeof vi.fn>;
let start: ReturnType<typeof vi.fn>;
let output: ReturnType<typeof vi.fn>;
let finish: ReturnType<typeof vi.fn>;
let gate: Awaited<ReturnType<typeof coordinateExecutions>>;
let liveRun: LocalRunDto;
let testResults: ITestResult[];
const request = { group: 2, targets: [], exclude: [] } as ResolvedTestRunRequest;

beforeEach(async () => {
  vi.useFakeTimers(); services.clear(); testResults = [];
  onSession = new Event(); onEndSession = new Event(); terminalCreated = new Event(); adapterEvents = new Event();
  liveRun = { id: 'run-1', ordinal: 1, initiator: 'USER', scope: 'TESTS', path: null, testClass: null, testMethod: null, module: null,
    command: 'Run tests', sourceHeadSha: 'a'.repeat(40), localPaths: [], sourceFingerprint: 'source', status: 'RUNNING',
    startedAt: '2026-01-01T00:00:00Z', endedAt: null, exitCode: null, tests: null, failures: null, errors: null, skipped: null,
    outputTruncated: false, omittedBytes: null, treeUnverified: false, results: [] };
  save = vi.fn().mockImplementation(async () => true);
  start = vi.fn().mockImplementation(async (request) => ({ ...liveRun, scope: request.scope }));
  output = vi.fn().mockImplementation(async () => {});
  finish = vi.fn().mockImplementation(async () => ({ ...liveRun, status: 'COMPLETED' }));
  vi.stubGlobal('window', { ideWorkbench: { startExecution: start, outputExecution: output, finishExecution: finish, stopExecution: vi.fn() } });
  originalTests = vi.fn().mockImplementation(async () => ({ id: 'test-result', name: 'tests', completedAt: 1, tests: [][Symbol.iterator](), tasks: [] as ITestResult['tasks'] }));
  originalDebug = vi.fn().mockImplementation(async () => { onSession.fire(session); return true; });
  originalTask = vi.fn().mockImplementation(async () => ({ exitCode: 0 }));
  services.set('tests', { runResolvedTests: originalTests, startContinuousRun: vi.fn(), onDidCancelTestRun: noEvent, cancelTestRun: vi.fn() });
  services.set('results', { results: testResults, onResultsChanged: noEvent });
  const adapters = { createDebugAdapter: () => ({ onEvent: adapterEvents.event, onError: noEvent }) };
  services.set('debug', { startDebugging: originalDebug, stopSession: vi.fn(), onWillNewSession: onSession.event, onDidEndSession: onEndSession.event, getAdapterManager: () => adapters });
  services.set('terminalInstances', { getBackend: async () => ({ getEnvironment: async () => ({}) }) });
  services.set('terminals', { instances: [], onDidCreateInstance: terminalCreated.event });
  services.set('commands', { executeCommand: vi.fn() });
  rawTask = { _executeTask: originalTask, _reRunTaskCommand: vi.fn() };
  services.set('tasks', { ...rawTask, onDidStateChange: noEvent, _getTaskSystem: () => ({ rerun: () => ({ promise: Promise.resolve({ exitCode: 0 }) }) }),
    _handleExecuteResult: (result: { promise: Promise<unknown> }) => result.promise });
  rawTask = services.get('tasks') as typeof rawTask;
  const api = { EventEmitter: Event, workspace: { saveAll: save }, window: {}, debug: {
    registerDebugConfigurationProvider: vi.fn(), registerDebugAdapterTrackerFactory: vi.fn(),
  } } as unknown as typeof vscode;
  gate = await coordinateExecutions(api, '/worktree', '127.0.0.1:5000');
});
afterEach(() => { gate?.dispose(); vi.useRealTimers(); vi.unstubAllGlobals(); });

it('saves before requesting ownership and never dispatches a test whose claim is denied', async () => {
  start.mockRejectedValueOnce(new Error('The agent is working'));
  const tests = await getService(ITestService);
  await expect(tests.runResolvedTests(request)).rejects.toThrow('The agent is working');
  expect(save.mock.invocationCallOrder[0]).toBeLessThan(start.mock.invocationCallOrder[0]);
  expect(originalTests).not.toHaveBeenCalled();
  expect(finish).not.toHaveBeenCalled();
  save.mockResolvedValue(false);
  await expect(tests.runResolvedTests(request)).rejects.toThrow('Save or resolve');
  expect(start).toHaveBeenCalledTimes(1);
});

it('holds a paused or restarting debug session until backend process verification succeeds', async () => {
  const debug = await getService(IDebugService);
  await debug.startDebugging(undefined, 'Example');
  expect(originalDebug).toHaveBeenCalledWith(undefined, 'Example', undefined, false);
  await vi.advanceTimersByTimeAsync(2000);
  expect(finish).not.toHaveBeenCalled();
  onEndSession.fire({ session, restart: true });
  await vi.advanceTimersByTimeAsync(500);
  expect(finish).not.toHaveBeenCalled();
  finish.mockResolvedValue({ ...liveRun, scope: 'DEBUG', status: 'COMPLETED', treeUnverified: true });
  onEndSession.fire({ session, restart: false });
  await vi.advanceTimersByTimeAsync(500);
  expect(gate.run?.treeUnverified).toBe(true);
  await expect(debug.startDebugging(undefined, 'Another')).rejects.toThrow('Stop the current execution');
  finish.mockResolvedValue({ ...liveRun, scope: 'DEBUG', status: 'COMPLETED', treeUnverified: false });
  await vi.advanceTimersByTimeAsync(250);
  expect(gate.run).toBeNull();
  expect(finish.mock.calls[0][1]).toMatchObject({ tests: [], cancelled: false, failed: false });
});

it('retries the same output sequence before finishing and preserves UTF-8 split across chunks', async () => {
  const text = 'x'.repeat(65_534) + '測試\n';
  const bytes = new TextEncoder().encode(text);
  const testOutput = { length: bytes.length, getRange: (offset: number, size: number) => ({ buffer: bytes.slice(offset, offset + size) }) };
  originalTests.mockResolvedValue({ id: 'test-result', name: 'tests', completedAt: 1, tests: [][Symbol.iterator](), tasks: [{ output: testOutput }] });
  output.mockRejectedValueOnce(new Error('Connection reset'));
  await (await getService(ITestService)).runResolvedTests(request);
  await vi.advanceTimersByTimeAsync(0);
  expect(finish).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(500);
  expect(output.mock.calls[0]).toEqual(output.mock.calls[1]);
  expect(output.mock.calls.slice(1).map(call => call[1].text).join('')).toBe(text);
  expect(finish).toHaveBeenCalledOnce();
});

it('coordinates task menu dispatch and the separate rerun path', async () => {
  await rawTask._executeTask({ _label: 'Cargo test' }, {}, 1);
  await vi.advanceTimersByTimeAsync(500);
  expect(start).toHaveBeenCalledWith({ scope: 'RUN', label: 'Cargo test', path: null });
  expect(originalTask).toHaveBeenCalledOnce();
  expect(finish).toHaveBeenCalledOnce();
  await rawTask._reRunTaskCommand(true);
  await vi.advanceTimersByTimeAsync(500);
  expect(start).toHaveBeenLastCalledWith({ scope: 'RUN', label: 'Rerun task', path: null });
  expect(finish).toHaveBeenCalledTimes(2);
});

it('keeps native test failures without duplicating computed container outcomes', () => {
  const item = (id: string, state: number, computed: number) => ({ item: { extId: id, label: id.split('\0').at(-1), uri: undefined as undefined },
    ownComputedState: state, computedState: computed, tasks: [{ messages: state === 4 ? [{ type: 0, message: { value: 'expected 2' }, expected: '2', actual: '1' }] : [] }] });
  const result = { name: 'Suite', tests: [item('ctrl\0Suite', 0, 4), item('ctrl\0Suite\0passes', 3, 3), item('ctrl\0Suite\0fails', 4, 4), item('ctrl\0Suite\0pending', 2, 2)][Symbol.iterator]() } as unknown as ITestResult;
  const evidence = testEvidence([result]);
  expect(evidence.incomplete).toBe(true);
  expect(evidence.tests.flatMap(group => group.methods).map(method => method.status)).toEqual(['PASSED', 'FAILED']);
  expect(evidence.tests[0].methods[1]).toMatchObject({ message: 'expected 2', trace: expect.stringContaining('Expected: 2\nActual: 1') });
});

it('rejects malformed execution frames and strips renderer-supplied owner fields', () => {
  expect(parseExecutionRequest({ operation: 'start', request: { scope: 'DEBUG', label: 'Example', path: null, taskId: 'other', command: 'rm' } }))
    .toEqual({ operation: 'start', request: { scope: 'DEBUG', label: 'Example', path: null } });
  expect(() => parseExecutionRequest({ operation: 'start', request: { scope: 'RUN', label: 'Run', path: '../outside' } })).toThrow();
  expect(() => parseExecutionRequest({ operation: 'finish', id: 'run-1', request: { tests: [], cancelled: false, failed: false, incomplete: false, exitCode: 2 ** 32 } })).toThrow('exit code');
  expect(() => parseExecutionRequest({ operation: 'output', id: 'run-1', request: { sequence: 0, text: '', truncated: false } })).toThrow('sequence');
});

it('records remote adapter events and closes only execution-owned shells before completion', async () => {
  const debug = await getService(IDebugService);
  await debug.startDebugging(undefined, 'Example');
  const adapter = debug.getAdapterManager().createDebugAdapter({ configuration: { request: 'launch' } } as IDebugSession);
  const nativeListener = vi.fn();
  adapter?.onEvent(nativeListener);
  const terminal = { onData: noEvent, onExit: noEvent, dispose: vi.fn() };
  terminalCreated.fire(terminal);
  adapterEvents.fire({ type: 'event', event: 'output', body: { output: 'program log\n' } });
  adapterEvents.fire({ type: 'event', event: 'exited', body: { exitCode: 7 } });
  expect(nativeListener).toHaveBeenCalledTimes(2);
  onEndSession.fire({ session, restart: false });
  await vi.advanceTimersByTimeAsync(500);
  expect(terminal.dispose).toHaveBeenCalledOnce();
  expect(terminal.dispose.mock.invocationCallOrder[0]).toBeLessThan(finish.mock.invocationCallOrder[0]);
  expect(output.mock.calls[0][1].text).toBe('program log\n');
  expect(finish.mock.calls[0][1]).toMatchObject({ failed: true, exitCode: 7, tests: [] });
});


it('retains controller launch failures that native notifications omit from test results', async () => {
  const controller = { runTests: vi.fn(async () => [{ error: 'JDK is missing' }]) };
  const original = controller.runTests;
  const tests = await getService(ITestService);
  tests.getTestController = () => controller as unknown as ReturnType<typeof tests.getTestController>;
  originalTests.mockImplementation(async () => {
    await controller.runTests();
    return { id: 'failed-launch', name: 'tests', completedAt: 1, tests: [][Symbol.iterator](), tasks: [] };
  });
  await tests.runResolvedTests({ ...request, targets: [{ controllerId: 'java', testIds: [], profileId: 1 }] });
  await vi.advanceTimersByTimeAsync(500);
  expect(controller.runTests).toBe(original);
  expect(output.mock.calls[0][1].text).toBe('JDK is missing\n');
  expect(finish.mock.calls[0][1]).toMatchObject({ failed: true, tests: [] });
});

it('preserves a suite setup error when its children never ran', () => {
  const result = { name: 'Suite', tests: [
    { item: { extId: 'ctrl\0Suite', label: 'Suite' }, ownComputedState: 6, tasks: [{ messages: [{ type: 0, message: 'setup failed' }] }] },
    { item: { extId: 'ctrl\0Suite\0case', label: 'case' }, ownComputedState: 0, tasks: [] },
  ][Symbol.iterator]() } as unknown as ITestResult;
  expect(testEvidence([result]).tests.flatMap(group => group.methods)).toMatchObject([{ status: 'ERROR', message: 'setup failed' }]);
});
