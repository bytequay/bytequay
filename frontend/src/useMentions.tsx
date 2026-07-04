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
import {
  useState,
  type ChangeEvent, type CSSProperties, type KeyboardEvent, type MouseEvent,
  type ReactNode, type RefObject,
} from 'react';

const MAX_MATCHES = 6;

/**
 * GitHub-style @mention autocomplete for a controlled `<textarea>`. Detects
 * an "@token" the caret sits in, offers matching logins from `candidates`,
 * and inserts the pick as "@login ". Shared by every markdown composer so
 * the review-submit box, the PR comment box, and inline comments all get
 * the same picker.
 *
 * Wire it up: spread the returned `onChange` / `onKeyDown` / `onClick` onto
 * the textarea (pass it the same `textareaRef`), and render `dropdown` as a
 * sibling inside a `position: relative` container. Inert (no dropdown, plain
 * passthrough) when `candidates` is empty/undefined.
 */
export function useMentions(opts: {
  value: string;
  onChange: (next: string) => void;
  candidates: string[] | undefined;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
}): {
  onChange: (e: ChangeEvent<HTMLTextAreaElement>) => void;
  /** Returns true when it consumed the key (dropdown navigation), so the
   *  caller can skip its own Enter/Tab handling. */
  onKeyDown: (e: KeyboardEvent<HTMLTextAreaElement>) => boolean;
  onClick: (e: MouseEvent<HTMLTextAreaElement>) => void;
  dropdown: ReactNode;
  active: boolean;
} {
  const { value, onChange, candidates, textareaRef } = opts;
  // `mention` is non-null when the caret sits in an "@token"; `idx` is the
  // highlighted row.
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [idx, setIdx] = useState(0);

  const matches = mention === null ? [] : (candidates ?? [])
    .filter(c => c.toLowerCase().startsWith(mention.query.toLowerCase()))
    .slice(0, MAX_MATCHES);
  const show = mention !== null && matches.length > 0;

  // A mention starts at an "@" that follows the line start or a separator,
  // so an email-style "a@b" never triggers it.
  const sync = (text: string, caret: number) => {
    const before = text.slice(0, caret);
    const m = before.match(/(?:^|[\s([{])@([A-Za-z0-9-]*)$/);
    setMention(m === null ? null : { start: caret - m[1].length - 1, query: m[1] });
    setIdx(0);
  };

  const insert = (login: string) => {
    if (mention === null) {
      return;
    }
    const before = value.slice(0, mention.start);
    const after = value.slice(mention.start + 1 + mention.query.length);
    const next = `${before}@${login} ${after}`;
    const caret = before.length + login.length + 2; // past "@login "
    onChange(next);
    setMention(null);
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (ta !== null) {
        ta.focus();
        ta.setSelectionRange(caret, caret);
      }
    });
  };

  const onChangeHandler = (e: ChangeEvent<HTMLTextAreaElement>) => {
    onChange(e.target.value);
    sync(e.target.value, e.target.selectionStart ?? e.target.value.length);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (!show) {
      return false;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setIdx(i => (i + 1) % matches.length);
    }
    else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setIdx(i => (i - 1 + matches.length) % matches.length);
    }
    else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      insert(matches[idx]);
    }
    else if (e.key === 'Escape') {
      e.preventDefault();
      setMention(null);
    }
    else {
      return false;
    }
    return true;
  };

  const onClick = (e: MouseEvent<HTMLTextAreaElement>) => {
    sync(e.currentTarget.value, e.currentTarget.selectionStart ?? 0);
  };

  const dropdown = show ? (
    <ul style={listStyle} role="listbox" aria-label="Mention a user">
      {matches.map((c, i) => (
        <li key={c} role="option" aria-selected={i === idx}>
          <button
            type="button"
            style={itemStyle(i === idx)}
            // mousedown (not click) so the textarea doesn't blur and lose
            // its caret before we insert.
            onMouseDown={(e) => { e.preventDefault(); insert(c); }}
            onMouseEnter={() => setIdx(i)}
          >
            @{c}
          </button>
        </li>
      ))}
    </ul>
  ) : null;

  return { onChange: onChangeHandler, onKeyDown, onClick, dropdown, active: show };
}

const listStyle: CSSProperties = {
  position: 'absolute',
  top: '100%',
  left: 0,
  marginTop: 4,
  minWidth: 220,
  maxHeight: 220,
  overflowY: 'auto',
  zIndex: 30,
  listStyle: 'none',
  padding: 4,
  background: 'var(--bg-1, #fff)',
  border: '1px solid var(--border, #d0d7de)',
  borderRadius: 8,
  boxShadow: '0 6px 22px rgba(0,0,0,0.14)',
};

function itemStyle(active: boolean): CSSProperties {
  return {
    display: 'block',
    width: '100%',
    textAlign: 'left',
    padding: '5px 10px',
    fontSize: 13,
    borderRadius: 6,
    border: 'none',
    cursor: 'pointer',
    background: active ? 'var(--accent, #2563eb)' : 'transparent',
    color: active ? '#fff' : 'var(--text-1, inherit)',
  };
}
