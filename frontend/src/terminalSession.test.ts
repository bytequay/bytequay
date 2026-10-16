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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { attachTerminal } from './terminalSession';
import type { Bridge } from './types';

const mocks = vi.hoisted(() => ({
  terminal: {
    cols: 80, rows: 24, options: {}, loadAddon: vi.fn(), open: vi.fn(), write: vi.fn(), focus: vi.fn(), dispose: vi.fn(),
    onData: vi.fn((_callback: (data: string) => void) => ({ dispose: vi.fn() })),
    onResize: vi.fn((_callback: (size: { cols: number; rows: number }) => void) => ({ dispose: vi.fn() })),
  },
  fit: vi.fn(),
}));
vi.mock('@xterm/xterm', () => ({ Terminal: vi.fn(() => mocks.terminal) }));
vi.mock('@xterm/addon-fit', () => ({ FitAddon: vi.fn(() => ({ fit: mocks.fit })) }));

let refit: () => void;
const disconnectResize = vi.fn();
const disconnectTheme = vi.fn();
const removeThemeListener = vi.fn();
const stopEvents = vi.fn();
const bridge = {
  writeReviewTerminal: vi.fn(), resizeReviewTerminal: vi.fn(),
  onReviewTerminalEvent: vi.fn((_listener: Parameters<Bridge['onReviewTerminalEvent']>[0]) => stopEvents),
};
const screen = { clientWidth: 600, clientHeight: 300 } as HTMLDivElement;

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('document', { documentElement: {} });
  vi.stubGlobal('getComputedStyle', () => ({ colorScheme: 'light' }));
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: removeThemeListener }));
  vi.stubGlobal('MutationObserver', class { observe() { return; } disconnect = disconnectTheme; });
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { refit = callback; }
    observe() { return; }
    disconnect = disconnectResize;
  });
});
afterEach(() => { vi.unstubAllGlobals(); });

it('replays scrollback once, routes only its session, and stops forwarding input after exit', async () => {
  const onStatus = vi.fn();
  let opened!: (value: { terminalId: string; scrollback: string }) => void;
  const open = vi.fn(() => new Promise<{ terminalId: string; scrollback: string }>(resolve => { opened = resolve; }));
  const dispose = attachTerminal(screen, bridge, open, { scrollback: 5000, onStatus, exitMessage: code => `shell exited with status ${code}` });
  const event = bridge.onReviewTerminalEvent.mock.calls[0][0];
  const input = mocks.terminal.onData.mock.calls[0][0];
  const resize = mocks.terminal.onResize.mock.calls[0][0];
  event({ terminalId: 'shell', data: 'early' });
  input('ignored');
  expect(mocks.terminal.write).not.toHaveBeenCalled();
  expect(bridge.writeReviewTerminal).not.toHaveBeenCalled();
  expect(open).toHaveBeenCalledWith(80, 24);
  opened({ terminalId: 'shell', scrollback: 'early' });
  await vi.waitFor(() => expect(mocks.terminal.write).toHaveBeenCalledWith('early'));
  expect(onStatus.mock.calls.map(([status]) => status.kind)).toEqual(['opening', 'open']);
  event({ terminalId: 'other', data: 'private' });
  event({ terminalId: 'other', exitCode: 1 });
  expect(mocks.terminal.write).toHaveBeenCalledTimes(1);
  input('help\r');
  resize({ cols: 100, rows: 30 });
  expect(bridge.writeReviewTerminal).toHaveBeenLastCalledWith('shell', 'help\r');
  expect(bridge.resizeReviewTerminal).toHaveBeenLastCalledWith('shell', 100, 30);
  event({ terminalId: 'shell', data: 'output' });
  expect(mocks.terminal.write).toHaveBeenLastCalledWith('output');
  event({ terminalId: 'shell', exitCode: 2 });
  expect(onStatus).toHaveBeenLastCalledWith({ kind: 'exited', exitCode: 2 });
  expect(mocks.terminal.write).toHaveBeenLastCalledWith('\r\n\x1b[90m[shell exited with status 2]\x1b[0m\r\n');
  input('ignored');
  resize({ cols: 120, rows: 40 });
  expect(bridge.writeReviewTerminal).toHaveBeenCalledTimes(1);
  expect(bridge.resizeReviewTerminal).toHaveBeenCalledTimes(2);
  dispose();
  for (const cleanup of [stopEvents, disconnectResize, disconnectTheme, removeThemeListener, mocks.terminal.dispose,
    mocks.terminal.onData.mock.results[0].value.dispose, mocks.terminal.onResize.mock.results[0].value.dispose]) {
    expect(cleanup).toHaveBeenCalledTimes(1);
  }
});

it('keeps hidden terminals at a usable size and leaves sign-in output free of shell messages', async () => {
  const hidden = { clientWidth: 0, clientHeight: 0 } as HTMLDivElement;
  const onStatus = vi.fn();
  const dispose = attachTerminal(hidden, bridge, async () => ({ terminalId: 'login', scrollback: 'sign in' }), { scrollback: 2000, onStatus });
  expect(mocks.fit).not.toHaveBeenCalled();
  Object.assign(hidden, { clientWidth: 600, clientHeight: 300 });
  refit();
  expect(mocks.fit).toHaveBeenCalledTimes(1);
  await Promise.resolve();
  bridge.onReviewTerminalEvent.mock.calls[0][0]({ terminalId: 'login', exitCode: 0 });
  expect(onStatus).toHaveBeenLastCalledWith({ kind: 'exited', exitCode: 0 });
  expect(mocks.terminal.write).toHaveBeenCalledWith('sign in');
  dispose();
});

it.each([false, true])('ignores an attach that settles after unmount (rejected: %s)', async reject => {
  let settle!: () => void;
  const onStatus = vi.fn();
  const open = () => new Promise<{ terminalId: string; scrollback: string }>((resolve, fail) => {
    settle = () => reject ? fail(new Error('closed')) : resolve({ terminalId: 'late', scrollback: 'late output' });
  });
  const dispose = attachTerminal(screen, bridge, open, { scrollback: 2000, onStatus });
  dispose();
  settle();
  await Promise.resolve();
  expect(onStatus).toHaveBeenCalledWith({ kind: 'opening' });
  expect(onStatus).toHaveBeenCalledTimes(1);
  expect(mocks.terminal.write).not.toHaveBeenCalled();
  expect(mocks.terminal.focus).not.toHaveBeenCalled();
  expect(bridge.resizeReviewTerminal).not.toHaveBeenCalled();
});

it('preserves opening failures for the caller to display', async () => {
  const onStatus = vi.fn();
  const error = new Error('provider unavailable');
  const dispose = attachTerminal(screen, bridge, () => Promise.reject(error), { scrollback: 2000, onStatus });
  await Promise.resolve();
  expect(onStatus).toHaveBeenLastCalledWith({ kind: 'failed', error });
  dispose();
});
