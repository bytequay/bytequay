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
import { describe, expect, it } from 'vitest';
import { CodeTerminals, terminalEnvironment, type TerminalProcess } from './terminals';

class FakeProcess implements TerminalProcess {
  data: (value: string) => void = () => {};
  exit: (event: { exitCode: number }) => void = () => {};
  written = '';
  sizes: Array<[number, number]> = [];
  killed = false;
  onData(listener: (value: string) => void) { this.data = listener; }
  onExit(listener: (event: { exitCode: number }) => void) { this.exit = listener; }
  write(value: string) { this.written += value; }
  resize(cols: number, rows: number) { this.sizes.push([cols, rows]); }
  kill() { this.killed = true; }
}

class FakeSender {
  sent: Array<[string, unknown]> = [];
  navigate: () => void = () => {};
  destroyed: () => void = () => {};
  isDestroyed() { return false; }
  send(channel: string, message: unknown) { this.sent.push([channel, message]); }
  on(_event: 'did-navigate', listener: () => void) { this.navigate = listener; }
  once(_event: 'destroyed', listener: () => void) { this.destroyed = listener; }
}

function setup() {
  const processes: FakeProcess[] = [];
  const terminals = new CodeTerminals(() => {
    const process = new FakeProcess();
    processes.push(process);
    return process;
  });
  return { terminals, processes, cwd: () => Promise.resolve('/tmp/review') };
}

describe('code terminals', () => {
  it('reattaches to the same shell with its scrollback and ignores other windows', async () => {
    const { terminals, processes, cwd } = setup();
    const sender = new FakeSender();
    const [first, racing] = await Promise.all([terminals.open(sender, 'review:r1', 80, 24, cwd), terminals.open(sender, 'review:r1', 80, 24, cwd)]);
    expect(racing.terminalId).toBe(first.terminalId);
    processes[0].data('ok\r\n');
    expect(await terminals.open(sender, 'review:r1', 80, 24, cwd)).toEqual({ terminalId: first.terminalId, scrollback: 'ok\r\n' });
    expect(sender.sent).toEqual([['terminal:data', { terminalId: first.terminalId, data: 'ok\r\n' }]]);

    const other = new FakeSender();
    terminals.write(other, first.terminalId, 'rm -rf .\r');
    terminals.resize(other, first.terminalId, 10, 10);
    expect(processes[0].written).toBe('');
    expect(processes[0].sizes).toEqual([]);
    expect(processes[0].killed).toBe(false);
    await terminals.open(other, 'review:r1', 80, 24, cwd);
    expect(processes).toHaveLength(2);
  });

  it('kills shells when their host worktree goes, their window reloads, or they exit', async () => {
    const { terminals, processes, cwd } = setup();
    const sender = new FakeSender();
    await terminals.open(sender, 'review:r1', 80, 24, cwd);
    await terminals.open(sender, 'review:r2', 80, 24, cwd);
    terminals.close('review:r1');
    expect(processes.map(process => process.killed)).toEqual([true, false]);
    sender.navigate();
    expect(processes[1].killed).toBe(true);

    const { terminalId } = await terminals.open(sender, 'review:r2', 80, 24, cwd);
    processes[2].exit({ exitCode: 3 });
    expect(sender.sent.at(-1)).toEqual(['terminal:exit', { terminalId, exitCode: 3 }]);
    expect((await terminals.open(sender, 'review:r2', 80, 24, cwd)).terminalId).not.toBe(terminalId);
  });

  it('keeps a review and a task with the same id in separate shells', async () => {
    const { terminals, processes, cwd } = setup();
    const sender = new FakeSender();
    const review = await terminals.open(sender, 'review:same', 80, 24, cwd);
    const task = await terminals.open(sender, 'task:same', 80, 24, cwd);
    expect(task.terminalId).not.toBe(review.terminalId);
    terminals.close('task:same');
    expect(processes.map(process => process.killed)).toEqual([false, true]);
  });

  it('drops the app variables and defaults the locale', () => {
    expect(terminalEnvironment({ PATH: '/bin', BYTEQUAY_SESSION: 'secret', TERM: 'dumb' })).toEqual({ PATH: '/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' });
    expect(terminalEnvironment({ LANG: 'de_DE.UTF-8' }).LANG).toBe('de_DE.UTF-8');
  });
});
