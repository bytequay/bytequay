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
import { ArrowUpRight, Check, ChevronDown, ChevronLeft, ChevronRight, CircleDot, Copy, GitPullRequest, Sparkles } from 'lucide-react';
import Avatar from '../Avatar';
import RepoAvatar from '../RepoAvatar';
import type {
  RecentChangeDto,
  RecentItemType,
  RecentReason,
  RecentRowDto,
  WorkspaceDto,
} from '../types';
import {
  calendarCells,
  clockTime,
  dayHeading,
  isoDay,
  mayLoad,
  parseDay,
  RANGE_OPTIONS,
  rangeLabel,
  rangeWindow,
  reasonTone,
  roleTone,
  rowMarkdown,
  shiftDay,
  splitRepo,
  streamErrorMessage,
  TYPE_ICON,
  type RangeKey,
} from './model';
import '../css/recent.css';

type LoadedDay = { day: string; rows: RecentRowDto[] };

const ACTION_OPTIONS: Array<{ value: RecentReason | ''; label: string }> = [
  { value: '', label: 'Any action' },
  { value: 'READ', label: 'Read' },
  { value: 'COMMENTED', label: 'Commented' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'CHANGES_REQUESTED', label: 'Changes requested' },
  { value: 'MERGED', label: 'Merged' },
  { value: 'PUSHED', label: 'Pushed' },
  { value: 'EDITED', label: 'Edited' },
  { value: 'PROMPTED', label: 'You prompted' },
];

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

export default function RecentPage({ active = true, onOpenRow }: {
  active?: boolean;
  onOpenRow: (row: RecentRowDto) => void;
}) {
  const today = isoDay(new Date());
  const [range, setRange] = useState<RangeKey>('last7');
  // One type at a time: All, PRs, or Issues. The chips are a single choice,
  // not a set — combining them says the same thing as All.
  const [type, setType] = useState<RecentItemType | null>(null);
  const [action, setAction] = useState<RecentReason | ''>('');
  const [workspace, setWorkspace] = useState<string>('');
  const [changedOnly, setChangedOnly] = useState(false);
  const [narrow, setNarrow] = useState('');
  const [query, setQuery] = useState('');
  const [days, setDays] = useState<LoadedDay[]>([]);
  const [activeDays, setActiveDays] = useState<string[]>([]);
  const [retentionDays, setRetentionDays] = useState(14);
  const [cursor, setCursor] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<{
    message: string; from: string | null; reset: boolean; quiet: boolean;
  } | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [changes, setChanges] = useState<Record<string, RecentChangeDto>>({});
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [menu, setMenu] = useState<'range' | 'calendar' | 'workspace' | 'action' | null>(null);
  const [calendarMonth, setCalendarMonth] = useState(today);
  const [scrollTo, setScrollTo] = useState<string | null>(null);

  const streamRef = useRef<HTMLDivElement>(null);
  const request = useRef(0);
  const inFlight = useRef(false);
  const loaded = useRef(false);
  const oldestDay = useRef<string | null>(null);

  const window_ = useMemo(() => rangeWindow(range, today), [range, today]);
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(narrow.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [narrow]);

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    void window.bridge.listWorkspaces().then(list => {
      if (!cancelled) setWorkspaces(list);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [active]);

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    window.bridge.fetchRecentChanges()
      .then(list => {
        if (!cancelled) setChanges(Object.fromEntries(list.map(change => [change.itemKey, change])));
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [active]);

  const load = useCallback(async (from: string | null, reset: boolean, quiet = false) => {
    // Only paging is allowed to be dropped when a request is already out —
    // stacking scroll loads would page past the day the user is reading. A
    // filter change must always re-run, or a second filter toggled while the
    // first is still in flight leaves its control selected and the stream stale.
    if (!mayLoad(reset, inFlight.current)) return;
    const current = ++request.current;
    inFlight.current = true;
    setLoading(true);
    setRefreshing(quiet);
    setError(null);
    try {
      const fetchDay = (cursor: string | null) => window.bridge.fetchRecent({
        from: window_.from,
        to: window_.to,
        types: type ? [type] : undefined,
        action: action || undefined,
        workspace: workspace || undefined,
        changedSinceRead: changedOnly || undefined,
        text: query || undefined,
        cursor: cursor ?? undefined,
      });
      const page = await fetchDay(from);
      if (request.current !== current) return;
      // Revalidate the loaded span together so returning never discards older
      // pages, and changed filters cannot leave stale rows in those pages.
      const pages = [page];
      const oldest = oldestDay.current;
      if (quiet && oldest !== null) {
        pages.push(...await Promise.all(page.activeDays
          .filter(day => day !== page.day && day >= oldest)
          .map(fetchDay)));
      }
      if (request.current !== current) return;
      setError(null);
      setActiveDays(page.activeDays);
      setRetentionDays(page.retentionDays);
      const last = pages[pages.length - 1];
      setCursor(last.nextCursor);
      setDone(last.endOfWindow);
      const nextDays = pages.flatMap(result => result.day ? [{ day: result.day, rows: result.rows }] : []);
      setDays(previous => (reset ? nextDays : [...previous, ...nextDays]));
      oldestDay.current = last.day;
      loaded.current = true;
    }
    catch (failure) {
      if (request.current === current) setError({ message: streamErrorMessage(failure), from, reset, quiet });
    }
    finally {
      // Only the newest request clears the flag; an older one finishing late
      // must not hand the paging guard away while its successor is still out.
      if (request.current === current) {
        inFlight.current = false;
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [action, changedOnly, query, type, window_.from, window_.to, workspace]);

  const loadedFilter = useRef(load);
  useEffect(() => {
    if (!active) return;
    const filterChanged = loadedFilter.current !== load;
    if (!filterChanged && inFlight.current) return;
    loadedFilter.current = load;
    if (filterChanged) {
      setDays([]);
      setCursor(null);
      setDone(false);
      loaded.current = false;
      oldestDay.current = null;
    }
    void load(null, true, loaded.current);
  }, [active, load]);

  useEffect(() => {
    const clear = () => {
      request.current++;
      inFlight.current = false;
      loaded.current = false;
      oldestDay.current = null;
      setDays([]);
      setChanges({});
      setCursor(null);
      setDone(false);
      setError(null);
      setLoading(true);
      setRefreshing(false);
      if (active) void load(null, true);
    };
    window.addEventListener('bytequay:recent-cleared', clear);
    return () => window.removeEventListener('bytequay:recent-cleared', clear);
  }, [active, load]);

  // Page a day at a time as the bottom comes into view, and end-cap rather
  // than spin once the window is spent.
  useEffect(() => {
    const node = streamRef.current;
    if (!active || !node || done || loading || error) return;
    const onScroll = () => {
      if (node.scrollTop + node.clientHeight >= node.scrollHeight - 120 && cursor) {
        void load(cursor, false);
      }
    };
    node.addEventListener('scroll', onScroll);
    onScroll();
    return () => node.removeEventListener('scroll', onScroll);
  }, [active, cursor, done, error, load, loading]);

  // Jumping to a date scrolls the stream to it; the days either side stay reachable.
  useEffect(() => {
    if (!active || !scrollTo || loading || error || inFlight.current) return;
    const target = document.getElementById(`rc-day-${scrollTo}`);
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      setScrollTo(null);
      return;
    }
    if (done || !cursor) {
      // The day fell outside what the current filters keep; stop hunting for it.
      setScrollTo(null);
      return;
    }
    void load(cursor, false);
  }, [active, cursor, days, done, error, load, loading, scrollTo]);

  // Picking the type that is already showing goes back to All, so the filters
  // never dead-end on a selection you cannot leave.
  const pickType = (next: RecentItemType) =>
    setType(current => (current === next ? null : next));

  const pickDay = (day: string) => {
    setMenu(null);
    setRange('last14');
    setScrollTo(day);
  };

  const windowStart = shiftDay(today, -(retentionDays - 1));

  return (
    <div className="rc-page" style={active ? undefined : { display: 'none' }} onClick={() => setMenu(null)}>
      <header className="rc-head" onClick={event => event.stopPropagation()}>
        <div className="rc-head__row">
          <h1>Recent</h1>
          <span className="rc-menu-anchor">
            <button type="button" className="bq-button rc-range"
              aria-haspopup="menu" aria-expanded={menu === 'range' || menu === 'calendar'}
              onClick={() => setMenu(menu === null ? 'range' : null)}>
              {rangeLabel(window_.from, window_.to)} <ChevronDown aria-hidden="true" />
            </button>
            {menu === 'range' && (
              <div className="rc-menu" role="menu">
                {RANGE_OPTIONS.map(option => (
                  <button key={option.key} type="button" role="menuitem"
                    className={range === option.key ? 'is-selected' : ''}
                    onClick={() => { setRange(option.key); setMenu(null); }}>
                    <span className="rc-menu__check">{range === option.key && <Check size={12} aria-hidden="true" />}</span>
                    {option.label}
                  </button>
                ))}
                <div className="rc-menu__rule" />
                <button type="button" role="menuitem" onClick={() => { setCalendarMonth(today); setMenu('calendar'); }}>
                  <span className="rc-menu__check" />Jump to a date…
                </button>
              </div>
            )}
            {menu === 'calendar' && (
              <Calendar
                month={calendarMonth}
                today={today}
                windowStart={windowStart}
                activeDays={activeDays}
                onMonth={setCalendarMonth}
                onPick={pickDay}
              />
            )}
          </span>
          <span className="rc-menu-anchor">
            <button type="button" className="bq-button"
              aria-haspopup="menu" aria-expanded={menu === 'workspace'}
              onClick={() => setMenu(menu === null ? 'workspace' : null)}>
              {workspaces.find(item => item.id === workspace)?.name ?? 'All workspaces'} <ChevronDown aria-hidden="true" />
            </button>
            {menu === 'workspace' && (
              <div className="rc-menu" role="menu">
                <button type="button" role="menuitem" className={workspace === '' ? 'is-selected' : ''}
                  onClick={() => { setWorkspace(''); setMenu(null); }}>
                  <span className="rc-menu__check">{workspace === '' && <Check size={12} aria-hidden="true" />}</span>All workspaces
                </button>
                {workspaces.map(item => (
                  <button key={item.id} type="button" role="menuitem"
                    className={workspace === item.id ? 'is-selected' : ''}
                    onClick={() => { setWorkspace(item.id); setMenu(null); }}>
                    <span className="rc-menu__check">{workspace === item.id && <Check size={12} aria-hidden="true" />}</span>{item.name}
                  </button>
                ))}
              </div>
            )}
          </span>
          <input className="bq-input rc-narrow" type="search" placeholder="Narrow this list…" aria-label="Narrow this list"
            value={narrow} onChange={event => setNarrow(event.target.value)} />
        </div>
        <div className="rc-filters">
          <div className="bq-button-group" role="group" aria-label="Item type">
            <button type="button" className="bq-button"
              aria-pressed={type === null} onClick={() => setType(null)}>All</button>
            <button type="button" className="bq-button" aria-pressed={type === 'PULL_REQUEST'}
              onClick={() => pickType('PULL_REQUEST')}><GitPullRequest aria-hidden="true" /> PRs</button>
            <button type="button" className="bq-button" aria-pressed={type === 'ISSUE'}
              onClick={() => pickType('ISSUE')}><CircleDot aria-hidden="true" /> Issues</button>
          </div>
          <span className="rc-menu-anchor">
            <button type="button" className="bq-button" aria-pressed={action !== ''}
              aria-haspopup="menu" aria-expanded={menu === 'action'}
              onClick={() => setMenu(menu === null ? 'action' : null)}>
              {ACTION_OPTIONS.find(option => option.value === action)?.label ?? 'Any action'} <ChevronDown aria-hidden="true" />
            </button>
            {menu === 'action' && (
              <div className="rc-menu" role="menu">
                {ACTION_OPTIONS.map(option => (
                  <button key={option.value || 'any'} type="button" role="menuitem"
                    className={action === option.value ? 'is-selected' : ''}
                    onClick={() => { setAction(option.value); setMenu(null); }}>
                    <span className="rc-menu__check">{action === option.value && <Check size={12} aria-hidden="true" />}</span>
                    {option.label}
                  </button>
                ))}
              </div>
            )}
          </span>
          <button type="button" className="bq-button"
            aria-pressed={changedOnly} onClick={() => setChangedOnly(value => !value)}>
            <Sparkles aria-hidden="true" /> Changed since I looked
          </button>
        </div>
      </header>

      <div className="rc-stream" ref={streamRef}>
        {error && <div className="rc-empty" role="alert">
          {error.message}{' '}
          <button type="button" className="bq-button"
            onClick={() => { void load(error.from, error.reset, error.quiet); }}>Retry</button>
        </div>}
        {days.map(group => (
          <div key={group.day} id={`rc-day-${group.day}`}>
            <div className="rc-daymark">
              <span>{dayHeading(group.day, today)}</span>
              <span className="rc-daymark__rule" />
            </div>
            {group.rows.map(row => (
              <Row
                key={row.id}
                row={row}
                change={changes[row.itemKey]}
                open={expanded === row.id}
                onToggle={() => setExpanded(current => (current === row.id ? null : row.id))}
                onOpen={() => onOpenRow(row)}
              />
            ))}
          </div>
        ))}
        {(!loading || refreshing) && !error && days.length === 0 && (
          <div className="rc-empty">Nothing recorded in this window yet.</div>
        )}
        {loading && !refreshing && (
          <div className="rc-foot" role="status"><span className="rc-spin" aria-hidden="true" />
            {days.length === 0 ? 'Loading recent…' : 'Loading earlier days…'}
          </div>
        )}
        {done && days.length > 0 && (
          <div className="rc-foot is-end">Nothing older — {retentionDays} days is everything kept.</div>
        )}
      </div>
    </div>
  );
}

export function Row({ row, change, open, onToggle, onOpen }: {
  row: RecentRowDto;
  change: RecentChangeDto | undefined;
  open: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const [copied, setCopied] = useState<string | null>(null);
  const copy = (label: string, value: string) => {
    void navigator.clipboard.writeText(value);
    setCopied(label);
    window.setTimeout(() => setCopied(null), 1200);
  };
  const repo = splitRepo(row.repo);
  const Icon = TYPE_ICON[row.type];
  // subtitle carries the author only for the two types that have one; on a
  // task it is the lane and on a commit it is the sha.
  const author = row.type === 'PULL_REQUEST' || row.type === 'ISSUE' ? row.subtitle : null;
  // There is only something to expand into when the user wrote something here.
  const activate = row.snippet ? onToggle : onOpen;
  const meta = change?.summary
    ? <span className="rc-row__meta is-changed"><span className="rc-dot is-moved" aria-hidden="true" />{change.summary}</span>
    : (
      <span className="rc-row__meta">
        {repo && <RepoAvatar owner={repo.owner} repo={repo.name} size={14} radius={4} />}
        {row.repo}
        {(row.mine || author) && <span className="rc-row__sep">·</span>}
        {author && <Avatar login={author} size={14} />}
        {(row.mine || author) && <span>{row.mine ? 'yours' : author}</span>}
      </span>
    );

  return (
    <>
      <div className={`rc-row${open ? ' is-open' : ''}`} role="button" tabIndex={0}
        aria-expanded={row.snippet ? open : undefined}
        onClick={activate}
        onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(); } }}>
        <span className="rc-row__time">{clockTime(row.touchedAt)}</span>
        <Icon className={`rc-glyph rc-row__glyph is-${roleTone(row)}`} size={16} strokeWidth={1.7} aria-hidden="true" />
        <span className="rc-row__main">
          <span className="rc-row__title">
            <span className="rc-row__text">{row.title}</span>
            {/* Outside the truncating span, so the number survives the ellipsis. */}
            {row.number !== null && <span className="rc-row__number">#{row.number}</span>}
          </span>
          {meta}
        </span>
        <span className="rc-row__tail">
          <span className="rc-row__actions">
            <button type="button" className="rc-mini" onClick={event => {
              event.stopPropagation();
              copy('link', row.htmlUrl ?? '');
            }}><Copy size={12} aria-hidden="true" />{copied === 'link' ? 'Copied' : 'Link'}</button>
            <button type="button" className="rc-mini" onClick={event => {
              event.stopPropagation();
              copy('markdown', rowMarkdown(row));
            }}><Copy size={12} aria-hidden="true" />{copied === 'markdown' ? 'Copied' : 'Markdown'}</button>
            {row.htmlUrl && (
              <button type="button" className="rc-mini" aria-label="Open on GitHub" onClick={event => {
                event.stopPropagation();
                void window.bridge.openInAppBrowser(row.htmlUrl ?? '');
              }}><ArrowUpRight size={12} aria-hidden="true" /></button>
            )}
          </span>
          <span className={`rc-badge is-${reasonTone(row.reason)}`}>{row.reasonLabel}</span>
        </span>
      </div>
      {open && row.snippet && (
        <div className="rc-wrote">
          <div className="rc-wrote__head">WHAT YOU WROTE · {clockTime(row.touchedAt)}</div>
          <div className="rc-wrote__body">“{row.snippet}”</div>
          <div className="rc-wrote__actions">
            <button type="button" className="rc-mini"
              onClick={() => copy('text', row.snippet ?? '')}>
              <Copy size={12} aria-hidden="true" />{copied === 'text' ? 'Copied' : 'Copy this text'}
            </button>
            <button type="button" className="rc-mini is-plain" onClick={onOpen}>Open the thread</button>
          </div>
        </div>
      )}
    </>
  );
}

/**
 * Days outside the kept window are greyed and unpickable, so retention
 * explains itself without a sentence.
 */
function Calendar({ month, today, windowStart, activeDays, onMonth, onPick }: {
  month: string;
  today: string;
  windowStart: string;
  activeDays: string[];
  onMonth: (month: string) => void;
  onPick: (day: string) => void;
}) {
  const cells = calendarCells(month, activeDays, windowStart, today);
  const date = parseDay(month);
  return (
    <div className="rc-menu rc-cal" role="dialog" aria-label="Jump to a date">
      <div className="rc-cal__title">
        <button type="button" aria-label="Previous month"
          onClick={() => onMonth(isoDay(new Date(date.getFullYear(), date.getMonth() - 1, 1)))}><ChevronLeft size={14} aria-hidden="true" /></button>
        <span>{MONTH_NAMES[date.getMonth()]} {date.getFullYear()}</span>
        <button type="button" aria-label="Next month"
          onClick={() => onMonth(isoDay(new Date(date.getFullYear(), date.getMonth() + 1, 1)))}><ChevronRight size={14} aria-hidden="true" /></button>
      </div>
      <div className="rc-cal__grid is-head">
        {['S', 'M', 'T', 'W', 'T', 'F', 'S'].map((label, index) => (
          <span key={index} className="rc-cal__weekday">{label}</span>
        ))}
      </div>
      <div className="rc-cal__grid">
        {cells.map((cell, index) => cell === null
          ? <span key={`pad-${index}`} />
          : (
            <button
              key={cell.iso}
              type="button"
              className={`rc-cal__day${cell.active ? ' has-activity' : ''}${cell.iso === today ? ' is-today' : ''}`}
              disabled={!cell.inWindow || !cell.active}
              onClick={() => onPick(cell.iso)}
            >
              {cell.day}
              <span className="rc-cal__mark" />
            </button>
          ))}
      </div>
    </div>
  );
}
