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
import { flushSync } from 'react-dom';
import InAppBrowser from '../InAppBrowser';
import { FocusBreakScreen } from '../focus/FocusBreakScreen';
import { CAT_ARRIVAL_SECONDS } from '../focus/BreakCompanion';
import type { InAppBrowserTab } from '../types';
import { assert, button, runScenarios, settle, until } from './harness';
import { createFixtureRoot } from './reactFixture';

declare global { interface Window { pressBrowserModalEscape: () => Promise<void> } }

function mount() {
  const view = createFixtureRoot();
  const mounts: string[] = [];
  const focusRestorations: boolean[] = [];
  let surface: string | null = null;
  let backs = 0;
  let boundsUpdates = 0;
  let breakShown = false;
  let currentTab = 'first';
  let searches = 0;
  const errors: string[] = [];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    mountInAppBrowser: async (id: string, _bounds: unknown, allowFocusRestore = true) => {
      surface = id; mounts.push(id); focusRestorations.push(allowFocusRestore);
    },
    unmountInAppBrowser: async (id: string) => { if (surface === id) surface = null; },
    setInAppBrowserBounds: async () => { boundsUpdates++; },
    onFindResult: () => () => {},
    onInAppBrowserFind: () => () => {},
    stopFindInPage: async () => undefined,
    findInPage: async () => { searches++; },
  } });
  const onError = (message: string | null) => { if (message !== null) errors.push(message); };
  const onBack = () => { backs++; };
  const render = (id: string) => {
    currentTab = id;
    const tab: InAppBrowserTab = {
      id, url: 'https://example.com', title: 'Example', canGoBack: false, canGoForward: false, loading: false,
      pinned: false, name: null, favicon: null, error: null,
    };
    flushSync(() => view.root.render(<>
      <InAppBrowser tab={tab} fullScreen railExpanded={false} error={null} onError={onError} onBack={onBack} />
      {breakShown && <FocusBreakScreen companion="chubby-cat" remainingSeconds={60} exiting={false} onLocked={() => {}} onExited={() => {}} onDismiss={() => { breakShown = false; render(currentTab); }} />}
    </>));
  };
  render('first');
  return { ...view, render, mounts, focusRestorations, errors, surface: () => surface, backs: () => backs, boundsUpdates: () => boundsUpdates,
    showBreak: () => { breakShown = true; render(currentTab); }, searches: () => searches };
}

async function untilCatArrives(root: ParentNode) {
  const arrived = () => {
    const video = root.querySelector<HTMLVideoElement>('.break-companion__video:not(.break-companion__leave-video)');
    return video !== null && video.currentTime >= CAT_ARRIVAL_SECONDS && root.querySelector('dialog')?.matches(':modal') === true;
  };
  const expires = performance.now() + 12_000;
  while (!arrived() && performance.now() < expires) await settle();
  assert(arrived(), 'The decoded cat walk reaches arrival before locking the app');
}

export async function run() {
  return runScenarios([
    ['detaches for a native modal, preserves Escape and restores the current tab', async () => {
      const view = mount();
      const dialog = document.createElement('dialog');
      dialog.setAttribute('role', 'dialog');
      dialog.setAttribute('aria-modal', 'true');
      dialog.innerHTML = '<button>Finish break</button>';
      document.body.append(dialog);
      try {
        await settle();
        assert(view.surface() === 'first', 'A closed modal must leave the browser mounted');
        dialog.showModal();
        const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        dialog.dispatchEvent(escape);
        assert(!escape.defaultPrevented && view.backs() === 0, 'Escape belongs to the modal even before its mutation observer runs');
        await settle();
        assert(view.surface() === null, 'A modal must detach the native page that would cover its controls');
        window.dispatchEvent(new Event('resize'));
        await settle();
        assert(view.mounts.join() === 'first', 'Resizing while covered must not remount the page');
        dialog.close();
        await settle();
        assert(view.surface() === 'first' && view.mounts.join() === 'first,first', 'Closing restores the same tab without navigating it');
        dialog.showModal();
        await settle();
        view.render('second');
        await settle();
        assert(view.surface() === null, 'Changing tabs while covered must keep the native page detached');
        dialog.close();
        await settle();
        assert(view.surface() === 'second', 'Closing restores the currently selected tab');
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }));
        assert(view.backs() === 1 && view.errors.length === 0, 'Browser Escape works again after the modal closes');
      }
      finally { dialog.remove(); view.dispose(); }
    }],
    ['ignores underlying navigation layers and follows visible custom modals', async () => {
      const view = mount();
      const layer = document.createElement('section');
      layer.setAttribute('role', 'dialog');
      layer.setAttribute('aria-modal', 'true');
      layer.setAttribute('data-navigation-layer', '');
      const dialog = document.createElement('dialog');
      dialog.setAttribute('aria-modal', 'true');
      const modal = document.createElement('section');
      modal.setAttribute('role', 'dialog');
      modal.setAttribute('aria-modal', 'true');
      modal.hidden = true;
      document.body.append(layer, dialog, modal);
      dialog.show();
      try {
        await settle();
        assert(view.surface() === 'first', 'Non-modal navigation layers and hidden modals must leave the browser visible');
        const updates = view.boundsUpdates();
        layer.textContent = 'An agent streamed more output';
        layer.style.color = 'blue';
        await settle();
        assert(view.boundsUpdates() === updates, 'Unrelated body mutations must not send native layout requests');
        modal.hidden = false;
        await settle();
        assert(view.surface() === null, 'A visible custom modal must hide the native page');
        modal.hidden = true;
        await settle();
        assert(view.surface() === 'first', 'Hiding a custom modal restores the same browser');
        modal.hidden = false;
        await settle();
        view.dispose();
        modal.remove();
        await settle();
        assert(view.surface() === null && view.mounts.join() === 'first,first', 'Unmounted browsers must not return when a modal leaves');
      }
      finally { layer.remove(); dialog.remove(); modal.remove(); if (view.host.isConnected) view.dispose(); }
    }],
    ['leaves browser Find open through confirmed cat dismissal', async () => {
      const view = mount();
      try {
        await settle();
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', metaKey: true, cancelable: true }));
        await settle();
        assert(view.host.querySelector('.find-bar') !== null, 'Browser Find opens before the break');
        const find = view.host.querySelector<HTMLInputElement>('.find-bar input');
        assert(find !== null, 'Browser Find has an editable input');
        find.focus();
        view.showBreak();
        await settle();
        assert(view.host.querySelector('dialog')?.matches(':modal') === false, 'The walk starts without locking the app');
        await untilCatArrives(view.host);
        const dismiss = button('Dismiss', view.host);
        assert(view.surface() === null && document.activeElement === dismiss, 'The locked break focuses Dismiss with the native page detached');
        const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
        dismiss.dispatchEvent(enter);
        assert(!enter.defaultPrevented && view.searches() === 0, 'Browser Find must leave Enter to the cat controls');
        await window.pressBrowserModalEscape();
        await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'Escape asks before dismissing the cat');
        assert(view.host.querySelector('.find-bar') !== null && view.backs() === 0 && view.surface() === null, 'The browser remains covered while confirmation is open');
        button('End break', view.host).click(); await settle();
        await until(() => view.host.querySelector('dialog') === null, 'Confirming dismisses the cat');
        assert(view.host.querySelector('.find-bar') !== null && view.backs() === 0 && view.surface() === 'first',
          'Dismissing the cat preserves both browser Find and the selected page');
        assert(view.focusRestorations.at(-1) === false && document.activeElement === find,
          'A Find input focused before entrance keeps focus when the browser returns');
      }
      finally { view.dispose(); }
    }],
    ['preserves a DOM focus choice made while the companion walks in', async () => {
      const view = mount();
      const input = document.createElement('input');
      document.body.append(input);
      try {
        await settle();
        assert(view.focusRestorations.join() === 'true', 'Normal browser navigation still focuses its native page');
        view.showBreak();
        await settle();
        assert(view.surface() === null && view.host.querySelector('[data-focus-break-entrance]') !== null,
          'The native page leaves room for the nonmodal entrance');
        input.focus();
        await untilCatArrives(view.host);
        button('Dismiss', view.host).click(); await settle();
        await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'Dismiss asks before ending the break');
        button('End break', view.host).click(); await settle();
        await until(() => view.host.querySelector('dialog') === null, 'Confirmed dismissal ends the break');
        assert(view.surface() === 'first' && view.focusRestorations.at(-1) === false && document.activeElement === input,
          'Remounting the same page must not steal the control chosen during entrance');
      }
      finally { input.remove(); view.dispose(); }
    }],
  ]);
}
