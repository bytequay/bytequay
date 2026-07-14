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
import { createPortal } from 'react-dom';

const MAX_MATCHES = 6;

/** One entry in the composer's slash menu. `run` fires when the user picks
 *  it; the hook clears the typed "/token" from the input first. */
export type SlashCommand = { name: string; desc: string; run: () => void };

/**
 * Slash-command autocomplete for a controlled `<textarea>` — the `/`-menu
 * twin of {@link useMentions}. A command only triggers when the caret sits
 * in a "/token" anchored to the very start of the input (so a "/" mid-message
 * never opens it), matching how chat clients gate slash commands.
 *
 * Wire it up like useMentions: spread `onChange` / `onKeyDown` / `onClick`
 * onto the textarea (same `textareaRef`) and render `dropdown` inside a
 * `position: relative` container. Inert (plain passthrough) when `commands`
 * is empty.
 */
export function useSlashCommands(opts: {
  value: string;
  onChange: (next: string) => void;
  commands: SlashCommand[];
  textareaRef: RefObject<HTMLTextAreaElement | null>;
}): {
  onChange: (e: ChangeEvent<HTMLTextAreaElement>) => void;
  /** Returns true when it consumed the key (menu navigation / run), so the
   *  caller skips its own Enter handling. */
  onKeyDown: (e: KeyboardEvent<HTMLTextAreaElement>) => boolean;
  onClick: (e: MouseEvent<HTMLTextAreaElement>) => void;
  dropdown: ReactNode;
  active: boolean;
} {
  const { value, onChange, commands, textareaRef } = opts;
  // `slash` is non-null when the caret sits in a start-anchored "/token".
  const [slash, setSlash] = useState<{ query: string } | null>(null);
  const [idx, setIdx] = useState(0);

  const matches = slash === null ? [] : commands
    .filter(c => c.name.toLowerCase().startsWith(slash.query.toLowerCase()))
    .slice(0, MAX_MATCHES);
  const show = slash !== null && matches.length > 0;

  // Only a "/word" that starts at position 0 and runs to the caret counts —
  // the whole prefix must be the command, no leading text and no space yet.
  const sync = (text: string, caret: number) => {
    const before = text.slice(0, caret);
    const m = before.match(/^\/([A-Za-z0-9-]*)$/);
    setSlash(m === null ? null : { query: m[1] });
    setIdx(0);
  };

  const run = (cmd: SlashCommand) => {
    if (slash === null) return;
    // Drop the "/token" the caret is in; whatever follows the caret stays.
    const after = value.slice(1 + slash.query.length);
    onChange(after);
    setSlash(null);
    cmd.run();
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (ta !== null) {
        ta.focus();
        ta.setSelectionRange(0, 0);
      }
    });
  };

  const onChangeHandler = (e: ChangeEvent<HTMLTextAreaElement>) => {
    onChange(e.target.value);
    sync(e.target.value, e.target.selectionStart ?? e.target.value.length);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (!show) return false;
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
      run(matches[idx]);
    }
    else if (e.key === 'Escape') {
      e.preventDefault();
      setSlash(null);
    }
    else {
      return false;
    }
    return true;
  };

  const onClick = (e: MouseEvent<HTMLTextAreaElement>) => {
    sync(e.currentTarget.value, e.currentTarget.selectionStart ?? 0);
  };

  // Portaled to <body> and fixed above the textarea: the composer sits at
  // the bottom of a scroll column whose ancestors clip an in-flow popover,
  // so it floats free like the pill's picker. Rect read at render — the
  // composer is pinned, so re-reading each keystroke keeps it glued.
  const rect = show ? textareaRef.current?.getBoundingClientRect() ?? null : null;
  const dropdown = rect === null ? null : createPortal(
    <ul style={floatStyle(rect)} role="listbox" aria-label="Slash commands">
      {matches.map((c, i) => (
        <li key={c.name} role="option" aria-selected={i === idx}>
          <button
            type="button"
            style={itemStyle(i === idx)}
            // mousedown (not click) so the textarea keeps its caret.
            onMouseDown={(e) => { e.preventDefault(); run(c); }}
            onMouseEnter={() => setIdx(i)}
          >
            <span style={{ fontWeight: 600 }}>/{c.name}</span>
            <span style={{ opacity: 0.75, marginLeft: 10 }}>{c.desc}</span>
          </button>
        </li>
      ))}
    </ul>,
    document.body,
  );

  return { onChange: onChangeHandler, onKeyDown, onClick, dropdown, active: show };
}

// Fixed, bottom-anchored just above the textarea so the menu grows upward
// and spans the composer's width.
function floatStyle(rect: DOMRect): CSSProperties {
  return {
    position: 'fixed',
    left: rect.left,
    bottom: window.innerHeight - rect.top + 6,
    width: rect.width,
    maxHeight: 260,
    overflowY: 'auto',
    zIndex: 1000,
    listStyle: 'none',
    padding: 4,
    margin: 0,
    background: 'var(--bg-elev, #fff)',
    border: '1px solid var(--border-mid, #d0d7de)',
    borderRadius: 10,
    boxShadow: '0 12px 32px rgba(0,0,0,0.16)',
  };
}

function itemStyle(active: boolean): CSSProperties {
  return {
    display: 'block',
    width: '100%',
    textAlign: 'left',
    padding: '6px 10px',
    fontSize: 13,
    borderRadius: 6,
    border: 'none',
    cursor: 'pointer',
    background: active ? 'var(--accent, #2563eb)' : 'transparent',
    color: active ? '#fff' : 'var(--text-1, inherit)',
  };
}
