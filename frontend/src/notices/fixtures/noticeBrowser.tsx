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
  const opened: string[] = []; const dismissed: string[] = []; const sizes: { height: number; width: number; folded: boolean }[] = [];
  const bridge: NoticeBridge = {
    read: async () => state, subscribe: callback => { listener = callback; return () => {}; },
    dismiss: async id => {
      if (rejectDismiss) throw new Error('Dismiss failed');
      dismissed.push(id); state = { ...state, popupIds: state.popupIds.filter(key => key !== id) }; listener(state);
    },
    remove: async id => {
      if (rejectDismiss) throw new Error('Dismiss failed');
      dismissed.push(id);
      state = { ...state, notices: state.notices.filter(notice => notice.id !== id),
        popupIds: state.popupIds.filter(key => key !== id), viewedIds: state.viewedIds.filter(key => key !== id) }; listener(state);
    },
    open: async id => { if (rejectOpen) throw new Error('Open failed'); opened.push(id); await bridge.dismiss(id); },
    resize: (height, width, folded) => sizes.push({ height, width, folded }),
  };
  const make = (id: string, kind: AppNotice['kind']): AppNotice => ({ id, kind, taskId: id, workspaceId: 'workspace', turnId: 'turn', reviewId: null,
    title: kind === 'QUESTION' ? 'Improve task recovery' : 'Review the settings screen',
    pullRequest: null, pullRequestTitle: null, changes: null,
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
    assert(!host.querySelector('.app-notices') && sizes.at(-1)?.height === 0, 'Empty state hides the notice counter and releases its native hit area');
    state = { ...state, notices: [make('done', 'TURN_FINISHED'), make('q', 'QUESTION'), make('approval', 'APPROVAL'), make('other', 'QUEUE_PAUSED')], popupIds: ['q', 'done', 'approval', 'other'] };
    listener(state); await wait();
    const launcher = host.querySelector<HTMLButtonElement>('.app-notices__launcher'); assert(launcher, 'New notices restore the counter');
    assert(host.querySelectorAll('.app-notice').length === 2, 'At most two popups are visible');
    const front = host.querySelector<HTMLElement>('.app-notice:not(.is-preview)');
    const preview = host.querySelector<HTMLElement>('.app-notice.is-preview');
    assert(front && preview && preview.inert, 'The covered preview has no hidden keyboard targets');
    assert(front.querySelector('h2')?.textContent === 'Review the settings screen' && !front.querySelector('.app-notice__changes'),
      'Tasks without PR context keep their title and omit change counts');
    const pullRequestTitle = 'Improve recovery when interrupted tasks resume with queued messages and pending approval requests across repositories';
    assert(front.querySelector('.app-notice__head')?.textContent?.startsWith('Turn finished') && front.querySelector('.app-notice__time')?.textContent === 'just now'
      && !front.querySelector('.app-notice__message'), 'Completed tasks state the outcome and its age without a generic message');
    assert(front.querySelector('.app-notice__actions span')?.textContent === 'bytequay/bytequay', 'The action row names the task repository');
    state = { ...state, notices: [{ ...state.notices[0], title: 'Review bytequay/bytequay#42', reviewId: 'review', pullRequest: 'bytequay/bytequay#42', pullRequestTitle,
      changes: { update: false, commits: 2, files: 3, additions: 42, deletions: 12 } }, ...state.notices.slice(1)] }; listener(state); await wait();
    assert(front.querySelector('h2')?.textContent === pullRequestTitle && front.querySelector('.app-notice__ref')?.textContent === 'bytequay/bytequay#42',
      'Review cards show both the PR title and its identity');
    assert(front.querySelector('.app-notice__head')?.textContent?.startsWith('Review finished'), 'Review completions name the review');
    const counts = [...front.querySelectorAll('.app-notice__changes span')];
    assert(counts.map(span => span.textContent).join() === '2 commits,3 files,+42,−12', 'Review cards show compact change counts');
    assert(getComputedStyle(counts[2]).color !== getComputedStyle(counts[3]).color, 'Added and deleted lines use different colors');
    const heading = front.querySelector('h2'); assert(heading, 'Review title missing');
    assert(heading.title === pullRequestTitle && heading.getBoundingClientRect().height <= parseFloat(getComputedStyle(heading).lineHeight) * 2 + 1,
      'Long PR titles stay within two lines and retain the full title on hover');
    assert(front.scrollWidth === front.clientWidth, 'Review context stays inside the narrow card');
    const frontRect = front.getBoundingClientRect(); const previewRect = preview.getBoundingClientRect();
    assert(frontRect.top > previewRect.top && frontRect.top < previewRect.bottom && previewRect.left > frontRect.left,
      'The second card peeks out behind the front card');
    assert(document.elementFromPoint(frontRect.left + frontRect.width / 2, frontRect.top + 5)?.closest('.app-notice') === front,
      'The front card receives clicks in the overlap');
    const stack = host.querySelector<HTMLElement>('.app-notices'); assert(stack, 'Stack missing');
    assert(Math.abs(stack.getBoundingClientRect().bottom - launcher.getBoundingClientRect().bottom) < 1,
      'The native surface ends at the counter without a transparent strip below it');
    assert(timers.size === 1 && [...timers.values()][0].delay === 8000, 'Only completion has an eight-second timer');
    const originalOrder = state.notices;
    state = { ...state, notices: [originalOrder[1], originalOrder[0], ...originalOrder.slice(2)] }; listener(state); await wait();
    assert(Number(timers.size) === 0, 'A covered completion does not expire before it can be read');
    state = { ...state, notices: originalOrder }; listener(state); await wait();
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
    assert(launcher.textContent?.includes('1 recent'), 'Timed-out completions stay in recent history');
    launcher.click(); await wait();
    const next = host.querySelector<HTMLButtonElement>('[aria-label="Next notices"]'); assert(next, 'Remaining notices have navigation');
    assert(host.querySelectorAll('.app-notice').length === 2 && host.querySelector('.app-notices__pages')?.textContent?.includes('1 / 4'),
      'Expanded notices still show at most two cards and advance one notice at a time');
    next.click(); await wait();
    const questionAction = host.querySelector<HTMLButtonElement>('.is-question .app-notice__actions button'); assert(questionAction, 'Question must remain reachable');
    assert(host.querySelector('.is-question .app-notice__message')?.textContent === 'Should interrupted tasks resume automatically?'
      && host.querySelector('.is-question .app-notice__head')?.textContent?.startsWith('Waiting for you'), 'Questions show what the agent asked');
    assert(!questionAction.closest('.is-preview'), 'Paging brings the second card forward for interaction');
    rejectOpen = true; questionAction.click(); await wait();
    assert(host.querySelector('.is-question') && host.querySelector('[role="alert"]'), 'Failed navigation keeps the card available to retry');
    rejectOpen = false; questionAction.click(); await wait(); assert(opened[0] === 'q', 'Card opens its own source');
    assert(!host.querySelector('.is-question') && host.querySelector('.is-approval') && !host.querySelector('[role="alert"]'),
      'Opening a task closes only its card and clears the navigation error');
    const rect = host.querySelector<HTMLElement>('.app-notices')?.getBoundingClientRect();
    assert(rect && rect.width <= window.innerWidth && rect.height <= 600, 'Cards fit their native surface');
    assert(sizes.some(size => size.height > 100 && size.width === 340), 'Native host receives measured card height and full width');
    state = { ...state, notices: [make('q', 'QUESTION')], popupIds: [] }; listener(state); await wait();
    assert(!host.querySelector('.app-notice') && launcher.textContent?.includes('1 needs you'), 'An opened card stays closed after refresh and remains in the counter');
    launcher.click(); await wait();
    host.querySelector<HTMLButtonElement>('[aria-label="Close notices"]')?.click(); await wait();
    assert(host.querySelectorAll('.app-notice').length === 1 && sizes.at(-1)?.width === 340, 'One pending card stays visible after popups are dismissed');
    const lastClose = host.querySelector<HTMLButtonElement>('.app-notice__close'); assert(lastClose, 'The final visible card has a close button');
    rejectDismiss = true; lastClose.click(); await wait();
    assert(host.querySelector('.app-notice') && host.querySelector('[role="alert"]'), 'Failed dismissals keep the card visible');
    rejectDismiss = false; lastClose.click(); await wait();
    assert(!host.querySelector('.app-notices') && !host.querySelector('[role="alert"]') && sizes.at(-1)?.height === 0,
      'Closing the last card removes its counter, clears any previous error, and releases the native hit area');
    listener({ ...state }); await wait();
    assert(!host.querySelector('.app-notices'), 'Removed cards stay absent across refreshes');
    state = { ...state, notices: [make('last', 'TURN_FINISHED')], popupIds: ['last'] }; listener(state); await wait();
    assert(host.querySelector('.is-turn_finished') && Number(timers.size) === 0, 'New notices appear after older cards were closed and the final completion does not expire');
    state = { ...state, popupIds: [] }; listener(state); await wait();
    assert(host.querySelector('.is-turn_finished'), 'Recent completions remain visible even without popup IDs');
    host.querySelector<HTMLButtonElement>('.is-turn_finished .app-notice__actions button')?.click(); await wait();
    assert(opened.includes('last') && !host.querySelector('.app-notice'), 'Opening the final completion closes its card');
    host.querySelector<HTMLButtonElement>('.app-notices__launcher')?.click(); await wait();
    host.querySelector<HTMLButtonElement>('.app-notice__close')?.click(); await wait();
    assert(!host.querySelector('.app-notices') && sizes.at(-1)?.height === 0, 'Removing the last completion hides the expanded list and its counter');
    state = { ...state, notices: Array.from({ length: 10 }, (_, i) => make(`recent-${i}`, 'TURN_FINISHED')), popupIds: [] }; listener(state); await wait();
    for (let remaining = 10; remaining > 0; remaining--) {
      const counter = host.querySelector<HTMLButtonElement>('.app-notices__launcher'); assert(counter, 'Recent notices have a counter');
      assert(counter.textContent?.includes(`${remaining} recent`), 'Each closed completion reduces the recent count');
      counter.click(); await wait();
      assert(host.querySelector('.app-notices__pages')?.textContent?.includes(`1 / ${remaining}`) || remaining === 1, 'Reopening shows only remaining notices');
      host.querySelector<HTMLButtonElement>('.app-notice:not(.is-preview) .app-notice__close')?.click(); await wait();
      host.querySelector<HTMLButtonElement>('[aria-label="Close notices"]')?.click(); await wait();
    }
    assert(!host.querySelector('.app-notices') && sizes.at(-1)?.height === 0, 'All ten recent notices can be cleared');
    state = { ...state, notices: [make('viewed', 'TURN_FINISHED')], viewedIds: ['viewed'] }; listener(state); await wait();
    const viewedLauncher = host.querySelector<HTMLButtonElement>('.app-notices__launcher'); assert(viewedLauncher, 'Viewed notices remain in the counter');
    assert(!host.querySelector('.app-notice') && (sizes.at(-1)?.width ?? 340) < 280, 'The visible task never falls back to a persistent card');
    viewedLauncher.click(); await wait();
    assert(host.querySelector('.is-turn_finished'), 'Viewed notices can be explicitly reopened from the counter');
    host.querySelector<HTMLButtonElement>('[aria-label="Close notices"]')?.click(); await wait();
    listener({ ...state }); await wait();
    assert(!host.querySelector('.app-notice'), 'Viewed notices stay quiet after closing the list and refreshing');
    state = { ...state, notices: [...state.notices, make('unviewed', 'QUESTION')], popupIds: ['unviewed'] }; listener(state); await wait();
    assert(host.querySelectorAll('.app-notice').length === 1 && host.querySelector('.is-question'), 'Other tasks still notify while the viewed task stays quiet');
    state = { ...state, notices: [], popupIds: [], viewedIds: [] }; listener(state); await wait();
    const canvas = document.createElement('canvas'); canvas.width = 1; canvas.height = 1;
    const context = canvas.getContext('2d'); assert(context, 'Color sampling unavailable');
    const color = (css: string) => { context.fillStyle = css; context.fillRect(0, 0, 1, 1); return [...context.getImageData(0, 0, 1, 1).data].slice(0, 3); };
    const luminance = (rgb: number[]) => rgb.map(channel => channel / 255).map(channel => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4)
      .reduce((sum, channel, i) => sum + channel * [.2126, .7152, .0722][i], 0);
    const surface = document.createElement('span'); surface.style.backgroundColor = 'var(--bg-elevated)'; host.append(surface);
    for (const theme of ['light', 'dark']) {
      document.documentElement.style.colorScheme = theme;
      let previousDistance = 0;
      const neutral = color(getComputedStyle(surface).backgroundColor);
      for (const count of [0, 1, 3, 9, 50]) {
        state = { ...state, notices: Array.from({ length: count }, (_, i) => make(`question-${i}`, 'QUESTION')), popupIds: [] }; listener(state); await wait();
        const counter = host.querySelector<HTMLButtonElement>('.app-notices__launcher');
        if (count === 0) {
          assert(!counter && sizes.at(-1)?.height === 0, `${theme}: empty notices hide the counter`);
          continue;
        }
        assert(counter && counter.getAttribute('aria-expanded') === 'false' && (sizes.at(-1)?.height ?? 0) > 0, `${theme}: new notices restore the collapsed counter`);
        const style = getComputedStyle(counter); const background = color(style.backgroundColor);
        const distance = background.reduce((sum, value, i) => sum + Math.abs(value - neutral[i]), 0);
        assert(distance > previousDistance || count === 50 && distance === previousDistance,
          `${theme}: the launcher tint grows with the notice count`);
        const foregroundLight = luminance(color(style.color)); const backgroundLight = luminance(background);
        assert((Math.max(foregroundLight, backgroundLight) + .05) / (Math.min(foregroundLight, backgroundLight) + .05) >= 4.5,
          `${theme}: counter text stays readable at ${count} notices`);
        previousDistance = distance;
        assert(host.querySelectorAll('.app-notice').length === Math.min(2, count), `${theme}: pending notices show up to two stacked cards`);
      }
      const counter = host.querySelector<HTMLButtonElement>('.app-notices__launcher'); assert(counter, 'Pending notices have a counter');
      const amber = getComputedStyle(counter).backgroundColor;
      state = { ...state, notices: Array.from({ length: 50 }, (_, i) => make(`done-${i}`, 'TURN_FINISHED')) }; listener(state); await wait();
      assert(getComputedStyle(counter).backgroundColor !== amber, `${theme}: recent completions use a different tint from pending requests`);
    }
    const dismissalsBeforeFold = dismissed.length;
    const fold = host.querySelector<HTMLButtonElement>('[aria-label="Fold notifications"]'); assert(fold, 'The stack has a fold arrow');
    fold.click(); await wait();
    const edge = host.querySelector<HTMLButtonElement>('.app-notices__edge'); assert(edge, 'Folded notices keep an edge tab');
    assert(!host.querySelector('.app-notice') && edge.textContent === '50' && Number(timers.size) === 0, 'Folding hides all cards without expiring them');
    const foldedSize = sizes.at(-1);
    assert(foldedSize?.folded && foldedSize.width <= 40 && foldedSize.height <= 80, 'Folding releases the native input area except for the narrow tab');
    assert(edge.getAttribute('aria-label') === 'Show 50 notifications' && document.activeElement === edge, 'Keyboard focus moves to the named reopen control');
    state = { ...state, notices: [make('new-while-folded', 'QUESTION'), ...state.notices], popupIds: ['new-while-folded'] }; listener(state); await wait();
    assert(!host.querySelector('.app-notice') && Number(edge.textContent) === 51, 'New arrivals update the count without covering the input again');
    assert(dismissed.length === dismissalsBeforeFold, 'Folding does not dismiss notifications');
    edge.click(); await wait();
    assert(host.querySelector('.is-question:not(.is-preview)') && sizes.at(-1)?.width === 340 && !sizes.at(-1)?.folded, 'The edge tab restores the cards and navigation');
    host.querySelector<HTMLButtonElement>('[aria-label="Fold notifications"]')?.click(); await wait();
    assert(!host.querySelector('.app-notices__header') && !host.querySelector('.app-notices__pages'), 'Expanded navigation also folds completely');
    state = { ...state, notices: [], popupIds: [] }; listener(state); await wait();
    assert(!host.querySelector('.app-notices') && sizes.at(-1)?.height === 0, 'An empty folded stack disappears');
    state = { ...state, notices: [make('fresh-question', 'QUESTION')], popupIds: ['fresh-question'] };
    listener(state); await wait();
    assert(!host.querySelector('.app-notices__edge') && !sizes.at(-1)?.folded, 'A fresh batch appears normally after the folded stack empties');
    const question = host.querySelector('.is-question'); assert(question, 'The new task card appears');
    const openQuestion = question.querySelector<HTMLButtonElement>('.app-notice__actions button'); assert(openQuestion?.textContent === 'Reply', 'Questions navigate to their task');
    openQuestion.click(); await wait();
    assert(opened.includes('fresh-question') && !host.querySelector('.is-question'), 'Opening the task closes its card');
    state = { ...state, notices: [{ ...make('failed', 'FAILURE'), message: 'Rate limited by GitHub API' }], popupIds: ['failed'] }; listener(state); await wait();
    const failed = host.querySelector<HTMLElement>('.is-failure'); assert(failed, 'Failure card missing');
    assert(failed.querySelector('.app-notice__message')?.textContent === 'Rate limited by GitHub API'
      && failed.querySelector('.app-notice__actions button')?.textContent === 'View log', 'Failures show their reason and open the task log');
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
    root.unmount(); host.remove(); document.documentElement.style.colorScheme = ''; window.setTimeout = originalTimeout; window.clearTimeout = originalClear; Date.now = originalNow;
  }
}
