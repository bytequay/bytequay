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
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ACTION_CATALOG,
  filterCatalog,
  type ControlAction,
  type ControlDispatch,
} from './actionCatalog';

/** A tag the current page declares so the control bar can show
 *  "on #tag-1 #tag-2…" context above the input. Tags are
 *  read-only display today — typing a tag does not yet narrow the
 *  catalog; that's the next slice of the grammar. */
export type PageContextTag = {
  label: string;
  /** Optional kind hint used to colour the chip. Defaults to
   *  "scope" (purple). */
  kind?: 'scope' | 'entity' | 'state';
};

type Props = {
  open: boolean;
  onClose: () => void;
  /** Host handles the dispatch — this component stays free of nav
   *  state knowledge. App.tsx routes the dispatch into setNav. */
  onDispatch: (action: ControlDispatch) => void;
  /** Tags the current page registered. Renders above the input as
   *  "on #tag-1 #tag-2…" so the user sees what scope the next verb
   *  would resolve against. */
  contextTags?: PageContextTag[];
};

/** Phase-9 MVP control bar.
 *
 *  <p>⌘K (handled by the host) opens the overlay; typing filters
 *  the action catalog; arrow keys move the selection; Enter
 *  executes; Esc closes. Tag-chip slot above the input is plumbed
 *  for the page-element registry follow-up but renders empty here.
 *
 *  <p>Deliberately small surface: no AI section, no per-row
 *  ⌘1-9 shortcuts, no action preview, no undo, no command grammar
 *  parser. Those each get their own commit. */
function ControlBar({ open, onClose, onDispatch, contextTags }: Props) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const results = useMemo(() => filterCatalog(query), [query]);

  // Reset the bar between opens so the user starts from a clean
  // query / top selection on each ⌘K.
  useEffect(() => {
    if (open) {
      setQuery('');
      setSelected(0);
      // Autofocus the input — defer to the next frame so the
      // overlay's render finishes before we steal focus.
      const id = window.requestAnimationFrame(() => inputRef.current?.focus());
      return () => window.cancelAnimationFrame(id);
    }
    return undefined;
  }, [open]);

  // Clamp the selection if the result list shrinks past the cursor.
  useEffect(() => {
    if (selected >= results.length) {
      setSelected(Math.max(0, results.length - 1));
    }
  }, [results.length, selected]);

  if (!open) return null;

  const execute = (action: ControlAction) => {
    onDispatch(action.dispatch);
    onClose();
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelected(s => Math.min(s + 1, Math.max(0, results.length - 1)));
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelected(s => Math.max(0, s - 1));
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      const target = results[selected];
      if (target) execute(target);
    }
  };

  return (
    <div
      style={overlayStyle}
      role="presentation"
      onClick={onClose}
    >
      <div
        style={panelStyle}
        role="dialog"
        aria-modal="true"
        aria-label="Command bar"
        onClick={e => e.stopPropagation()}
        onKeyDown={handleKeyDown}
      >
        {contextTags && contextTags.length > 0 && (
          <div style={contextRowStyle}>
            <span style={contextLabelStyle}>on</span>
            {contextTags.map((tag, i) => (
              <span key={i} style={contextChipStyle(tag.kind ?? 'scope')}>
                #{tag.label}
              </span>
            ))}
          </div>
        )}
        <div style={inputRowStyle}>
          <span style={inputIconStyle} aria-hidden>⌘K</span>
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder=":go trunks · :open memory · :create trunk …"
            style={inputStyle}
            aria-label="Command bar input"
            aria-controls="control-bar-results"
            aria-activedescendant={
                results[selected] ? `control-bar-result-${results[selected].id}` : undefined}
          />
          <span style={escHintStyle}>Esc to close</span>
        </div>
        {query.trim().length === 0 && (
          <div style={verbHintsRowStyle}>
            <span style={verbHintLabelStyle}>verbs</span>
            <button
              type="button"
              style={verbChipStyle}
              onClick={() => setQuery(':go ')}
            >
              :go
            </button>
            <button
              type="button"
              style={verbChipStyle}
              onClick={() => setQuery(':open ')}
            >
              :open
            </button>
            <button
              type="button"
              style={verbChipStyle}
              onClick={() => setQuery(':create ')}
            >
              :create
            </button>
          </div>
        )}

        <ResultList
          results={results}
          selected={selected}
          onSelect={i => setSelected(i)}
          onExecute={execute}
        />

        <footer style={footerStyle}>
          <span style={footerHintStyle}>↑↓ navigate</span>
          <span style={footerHintStyle}>⏎ execute</span>
          <span style={footerHintStyle}>esc close</span>
          <span style={{ flex: 1 }} />
          <span style={footerSummonStyle}>⌘K to summon</span>
        </footer>
      </div>
    </div>
  );
}

function ResultList({ results, selected, onSelect, onExecute }: {
  results: ControlAction[];
  selected: number;
  onSelect: (index: number) => void;
  onExecute: (action: ControlAction) => void;
}) {
  if (results.length === 0) {
    return (
      <div style={emptyStyle}>
        No commands match. Try a different word, or open{' '}
        <button
          type="button"
          style={emptyLinkStyle}
          onClick={() => onExecute(ACTION_CATALOG[0])}
        >
          Workspace home →
        </button>
      </div>
    );
  }
  return (
    <ul id="control-bar-results" role="listbox" style={listStyle}>
      {results.map((action, i) => (
        <li
          key={action.id}
          id={`control-bar-result-${action.id}`}
          role="option"
          aria-selected={i === selected}
          style={rowStyle(i === selected)}
          onMouseEnter={() => onSelect(i)}
          onClick={() => onExecute(action)}
        >
          <span style={rowIconStyle(action.source)}>{action.icon}</span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={rowLabelStyle}>{action.label}</div>
            <div style={rowDescStyle}>{action.description}</div>
          </div>
          <span style={rowSourceStyle}>{action.source}</span>
        </li>
      ))}
    </ul>
  );
}

/* ── styles ────────────────────────────────────────────────── */

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(31, 27, 46, 0.22)',
  backdropFilter: 'blur(4px)',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'center',
  paddingTop: '12vh',
  zIndex: 120,
};

const panelStyle: React.CSSProperties = {
  width: 560,
  maxWidth: 'calc(100vw - 40px)',
  background: 'rgba(255, 255, 255, 0.97)',
  border: '1px solid var(--border)',
  borderRadius: 14,
  boxShadow: '0 20px 60px rgba(31, 35, 40, 0.16), 0 4px 12px rgba(0,0,0,0.08)',
  padding: 0,
  overflow: 'hidden',
  color: 'var(--text-1)',
};

const inputRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '12px 14px',
  borderBottom: '1px solid var(--border)',
};

const inputIconStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--accent)',
  padding: '3px 7px',
  background: 'var(--accent-a7)',
  borderRadius: 5,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const inputStyle: React.CSSProperties = {
  flex: 1,
  border: 'none',
  outline: 'none',
  background: 'transparent',
  fontSize: 14,
  fontFamily: 'inherit',
  color: 'var(--text-1)',
};

const escHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const contextRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '10px 14px 0',
  flexWrap: 'wrap',
};

const contextLabelStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  fontWeight: 600,
};

function contextChipStyle(kind: 'scope' | 'entity' | 'state'): React.CSSProperties {
  // Three colours so the chip kind is visible at a glance: scope is
  // purple (workspace / section), entity is blue (a specific thread
  // / PR / repo), state is orange (RUNNING / AWAITING / etc.).
  const palette = kind === 'entity'
      ? { bg: 'rgba(0, 102, 204, 0.10)', fg: '#0050a0', border: 'rgba(0, 102, 204, 0.25)' }
      : kind === 'state'
          ? { bg: 'rgba(217, 119, 6, 0.10)', fg: '#a55c00', border: 'rgba(217, 119, 6, 0.25)' }
          : { bg: 'var(--accent-a10)', fg: 'var(--accent-deep)', border: 'var(--accent-border)' };
  return {
    fontSize: 11,
    fontFamily: 'ui-monospace, SFMono-Regular, monospace',
    padding: '2px 8px',
    borderRadius: 999,
    background: palette.bg,
    color: palette.fg,
    border: `1px solid ${palette.border}`,
  };
}

const verbHintsRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 14px 0',
};

const verbHintLabelStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
};

const verbChipStyle: React.CSSProperties = {
  padding: '3px 9px',
  fontSize: 11,
  fontWeight: 600,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  border: '1px solid var(--accent-border)',
  borderRadius: 999,
  background: 'var(--accent-a7)',
  color: 'var(--accent-deep)',
  cursor: 'pointer',
};

const listStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: '6px 0',
  maxHeight: 360,
  overflowY: 'auto',
};

function rowStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '8px 14px',
    cursor: 'pointer',
    background: active ? 'var(--bg-hover)' : 'transparent',
    transition: 'background 140ms ease',
  };
}

function rowIconStyle(source: 'navigation' | 'create'): React.CSSProperties {
  const color = source === 'create' ? '#16a34a' : 'var(--accent)';
  return {
    width: 26,
    height: 26,
    borderRadius: 7,
    background: source === 'create' ? 'rgba(22, 163, 74, 0.12)' : 'var(--accent-a10)',
    color,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 14,
    fontWeight: 700,
    flexShrink: 0,
  };
}

const rowLabelStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const rowDescStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  marginTop: 2,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const rowSourceStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
  padding: '2px 6px',
  borderRadius: 4,
  background: 'var(--bg-hover)',
  flexShrink: 0,
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  padding: '8px 14px',
  borderTop: '1px solid var(--border)',
  background: 'var(--bg-elevated)',
};

const footerHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const footerSummonStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--accent)',
  fontWeight: 600,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const emptyStyle: React.CSSProperties = {
  padding: 22,
  textAlign: 'center',
  color: 'var(--text-3)',
  fontSize: 13,
};

const emptyLinkStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--accent)',
  cursor: 'pointer',
  fontSize: 13,
  fontWeight: 600,
};

export default ControlBar;
