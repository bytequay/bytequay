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
import { useEffect, useRef, useState, type CSSProperties } from 'react';

declare global {
  interface Window {
    /** Chromium's non-standard in-page find, not in the TS DOM lib.
     *  find(query, caseSensitive, backwards, wrapAround, wholeWord, searchInFrames, showDialog) */
    find(
      query: string,
      caseSensitive?: boolean,
      backwards?: boolean,
      wrapAround?: boolean,
      wholeWord?: boolean,
      searchInFrames?: boolean,
      showDialog?: boolean,
    ): boolean;
  }
}

interface FindBarProps {
  open: boolean;
  onClose: () => void;
}

/**
 * In-page find for the main app window. Electron ships no browser find UI,
 * so this is a minimal bar over Chromium's native `window.find`: type to
 * jump to the first match, Enter / Shift+Enter (or the arrows) to step
 * next / previous, Esc to close. `window.find` walks the rendered DOM, so
 * this searches whatever the renderer is showing — it does not reach into
 * embedded GitHub WebContentsViews (those are separate webContents).
 *
 * ponytail: window.find, no match counter or highlight-all. Move to the
 * Electron `findInPage` IPC path if the user needs counts / all-matches.
 */
export default function FindBar({ open, onClose }: FindBarProps): React.JSX.Element | null {
  const [query, setQuery] = useState('');
  const [noMatch, setNoMatch] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Focus + select whenever the bar opens so a re-press of ⌘F over an
  // existing query lets the user retype straight away.
  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [open]);

  if (!open) {
    return null;
  }

  const runFind = (value: string, backwards: boolean, fromTop: boolean) => {
    if (value === '') {
      setNoMatch(false);
      return;
    }
    // Restart from the top of the document on each incremental keystroke;
    // for next/prev keep the current selection so find continues from it.
    if (fromTop) {
      window.getSelection()?.removeAllRanges();
    }
    // find(query, caseSensitive, backwards, wrapAround, wholeWord, searchInFrames, showDialog)
    const found = window.find(value, false, backwards, true, false, false, false);
    setNoMatch(!found);
  };

  const close = () => {
    window.getSelection()?.removeAllRanges();
    onClose();
  };

  return (
    <div style={barStyle} role="search">
      <input
        ref={inputRef}
        type="text"
        value={query}
        placeholder="Find in page"
        aria-label="Find in page"
        style={{ ...inputStyle, color: noMatch ? '#c0392b' : '#1a1a1a' }}
        onChange={e => {
          const v = e.target.value;
          setQuery(v);
          runFind(v, false, true);
        }}
        onKeyDown={e => {
          if (e.key === 'Enter') {
            e.preventDefault();
            runFind(query, e.shiftKey, false);
          }
          else if (e.key === 'Escape') {
            e.preventDefault();
            close();
          }
        }}
      />
      <button type="button" aria-label="Previous match" style={btnStyle} onClick={() => runFind(query, true, false)}>↑</button>
      <button type="button" aria-label="Next match" style={btnStyle} onClick={() => runFind(query, false, false)}>↓</button>
      <button type="button" aria-label="Close find" style={btnStyle} onClick={close}>✕</button>
    </div>
  );
}

const barStyle: CSSProperties = {
  position: 'fixed',
  top: 12,
  right: 16,
  zIndex: 10000,
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  padding: '6px 8px',
  background: '#fff',
  border: '1px solid #d0d0d0',
  borderRadius: 8,
  boxShadow: '0 4px 16px rgba(0,0,0,0.18)',
};

const inputStyle: CSSProperties = {
  border: 'none',
  outline: 'none',
  fontSize: 13,
  width: 200,
  background: 'transparent',
};

const btnStyle: CSSProperties = {
  border: 'none',
  background: 'transparent',
  cursor: 'pointer',
  fontSize: 13,
  lineHeight: 1,
  padding: '2px 6px',
  color: '#555',
};
