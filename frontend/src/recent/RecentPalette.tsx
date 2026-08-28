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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { RecentChangeDto, RecentRowDto, RecentWordHitDto } from '../types';
import { relativeTime } from '../relativeTime';
import { highlightParts, roleTone, rowMarkdown, TYPE_GLYPH } from './model';
import '../css/recent.css';

type Mode = 'items' | 'words';

const PAGE_LIMIT = 10;

/**
 * Scrolls the pull-request view to the comment the user last wrote in. The
 * detail loads asynchronously after the route changes, so this waits for the
 * node rather than threading a focus prop through the whole detail tree.
 */
export function scrollToWrittenText(textId: string | null) {
  const githubId = textId?.match(/(\d+)$/)?.[1];
  if (!githubId) return;
  const deadline = Date.now() + 4000;
  const attempt = () => {
    const anchor = document.getElementById(`pr-activity-${githubId}`);
    if (anchor) {
      anchor.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    if (Date.now() < deadline) window.setTimeout(attempt, 150);
  };
  window.setTimeout(attempt, 150);
}

export default function RecentPalette({
  onClose,
  onOpenRow,
  onOpenWordHit,
}: {
  onClose: () => void;
  onOpenRow: (row: RecentRowDto) => void;
  onOpenWordHit: (hit: RecentWordHitDto) => void;
}) {
  const [mode, setMode] = useState<Mode>('items');
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<RecentRowDto[]>([]);
  const [hits, setHits] = useState<RecentWordHitDto[]>([]);
  const [changes, setChanges] = useState<Record<string, RecentChangeDto>>({});
  const [active, setActive] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const request = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => { inputRef.current?.focus(); }, []);

  useEffect(() => {
    window.bridge.fetchRecentChanges()
      .then(list => setChanges(Object.fromEntries(list.map(change => [change.itemKey, change]))))
      // The dots are the one signal that leans on a GitHub refresh having
      // happened. Losing them must never blank the stream.
      .catch(() => setChanges({}));
  }, []);

  useEffect(() => {
    const current = ++request.current;
    const run = async (): Promise<void> => {
      if (mode === 'items') {
        const result = await window.bridge.searchRecent(query, PAGE_LIMIT);
        if (request.current === current) setRows(result);
        return;
      }
      const result = await window.bridge.searchRecentWords(query, PAGE_LIMIT);
      if (request.current === current) setHits(result);
    };
    void run()
      .catch(() => {
        // An unreachable backend leaves the last result on screen rather than
        // replacing it with an error the palette has no room to explain.
      })
      .finally(() => {
        if (request.current === current) setLoaded(true);
      });
    setActive(0);
  }, [mode, query]);

  // Rows with a dot sort ahead of the rest: something moved, or something wants you.
  const ordered = useMemo(() => {
    if (mode !== 'items') return rows;
    const flagged = (row: RecentRowDto): number => {
      const change = changes[row.itemKey];
      return change && (change.moved || change.wantsYou) ? 0 : 1;
    };
    return [...rows].sort((left, right) => flagged(left) - flagged(right));
  }, [changes, mode, rows]);

  const count = mode === 'items' ? ordered.length : hits.length;

  useEffect(() => {
    listRef.current?.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' });
  }, [active, count]);

  const copy = useCallback((value: string) => {
    void navigator.clipboard.writeText(value);
    onClose();
  }, [onClose]);

  const handleKey = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'Tab') {
      event.preventDefault();
      setMode(current => (current === 'items' ? 'words' : 'items'));
      return;
    }
    if (event.key === 'ArrowDown' || (event.key === 'n' && event.ctrlKey)) {
      event.preventDefault();
      setActive(index => (count === 0 ? 0 : (index + 1) % count));
      return;
    }
    if (event.key === 'ArrowUp' || (event.key === 'p' && event.ctrlKey)) {
      event.preventDefault();
      setActive(index => (count === 0 ? 0 : (index - 1 + count) % count));
      return;
    }
    const enter = event.key === 'Enter';
    const copyKey = event.metaKey && event.key.toLowerCase() === 'c';
    if (!enter && !copyKey) return;

    if (mode === 'items') {
      const row = ordered[active];
      if (!row) return;
      event.preventDefault();
      // The copy paths never navigate: the usual errand is getting the text
      // back without losing your place.
      if (copyKey && event.shiftKey) return copy(row.snippet ?? rowMarkdown(row));
      if (copyKey) return copy(row.htmlUrl ?? rowMarkdown(row));
      onOpenRow(row);
      return;
    }
    const hit = hits[active];
    if (!hit) return;
    event.preventDefault();
    // Words mode inverts it: Enter copies, and going there is the deliberate act.
    if (enter && event.metaKey) {
      onOpenWordHit(hit);
      return;
    }
    if (enter) copy(hit.body);
  };

  return (
    <div className="rc-scrim" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <div className="rc-palette" role="dialog" aria-label="Recent" aria-modal="true" onKeyDown={handleKey}>
        <div className="rc-palette__head">
          <span aria-hidden="true">⌕</span>
          <input
            ref={inputRef}
            className="rc-palette__input"
            value={query}
            placeholder={mode === 'items' ? 'Type to narrow…' : 'Search what you wrote…'}
            aria-label={mode === 'items' ? 'Narrow recent items' : 'Search your own words'}
            onChange={event => setQuery(event.target.value)}
          />
          <span className="rc-modes" role="tablist">
            <button type="button" role="tab" aria-selected={mode === 'items'}
              className={mode === 'items' ? 'is-active' : ''} onClick={() => setMode('items')}>
              {mode === 'items' ? 'Items' : 'Items ⇥'}
            </button>
            <button type="button" role="tab" aria-selected={mode === 'words'}
              className={mode === 'words' ? 'is-active' : ''} onClick={() => setMode('words')}>
              {mode === 'words' ? 'Words' : 'Words ⇥'}
            </button>
          </span>
        </div>
        <div className="rc-palette__list" ref={listRef}>
          {mode === 'items' && ordered.map((row, index) => (
            <ItemRow
              key={row.id}
              row={row}
              change={changes[row.itemKey]}
              active={index === active}
              onHover={() => setActive(index)}
              onOpen={() => onOpenRow(row)}
            />
          ))}
          {mode === 'words' && hits.map((hit, index) => (
            <WordRow
              key={hit.id}
              hit={hit}
              active={index === active}
              onHover={() => setActive(index)}
              onCopy={() => copy(hit.body)}
            />
          ))}
          {loaded && count === 0 && (
            <div className="rc-palette__empty">
              {mode === 'items'
                ? (query ? 'Nothing touched matches that.' : 'Nothing touched in the last two weeks.')
                : (query ? 'You did not write that anywhere.' : 'Type to search what you wrote.')}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ItemRow({ row, change, active, onHover, onOpen }: {
  row: RecentRowDto;
  change: RecentChangeDto | undefined;
  active: boolean;
  onHover: () => void;
  onOpen: () => void;
}) {
  const dot = change?.moved ? 'is-moved' : change?.wantsYou ? 'is-wants' : null;
  return (
    <button type="button" className={`rc-item${active ? ' is-active' : ''}`}
      onMouseMove={onHover} onClick={onOpen}>
      <span className="rc-item__line">
        <span className={`rc-glyph is-${roleTone(row)}`} aria-hidden="true">{TYPE_GLYPH[row.type]}</span>
        <span className="rc-item__title">{row.title}</span>
        {dot && <span className={`rc-dot ${dot}`} aria-hidden="true" />}
      </span>
      {/* Metadata only on the active row — the list has to stay scannable. */}
      {active && (
        <span className="rc-item__meta">
          {row.number !== null && <code>#{row.number}</code>}
          {row.number !== null && <span className="rc-row__sep">·</span>}
          {row.reasonLabel.toLowerCase()} {relativeTime(row.touchedAt)}
          {change?.summary && <span className="rc-row__sep">·</span>}
          {change?.summary && <span className="rc-changed">{change.summary.replace(' since you read it', '')}</span>}
        </span>
      )}
    </button>
  );
}

function WordRow({ hit, active, onHover, onCopy }: {
  hit: RecentWordHitDto;
  active: boolean;
  onHover: () => void;
  onCopy: () => void;
}) {
  return (
    <button type="button" className={`rc-word${active ? ' is-active' : ''}`}
      onMouseMove={onHover} onClick={onCopy}>
      <span className="rc-word__sentence">
        “{highlightParts(hit.sentence, hit.highlights).map((part, index) => (
          part.match ? <mark key={index}>{part.text}</mark> : <span key={index}>{part.text}</span>
        ))}”
      </span>
      <span className="rc-word__meta">
        {hit.neverPosted && <span className="rc-badge is-amber">Never posted</span>}
        {!hit.neverPosted && <span>{hit.kindLabel}</span>}
        {!hit.neverPosted && <span className="rc-row__sep">·</span>}
        {hit.number !== null && <code>#{hit.number}</code>}
        {hit.number !== null && <span className="rc-row__sep">·</span>}
        <span>{relativeTime(hit.writtenAt)}</span>
      </span>
    </button>
  );
}
