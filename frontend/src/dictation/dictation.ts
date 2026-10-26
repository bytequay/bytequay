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
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { createInterface } from 'node:readline';
import type { DictationEvent, DictationRefusal, DictationStart, VoiceLanguage, VoiceLanguages, VoiceSettings, VoiceState, VoiceSupport, VoiceToggle } from './contracts';

/** What Electron main supplies; a test stands in for each. */
export interface DictationHost {
  /** The helper binary, or null when this build has none. */
  helper: string | null;
  /** `process.getSystemVersion()`, read when needed because only Electron has it. */
  macos(): string;
  /** Where recordings live; emptied at startup and quit. */
  folder: string;
  settingsFile: string;
  /** `systemPreferences.getMediaAccessStatus('microphone')`. */
  microphone(): string;
  askMicrophone(): Promise<boolean>;
  broadcast(channel: string, message: unknown): void;
}

export interface DictationSender {
  isDestroyed(): boolean;
  send(channel: string, message: unknown): void;
  on(event: 'did-navigate', listener: () => void): unknown;
  once(event: 'destroyed', listener: () => void): unknown;
}

/** The helper's `check` line; `translation` is null when nothing needs translating. */
export type CheckFacts = { available: boolean; language: string | null; languageName: string; model: string | null; translation: string | null; microphone: boolean };

type Line = { type: string; [key: string]: unknown };

type Session = {
  id: string;
  sender: DictationSender;
  child: ChildProcessWithoutNullStreams;
  recording: string;
  /** Seconds already known from an earlier attempt; a retry has no listening of its own. */
  seconds: number;
  listeningAt: number | null;
  stoppedAt: number | null;
  ending: 'stop' | 'cancel' | null;
  done: string | null;
  error: string | null;
  settled: boolean;
  timer?: NodeJS.Timeout;
};

type Kept = { sender: DictationSender; recording: string; seconds: number };

// A stopped or cancelled helper finishes in well under a second; past this it is stuck.
const SETTLE_MS = 10_000;

const blocked = (microphone: string) => microphone === 'denied' || microphone === 'restricted';

export function voiceSupport(facts: CheckFacts | null, macos: string, microphone: string, installing = false): VoiceSupport {
  if (!(Number.parseInt(macos, 10) >= 26)) return { state: 'macos-too-old', macos };
  if (facts === null || !facts.available) return { state: 'unavailable', macos };
  const state: VoiceState = facts.language === null ? 'language-unsupported'
    : !facts.microphone ? 'no-microphone'
      : blocked(microphone) ? 'microphone-denied'
        : installing || facts.model === 'downloading' ? 'downloading'
          : facts.model !== 'ready' ? 'needs-model'
            : facts.translation === 'missing' ? 'needs-translation'
              : facts.translation === 'unsupported' ? 'language-unsupported' : 'ready';
  return { state, macos, language: facts.languageName };
}

export function parseCheckFacts(line: Line | undefined): CheckFacts | null {
  if (line === undefined || typeof line.available !== 'boolean' || typeof line.microphone !== 'boolean' || typeof line.languageName !== 'string'
    || (line.language !== null && typeof line.language !== 'string') || (line.model !== null && typeof line.model !== 'string')) return null;
  return {
    available: line.available, language: line.language, languageName: line.languageName, model: line.model,
    translation: typeof line.translation === 'string' ? line.translation : null, microphone: line.microphone,
  };
}

const languageList = (value: unknown): VoiceLanguage[] => Array.isArray(value)
  ? value.filter((item): item is VoiceLanguage => typeof item === 'object' && item !== null && typeof item.id === 'string' && typeof item.name === 'string')
  : [];

const optionalText = (value: unknown): string | null => (typeof value === 'string' && value !== '' ? value : null);

function readLines(child: ChildProcessWithoutNullStreams, receive: (line: Line) => void): void {
  createInterface({ input: child.stdout }).on('line', text => {
    try {
      const value: unknown = JSON.parse(text);
      if (typeof value === 'object' && value !== null && 'type' in value && typeof value.type === 'string') receive(value as Line);
    }
    catch {
      // Not one of the helper's lines.
    }
  });
}

/** Owns the helper processes, the recordings kept for Retry, and the per-Mac Voice switch. */
export class Dictation {
  private stored: VoiceSettings | null = null;
  private session: Session | null = null;
  private readonly kept = new Map<string, Kept>();
  private installing: Promise<void> | null = null;
  private downloading: Promise<void> | null = null;
  private readonly watched = new WeakSet<DictationSender>();

  constructor(private readonly host: DictationHost) {}

  settings(): VoiceSettings {
    if (this.stored === null) {
      try {
        const saved: unknown = JSON.parse(fs.readFileSync(this.host.settingsFile, 'utf8'));
        const value = typeof saved === 'object' && saved !== null ? saved as Record<string, unknown> : {};
        this.stored = { enabled: value.enabled === true, spoken: optionalText(value.spoken), written: optionalText(value.written) };
      }
      catch {
        this.stored = { enabled: false, spoken: null, written: null };
      }
    }
    return { ...this.stored };
  }

  async check(): Promise<VoiceSupport> {
    if (!(Number.parseInt(this.host.macos(), 10) >= 26) || this.host.helper === null) return voiceSupport(null, this.host.macos(), this.host.microphone());
    return voiceSupport(await this.facts(), this.host.macos(), this.host.microphone(), this.installing !== null);
  }

  async languages(spoken: string | null): Promise<VoiceLanguages> {
    const lines = await this.run(['languages', ...(spoken === null ? [] : ['--locale', spoken])]).catch((): Line[] => []);
    const line = lines.find(candidate => candidate.type === 'languages');
    return { spoken: languageList(line?.spoken), written: languageList(line?.written) };
  }

  /** A written language the new spoken one cannot be translated into is dropped, so the draft keeps the spoken language. */
  async setLanguages(next: { spoken: string | null; written: string | null }): Promise<VoiceToggle> {
    const { written } = await this.languages(next.spoken);
    const keep = next.written !== null && written.some(language => language.id === next.written) ? next.written : null;
    this.save({ ...this.settings(), spoken: next.spoken, written: keep });
    let support = await this.check();
    if (this.settings().enabled && support.state === 'ready') {
      // A newly chosen spoken language is reserved as turning dictation on would reserve it.
      await this.install().catch(() => {});
      support = await this.check();
    }
    return { ...this.settings(), support };
  }

  /** Apple downloads a translation pair only after asking in its own sheet, which needs the helper's small window. */
  downloadTranslation(): Promise<void> {
    const helper = this.host.helper;
    const { written } = this.settings();
    if (helper === null || written === null) return Promise.reject(new Error('unavailable'));
    this.downloading ??= this.spokenLocale().then(from => new Promise<void>((resolve, reject) => {
      if (from === null) {
        reject(new Error('language-unsupported'));
        return;
      }
      const child = spawn(helper, ['translation-download', from, written]);
      let installed = false;
      let error: string | null = null;
      readLines(child, line => {
        if (line.type === 'installed') installed = true;
        if (line.type === 'error') error = typeof line.code === 'string' ? line.code : 'failed';
      });
      child.on('error', () => reject(new Error('unavailable')));
      // Closing the window without downloading leaves no line at all.
      child.on('exit', () => (installed ? resolve() : reject(new Error(error ?? 'cancelled'))));
    })).finally(() => {
      this.downloading = null;
    });
    return this.downloading;
  }

  async setEnabled(on: boolean): Promise<VoiceToggle> {
    if (!on) {
      this.save({ ...this.settings(), enabled: false });
      if (this.session !== null) this.cancel(this.session.sender, this.session.id);
      for (const [id, kept] of [...this.kept]) this.discard(kept.sender, id);
      return { ...this.settings(), support: await this.check() };
    }
    let support = await this.check();
    if (support.state === 'ready' && this.host.microphone() === 'not-determined' && !await this.host.askMicrophone()) {
      support = { ...support, state: 'microphone-denied' };
    }
    if (support.state === 'ready') {
      // Reserving the language is instant when macOS already has its model, which the check required.
      await this.install().catch(() => {});
      support = await this.check();
    }
    if (support.state === 'ready') this.save({ ...this.settings(), enabled: true });
    return { ...this.settings(), support };
  }

  install(): Promise<void> {
    const helper = this.host.helper;
    if (helper === null) return Promise.reject(new Error('unavailable'));
    this.installing ??= new Promise<void>((resolve, reject) => {
      const child = spawn(helper, ['install', ...this.options()]);
      let error: string | null = null;
      readLines(child, line => {
        if (line.type === 'progress' && typeof line.fraction === 'number') this.host.broadcast('voice:install-progress', { fraction: line.fraction });
        if (line.type === 'error') error = typeof line.code === 'string' ? line.code : 'failed';
      });
      child.on('error', () => reject(new Error('unavailable')));
      child.on('exit', code => (code === 0 ? resolve() : reject(new Error(error ?? 'failed'))));
    }).finally(() => {
      this.installing = null;
    });
    return this.installing;
  }

  start(sender: DictationSender): DictationStart {
    const refused = this.refusal(true);
    if (refused !== null) return { refused };
    const id = randomUUID();
    const recording = path.join(this.host.folder, `${id}.caf`);
    fs.mkdirSync(this.host.folder, { recursive: true });
    this.begin(sender, id, recording, ['listen', recording, ...this.options()], 0);
    return { id };
  }

  stop(sender: DictationSender, id: string): void {
    const session = this.owned(sender, id);
    if (session === null || session.ending !== null) return;
    session.ending = 'stop';
    session.stoppedAt = Date.now();
    session.child.stdin.write('stop\n');
    this.settleWithin(session);
    this.emit(session.sender, { id, type: 'finishing' });
  }

  cancel(sender: DictationSender, id: string): void {
    const session = this.owned(sender, id);
    if (session === null || session.ending === 'cancel') return;
    session.ending = 'cancel';
    session.child.stdin.end('cancel\n');
    this.settleWithin(session);
  }

  retry(sender: DictationSender, id: string): DictationStart {
    const kept = this.kept.get(id);
    if (kept === undefined || kept.sender !== sender) return { refused: 'unavailable' };
    const refused = this.refusal(false);
    if (refused !== null) return { refused };
    this.kept.delete(id);
    this.begin(sender, id, kept.recording, ['file', kept.recording, ...this.options()], kept.seconds);
    return { id };
  }

  discard(sender: DictationSender, id: string): void {
    const kept = this.kept.get(id);
    if (kept === undefined || kept.sender !== sender) return;
    this.kept.delete(id);
    fs.rmSync(kept.recording, { force: true });
  }

  /** At startup and quit: no recording outlives the app session. */
  clearRecordings(): void {
    if (this.session !== null) this.session.child.kill();
    this.session = null;
    this.kept.clear();
    fs.rmSync(this.host.folder, { recursive: true, force: true });
  }

  private refusal(listening: boolean): DictationRefusal | null {
    if (!this.settings().enabled) return 'off';
    if (this.session !== null) return 'busy';
    if (!(Number.parseInt(this.host.macos(), 10) >= 26)) return 'macos-too-old';
    if (this.host.helper === null) return 'unavailable';
    if (listening && blocked(this.host.microphone())) return 'microphone-denied';
    return null;
  }

  private begin(sender: DictationSender, id: string, recording: string, args: string[], seconds: number): void {
    const helper = this.host.helper;
    if (helper === null) throw new Error('No dictation helper in this build');
    this.watch(sender);
    const child = spawn(helper, args);
    const session: Session = { id, sender, child, recording, seconds, listeningAt: null, stoppedAt: null, ending: null, done: null, error: null, settled: false };
    this.session = session;
    readLines(child, line => {
      if (session.ending === 'cancel') return;
      const text = typeof line.text === 'string' ? line.text : '';
      if (line.type === 'listening') {
        session.listeningAt = Date.now();
        this.emit(sender, { id, type: 'listening' });
      }
      else if (line.type === 'partial') this.emit(sender, { id, type: 'partial', text });
      else if (line.type === 'final') this.emit(sender, { id, type: 'final', text, ...(typeof line.original === 'string' ? { original: line.original } : {}) });
      else if (line.type === 'done') session.done = text;
      else if (line.type === 'error') session.error = typeof line.code === 'string' ? line.code : 'failed';
    });
    child.stdin.on('error', () => {
      // The helper already exited; its exit settles the session.
    });
    child.on('error', () => this.settle(session, null));
    child.on('exit', code => this.settle(session, code));
  }

  private settle(session: Session, code: number | null): void {
    if (session.settled) return;
    session.settled = true;
    clearTimeout(session.timer);
    if (this.session === session) this.session = null;
    const { id, sender, recording } = session;
    if (session.ending === 'cancel') {
      fs.rmSync(recording, { force: true });
      this.emit(sender, { id, type: 'cancelled' });
    }
    else if (code === 0 && session.done !== null) {
      fs.rmSync(recording, { force: true });
      this.emit(sender, { id, type: 'done', text: session.done });
    }
    else {
      const heard = session.listeningAt === null ? 0 : Math.round(((session.stoppedAt ?? Date.now()) - session.listeningAt) / 1000);
      const seconds = Math.max(session.seconds, heard);
      // A failure before any audio leaves nothing worth retrying.
      if (session.listeningAt !== null || session.seconds > 0) this.kept.set(id, { sender, recording, seconds });
      else fs.rmSync(recording, { force: true });
      this.emit(sender, { id, type: 'failed', code: session.error ?? 'failed', seconds });
    }
  }

  private settleWithin(session: Session): void {
    clearTimeout(session.timer);
    session.timer = setTimeout(() => session.child.kill(), SETTLE_MS);
  }

  /** The helper's options for the chosen languages; none follows the Mac's language and leaves the text untranslated. */
  private options(): string[] {
    const { spoken, written } = this.settings();
    return [...(spoken === null ? [] : ['--locale', spoken]), ...(written === null ? [] : ['--translate', written])];
  }

  private async facts(): Promise<CheckFacts | null> {
    const lines = await this.run(['check', ...this.options()]).catch((): Line[] => []);
    return parseCheckFacts(lines.find(line => line.type === 'check'));
  }

  private async spokenLocale(): Promise<string | null> {
    return this.settings().spoken ?? (await this.facts())?.language ?? null;
  }

  private run(args: string[]): Promise<Line[]> {
    const helper = this.host.helper;
    if (helper === null) return Promise.reject(new Error('unavailable'));
    return new Promise((resolve, reject) => {
      const child = spawn(helper, args);
      const lines: Line[] = [];
      const timer = setTimeout(() => child.kill(), SETTLE_MS);
      readLines(child, line => lines.push(line));
      child.on('error', error => {
        clearTimeout(timer);
        reject(error);
      });
      child.on('exit', () => {
        clearTimeout(timer);
        resolve(lines);
      });
    });
  }

  private owned(sender: DictationSender, id: string): Session | null {
    return this.session !== null && this.session.id === id && this.session.sender === sender ? this.session : null;
  }

  private emit(sender: DictationSender, event: DictationEvent): void {
    if (!sender.isDestroyed()) sender.send('dictation:event', event);
  }

  private save(next: VoiceSettings): void {
    this.stored = { ...next };
    try {
      fs.mkdirSync(path.dirname(this.host.settingsFile), { recursive: true });
      fs.writeFileSync(`${this.host.settingsFile}.tmp`, JSON.stringify(next));
      fs.renameSync(`${this.host.settingsFile}.tmp`, this.host.settingsFile);
    }
    catch (error) {
      console.warn('Unable to save the Voice setting', error);
    }
    this.host.broadcast('voice:changed', next);
  }

  private watch(sender: DictationSender): void {
    if (this.watched.has(sender)) return;
    this.watched.add(sender);
    const gone = () => {
      if (this.session !== null && this.session.sender === sender) this.cancel(sender, this.session.id);
      for (const [id, kept] of [...this.kept]) {
        if (kept.sender === sender) this.discard(sender, id);
      }
    };
    sender.on('did-navigate', gone);
    sender.once('destroyed', gone);
  }
}
