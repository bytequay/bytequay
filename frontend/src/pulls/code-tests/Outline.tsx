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
import { useCallback, useEffect, useRef, useState } from 'react';
import { Braces, Code, Search, X } from 'lucide-react';
import { fileName, navigableSymbols, symbolGroup, symbolLabel, testClassName } from './codeModel';
import type { CodeGraphAvailability, LocalRunDto, ReviewCodeSymbolDto, ReviewCodeSymbolsDto } from './contracts';
import RunButton, { type RunTarget } from './RunButton';
import type { RunGate } from './runModel';
import { graphNotice } from './SymbolPalette';

export type OutlineState = { generation: number } & ({ status: 'loading' } | { status: 'loaded'; result: ReviewCodeSymbolsDto });

/** Per-file outlines, re-read in the background when the file list moves to a newer generation. */
// ponytail: about ten minutes of backoff, then the next open or expand asks again.
const OUTLINE_RETRIES = 40;

export function useOutlines(read: (path: string) => Promise<ReviewCodeSymbolsDto>, generation: number) {
  const [outlines, setOutlines] = useState<ReadonlyMap<string, OutlineState>>(new Map());
  const current = useRef(outlines);
  current.current = outlines;
  const inFlight = useRef(new Set<string>());
  const retries = useRef(0);
  const request = useCallback((path: string) => {
    const existing = current.current.get(path);
    // Only a read in flight or a READY answer is final; failures retry on the next open or expand.
    if (inFlight.current.has(path) || existing?.generation === generation && (existing.status === 'loading' || existing.result.status === 'READY')) return;
    // A retry keeps showing its BUSY or UPDATING note instead of flashing back to loading.
    if (!(existing?.status === 'loaded' && existing.generation === generation)) {
      const loading: OutlineState = { status: 'loading', generation };
      current.current = new Map(current.current).set(path, existing?.status === 'loaded' && existing.result.status === 'READY' ? { ...existing, generation } : loading);
      setOutlines(current.current);
    }
    inFlight.current.add(path);
    void read(path)
      .then(result => setOutlines(map => new Map(map).set(path, { status: 'loaded', result, generation })))
      .catch(() => setOutlines(map => new Map(map).set(path, { status: 'loaded', result: { status: 'UNAVAILABLE', symbols: [] }, generation })))
      .finally(() => inFlight.current.delete(path));
  }, [generation, read]);

  // BUSY and UPDATING pass once the agent releases the index or its update finishes, so the outline and run markers fill in by themselves.
  useEffect(() => {
    const waiting = [...outlines]
      .filter(([, state]) => state.status === 'loaded' && state.generation === generation && (state.result.status === 'BUSY' || state.result.status === 'UPDATING'))
      .map(([path]) => path);
    if (waiting.length === 0) {
      retries.current = 0;
      return;
    }
    if (retries.current >= OUTLINE_RETRIES) return;
    const timer = setTimeout(() => {
      retries.current++;
      waiting.forEach(request);
    }, Math.min(2_000 * 2 ** retries.current, 15_000));
    return () => clearTimeout(timer);
  }, [generation, outlines, request]);

  return { outlines, request };
}

/**
 * Closes a popover on an outside press or Escape. Escape listens on document so it runs before the window listener that
 * closes a zoomed or overlaid pull request, which then sees the event handled.
 *
 * @param withTrigger treat the popover's parent, which holds its toggle button, as inside, so the toggle can close it
 */
export function usePopoverDismiss(onClose: () => void, withTrigger = false) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const close = (event: PointerEvent) => {
      const boundary = withTrigger ? host.current?.parentElement : host.current;
      if (!boundary?.contains(event.target as Node)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      onClose();
    };
    window.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      window.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [onClose, withTrigger]);
  return host;
}

export function OutlinePopover({ path, outline, codeGraph, currentLine, onOpen, onClose, runs, gate, onRun }: {
  path: string;
  outline: OutlineState | undefined;
  codeGraph: CodeGraphAvailability;
  currentLine: number;
  onOpen: (line: number) => void;
  onClose: () => void;
  runs: readonly LocalRunDto[];
  gate: RunGate;
  onRun: (target: RunTarget) => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  const [filter, setFilter] = useState('');
  const loaded = outline?.status === 'loaded' ? outline.result : null;
  const declared = loaded === null ? [] : navigableSymbols(loaded.symbols).filter(symbol => symbol.path === path);
  const needle = filter.trim().toLowerCase();
  const members = needle === '' ? declared : declared.filter(symbol => symbol.name.toLowerCase().includes(needle));
  const notice = graphNotice(codeGraph, loaded?.status ?? null);
  return (
    <div className="ct-popover ct-outline" ref={host} role="dialog" aria-label={`Outline of ${fileName(path)}`}>
      {notice === null && loaded !== null && declared.length > 0 && (
        // ponytail: Enter takes the first match; a filtered list is short enough to click through.
        <label className="ct-search ct-outline__filter">
          <Search size={12} strokeWidth={1.8} aria-hidden="true" />
          <input type="search" autoFocus value={filter} placeholder="Filter types and methods"
            aria-label={`Filter the types and methods of ${fileName(path)}`}
            onChange={event => setFilter(event.target.value)}
            onKeyDown={event => {
              if (event.key !== 'Enter' || members.length === 0) return;
              event.preventDefault();
              onOpen(members[0].startLine);
            }} />
        </label>
      )}
      {notice !== null
        ? <div className="ct-popover__empty">{notice}</div>
        : loaded === null
          ? <div className="ct-popover__empty" role="status">Reading the outline…</div>
          : members.length === 0
            ? <div className="ct-popover__empty">{needle === '' ? 'No types or methods in this file.' : `No type or method matches “${filter.trim()}”`}</div>
            : (
              <ul>
                {members.map((symbol, index) => (
                  <li key={`${symbol.startLine}:${symbol.name}`} className="ct-outline__item">
                    {symbol.test && <RunButton target={{ path, className: testClassName(path), method: symbol.name }} runs={runs} gate={gate} onRun={onRun} />}
                    <button type="button" className={`ct-outline__row${symbol.startLine === currentLine ? ' is-current' : ''}${needle !== '' && index === 0 ? ' is-active' : ''}`}
                      onClick={() => onOpen(symbol.startLine)}>
                      {symbolGroup(symbol) === 'type'
                        ? <Braces className="ct-outline__icon is-type" size={12} strokeWidth={1.8} aria-hidden="true" />
                        : <Code className={`ct-outline__icon${symbol.test ? ' is-test' : ''}`} size={12} strokeWidth={1.8} aria-hidden="true" />}
                      <span className="ct-outline__name">{symbolLabel(symbol)}</span>
                      <span className="ct-outline__line">{symbol.startLine}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
    </div>
  );
}

export type ChooserState = { name: string; options: ReviewCodeSymbolDto[]; truncated: boolean; x: number; y: number };

export function SymbolChooser({ chooser, changedPaths, onOpen, onClose }: {
  chooser: ChooserState;
  changedPaths: ReadonlySet<string>;
  onOpen: (symbol: ReviewCodeSymbolDto) => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose);
  // A ⌘-click low in the window must still show rows: the popover keeps a usable height, stays inside the window, and scrolls its list.
  const top = Math.max(8, Math.min(chooser.y + 14, window.innerHeight - 320));
  return (
    <div className="ct-popover ct-chooser" ref={host} role="dialog" aria-label={`Definitions of ${chooser.name}`}
      style={{ left: Math.max(8, Math.min(chooser.x, window.innerWidth - 450)), top, maxHeight: window.innerHeight - top - 12 }}>
      <header className="ct-chooser__header">
        <span>{chooser.truncated && 'The first '}{chooser.options.length} possible definitions for <code>{chooser.name}</code></span>
        <button type="button" className="ct-icon-button" aria-label="Close" onClick={onClose}><X size={13} strokeWidth={1.8} aria-hidden="true" /></button>
      </header>
      <p className="ct-chooser__caveat">
        CodeGraph matched this name structurally. These are candidates, not proof of the call target at runtime.
      </p>
      <ul>
        {chooser.options.map(symbol => (
          <li key={`${symbol.path}:${symbol.startLine}`}>
            <button type="button" className="ct-chooser__row" onClick={() => onOpen(symbol)}>
              <span className="ct-chooser__signature">{symbol.qualifiedName ?? symbol.signature ?? symbolLabel(symbol)}</span>
              <span className="ct-chooser__where">
                {fileName(symbol.path)}:{symbol.startLine} · {changedPaths.has(symbol.path) ? 'changed in this PR' : 'unchanged'}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
