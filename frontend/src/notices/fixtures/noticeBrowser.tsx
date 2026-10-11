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
import { createRoot } from 'react-dom/client';
import NoticeStack from '../NoticeStack';
import type { AppNotice, NoticeBridge, NoticeSnapshot } from '../contracts';
import '../../css/base.css';
import '../notices.css';

const wait = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(value: unknown, message: string): asserts value { if (!value) throw new Error(message); }
export async function run() {
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  let state: NoticeSnapshot = { notices: [], popupIds: [], active: true, maxHeight: 600 };
  let listener: (value: NoticeSnapshot) => void = () => {};
  const opened: string[] = []; const dismissed: string[] = []; const sizes: number[] = [];
  const bridge: NoticeBridge = {
    read: async () => state, subscribe: callback => { listener = callback; return () => {}; },
    dismiss: async id => { dismissed.push(id); state = { ...state, popupIds: state.popupIds.filter(key => key !== id) }; listener(state); },
    open: async id => { opened.push(id); await bridge.dismiss(id); },
    resize: height => sizes.push(height),
  };
  const make = (id: string, kind: AppNotice['kind']): AppNotice => ({ id, kind, taskId: id, workspaceId: 'workspace', turnId: 'turn',
    title: kind === 'QUESTION' ? 'Improve task recovery' : 'Review the settings screen',
    message: kind === 'QUESTION' ? 'Should interrupted tasks resume automatically?' : 'The latest response is ready to read.',
    laneId: 'codex', repository: 'bytequay/bytequay', occurredAt: new Date().toISOString() });
  const originalTimeout = window.setTimeout.bind(window);
  const originalClear = window.clearTimeout.bind(window);
  const originalNow = Date.now;
  let clock = originalNow(); let id = 1000000;
  const timers = new Map<number, { callback: () => void; delay: number }>();
  window.setTimeout = ((callback: TimerHandler, delay?: number) => {
    if (typeof callback === 'function' && delay !== undefined && delay >= 1000 && delay <= 8000) {
      const key = ++id; timers.set(key, { callback: callback as () => void, delay }); return key;
    }
    return originalTimeout(callback, delay);
  }) as typeof window.setTimeout;
  window.clearTimeout = handle => { if (typeof handle === 'number' && timers.has(handle)) timers.delete(handle); else originalClear(handle); };
  Date.now = () => clock;
  try {
    root.render(<NoticeStack bridge={bridge} />); await wait();
    state = { ...state, notices: [make('q', 'QUESTION'), make('done', 'TURN_FINISHED'), make('approval', 'APPROVAL'), make('other', 'QUEUE_PAUSED')], popupIds: ['q', 'done', 'approval', 'other'] };
    listener(state); await wait();
    assert(host.querySelectorAll('.app-notice').length === 3, 'At most three popups are visible');
    assert(timers.size === 1 && [...timers.values()][0].delay === 8000, 'Only completion has an eight-second timer');
    const done = host.querySelector<HTMLElement>('.is-turn_finished'); assert(done, 'Completion card missing');
    clock += 3000;
    done.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); await wait();
    assert(Number(timers.size) === 0, 'Hover pauses expiry');
    clock += 20000;
    done.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); await wait();
    assert([...timers.values()][0]?.delay === 5000, 'Hover preserves remaining display time');
    state = { ...state, active: false }; listener(state); await wait();
    assert(Number(timers.size) === 0, 'Background windows do not consume display time');
    state = { ...state, active: true }; listener(state); await wait();
    const action = done.querySelector<HTMLButtonElement>('.app-notice__actions button'); assert(action, 'Completion action missing');
    // The fixture window is hidden, so deliver the focus events without asking macOS for focus.
    action.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    await wait(); assert(Number(timers.size) === 0, 'Keyboard focus pauses expiry');
    action.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); await wait();
    [...timers.values()][0].callback(); await wait();
    assert(dismissed.includes('done') && host.querySelector('.is-turn_finished') === null, 'Completion expires');
    const questionClose = host.querySelector<HTMLButtonElement>('.is-question .app-notice__close'); assert(questionClose, 'Dismiss question missing');
    questionClose.click(); await wait();
    assert(state.notices.some(item => item.id === 'q'), 'Dismissing must preserve the pending question');
    const launcher = host.querySelector<HTMLButtonElement>('.app-notices__launcher'); assert(launcher, 'Notice launcher missing');
    launcher.click(); await wait();
    const questionAction = host.querySelector<HTMLButtonElement>('.is-question .app-notice__actions button'); assert(questionAction, 'Question must remain reachable');
    questionAction.click(); await wait(); assert(opened[0] === 'q', 'Card opens its own source');
    const rect = host.querySelector<HTMLElement>('.app-notices')?.getBoundingClientRect();
    assert(rect && rect.width <= window.innerWidth && rect.height <= 600, 'Cards fit their native surface');
    assert(sizes.some(height => height > 100), 'Native host receives measured card height');
    state = { ...state, notices: [], popupIds: [] }; listener(state); await wait();
    assert(sizes.at(-1) === 0, 'Resolved notices release the native hit area');
    return { passed: true, dismissed, opened };
  } finally {
    root.unmount(); host.remove(); window.setTimeout = originalTimeout; window.clearTimeout = originalClear; Date.now = originalNow;
  }
}
