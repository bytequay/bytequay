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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import type { DictationEvent } from './contracts';
import { Dictation, parseCheckFacts, voiceSupport, type CheckFacts, type DictationHost } from './dictation';

// Speaks the helper's protocol. FAKE_* variables pick its behavior; every run is logged to FAKE_LOG.
const FAKE_HELPER = `#!${process.execPath}
const fs = require('node:fs');
const [command, file] = process.argv.slice(2);
const say = line => console.log(JSON.stringify(line));
fs.appendFileSync(process.env.FAKE_LOG, command + '\\n');
if (command === 'check') {
  say({ type: 'check', available: true, language: 'en_US', languageName: 'English (United States)', model: process.env.FAKE_MODEL || 'ready', microphone: true });
}
else if (command === 'install') {
  say({ type: 'progress', fraction: 0.5 });
  if (process.env.FAKE_INSTALL === 'fail') { say({ type: 'error', code: 'failed', message: 'no network' }); process.exit(1); }
  say({ type: 'installed' });
}
else if (command === 'listen') {
  if (process.env.FAKE_LISTEN === 'denied') { say({ type: 'error', code: 'microphone-denied', message: 'no' }); process.exit(1); }
  fs.writeFileSync(file, 'audio');
  say({ type: 'listening' });
  say({ type: 'partial', text: 'Hel' });
  say({ type: 'final', text: 'Hello.' });
  require('node:readline').createInterface({ input: process.stdin }).on('line', line => {
    if (line === 'cancel') process.exit(0);
    if (line !== 'stop') return;
    if (process.env.FAKE_LISTEN === 'fail') { say({ type: 'error', code: 'failed', message: 'model crashed' }); process.exit(1); }
    say({ type: 'done', text: 'Hello.' });
    process.exit(0);
  }).on('close', () => process.exit(0));
}
else if (command === 'file') {
  say({ type: 'final', text: 'Again.' });
  say({ type: 'done', text: 'Again.' });
}
`;

class FakeSender {
  sent: Array<[string, unknown]> = [];
  navigate: () => void = () => {};
  destroyed: () => void = () => {};
  isDestroyed() { return false; }
  send(channel: string, message: unknown) { this.sent.push([channel, message]); }
  on(_event: 'did-navigate', listener: () => void) { this.navigate = listener; }
  once(_event: 'destroyed', listener: () => void) { this.destroyed = listener; }
  events(): DictationEvent[] { return this.sent.filter(([channel]) => channel === 'dictation:event').map(([, event]) => event as DictationEvent); }
}

const ready: CheckFacts = { available: true, language: 'en_US', languageName: 'English (United States)', model: 'ready', microphone: true };

let root: string;
let host: DictationHost & { microphoneStatus: string; askMicrophone: Mock<[], Promise<boolean>>; broadcast: Mock<[string, unknown], void> };
const runs = () => fs.readFileSync(path.join(root, 'runs'), 'utf8').split('\n').filter(Boolean);

beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'bytequay-dictation-test-'));
  const helper = path.join(root, 'bytequay-dictate');
  fs.writeFileSync(helper, FAKE_HELPER, { mode: 0o755 });
  fs.writeFileSync(path.join(root, 'runs'), '');
  vi.stubEnv('FAKE_LOG', path.join(root, 'runs'));
  host = {
    helper,
    macos: () => '26.5.1',
    folder: path.join(root, 'recordings'),
    settingsFile: path.join(root, 'voice.json'),
    microphoneStatus: 'granted',
    microphone: () => host.microphoneStatus,
    askMicrophone: vi.fn<[], Promise<boolean>>(async () => true),
    broadcast: vi.fn<[string, unknown], void>(),
  };
});

afterEach(() => {
  vi.unstubAllEnvs();
  fs.rmSync(root, { recursive: true, force: true });
});

async function enabled(): Promise<Dictation> {
  fs.writeFileSync(host.settingsFile, JSON.stringify({ enabled: true }));
  return new Dictation(host);
}

describe('voice support', () => {
  it.each([
    ['an older macOS', ready, '15.6', 'granted', false, 'macos-too-old'],
    ['no helper answer', null, '26.5', 'granted', false, 'unavailable'],
    ['a Mac without on-device speech', { ...ready, available: false }, '26.5', 'granted', false, 'unavailable'],
    ['an unsupported language', { ...ready, language: null, model: null }, '26.5', 'granted', false, 'language-unsupported'],
    ['no microphone', { ...ready, microphone: false }, '26.5', 'granted', false, 'no-microphone'],
    ['a denied microphone', ready, '26.5', 'denied', false, 'microphone-denied'],
    ['a restricted microphone', ready, '27.0.1', 'restricted', false, 'microphone-denied'],
    ['a missing model', { ...ready, model: 'missing' }, '26.5', 'granted', false, 'needs-model'],
    ['a model macOS is downloading', { ...ready, model: 'downloading' }, '26.5', 'granted', false, 'downloading'],
    ['a download ByteQuay started', { ...ready, model: 'missing' }, '26.5', 'granted', true, 'downloading'],
    ['a microphone never asked about', ready, '26.5', 'not-determined', false, 'ready'],
    ['everything in place', ready, '26.5', 'granted', false, 'ready'],
  ] as const)('maps %s', (_name, facts, macos, microphone, installing, state) => {
    expect(voiceSupport(facts, macos, microphone, installing).state).toBe(state);
  });

  it('reads only well-formed check lines', () => {
    expect(parseCheckFacts({ type: 'check', ...ready })).toEqual(ready);
    expect(parseCheckFacts({ type: 'check', ...ready, microphone: 'yes' })).toBeNull();
    expect(parseCheckFacts(undefined)).toBeNull();
  });

  it('asks the helper, but never starts it on an older macOS', async () => {
    const dictation = new Dictation(host);
    expect(await dictation.check()).toEqual({ state: 'ready', macos: '26.5.1', language: 'English (United States)' });
    host.macos = () => '15.6';
    expect((await dictation.check()).state).toBe('macos-too-old');
    expect(runs()).toEqual(['check']);
  });
});

describe('the Voice setting', () => {
  it('turns on after asking for the microphone and reserving the language, and tells every window', async () => {
    host.microphoneStatus = 'not-determined';
    const dictation = new Dictation(host);
    expect(dictation.settings()).toEqual({ enabled: false });
    const toggle = await dictation.setEnabled(true);
    expect(toggle).toMatchObject({ enabled: true, support: { state: 'ready' } });
    expect(host.askMicrophone).toHaveBeenCalledOnce();
    expect(runs()).toEqual(['check', 'install', 'check']);
    expect(JSON.parse(fs.readFileSync(host.settingsFile, 'utf8'))).toEqual({ enabled: true });
    expect(host.broadcast).toHaveBeenCalledWith('voice:changed', { enabled: true });
    expect(new Dictation(host).settings()).toEqual({ enabled: true });
  });

  it('stays off when a check fails or the microphone is refused', async () => {
    vi.stubEnv('FAKE_MODEL', 'missing');
    const dictation = new Dictation(host);
    expect(await dictation.setEnabled(true)).toMatchObject({ enabled: false, support: { state: 'needs-model' } });
    vi.stubEnv('FAKE_MODEL', 'ready');
    host.microphoneStatus = 'not-determined';
    host.askMicrophone.mockResolvedValueOnce(false);
    expect(await dictation.setEnabled(true)).toMatchObject({ enabled: false, support: { state: 'microphone-denied' } });
    expect(fs.existsSync(host.settingsFile)).toBe(false);
  });

  it('reports download progress to every window and the failure code', async () => {
    const dictation = new Dictation(host);
    await dictation.install();
    expect(host.broadcast).toHaveBeenCalledWith('voice:install-progress', { fraction: 0.5 });
    vi.stubEnv('FAKE_INSTALL', 'fail');
    await expect(dictation.install()).rejects.toThrow('failed');
  });

  it('treats a missing or malformed setting as off', () => {
    fs.writeFileSync(host.settingsFile, '{nope');
    expect(new Dictation(host).settings()).toEqual({ enabled: false });
  });
});

describe('a dictation', () => {
  it('sends live text to its own window and the finished text after stop, then deletes the recording', async () => {
    const dictation = await enabled();
    const owner = new FakeSender();
    const start = dictation.start(owner);
    if (!('id' in start)) throw new Error(`refused ${start.refused}`);
    const { id } = start;
    await vi.waitFor(() => expect(owner.events()).toContainEqual({ id, type: 'final', text: 'Hello.' }));
    expect(owner.events().slice(0, 2)).toEqual([{ id, type: 'listening' }, { id, type: 'partial', text: 'Hel' }]);
    const other = new FakeSender();
    dictation.stop(other, id);
    expect(dictation.start(other)).toEqual({ refused: 'busy' });
    dictation.stop(owner, id);
    await vi.waitFor(() => expect(owner.events().at(-1)).toEqual({ id, type: 'done', text: 'Hello.' }));
    expect(owner.events()).toContainEqual({ id, type: 'finishing' });
    expect(other.sent).toEqual([]);
    expect(fs.readdirSync(host.folder)).toEqual([]);
  });

  it('is refused while off, without macOS 26, without a helper, or with a denied microphone', async () => {
    const sender = new FakeSender();
    expect(new Dictation(host).start(sender)).toEqual({ refused: 'off' });
    const dictation = await enabled();
    host.microphoneStatus = 'denied';
    expect(dictation.start(sender)).toEqual({ refused: 'microphone-denied' });
    host.macos = () => '15.6';
    expect(dictation.start(sender)).toEqual({ refused: 'macos-too-old' });
    host.macos = () => '26.5';
    host.helper = null;
    expect(dictation.start(sender)).toEqual({ refused: 'unavailable' });
    expect(runs()).toEqual([]);
  });

  it('cancels without text and deletes the recording', async () => {
    const dictation = await enabled();
    const owner = new FakeSender();
    const start = dictation.start(owner);
    if (!('id' in start)) throw new Error('refused');
    await vi.waitFor(() => expect(owner.events()).toContainEqual({ id: start.id, type: 'listening' }));
    dictation.cancel(owner, start.id);
    await vi.waitFor(() => expect(owner.events().at(-1)).toEqual({ id: start.id, type: 'cancelled' }));
    expect(owner.events().map(event => event.type)).not.toContain('done');
    expect(fs.readdirSync(host.folder)).toEqual([]);
  });

  it('keeps a failed recording for Retry, and Discard deletes it', async () => {
    vi.stubEnv('FAKE_LISTEN', 'fail');
    const dictation = await enabled();
    const owner = new FakeSender();
    const start = dictation.start(owner);
    if (!('id' in start)) throw new Error('refused');
    const { id } = start;
    await vi.waitFor(() => expect(owner.events()).toContainEqual({ id, type: 'listening' }));
    dictation.stop(owner, id);
    await vi.waitFor(() => expect(owner.events().at(-1)).toMatchObject({ id, type: 'failed', code: 'failed' }));
    expect(fs.readdirSync(host.folder)).toEqual([`${id}.caf`]);

    expect(dictation.retry(new FakeSender(), id)).toEqual({ refused: 'unavailable' });
    expect(dictation.retry(owner, id)).toEqual({ id });
    await vi.waitFor(() => expect(owner.events().at(-1)).toEqual({ id, type: 'done', text: 'Again.' }));
    expect(runs()).toEqual(['listen', 'file']);
    expect(fs.readdirSync(host.folder)).toEqual([]);

    const again = dictation.start(owner);
    if (!('id' in again)) throw new Error('refused');
    await vi.waitFor(() => expect(owner.events()).toContainEqual({ id: again.id, type: 'listening' }));
    dictation.stop(owner, again.id);
    await vi.waitFor(() => expect(owner.events().at(-1)).toMatchObject({ id: again.id, type: 'failed' }));
    dictation.discard(owner, again.id);
    expect(fs.readdirSync(host.folder)).toEqual([]);
  });

  it('keeps nothing when it fails before any audio', async () => {
    vi.stubEnv('FAKE_LISTEN', 'denied');
    const dictation = await enabled();
    const owner = new FakeSender();
    const start = dictation.start(owner);
    if (!('id' in start)) throw new Error('refused');
    await vi.waitFor(() => expect(owner.events().at(-1)).toEqual({ id: start.id, type: 'failed', code: 'microphone-denied', seconds: 0 }));
    expect(dictation.retry(owner, start.id)).toEqual({ refused: 'unavailable' });
    expect(fs.readdirSync(host.folder)).toEqual([]);
  });

  it('ends with its window, and the folder is emptied at quit', async () => {
    const dictation = await enabled();
    const owner = new FakeSender();
    const start = dictation.start(owner);
    if (!('id' in start)) throw new Error('refused');
    await vi.waitFor(() => expect(owner.events()).toContainEqual({ id: start.id, type: 'listening' }));
    owner.navigate();
    await vi.waitFor(() => expect(owner.events().at(-1)).toEqual({ id: start.id, type: 'cancelled' }));
    expect(fs.readdirSync(host.folder)).toEqual([]);
    dictation.clearRecordings();
    expect(fs.existsSync(host.folder)).toBe(false);
  });
});
