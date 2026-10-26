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
import { createRef, useState } from 'react';
import { flushSync } from 'react-dom';
import Composer from '../../Composer';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, button, runScenarios, settle, until } from '../../fixtures/harness';
import { settingsRoute } from '../../settings/routes';
import type { Bridge } from '../../types';
import type { DictationEvent, DictationStart, VoiceSettings } from '../contracts';
import '../../css/base.css';
import '../../css/agents.css';

const DRAFT = 'Rename the helper';
let calls: string[] = [];
let emit: (event: DictationEvent) => void = () => {};
let voiceChanged: (settings: VoiceSettings) => void = () => {};
let nextStart: DictationStart = { id: 'd1' };
let sent = 0;
let escapes = 0;

window.bridge = {
  getVoiceSettings: async () => ({ enabled: false }),
  onVoiceChanged: (listener: (settings: VoiceSettings) => void) => { voiceChanged = listener; return () => {}; },
  startDictation: async () => { calls.push('start'); return nextStart; },
  stopDictation: async (id: string) => { calls.push(`stop ${id}`); },
  cancelDictation: async (id: string) => { calls.push(`cancel ${id}`); },
  retryDictation: async (id: string) => { calls.push(`retry ${id}`); return { id }; },
  discardDictation: async (id: string) => { calls.push(`discard ${id}`); },
  onDictationEvent: (listener: (event: DictationEvent) => void) => {
    emit = listener;
    return () => { if (emit === listener) emit = () => {}; };
  },
} as unknown as Bridge;
document.addEventListener('keydown', event => { if (event.key === 'Escape') escapes++; });

const { host, root, dispose } = createFixtureRoot();
const field = createRef<HTMLTextAreaElement>();
let mounts = 0;

function Host() {
  const [draft, setDraft] = useState(DRAFT);
  return <Composer inputRef={field} aria-label="Message" value={draft} onChange={event => setDraft(event.target.value)}
    onSubmit={() => { sent++; }} submitEnabled />;
}

async function mount(focus = true): Promise<HTMLTextAreaElement> {
  calls = [];
  sent = 0;
  escapes = 0;
  flushSync(() => root.render(<Host key={++mounts} />));
  await settle();
  const input = field.current;
  assert(input !== null, 'The composer forwards its textarea to the host');
  if (focus) {
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  }
  return input;
}

const strip = () => host.querySelector('.agt-dictation')?.textContent ?? '';
const mic = () => host.querySelector<HTMLButtonElement>('[aria-label="Dictate (hold right ⌥)"], [aria-label="Stop dictation"]');
const key = (input: HTMLTextAreaElement, type: 'keydown' | 'keyup', init: KeyboardEventInit) =>
  input.dispatchEvent(new KeyboardEvent(type, { bubbles: true, cancelable: true, ...init }));
const rightOption = { key: 'Alt', code: 'AltRight', altKey: true };
const send = async (event: DictationEvent) => {
  flushSync(() => emit(event));
  await settle();
};

async function listening(id: string): Promise<HTMLTextAreaElement> {
  nextStart = { id };
  const input = await mount();
  mic()?.click();
  await until(() => calls.includes('start') && strip().includes('Listening…'), 'A click on the mic starts listening');
  return input;
}

export function run() {
  return runScenarios([
    ['Shows the mic only while Voice is on', async () => {
      await mount();
      assert(mic() === null, 'No mic while Voice is off');
      flushSync(() => voiceChanged({ enabled: true, spoken: null, written: null }));
      await until(() => mic() !== null, 'The mic appears as soon as Voice is turned on');
    }],
    ['Shows live words and inserts the finished text at the caret, without sending', async () => {
      const input = await listening('d1');
      await send({ id: 'd1', type: 'listening' });
      await send({ id: 'd1', type: 'partial', text: 'add a te' });
      assert(strip().includes('add a te'), 'Live words show in the strip');
      assert(input.value === DRAFT, 'Live words never touch the draft');
      await send({ id: 'd1', type: 'final', text: 'Add a test.' });
      mic()?.click();
      await until(() => calls.includes('stop d1'), 'A second click stops listening');
      await send({ id: 'd1', type: 'finishing' });
      assert(strip().includes('Finishing up'), 'The strip says it is finishing');
      await send({ id: 'd1', type: 'done', text: 'Add a test.' });
      await until(() => input.value === `${DRAFT} Add a test.`, 'The finished text lands at the caret after one space');
      assert(strip() === '' && sent === 0, 'The strip closes and nothing is sent');
      document.execCommand('undo');
      await until(() => input.value === DRAFT, '⌘Z removes the dictated words');
    }],
    ['Words go at the end of a draft never clicked into, and keep a space from text after the caret', async () => {
      nextStart = { id: 'd7' };
      let input = await mount(false);
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'The mic starts listening without a click in the text');
      await send({ id: 'd7', type: 'done', text: 'Add a test.' });
      await until(() => input.value === `${DRAFT} Add a test.`, 'The words follow the existing draft');
      nextStart = { id: 'd8' };
      input = await mount();
      input.setSelectionRange(0, 0);
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'Dictation starts at the caret');
      await send({ id: 'd8', type: 'done', text: 'Please' });
      await until(() => input.value === `Please ${DRAFT}`, 'The words stay where the caret was, apart from the text after it');
    }],
    ['Holding right ⌥ dictates; another key keeps ⌥ ordinary', async () => {
      nextStart = { id: 'd2' };
      const input = await mount();
      key(input, 'keydown', rightOption);
      await until(() => strip().includes('Listening…'), 'Holding right ⌥ starts dictation');
      assert(strip().includes('release to stop'), 'The strip says how to stop');
      key(input, 'keyup', rightOption);
      await until(() => calls.includes('stop d2'), 'Releasing right ⌥ stops it');
      await send({ id: 'd2', type: 'cancelled' });
      calls = [];
      key(input, 'keydown', rightOption);
      key(input, 'keydown', { key: 'é', code: 'KeyE', altKey: true });
      await new Promise(resolve => window.setTimeout(resolve, 400));
      key(input, 'keyup', rightOption);
      await settle();
      assert(calls.length === 0, 'An ⌥ character does not start dictation');
    }],
    ['Esc cancels without closing the panel around the composer', async () => {
      const input = await listening('d3');
      key(input, 'keydown', { key: 'Escape', code: 'Escape' });
      await until(() => calls.includes('cancel d3'), 'Esc cancels the dictation');
      assert(escapes === 0, 'Esc stops at the composer');
      await send({ id: 'd3', type: 'cancelled' });
      assert(strip() === '' && input.value === DRAFT, 'Nothing is inserted');
    }],
    ['A failure keeps the recording for Retry or Discard', async () => {
      const input = await listening('d4');
      await send({ id: 'd4', type: 'failed', code: 'failed', seconds: 4 });
      assert(strip().includes('Couldn’t turn 4 s of speech into text. Your recording is kept.'), 'The strip explains the failure');
      button('Retry', host).click();
      await until(() => calls.includes('retry d4') && strip().includes('Finishing up'), 'Retry transcribes the recording again');
      await send({ id: 'd4', type: 'done', text: 'again' });
      await until(() => input.value === `${DRAFT} again`, 'The retried text lands in the draft');
      nextStart = { id: 'd5' };
      mic()?.click();
      await until(() => strip().includes('Listening…'), 'Dictation starts again');
      await send({ id: 'd5', type: 'failed', code: 'failed', seconds: 2 });
      button('Discard', host).click();
      await until(() => calls.includes('discard d5') && strip() === '', 'Discard deletes the recording and closes the strip');
    }],
    ['Says why a start was refused and when no speech was heard', async () => {
      nextStart = { refused: 'busy' };
      await mount();
      mic()?.click();
      await until(() => strip().includes('Dictation is running in another composer.'), 'A busy start says so');
      nextStart = { refused: 'microphone-denied' };
      mic()?.click();
      await until(() => strip().includes('Dictation isn’t available right now.'), 'A blocked start says so');
      button('Open Voice settings', host).click();
      assert(window.location.hash === settingsRoute('features'), 'The strip opens the Voice setting');
      const input = await listening('d6');
      await send({ id: 'd6', type: 'done', text: '' });
      assert(strip().includes('No speech heard') && input.value === DRAFT, 'Silence inserts nothing');
    }],
  ], () => {
    flushSync(() => root.render(null));
  }).finally(dispose);
}
