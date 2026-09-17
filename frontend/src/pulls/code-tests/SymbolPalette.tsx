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
import { Braces, Code, File, Search } from 'lucide-react';
import { fileName, navigableSymbols, paletteFiles, symbolGroup, symbolLabel } from './codeModel';
import type { CodeGraphAvailability, CodeSymbolStatus, ReviewCodeFileEntryDto, ReviewCodeSymbolDto, ReviewCodeSymbolsDto } from './contracts';

type Row = { kind: 'file'; file: ReviewCodeFileEntryDto } | { kind: 'symbol'; symbol: ReviewCodeSymbolDto };

const FILE_ROWS = 6;
const SYMBOL_ROWS = 12;

export function graphNotice(availability: CodeGraphAvailability, status: CodeSymbolStatus | null): string | null {
  if (availability === 'OFF') return 'CodeGraph is off for this working copy — symbol search and symbol links are unavailable. File names still work.';
  if (availability === 'UNAVAILABLE' || status === 'UNAVAILABLE') {
    return 'CodeGraph index unavailable — symbol search and symbol links are off. File names still work.';
  }
  if (status === 'BUSY') return 'CodeGraph is busy with the agent — symbol results will come back shortly.';
  if (status === 'UPDATING') return 'CodeGraph is updating its index — symbol results will come back shortly.';
  return null;
}

export default function SymbolPalette({ files, query, onQueryChange, codeGraph, search, onOpen, focusToken }: {
  files: readonly ReviewCodeFileEntryDto[];
  query: string;
  onQueryChange: (query: string) => void;
  codeGraph: CodeGraphAvailability;
  search: (query: string) => Promise<ReviewCodeSymbolsDto>;
  onOpen: (path: string, line: number) => void;
  /** Changes when ⌘P asks for the field. */
  focusToken: number;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [symbols, setSymbols] = useState<{ query: string; result: ReviewCodeSymbolsDto } | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const host = useRef<HTMLDivElement>(null);
  const needle = query.trim();

  useEffect(() => {
    if (focusToken === 0) return;
    input.current?.focus();
    input.current?.select();
    setOpen(true);
  }, [focusToken]);

  useEffect(() => {
    if (!open || codeGraph !== 'READY' || needle.length < 2) return;
    let live = true;
    const timer = setTimeout(() => {
      void search(needle)
        .then(result => { if (live) setSymbols({ query: needle, result }); })
        .catch(() => { if (live) setSymbols({ query: needle, result: { status: 'UNAVAILABLE', symbols: [] } }); });
    }, 180);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [codeGraph, needle, open, search]);

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => {
      if (!host.current?.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener('pointerdown', close);
    return () => window.removeEventListener('pointerdown', close);
  }, [open]);

  const rows = useMemo<Row[]>(() => {
    const symbolRows = symbols?.query === needle && symbols.result.status === 'READY'
      ? navigableSymbols(symbols.result.symbols).slice(0, SYMBOL_ROWS).map(symbol => ({ kind: 'symbol' as const, symbol }))
      : [];
    return [...paletteFiles(files, needle, FILE_ROWS).map(file => ({ kind: 'file' as const, file })), ...symbolRows];
  }, [files, needle, symbols]);

  useEffect(() => setActive(0), [needle]);

  const choose = (row: Row | undefined) => {
    if (row === undefined) return;
    if (row.kind === 'file') onOpen(row.file.path, 0);
    else onOpen(row.symbol.path, row.symbol.startLine);
    onQueryChange('');
    setOpen(false);
    input.current?.blur();
  };

  const status = symbols?.query === needle ? symbols.result.status : null;
  const notice = needle.length >= 2 ? graphNotice(codeGraph, status) : codeGraph === 'READY' ? null : graphNotice(codeGraph, null);

  return (
    <div className="ct-palette" ref={host}>
      <label className={`ct-search${open ? ' is-open' : ''}`}>
        <Search size={13} strokeWidth={1.8} aria-hidden="true" />
        <input
          ref={input}
          type="search"
          value={query}
          placeholder="Go to file or symbol"
          aria-label="Go to file or symbol"
          aria-expanded={open}
          aria-controls="ct-palette-results"
          role="combobox"
          aria-autocomplete="list"
          onFocus={() => setOpen(true)}
          onChange={event => {
            onQueryChange(event.target.value);
            setOpen(true);
          }}
          onKeyDown={event => {
            if (event.key === 'ArrowDown') {
              event.preventDefault();
              setActive(index => Math.min(rows.length - 1, index + 1));
            }
            else if (event.key === 'ArrowUp') {
              event.preventDefault();
              setActive(index => Math.max(0, index - 1));
            }
            else if (event.key === 'Enter') {
              event.preventDefault();
              choose(rows[active]);
            }
            else if (event.key === 'Escape') {
              event.preventDefault();
              onQueryChange('');
              setOpen(false);
              event.currentTarget.blur();
            }
          }} />
        <kbd>⌘P</kbd>
      </label>
      {open && needle !== '' && (
        <div className="ct-popover ct-palette__popover">
          <ul id="ct-palette-results" role="listbox" aria-label="Files and symbols">
            {rows.map((row, index) => {
              const group = row.kind === 'file' ? 'file' : symbolGroup(row.symbol);
              const path = row.kind === 'file' ? row.file.path : row.symbol.path;
              return (
                <li key={row.kind === 'file' ? `file:${path}` : `symbol:${path}:${row.symbol.startLine}:${row.symbol.name}`}
                  role="option" aria-selected={index === active}
                  className={`ct-palette__row${index === active ? ' is-active' : ''}`}
                  onMouseEnter={() => setActive(index)}
                  onMouseDown={event => {
                    event.preventDefault();
                    choose(row);
                  }}>
                  {group === 'file'
                    ? <File className="ct-palette__icon" size={14} strokeWidth={1.7} aria-hidden="true" />
                    : group === 'type'
                      ? <Braces className="ct-palette__icon is-type" size={14} strokeWidth={1.7} aria-hidden="true" />
                      : <Code className="ct-palette__icon is-method" size={14} strokeWidth={1.7} aria-hidden="true" />}
                  <span className="ct-palette__name">{row.kind === 'file' ? fileName(path) : symbolLabel(row.symbol)}</span>
                  <span className="ct-palette__where">{row.kind === 'file' ? path.slice(0, Math.max(0, path.lastIndexOf('/'))) : `${fileName(path)}:${row.symbol.startLine}`}</span>
                </li>
              );
            })}
          </ul>
          {rows.length === 0 && <div className="ct-popover__empty">No file or symbol matches “{needle}”</div>}
          <footer className="ct-popover__footer">
            <span>{notice ?? (rows.length === 0 ? 'files, types and methods' : `${rows.length} ${rows.length === 1 ? 'match' : 'matches'}`)}</span>
            <button type="button" onMouseDown={event => {
              event.preventDefault();
              onQueryChange('');
              setOpen(false);
            }}>esc</button>
          </footer>
        </div>
      )}
    </div>
  );
}
