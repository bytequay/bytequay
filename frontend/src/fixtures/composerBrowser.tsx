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
import { createRef, type ComponentProps } from 'react';
import { flushSync } from 'react-dom';
import { createFixtureRoot } from './reactFixture';
import Composer from '../Composer';
import { assert, changeInput, element } from './harness';
import '../css/base.css';
import '../css/assistant.css';
import '../css/artifacts.css';

export function run(): { name: string; error?: string }[] {
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  const results: { name: string; error?: string }[] = [];
  const field = createRef<HTMLTextAreaElement>();
  let sent = 0;
  const mount = (props: Partial<ComponentProps<typeof Composer>> = {}) => {
    flushSync(() => root.render(<Composer inputRef={field} aria-label="Message" value="Keep my draft" onChange={() => {}}
      onSubmit={() => { sent++; }} submitEnabled {...props} />));
    const input = field.current;
    assert(input !== null, 'Shared composer forwards the textarea ref');
    return input;
  };
  const key = (input: HTMLTextAreaElement, options: KeyboardEventInit = {}) => {
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...options });
    input.dispatchEvent(event);
    return event;
  };
  const test = (name: string, check: () => void) => {
    sent = 0;
    try { check(); results.push({ name }); }
    catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
  };
  try {
    test('Enter sends; Shift+Enter and IME input do not', () => {
      const input = mount();
      assert(!key(input, { shiftKey: true }).defaultPrevented, 'Shift+Enter keeps its newline');
      key(input, { isComposing: true });
      key(input, { keyCode: 229 });
      input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
      key(input);
      assert(sent === 0, 'No message is sent while composing text');
      input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      assert(key(input).defaultPrevented && Number(sent) === 1, 'Enter sends exactly once');
      assert(input.value === 'Keep my draft', 'The host retains ownership of the draft');
    });
    test('Discussion shortcuts and mention handlers keep control of Enter', () => {
      let input = mount({ submitShortcut: 'mod-enter' });
      assert(!key(input).defaultPrevented && sent === 0, 'Plain Enter remains a newline in discussions');
      key(input, { metaKey: true }); key(input, { ctrlKey: true });
      assert(Number(sent) === 2, 'Both platform modifier shortcuts submit');
      input = mount({ onKeyDown: event => event.preventDefault() });
      key(input);
      assert(Number(sent) === 2, 'Selecting a mention must not also send');
    });
    test('Blocked submissions and paste handling stay intact', () => {
      let pasted = false;
      const input = mount({ submitEnabled: false, onPaste: () => { pasted = true; } });
      key(input);
      const button = host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
      assert(button !== null && button.disabled, 'Send is visibly disabled');
      button.click();
      input.dispatchEvent(new ClipboardEvent('paste', { bubbles: true }));
      assert(sent === 0 && pasted, 'Blocked sending does not consume paste or send a message');
    });
    test('Keywords stay colored through edits, wrapping and scrolling', () => {
      let draft = '@backport, ask @ci and (@review-team)\nEmail user@example.com; bare @ stays plain.';
      const input = mount({ value: draft, onChange: event => { draft = event.currentTarget.value; mount({ value: draft }); } });
      const mirror = element<HTMLElement>('.agt-compose__highlights', host);
      assert([...mirror.querySelectorAll('.agt-compose__keyword')].map(node => node.textContent).join() === '@backport,@ci,@review-team',
        'Color keywords without coloring email addresses, punctuation or a bare @');
      assert(mirror.getAttribute('aria-hidden') === 'true', 'Assistive technology reads only the editable draft');
      for (const theme of ['light', 'dark']) {
        host.style.colorScheme = theme;
        const keyword = element<HTMLElement>('.agt-compose__keyword', mirror);
        assert(getComputedStyle(keyword).color !== getComputedStyle(input).color, 'Keywords have a distinct color in both themes');
      }
      input.setSelectionRange(0, 9);
      assert(input.value.slice(input.selectionStart, input.selectionEnd) === '@backport', 'Keywords remain selectable plain text');
      flushSync(() => changeInput(input, 'Plain text'));
      assert(!host.querySelector('.has-keywords') && input.value === draft && mirror.textContent === `${draft}\n`, 'Editing clears the highlight and keeps the actual draft');

      const longDraft = Array.from({ length: 30 }, (_, index) => `${index} @backport ${'long-word-'.repeat(12)}`).join('\n') + '\n';
      for (const surface of ['', 'assistant-float', 'artifacts-host']) {
        host.className = surface;
        for (const width of [280, 680]) {
          host.style.width = `${width}px`;
          mount({ value: longDraft, className: surface === '' ? 'is-new-task' : surface === 'artifacts-host' ? 'artifacts-compose' : '' });
          assert(mirror.clientWidth === input.clientWidth && mirror.clientHeight === input.clientHeight,
            `${surface || 'task'} at ${width}: highlight viewport ${mirror.clientWidth}×${mirror.clientHeight}, textarea ${input.clientWidth}×${input.clientHeight}`);
          assert(mirror.scrollHeight === input.scrollHeight && input.scrollHeight > input.clientHeight,
            `${surface || 'task'} at ${width}: wrapped highlight height ${mirror.scrollHeight}, textarea ${input.scrollHeight}`);
          input.scrollTop = input.scrollHeight;
          input.dispatchEvent(new Event('scroll'));
          assert(mirror.scrollTop === input.scrollTop && input.scrollTop > 0, 'Highlights scroll with the editable text');
        }
      }
    });
    return results;
  } finally { disposeRoot(); }
}
