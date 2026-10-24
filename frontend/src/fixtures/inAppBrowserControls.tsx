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
import type { Bridge, FindResult, InAppBrowserTab } from '../types';
import { assert, button, changeInput, element, runScenarios, settle, until } from './harness';
import { createFixtureRoot } from './reactFixture';
import '../index.css';

const page: InAppBrowserTab = {
  id: 'docs', url: 'https://example.com/docs', title: 'Docs', name: null, favicon: null,
  pinned: false, canGoBack: false, canGoForward: false, loading: false, error: null,
};
type Bounds = Parameters<Bridge['mountInAppBrowser']>[1];

function mount(initial: InAppBrowserTab) {
  const fixture = createFixtureRoot();
  let tab = initial;
  let back = 0;
  let bounds: Bounds | undefined;
  let findRequested: (id: string) => void = () => {};
  let found: (result: FindResult) => void = () => {};
  const searches: Parameters<Bridge['findInPage']>[] = [];
  const stopped: (string | undefined)[] = [];
  const navigated: [string, string][] = [];
  const onBack = () => { back++; };
  const onError = (message: string | null) => { if (message !== null) throw new Error(message); };
  const render = (next: InAppBrowserTab) => {
    tab = next;
    flushSync(() => fixture.root.render(<InAppBrowser tab={tab} fullScreen railExpanded={false} error={null} onError={onError} onBack={onBack} />));
  };
  const bridge = {
    mountInAppBrowser: async (_id, value) => { bounds = value; },
    setInAppBrowserBounds: async (_id, value) => { bounds = value; },
    unmountInAppBrowser: async () => {},
    onInAppBrowserFind: listener => { findRequested = listener; return () => { findRequested = () => {}; }; },
    onFindResult: listener => { found = listener; return () => { found = () => {}; }; },
    findInPage: async (...args) => { searches.push(args); },
    stopFindInPage: async id => { stopped.push(id); },
    inAppNavigate: async (id, url) => { navigated.push([id, url]); render({ ...tab, url, title: 'Docs' }); },
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.localStorage.setItem('bytequay.browser-display-mode', 'fullscreen');
  render(tab);
  return {
    render, dispose: fixture.dispose, searches, stopped, navigated,
    requestFind: (id: string) => findRequested(id),
    found: (result: FindResult) => found(result),
    bounds: () => bounds,
    back: () => back,
  };
}

function key(target: EventTarget, value: string, options: KeyboardEventInit = {}) {
  target.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }));
}

async function browserFind() {
  const browser = mount(page);
  try {
    await until(() => browser.bounds() !== undefined, 'The web page has native bounds');
    const initial = browser.bounds();
    assert(initial !== undefined, 'Initial bounds exist');
    key(window, 'f', { metaKey: true });
    await until(() => document.querySelector('[aria-label="Find in page"]') !== null, 'Cmd-F opens browser Find');
    const input = element<HTMLInputElement>('[aria-label="Find in page"]');
    assert(document.activeElement === input, 'Find receives keyboard focus');
    await until(() => (browser.bounds()?.y ?? 0) > initial.y, 'The native page moves below Find');
    const findBottom = element('.find-bar').getBoundingClientRect().bottom;
    const resized = browser.bounds();
    assert(resized !== undefined && resized.y >= findBottom && resized.height < initial.height, 'Find remains above the resized native surface');

    changeInput(input, 'needle');
    await settle();
    key(input, 'Enter');
    await settle();
    assert(JSON.stringify(browser.searches[0]) === JSON.stringify(['needle', { forward: true, findNext: true }, page.id]), 'Enter searches the active browser tab');
    browser.found({ tabId: page.id, activeMatchOrdinal: 1, matches: 3 });
    await until(() => element('.find-bar__count').textContent === '1/3', 'The active page reports its match count');
    browser.found({ tabId: 'other', activeMatchOrdinal: 99, matches: 99 });
    browser.found({ activeMatchOrdinal: 88, matches: 88 });
    await settle();
    assert(element('.find-bar__count').textContent === '1/3', 'App and other-tab search results are ignored');
    button('Next match').click();
    await settle();
    button('Previous match').click();
    await settle();
    assert(JSON.stringify(browser.searches.slice(1)) === JSON.stringify([
      ['needle', { forward: true, findNext: false }, page.id], ['needle', { forward: false, findNext: false }, page.id],
    ]), 'Next and previous step through the browser search');
    key(input, 'Escape');
    await until(() => document.querySelector('.find-bar') === null, 'Escape closes Find');
    assert(browser.back() === 0 && browser.stopped.includes(page.id), 'Closing Find clears browser matches without leaving the page');
    await until(() => browser.bounds()?.y === initial.y, 'Closing Find restores the native page height');

    browser.requestFind(page.id);
    await until(() => document.querySelector('.find-bar') !== null, 'A shortcut from the native page opens Find');
    const stopsBeforeNavigation = browser.stopped.length;
    browser.render({ ...page, url: 'https://example.com/next-document' });
    await settle();
    assert(document.querySelector('.find-bar') === null && browser.stopped.length > stopsBeforeNavigation, 'Navigation in the same tab clears the old search');
    browser.requestFind(page.id);
    await until(() => document.querySelector('.find-bar') !== null, 'The new document can open Find');
    assert(element<HTMLInputElement>('[aria-label="Find in page"]').value === '', 'A new document starts a fresh query');
    browser.render({ ...page, id: 'second', url: 'https://example.com/second' });
    await settle();
    assert(document.querySelector('.find-bar') === null, 'Switching pages closes the old search');
    browser.requestFind(page.id);
    await settle();
    assert(document.querySelector('.find-bar') === null, 'A stale shortcut cannot search another page');
    browser.requestFind('second');
    await until(() => document.querySelector('.find-bar') !== null, 'The new page has its own Find');
    assert(element<HTMLInputElement>('[aria-label="Find in page"]').value === '', 'The old query does not leak into another tab');
  }
  finally { browser.dispose(); }
}

async function blankPage() {
  const browser = mount({ ...page, id: 'new', url: '', title: 'New page' });
  try {
    await settle();
    const address = element<HTMLInputElement>('[aria-label="Web address"]');
    assert(document.activeElement === address && address.value === '', 'A new page focuses an empty address field');
    assert(element('.inapp-browser__slot').textContent?.includes('New page') ?? false, 'A new page has a local start prompt');
    for (const label of ['Reload', 'Pin page', 'Pop out page', 'Open in default browser']) assert(button(label).disabled, `${label} waits for a web address`);
    changeInput(address, 'example.com/docs');
    await settle();
    element<HTMLFormElement>('[aria-label="Open web page"]').requestSubmit();
    await until(() => browser.navigated.length === 1, 'Submitting the address opens this tab');
    assert(JSON.stringify(browser.navigated[0]) === JSON.stringify(['new', 'https://example.com/docs']), 'Navigation keeps the new tab and normalizes its address');
    assert(!button('Reload').disabled && !button('Pin page').disabled, 'Page controls become available after navigation');
  }
  finally { browser.dispose(); }
}

export const run = () => runScenarios([
  ['Browser Find targets its page, stays above the native surface, and handles Escape and tab changes', browserFind],
  ['New pages focus the address field and enable page controls after navigation', blankPage],
]);
