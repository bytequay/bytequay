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
import { randomUUID } from 'node:crypto';

/** The part of a node-pty process a terminal uses, so a test can stand in for one. */
export interface TerminalProcess {
  onData(listener: (data: string) => void): unknown;
  onExit(listener: (event: { exitCode: number }) => void): unknown;
  write(data: string): void;
  resize(cols: number, rows: number): void;
  kill(): void;
}

interface TerminalSender {
  isDestroyed(): boolean;
  send(channel: string, message: unknown): void;
  on(event: 'did-navigate', listener: () => void): unknown;
  once(event: 'destroyed', listener: () => void): unknown;
}

interface Terminal {
  id: string;
  key: string;
  hostKey: string;
  sender: TerminalSender;
  process: TerminalProcess;
  scrollback: string;
}

// ponytail: trims by UTF-16 units, not bytes; close enough for a replay buffer.
const SCROLLBACK_LIMIT = 256 * 1024;

/** Shell environment: the app's own variables removed, and a locale a Finder-launched app does not inherit. */
export function terminalEnvironment(base: NodeJS.ProcessEnv): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(base)) {
    if (value !== undefined && !key.startsWith('BYTEQUAY_')) env[key] = value;
  }
  env.TERM = 'xterm-256color';
  env.LANG ??= 'en_US.UTF-8';
  return env;
}

/**
 * One login shell per Code & Tests host (`review:<id>` or `task:<id>`) per window. Renderers only attach and detach; the
 * shell dies when it exits, when its host's worktree is about to go, when its window reloads or closes, and when the app quits.
 */
export class CodeTerminals {
  private readonly terminals = new Map<string, Terminal>();
  private readonly opening = new Map<string, Promise<Terminal>>();
  private readonly senderKeys = new WeakMap<TerminalSender, string>();
  private readonly watched = new WeakSet<TerminalSender>();

  constructor(private readonly spawn: (cwd: string, cols: number, rows: number) => TerminalProcess) {}

  async open(sender: TerminalSender, hostKey: string, cols: number, rows: number, workingDirectory: () => Promise<string>) {
    const key = `${this.senderKey(sender)}:${hostKey}`;
    let terminal = this.terminals.get(key);
    if (terminal === undefined) {
      // Two attaches racing through the backend check must share one shell.
      let pending = this.opening.get(key);
      if (pending === undefined) {
        pending = workingDirectory().then(cwd => this.start(key, sender, hostKey, cwd, cols, rows));
        this.opening.set(key, pending);
        void pending.catch(() => {}).finally(() => this.opening.delete(key));
      }
      terminal = await pending;
    }
    return { terminalId: terminal.id, scrollback: terminal.scrollback };
  }

  write(sender: TerminalSender, terminalId: string, data: string): void {
    this.owned(sender, terminalId)?.process.write(data);
  }

  resize(sender: TerminalSender, terminalId: string, cols: number, rows: number): void {
    if (cols > 0 && rows > 0) this.owned(sender, terminalId)?.process.resize(cols, rows);
  }

  /** Before End review, or a task's delete or cleanup, which removes every shell's working directory. */
  close(hostKey: string): void {
    for (const terminal of [...this.terminals.values()]) {
      if (terminal.hostKey === hostKey) this.kill(terminal);
    }
  }

  stopAll(): void {
    for (const terminal of [...this.terminals.values()]) this.kill(terminal);
  }

  private start(key: string, sender: TerminalSender, hostKey: string, cwd: string, cols: number, rows: number): Terminal {
    if (sender.isDestroyed()) throw new Error('The window closed before the terminal opened');
    const terminal: Terminal = { id: randomUUID(), key, hostKey, sender, process: this.spawn(cwd, cols, rows), scrollback: '' };
    this.terminals.set(key, terminal);
    this.watch(sender);
    terminal.process.onData(data => {
      const scrollback = terminal.scrollback + data;
      terminal.scrollback = scrollback.length > SCROLLBACK_LIMIT ? scrollback.slice(scrollback.length - SCROLLBACK_LIMIT) : scrollback;
      if (!sender.isDestroyed()) sender.send('terminal:data', { terminalId: terminal.id, data });
    });
    terminal.process.onExit(({ exitCode }) => {
      if (this.terminals.get(key) === terminal) this.terminals.delete(key);
      if (!sender.isDestroyed()) sender.send('terminal:exit', { terminalId: terminal.id, exitCode });
    });
    return terminal;
  }

  private kill(terminal: Terminal): void {
    if (this.terminals.get(terminal.key) === terminal) this.terminals.delete(terminal.key);
    try {
      terminal.process.kill();
    }
    catch {
      // Already gone.
    }
  }

  private owned(sender: TerminalSender, terminalId: string): Terminal | undefined {
    for (const terminal of this.terminals.values()) {
      if (terminal.id === terminalId && terminal.sender === sender) return terminal;
    }
    return undefined;
  }

  private senderKey(sender: TerminalSender): string {
    let key = this.senderKeys.get(sender);
    if (key === undefined) {
      key = randomUUID();
      this.senderKeys.set(sender, key);
    }
    return key;
  }

  private watch(sender: TerminalSender): void {
    if (this.watched.has(sender)) return;
    this.watched.add(sender);
    const closeSender = () => {
      for (const terminal of [...this.terminals.values()]) {
        if (terminal.sender === sender) this.kill(terminal);
      }
    };
    sender.on('did-navigate', closeSender);
    sender.once('destroyed', closeSender);
  }
}
