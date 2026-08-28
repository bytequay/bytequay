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
import { createRef } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import BetterWordsButton, {
  isCurrentBetterWordsResponse,
  normalizeTextareaText,
  replaceTextareaText,
} from './BetterWordsButton';

describe('BetterWordsButton', () => {
  it('is unavailable until the editor has text', () => {
    const textareaRef = createRef<HTMLTextAreaElement>();
    const blank = renderToStaticMarkup(
      <BetterWordsButton text="  " textareaRef={textareaRef} repository="owner/repo" />,
    );
    const draft = renderToStaticMarkup(
      <BetterWordsButton text="Needs polish" textareaRef={textareaRef} repository="owner/repo" />,
    );

    expect(blank).toContain('disabled=""');
    expect(blank).toContain('>Better words</button>');
    expect(draft).not.toContain('disabled=""');
  });

  it('uses the textarea document editing command so the replacement is undoable', () => {
    const focus = vi.fn();
    const setSelectionRange = vi.fn();
    const textarea = {
      value: 'rough words',
      focus,
      setSelectionRange,
      ownerDocument: { execCommand: () => false },
    } as unknown as HTMLTextAreaElement;
    const execCommand = vi.spyOn(textarea.ownerDocument, 'execCommand')
      .mockImplementation((_command, _showUi, value) => {
        textarea.value = String(value);
        return true;
      });

    expect(replaceTextareaText(textarea, ' precise words\r\n')).toBe(true);
    expect(focus).toHaveBeenCalledOnce();
    expect(setSelectionRange).toHaveBeenCalledWith(0, 'rough words'.length);
    expect(execCommand).toHaveBeenCalledWith('insertText', false, ' precise words\n');
    expect(normalizeTextareaText('one\r\ntwo\rthree')).toBe('one\ntwo\nthree');
  });

  it('rejects a response after the draft or active request changes', () => {
    expect(isCurrentBetterWordsResponse(3, 3, 'draft', 'draft')).toBe(true);
    expect(isCurrentBetterWordsResponse(3, 4, 'draft', 'draft')).toBe(false);
    expect(isCurrentBetterWordsResponse(3, 3, 'draft', 'edited')).toBe(false);
  });
});
