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
import { History, MessageSquare, Search, SquareTerminal } from 'lucide-react';
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import '../css/recent.css';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { PullRequestStateIcon, pullRequestState } from '../pulls/PullRequestStateIcon';
import type { RecentChangeDto, RecentRowDto, RecentWordHitDto } from '../types';
import { useModalDialog } from '../useModalDialog';
import { groupForPalette, highlightParts, isoDay, rememberSearch, roleTone, rowMarkdown, TYPE_ICON, type PaletteGroup } from './model';

type Mode = 'items' | 'words';
type Scope = 'ALL' | RecentWordHitDto['kind'];

const ITEM_LIMIT = 10;
// The backend's cap. Scope counts come from this one fetch, so they top out here.
const WORD_LIMIT = 50;
const SEARCHES_KEY = 'bytequay.recent.searches';

const SCOPES: Array<[Scope, string]> = [
  ['ALL', 'All'],
  ['COMMENT', 'Comments'],
  ['REVIEW_BODY', 'Reviews'],
  ['TASK_PROMPT', 'Task prompts'],
  ['DRAFT', 'Drafts'],
];

const STATE_LABEL = { open: 'Open', merged: 'Merged', closed: 'Closed', draft: 'Draft', queued: 'Queued' } as const;

function readSearches(): string[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(SEARCHES_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * Scrolls the pull-request view to the comment the user last wrote in. The
 * detail loads asynchronously after it zooms in, so this waits for the node
 * rather than threading a focus prop through the whole detail tree.
 */
export function scrollToWrittenText(textId: string | null) {
  const githubId = textId?.match(/(\d+)$/)?.[1];
  if (!githubId) return;
  const deadline = Date.now() + 4000;
  const attempt = () => {
    // The zoomed pull request can sit over a page showing the same one; the top layer comes last.
    const anchors = document.querySelectorAll(`#pr-activity-${githubId}`);
    const anchor = anchors[anchors.length - 1];
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
  const [scope, setScope] = useState<Scope>('ALL');
  const [rows, setRows] = useState<RecentRowDto[]>([]);
  const [hits, setHits] = useState<RecentWordHitDto[]>([]);
  const [changes, setChanges] = useState<Record<string, RecentChangeDto>>({});
  const [searches] = useState(readSearches);
  const [searchCounts, setSearchCounts] = useState<Record<string, number>>({});
  const [active, setActive] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const request = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const searching = query.trim() !== '';
  const idle = mode === 'words' && !searching;

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
        const result = await window.bridge.searchRecent(query, ITEM_LIMIT);
        if (request.current === current) setRows(result);
        return;
      }
      const result = await window.bridge.searchRecentWords(query, WORD_LIMIT);
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

  // Counts are re-run rather than stored, so a remembered search never shows a stale number.
  useEffect(() => {
    if (!idle || searches.length === 0) return;
    let live = true;
    void Promise.all(searches.map(text => window.bridge.searchRecentWords(text, WORD_LIMIT).then(found => [text, found.length] as const)))
      .then(entries => { if (live) setSearchCounts(Object.fromEntries(entries)); })
      .catch(() => {});
    return () => { live = false; };
  }, [idle, searches]);

  const today = isoDay(new Date());

  const itemEntries = useMemo(() => groupForPalette(rows, row => row.touchedAt, today), [rows, today]);

  // Before you type, a reply to the previous query may still be on its way; it must not be openable.
  const wordEntries = useMemo(
    () => (searching ? groupForPalette(scope === 'ALL' ? hits : hits.filter(hit => hit.kind === scope), hit => hit.writtenAt, today) : []),
    [hits, scope, searching, today],
  );

  const count = mode === 'items' ? itemEntries.length : wordEntries.length;

  useEffect(() => {
    listRef.current?.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' });
  }, [active, count]);

  const copy = useCallback((value: string) => {
    void navigator.clipboard.writeText(value);
    onClose();
  }, [onClose]);

  const remember = () => {
    try {
      window.localStorage.setItem(SEARCHES_KEY, JSON.stringify(rememberSearch(searches, query)));
    } catch {
      // Recent searches are a convenience; a blocked store just leaves the list as it was.
    }
  };
  const openHit = (hit: RecentWordHitDto) => {
    remember();
    onOpenWordHit(hit);
  };
  const copyHit = (hit: RecentWordHitDto) => {
    remember();
    copy(hit.body);
  };

  const dialog = useModalDialog(inputRef);
  const handleKey = (event: React.KeyboardEvent) => {
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
      const row = itemEntries[active]?.item;
      if (!row) return;
      event.preventDefault();
      // The copy paths never navigate: the usual errand is getting the text
      // back without losing your place.
      if (copyKey && event.shiftKey) return copy(row.snippet ?? rowMarkdown(row));
      if (copyKey) return copy(row.htmlUrl ?? rowMarkdown(row));
      onOpenRow(row);
      return;
    }
    const hit = wordEntries[active]?.item;
    if (!hit) return;
    event.preventDefault();
    if (copyKey) return copyHit(hit);
    openHit(hit);
  };

  const activeHit = mode === 'words' ? wordEntries[active]?.item : undefined;
  const scopeCount = (key: Scope): number => (key === 'ALL' ? hits.length : hits.filter(hit => hit.kind === key).length);

  return (
    <dialog ref={dialog} className="rc-palette" onCancel={event => { event.preventDefault(); onClose(); }} onMouseDown={event => {
      if (event.target === event.currentTarget) onClose();
    }} role="dialog" aria-label="Recent" aria-modal="true" onKeyDown={handleKey}>
        <div className="rc-palette__head">
          <Search size={16} aria-hidden="true" />
          <input
            ref={inputRef}
            className="rc-palette__input"
            value={query}
            placeholder={mode === 'items' ? 'Search recent items…' : 'Search what you wrote…'}
            aria-label={mode === 'items' ? 'Narrow recent items' : 'Search your own words'}
            onChange={event => setQuery(event.target.value)}
          />
          <span className="rc-modes" role="tablist">
            <button type="button" role="tab" aria-selected={mode === 'items'}
              className={mode === 'items' ? 'is-active' : ''} onClick={() => setMode('items')}>Items</button>
            <button type="button" role="tab" aria-selected={mode === 'words'}
              className={mode === 'words' ? 'is-active' : ''} onClick={() => setMode('words')}>Words</button>
          </span>
        </div>
        {mode === 'words' && (
          <div className="rc-scopes">
            {SCOPES.map(([key, label]) => {
              const found = searching ? scopeCount(key) : 0;
              return (
                <button key={key} type="button" className={`rc-scope${scope === key ? ' is-active' : ''}`} aria-pressed={scope === key}
                  onClick={() => { setScope(key); setActive(0); }}>
                  {label}
                  {found > 0 && <span className="rc-scope__count">{found === WORD_LIMIT ? `${found}+` : found}</span>}
                </button>
              );
            })}
          </div>
        )}
        <div className="rc-palette__list" ref={listRef}>
          {mode === 'items' && itemEntries.map(({ group, item: row }, index) => (
            <Fragment key={row.id}>
              {startsGroup(itemEntries, index) && <GroupHeading group={group} />}
              <ItemRow
                row={row}
                change={changes[row.itemKey]}
                active={index === active}
                onHover={() => setActive(index)}
                onOpen={() => onOpenRow(row)}
              />
            </Fragment>
          ))}
          {mode === 'words' && wordEntries.map(({ group, item: hit }, index) => (
            <Fragment key={hit.id}>
              {startsGroup(wordEntries, index) && <GroupHeading group={group} />}
              <WordRow hit={hit} active={index === active} onHover={() => setActive(index)} onOpen={() => openHit(hit)} />
            </Fragment>
          ))}
          {idle && searches.length > 0 && (
            <>
              <div className="rc-group">Recent searches</div>
              {searches.map(text => (
                <button key={text} type="button" className="rc-item is-search" onClick={() => setQuery(text)}>
                  <History className="rc-glyph is-quiet" size={14} strokeWidth={1.6} aria-hidden="true" />
                  <span className="rc-item__title">{text}</span>
                  <span className="rc-item__end">{matchCount(searchCounts[text])}</span>
                </button>
              ))}
            </>
          )}
          {idle && <div className="rc-palette__hint">Searches the comments, reviews and task prompts you wrote in the last two weeks.</div>}
          {loaded && !idle && count === 0 && (
            <div className="rc-palette__empty">
              {mode === 'items'
                ? (searching ? `No recent items match “${query.trim()}”` : 'Nothing touched in the last two weeks.')
                : `Nothing you wrote matches “${query.trim()}”`}
            </div>
          )}
        </div>
        <div className="rc-palette__foot">
          <span>↑↓ Navigate</span>
          <span>↵ {mode === 'items' ? 'Open' : activeHit?.kind === 'TASK_PROMPT' ? 'Open task' : 'Jump to comment'}</span>
          <span>⌘C Copy</span>
          <span>⇥ {mode === 'items' ? 'Words' : 'Items'}</span>
          <span>{mode === 'words' && searching ? `${count} ${count === 1 ? 'result' : 'results'}` : 'esc Close'}</span>
        </div>
    </dialog>
  );
}

function startsGroup(entries: Array<{ group: PaletteGroup }>, index: number): boolean {
  return index === 0 || entries[index - 1].group !== entries[index].group;
}

function GroupHeading({ group }: { group: PaletteGroup }) {
  return <div className="rc-group">{group}</div>;
}

function matchCount(found: number | undefined): string {
  if (!found) return '';
  if (found >= WORD_LIMIT) return `${WORD_LIMIT}+ matches`;
  return found === 1 ? '1 match' : `${found} matches`;
}

/** Joins the present parts of a metadata line with dim separators. */
function withSeparators(parts: ReactNode[]): ReactNode[] {
  return parts.filter(Boolean).flatMap((part, index) => (index === 0 ? [part] : [<span key={`sep-${index}`} className="rc-row__sep">·</span>, part]));
}

function ItemRow({ row, change, active, onHover, onOpen }: {
  row: RecentRowDto;
  change: RecentChangeDto | undefined;
  active: boolean;
  onHover: () => void;
  onOpen: () => void;
}) {
  const Icon = TYPE_ICON[row.type];
  const state = row.type === 'PULL_REQUEST' ? pullRequestState(row.pullRequest) : null;
  const flagged = Boolean(change?.moved || change?.wantsYou);
  const activity = change?.moved && change.summary
    ? change.summary.replace(' since you read it', '')
    : change?.wantsYou ? 'Review requested' : row.reasonLabel;
  const source = row.type === 'JIRA' ? row.subtitle : row.repo;
  return (
    <button type="button" className={`rc-item${active ? ' is-active' : ''}`}
      onMouseMove={onHover} onClick={onOpen}>
      {row.type === 'PULL_REQUEST'
        ? <PullRequestStateIcon pullRequest={row.pullRequest} size={16} className="rc-glyph" />
        : <Icon className={`rc-glyph is-${roleTone(row)}`} size={16} strokeWidth={1.7} aria-hidden="true" />}
      <span className="rc-item__body">
        <span className="rc-item__title">{row.title}</span>
        <span className="rc-item__meta">
          {withSeparators([
            source && <span key="source" className="rc-item__source">{source}</span>,
            row.number !== null && <code key="number">#{row.number}</code>,
            state && <span key="state" className={`agt-pr-icon is-${state}`}>{STATE_LABEL[state]}</span>,
            <span key="activity" className={flagged ? 'rc-changed' : undefined}>{activity}</span>,
          ])}
        </span>
      </span>
      <span className="rc-item__end">
        {flagged && <span className="rc-dot is-moved" role="img" aria-label="New since you read it" />}
        <span className="rc-item__time">{relativeTime(row.touchedAt, { suffix: false })}</span>
      </span>
    </button>
  );
}

function WordRow({ hit, active, onHover, onOpen }: {
  hit: RecentWordHitDto;
  active: boolean;
  onHover: () => void;
  onOpen: () => void;
}) {
  const Icon = hit.kind === 'TASK_PROMPT' ? SquareTerminal : MessageSquare;
  const where = hit.title ?? hit.repo;
  return (
    <button type="button" className={`rc-item${active ? ' is-active' : ''}`}
      onMouseMove={onHover} onClick={onOpen}>
      <Icon className="rc-glyph is-quiet" size={16} strokeWidth={1.6} aria-hidden="true" />
      <span className="rc-item__body">
        <span className="rc-word__sentence">
          {highlightParts(hit.sentence, hit.highlights).map((part, index) => (
            part.match ? <mark key={index}>{part.text}</mark> : <span key={index}>{part.text}</span>
          ))}
        </span>
        <span className="rc-item__meta">
          {withSeparators([
            hit.neverPosted
              ? <span key="kind" className="rc-badge is-amber">Never posted</span>
              : <span key="kind" className="rc-item__source">{hit.kindLabel.charAt(0).toUpperCase() + hit.kindLabel.slice(1)}</span>,
            hit.number !== null && <code key="number">#{hit.number}</code>,
            where && <span key="where">{where}</span>,
          ])}
        </span>
      </span>
      <span className="rc-item__end">
        <span className="rc-item__time">{relativeTime(hit.writtenAt, { suffix: false })}</span>
      </span>
    </button>
  );
}
