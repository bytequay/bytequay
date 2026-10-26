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
import { useEffect, useRef, useState } from 'react';
import type { FindResult } from './types';

const NO_MATCHES: FindResult = { activeMatchOrdinal: 0, matches: 0 };

export function isFindShortcut(
  event: Pick<KeyboardEvent, 'altKey' | 'ctrlKey' | 'key' | 'metaKey'>,
): boolean {
  return event.key === 'f' && !event.altKey && (event.metaKey || event.ctrlKey);
}

/** Whether a key pressed here belongs to some other editor on the page. */
function editingElsewhere(target: EventTarget | null, own: HTMLElement | null): boolean {
  if (!(target instanceof HTMLElement) || target === own) return false;
  return target.isContentEditable || target.tagName === 'INPUT' || target.tagName === 'TEXTAREA';
}

/**
 * Chromium's own find-in-page drives the search, so a match inside a nested
 * scroller is highlighted and scrolled into view. It only sees rendered text:
 * a collapsed diff file has no rows in the DOM and cannot match.
 */
export default function FindBar({ browserTabId }: { browserTabId?: string }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [searched, setSearched] = useState('');
  const [result, setResult] = useState(NO_MATCHES);
  const inputRef = useRef<HTMLInputElement>(null);

  // Searching runs only on Enter or an arrow, never per keystroke: scanning a
  // large diff blocks the renderer long enough to stall typing.
  const search = (forward = true) => {
    setSearched(text);
    if (text === '') {
      setResult(NO_MATCHES);
      void window.bridge.stopFindInPage(browserTabId);
      return;
    }
    // `findNext` opens a finding session rather than stepping within one: the
    // first request for a query must send true, and every step after it false.
    // False with no session open is dropped, and backward needs an open one.
    void window.bridge.findInPage(text, { forward, findNext: text !== searched }, browserTabId);
  };

  const close = () => {
    void window.bridge.stopFindInPage(browserTabId);
    setSearched('');
    setResult(NO_MATCHES);
    setOpen(false);
  };

  useEffect(() => window.bridge.onFindResult(next => {
    if (next.tabId === browserTabId) setResult(next);
  }), [browserTabId]);

  useEffect(() => {
    if (browserTabId === undefined) return;
    return window.bridge.onInAppBrowserFind(id => {
      if (id !== browserTabId) return;
      setOpen(true);
      inputRef.current?.select();
    });
  }, [browserTabId]);

  // Stepping is driven from the window, not the input: each search moves the
  // page selection and blurs the bar, and refocusing it would re-anchor the
  // search back to the top.
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Browser Find listens in capture, before a modal can stop keys meant for its own controls.
      if ((isFindShortcut(event) || event.key === 'Enter' || event.key === 'Escape')
        && [...document.querySelectorAll('dialog:modal, [aria-modal="true"]:not(dialog):not([data-navigation-layer])')].some(dialog => dialog.checkVisibility())) return;
      // The code editor searches its own document, because find-in-page only sees the lines it has rendered.
      if (isFindShortcut(event) && !event.defaultPrevented) {
        event.preventDefault();
        setOpen(true);
        inputRef.current?.select();
        return;
      }
      if (!open || (event.key !== 'Enter' && event.key !== 'Escape')) return;
      if (editingElsewhere(event.target, inputRef.current)) return;
      // The zoomed pull-request pane exits on any Escape it still sees.
      event.preventDefault();
      if (event.key === 'Escape') close();
      else search(!event.shiftKey);
    };
    // Browser Escape must close Find before the surrounding browser handles it.
    const capture = browserTabId !== undefined;
    window.addEventListener('keydown', handleKeyDown, capture);
    return () => window.removeEventListener('keydown', handleKeyDown, capture);
  });

  useEffect(() => {
    if (open) inputRef.current?.select();
  }, [open]);

  useEffect(() => () => { void window.bridge.stopFindInPage(browserTabId); }, [browserTabId]);

  if (!open) return null;

  return (
    <div className="find-bar" role="search">
      <input
        ref={inputRef}
        className="find-bar__input"
        type="text"
        value={text}
        placeholder="Find in page"
        aria-label="Find in page"
        onChange={event => setText(event.target.value)}
      />
      <span className="find-bar__count" aria-live="polite">
        {text === '' || text !== searched ? '' : `${result.activeMatchOrdinal}/${result.matches}`}
      </span>
      <button type="button" onMouseDown={event => event.preventDefault()}
        aria-label="Previous match" title="Previous match (Shift-Enter)"
        onClick={() => search(false)}>↑</button>
      <button type="button" onMouseDown={event => event.preventDefault()}
        aria-label="Next match" title="Next match (Enter)"
        onClick={() => search()}>↓</button>
      <button type="button" aria-label="Close find" title="Close (Esc)" onClick={close}>✕</button>
    </div>
  );
}
