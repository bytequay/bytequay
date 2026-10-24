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
import { isAbsolute } from 'node:path';

/** Only Electron main receives these paths; the renderer cannot choose a profile. */
export interface TerminalLaunch {
  workingDirectory: string;
  claudeConfigDirectory: string | null;
}

export function parseTerminalLaunch(value: unknown): TerminalLaunch {
  if (typeof value !== 'object' || value === null || !('workingDirectory' in value) || !('claudeConfigDirectory' in value)
    || typeof value.workingDirectory !== 'string' || !isAbsolute(value.workingDirectory)
    || (value.claudeConfigDirectory !== null && (typeof value.claudeConfigDirectory !== 'string' || !isAbsolute(value.claudeConfigDirectory)))) {
    throw new Error('Malformed terminal response');
  }
  return { workingDirectory: value.workingDirectory, claudeConfigDirectory: typeof value.claudeConfigDirectory === 'string' ? value.claudeConfigDirectory : null };
}

/** The part of a node-pty process a terminal uses, so a test can stand in for one. */
export interface TerminalProcess {
  onData(listener: (data: string) => void): unknown;
  onExit(listener: (event: { exitCode: number }) => void): unknown;
  write(data: string): void;
  resize(cols: number, rows: number): void;
  kill(): void;
}

interface TerminalSender {
  readonly id: number;
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
  lastUsed: number;
}

// ponytail: trims by UTF-16 units, not bytes; close enough for a replay buffer.
const SCROLLBACK_LIMIT = 256 * 1024;

/** Shell environment: the app's own variables removed, and a locale a Finder-launched app does not inherit. */
export function terminalEnvironment(base: NodeJS.ProcessEnv, claudeConfigDirectory: string | null = null): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(base)) {
    if (value === undefined || key.startsWith('BYTEQUAY_')) continue;
    // An inherited API token must not override the task's Claude login.
    if (claudeConfigDirectory !== null && (key.startsWith('ANTHROPIC_') || key === 'CLAUDE_CODE_OAUTH_TOKEN')) continue;
    env[key] = value;
  }
  if (claudeConfigDirectory !== null) env.CLAUDE_CONFIG_DIR = claudeConfigDirectory;
  env.TERM = 'xterm-256color';
  env.LANG ??= 'en_US.UTF-8';
  return env;
}

/**
 * One login shell per Tears of time host (`review:<id>` or `task:<id>`) per window. Renderers only attach and detach; the
 * shell dies when it exits, when its host's worktree is about to go, when its window reloads or closes, and when the app quits.
 */
export class CodeTerminals {
  private readonly terminals = new Map<string, Terminal>();
  private readonly opening = new Map<string, { sender: TerminalSender; hostKey: string; promise: Promise<Terminal> }>();
  private readonly watched = new WeakSet<TerminalSender>();

  constructor(private readonly spawn: (launch: TerminalLaunch, cols: number, rows: number, command?: string) => TerminalProcess) {}

  /**
   * Attaches to this host's shell, starting one if it has none.
   *
   * A `command` makes that shell one-shot: it runs the command and exits, so its exit is the
   * command's own result. Without one the shell is interactive and outlives any single command.
   */
  async open(sender: TerminalSender, hostKey: string, cols: number, rows: number, launch: () => Promise<TerminalLaunch>, command?: string) {
    this.watch(sender);
    const key = `${sender.id}:${hostKey}`;
    let terminal = this.terminals.get(key);
    if (terminal === undefined) {
      // Two attaches racing through the backend check must share one shell.
      let pending = this.opening.get(key);
      if (pending === undefined) {
        const promise = Promise.resolve().then(launch).then(value => {
          if (this.opening.get(key)?.promise !== promise) throw new Error('The terminal was closed before it opened');
          return this.start(key, sender, hostKey, value, cols, rows, command);
        });
        pending = { sender, hostKey, promise };
        this.opening.set(key, pending);
        void promise.catch(() => {}).finally(() => {
          if (this.opening.get(key)?.promise === promise) this.opening.delete(key);
        });
      }
      terminal = await pending.promise;
    }
    terminal.lastUsed = Date.now();
    return { terminalId: terminal.id, scrollback: terminal.scrollback.slice(-SCROLLBACK_LIMIT) };
  }

  write(sender: TerminalSender, terminalId: string, data: string): void {
    const terminal = this.owned(sender, terminalId);
    if (!terminal) return;
    terminal.lastUsed = Date.now();
    terminal.process.write(data);
  }

  resize(sender: TerminalSender, terminalId: string, cols: number, rows: number): void {
    if (cols > 0 && rows > 0) this.owned(sender, terminalId)?.process.resize(cols, rows);
  }

  /** Before End review, or a task's delete or cleanup, which removes every shell's working directory. */
  close(hostKey: string): void {
    for (const [key, pending] of this.opening) {
      if (pending.hostKey === hostKey) this.opening.delete(key);
    }
    for (const terminal of [...this.terminals.values()]) {
      if (terminal.hostKey === hostKey) this.kill(terminal);
    }
  }

  /** When this window last attached to, typed into or got output from one of its shells; 0 when it has none. */
  lastUsed(sender: TerminalSender): number {
    return Math.max(0, ...[...this.terminals.values()].filter(terminal => terminal.sender === sender).map(terminal => terminal.lastUsed));
  }

  stopAll(): void {
    this.opening.clear();
    for (const terminal of [...this.terminals.values()]) this.kill(terminal);
  }

  private start(key: string, sender: TerminalSender, hostKey: string, launch: TerminalLaunch, cols: number, rows: number, command?: string): Terminal {
    if (sender.isDestroyed()) throw new Error('The window closed before the terminal opened');
    const terminal: Terminal = { id: randomUUID(), key, hostKey, sender, process: this.spawn(launch, cols, rows, command), scrollback: '', lastUsed: Date.now() };
    this.terminals.set(key, terminal);
    terminal.process.onData(data => {
      terminal.lastUsed = Date.now();
      const scrollback = terminal.scrollback + data;
      // Trimming copies the whole buffer; at twice the limit a busy build pays that once per 256 KiB, not on every chunk.
      terminal.scrollback = scrollback.length > 2 * SCROLLBACK_LIMIT ? scrollback.slice(scrollback.length - SCROLLBACK_LIMIT) : scrollback;
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

  private watch(sender: TerminalSender): void {
    if (this.watched.has(sender)) return;
    this.watched.add(sender);
    const closeSender = () => {
      for (const [key, pending] of this.opening) {
        if (pending.sender === sender) this.opening.delete(key);
      }
      for (const terminal of [...this.terminals.values()]) {
        if (terminal.sender === sender) this.kill(terminal);
      }
    };
    sender.on('did-navigate', closeSender);
    sender.once('destroyed', closeSender);
  }
}
