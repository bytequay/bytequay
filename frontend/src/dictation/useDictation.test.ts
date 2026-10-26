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
import { nextView, refusalNotice, spaced, type DictationView } from './useDictation';

const listening: DictationView = { kind: 'listening', id: 'd1', hold: true, finished: '', partial: '', since: 0 };

describe('a composer dictation view', () => {
  it('replaces live words, appends finished ones, and ignores other dictations', () => {
    let view = nextView(listening, { id: 'd1', type: 'partial', text: 'Hel' });
    expect(view).toMatchObject({ finished: '', partial: 'Hel' });
    view = nextView(view, { id: 'd1', type: 'final', text: 'Hello.' });
    expect(view).toMatchObject({ finished: 'Hello.', partial: '' });
    view = nextView(view, { id: 'd1', type: 'partial', text: ' Again' });
    expect(nextView(view, { id: 'other', type: 'final', text: 'Not mine.' })).toBe(view);
    view = nextView(view, { id: 'd1', type: 'finishing' });
    expect(view).toEqual({ kind: 'finishing', id: 'd1', finished: 'Hello.', partial: ' Again' });
    expect(nextView(view, { id: 'd1', type: 'done', text: 'Hello. Again.' })).toEqual({ kind: 'idle' });
  });

  it('keeps a failure with audio for Retry, and explains one without', () => {
    expect(nextView(listening, { id: 'd1', type: 'failed', code: 'failed', seconds: 4 })).toEqual({ kind: 'failed', id: 'd1', seconds: 4 });
    expect(nextView(listening, { id: 'd1', type: 'failed', code: 'microphone-denied', seconds: 0 })).toMatchObject({ kind: 'notice', settings: true });
    expect(nextView(listening, { id: 'd1', type: 'cancelled' })).toEqual({ kind: 'idle' });
  });

  it('points a refusal at the Voice setting unless another composer is dictating', () => {
    expect(refusalNotice('busy')).toMatchObject({ settings: false });
    expect(refusalNotice('microphone-denied')).toMatchObject({ settings: true });
  });

  it('puts one space between dictated words and any text they touch', () => {
    expect(spaced('Rename the helper', ' add a test. ')).toBe(' add a test.');
    expect(spaced('', 'Add a test.')).toBe('Add a test.');
    expect(spaced('First line\n', 'Second.')).toBe('Second.');
    expect(spaced('Done. ', 'Next.')).toBe('Next.');
    expect(spaced('', 'Please', 'rename it')).toBe('Please ');
    expect(spaced('Rename', 'the retry', ' helper')).toBe(' the retry');
  });
});
