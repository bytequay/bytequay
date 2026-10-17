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
import { observeNoticeOcclusion } from '../occlusion';
import { useNoticeSource } from '../useNoticeSource';
import { LayerCovered } from '../../Zoom';
import type { AppNotice, NoticeBridge, NoticeSnapshot } from '../contracts';
import '../../css/base.css';
import '../notices.css';

const wait = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(value: unknown, message: string): asserts value { if (!value) throw new Error(message); }
function TaskSource({ taskId }: { taskId: string | null }): null { useNoticeSource(taskId); return null; }
export async function run() {
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  let state: NoticeSnapshot = { notices: [], popupIds: [], viewedIds: [], active: true, maxHeight: 600 };
  let listener: (value: NoticeSnapshot) => void = () => {};
  let rejectDismiss = false;
  let rejectOpen = false;
  const opened: string[] = []; const dismissed: string[] = []; const sizes: number[] = [];
  const bridge: NoticeBridge = {
    read: async () => state, subscribe: callback => { listener = callback; return () => {}; },
    dismiss: async id => {
      if (rejectDismiss) throw new Error('Dismiss failed');
      dismissed.push(id); state = { ...state, popupIds: state.popupIds.filter(key => key !== id) }; listener(state);
    },
    open: async id => {
      if (rejectOpen) throw new Error('Open failed');
      opened.push(id);
      // As the host does: an opened completion leaves, and anything else only stops alerting.
      const finished = state.notices.find(notice => notice.id === id)?.kind === 'TURN_FINISHED';
      state = { ...state, notices: finished ? state.notices.filter(notice => notice.id !== id) : state.notices, popupIds: state.popupIds.filter(key => key !== id) };
      listener(state);
    },
    resize: height => sizes.push(height),
  };
  const make = (id: string, kind: AppNotice['kind'], minutesAgo = 0): AppNotice => ({ id, kind, taskId: id, workspaceId: 'workspace', turnId: 'turn', reviewId: null,
    title: kind === 'QUESTION' ? 'Improve task recovery' : 'Review the settings screen',
    pullRequest: null, pullRequestTitle: null, changes: null,
    message: kind === 'QUESTION' ? 'Should interrupted tasks resume automatically?' : 'The latest response is ready to read.',
    laneId: 'codex', repository: 'bytequay/bytequay', occurredAt: new Date(Date.now() - minutesAgo * 60_000).toISOString() });
  const alert = () => host.querySelector<HTMLElement>('.app-notice');
  const action = () => alert()?.querySelector<HTMLButtonElement>('.app-notice__actions button') ?? null;
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
    assert(!host.querySelector('.app-notices') && sizes.at(-1) === 0, 'Nothing new shows no alert and releases the native hit area');
    const pullRequestTitle = 'Improve recovery when interrupted tasks resume with queued messages and pending approval requests across repositories';
    state = { ...state, notices: [make('approval:a', 'APPROVAL', 1), make('approval:q', 'QUESTION', 2), make('question:t', 'QUESTION', 3),
      make('other', 'QUEUE_PAUSED', 4), make('done', 'TURN_FINISHED')], popupIds: ['approval:a', 'approval:q', 'question:t', 'other', 'done'] };
    listener(state); await wait();
    assert(host.querySelectorAll('.app-notice').length === 1, 'At most one alert shows');
    let front = alert(); assert(front?.classList.contains('is-turn_finished'), 'The alert is the newest notice not seen yet, whatever its kind');
    assert(!host.querySelector('.app-notices__launcher, .app-notices__header, .app-notices__pages, .app-notices__fold, .app-notices__edge, .is-preview')
      && host.querySelectorAll('button').length === 2, 'No launcher, list, pages, fold tab or stacked preview: only the alert’s action and ×');
    assert(front.querySelector('h2')?.textContent === 'Review the settings screen' && !front.querySelector('.app-notice__changes'),
      'Tasks without PR context keep their title and omit change counts');
    assert(front.querySelector('.app-notice__head')?.textContent?.startsWith('Turn finished') && front.querySelector('.app-notice__time')?.textContent === 'just now'
      && !front.querySelector('.app-notice__message'), 'Completed tasks state the outcome and its age without a generic message');
    assert(action()?.textContent === 'View response' && front.querySelector('.app-notice__actions span')?.textContent === 'bytequay/bytequay',
      'A completion opens its response, and the action row names the task repository');
    state = { ...state, notices: state.notices.map(notice => notice.id !== 'done' ? notice : { ...notice, title: 'Review bytequay/bytequay#42', reviewId: 'review',
      pullRequest: 'bytequay/bytequay#42', pullRequestTitle, changes: { update: false, commits: 2, files: 3, additions: 42, deletions: 12 } }) }; listener(state); await wait();
    front = alert(); assert(front, 'Review alert missing');
    assert(front.querySelector('h2')?.textContent === pullRequestTitle && front.querySelector('.app-notice__ref')?.textContent === 'bytequay/bytequay#42',
      'Review alerts show both the PR title and its identity');
    assert(front.querySelector('.app-notice__head')?.textContent?.startsWith('Review finished'), 'Review completions name the review');
    const counts = [...front.querySelectorAll('.app-notice__changes span')];
    assert(counts.map(span => span.textContent).join() === '2 commits,3 files,+42,−12', 'Review alerts show compact change counts');
    assert(getComputedStyle(counts[2]).color !== getComputedStyle(counts[3]).color, 'Added and deleted lines use different colors');
    const heading = front.querySelector('h2'); assert(heading, 'Review title missing');
    assert(heading.title === pullRequestTitle && heading.getBoundingClientRect().height <= parseFloat(getComputedStyle(heading).lineHeight) * 2 + 1,
      'Long PR titles stay within two lines and retain the full title on hover');
    assert(front.scrollWidth === front.clientWidth, 'Review context stays inside the narrow alert');
    const stack = host.querySelector<HTMLElement>('.app-notices'); assert(stack, 'Alert surface missing');
    // The alert slides in, so measure where it settles.
    await Promise.all(front.getAnimations().map(animation => animation.finished));
    const surface = stack.getBoundingClientRect();
    assert(Math.abs(surface.bottom - front.getBoundingClientRect().bottom) < 1 && surface.width <= window.innerWidth && surface.height <= 600,
      `The native surface ends at the alert, without a transparent strip below it, and fits its view: ${JSON.stringify({ surface, alert: front.getBoundingClientRect() })}`);
    assert(sizes.at(-1) === Math.ceil(surface.height) && surface.height > 100, 'The native host receives the alert’s measured height');
    assert(timers.size === 1 && [...timers.values()][0].delay === 8000, 'A completion alert has an eight-second timer');
    clock += 3000;
    front.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); await wait();
    assert(Number(timers.size) === 0, 'Hover pauses expiry');
    clock += 20000;
    front.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); await wait();
    assert([...timers.values()][0]?.delay === 5000, 'Hover preserves remaining display time');
    state = { ...state, active: false }; listener(state); await wait();
    assert(Number(timers.size) === 0, 'Background windows do not consume display time');
    state = { ...state, active: true }; listener(state); await wait();
    const view = action(); assert(view, 'Completion action missing');
    // The fixture window is hidden, so deliver the focus events without asking macOS for focus.
    view.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    await wait(); assert(Number(timers.size) === 0, 'Keyboard focus pauses expiry');
    view.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); await wait();
    [...timers.values()][0].callback(); await wait();
    assert(dismissed.join() === 'done' && state.notices.some(notice => notice.id === 'done'), 'An expired completion only stops alerting; it stays for the panel');
    front = alert();
    assert(front?.classList.contains('is-approval') && host.querySelectorAll('.app-notice').length === 1, 'The next newest notice alerts once the last one closes');
    assert(Number(timers.size) === 0, 'An alert that needs the user does not expire');
    const answer = action(); assert(answer?.textContent === 'Answer', 'An approval alert answers in the Assistant panel');
    rejectOpen = true; answer.click(); await wait();
    assert(alert()?.classList.contains('is-approval') && host.querySelector('[role="alert"]')?.textContent === 'Unable to open this notice. Try again.',
      'A failed Answer keeps the alert to try again');
    rejectOpen = false; answer.click(); await wait();
    assert(opened.join() === 'approval:a' && !host.querySelector('[role="alert"]'), 'Answer goes to the host, which opens the panel at the card');
    front = alert();
    assert(front?.classList.contains('is-question') && action()?.textContent === 'Answer'
      && front.querySelector('.app-notice__message')?.textContent === 'Should interrupted tasks resume automatically?'
      && front.querySelector('.app-notice__head')?.textContent?.startsWith('Waiting for you'), 'A question card alerts with what the agent asked and answers in the panel');
    const close = front.querySelector<HTMLButtonElement>('.app-notice__close'); assert(close, 'The alert has a close button');
    rejectDismiss = true; close.click(); await wait();
    assert(alert()?.classList.contains('is-question') && action()?.textContent === 'Answer'
      && host.querySelector('[role="alert"]')?.textContent === 'Unable to dismiss this notice.', 'A failed close keeps the alert');
    rejectDismiss = false; close.click(); await wait();
    assert(dismissed.at(-1) === 'approval:q' && state.notices.some(notice => notice.id === 'approval:q') && !opened.includes('approval:q')
      && !host.querySelector('[role="alert"]'), '× only closes the alert; the notice stays for the panel');
    assert(alert()?.classList.contains('is-question') && action()?.textContent === 'Reply', 'A task waiting for a reply keeps Reply');
    action()?.click(); await wait();
    assert(opened.at(-1) === 'question:t', 'Reply opens its task');
    assert(alert()?.classList.contains('is-queue_paused') && action()?.textContent === 'View queue', 'Other kinds keep their own action');
    alert()?.querySelector<HTMLButtonElement>('.app-notice__close')?.click(); await wait();
    assert(!host.querySelector('.app-notices') && sizes.at(-1) === 0, 'With every notice seen no alert shows, and the native hit area is released');
    listener({ ...state }); await wait();
    assert(!host.querySelector('.app-notices'), 'Closed alerts stay closed across refreshes');
    state = { ...state, notices: [make('viewed', 'TURN_FINISHED')], popupIds: [], viewedIds: ['viewed'] }; listener(state); await wait();
    assert(!host.querySelector('.app-notice'), 'The visible task never alerts');
    state = { ...state, notices: [...state.notices, make('earlier', 'QUESTION', 5)] }; listener(state); await wait();
    assert(!host.querySelector('.app-notice'), 'A notice that was there before the app started waits in the panel');
    state = { ...state, notices: [...state.notices, make('unviewed', 'QUESTION')], popupIds: ['unviewed'] }; listener(state); await wait();
    assert(host.querySelectorAll('.app-notice').length === 1 && host.querySelector('.is-question'), 'Other tasks still alert while the viewed task stays quiet');
    state = { ...state, notices: [make('last', 'TURN_FINISHED')], popupIds: ['last'], viewedIds: [] }; listener(state); await wait();
    rejectOpen = true; action()?.click(); await wait();
    assert(alert()?.classList.contains('is-turn_finished') && host.querySelector('[role="alert"]'), 'Failed navigation keeps the completion alert');
    rejectOpen = false; action()?.click(); await wait();
    assert(opened.at(-1) === 'last' && !host.querySelector('.app-notices') && sizes.at(-1) === 0,
      'Opening a completion closes its alert and releases the native hit area');
    state = { ...state, notices: [{ ...make('failed', 'FAILURE'), message: 'Rate limited by GitHub API' }], popupIds: ['failed'] }; listener(state); await wait();
    const failed = host.querySelector<HTMLElement>('.is-failure'); assert(failed, 'Failure alert missing');
    assert(failed.querySelector('.app-notice__message')?.textContent === 'Rate limited by GitHub API' && action()?.textContent === 'View log',
      'Failures show their reason and open the task log');
    const occlusion: boolean[] = [];
    const stopOcclusion = observeNoticeOcclusion(value => occlusion.push(value));
    const modal = document.createElement('section'); modal.setAttribute('aria-modal', 'true'); modal.textContent = 'Submit review';
    document.body.append(modal); await wait();
    assert(occlusion.join() === 'false,true', 'Opening a review modal hides native notices');
    modal.append(document.createElement('button')); await wait();
    assert(occlusion.length === 2, 'Modal content changes do not repeatedly publish visibility');
    modal.style.display = 'none'; await wait();
    assert(occlusion.at(-1) === false, 'Hidden modals release notices');
    modal.remove();
    const dialog = document.createElement('dialog'); document.body.append(dialog); dialog.showModal(); await wait();
    assert(occlusion.at(-1) === true, 'Native HTML dialogs also hide notices');
    dialog.close(); await wait();
    assert(occlusion.at(-1) === false, 'Closing the final dialog restores notices');
    dialog.remove(); stopOcclusion();
    const originalBridge = window.bridge;
    const sources = new Map<string, string>();
    window.bridge = { ...originalBridge, setNoticeSource: async (key, taskId) => { if (taskId === null) sources.delete(key); else sources.set(key, taskId); } };
    const sourceHost = document.createElement('div'); host.append(sourceHost);
    const sourceRoot = createRoot(sourceHost);
    const renderSource = async (taskId: string | null, covered = false) => {
      sourceRoot.render(<LayerCovered.Provider value={covered}><TaskSource taskId={taskId} /></LayerCovered.Provider>); await wait();
    };
    try {
      await renderSource('task'); assert([...sources.values()].join() === 'task', 'The visible task registers independently of turn or scroll position');
      await renderSource('task', true); assert(sources.size === 0, 'A covered task unregisters its notice source');
      await renderSource('other'); assert([...sources.values()].join() === 'other', 'Switching tasks registers only the visible task');
      await renderSource(null); assert(sources.size === 0, 'Leaving the task removes its source');
      await renderSource('task'); sourceRoot.render(null); await wait();
      assert(sources.size === 0, 'Unmounting the task removes its source');
    } finally { sourceRoot.unmount(); sourceHost.remove(); window.bridge = originalBridge; }
    return { passed: true, dismissed, opened };
  } finally {
    root.unmount(); host.remove(); window.setTimeout = originalTimeout; window.clearTimeout = originalClear; Date.now = originalNow;
  }
}
