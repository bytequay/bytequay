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
import { createRoot } from 'react-dom/client';
import Composer from '../Composer';
import { assert } from './harness';

export function run(): { name: string; error?: string }[] {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
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
    return results;
  } finally { flushSync(() => root.unmount()); host.remove(); }
}
