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
import { relativeTime } from '@bytequay/ui/relativeTime';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { isFindShortcut } from '../FindBar';
import type { NavigationMode } from '../navigationHistory';
import { remoteErrorMessage } from '../remoteError';
import PullsScreen, { PullFilterTabs, RefreshIcon, shortcutTab, tabKeyTarget } from '../pulls/PullsScreen';
import { exactPullNumber, parsePullSearch, PULL_TABS, pullRequestMatchesSearch, rowsForPullSearch, rowsForTab, type PullRow, type PullTab } from '../pulls/model';
import PullRowItem from '../pulls/PullRowItem';
import type { HostingPullRequestSummaryDto, WorkspaceDto, WorkspaceHostingRequestDto } from '../types';
import HostingRequestOverview from './HostingRequestOverview';
import './hosting.css';

const PAGE_SIZE = 50;

type Props = {
  workspace: WorkspaceDto;
  initialPullRequestNumber: number | null;
  browserOpen?: boolean;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onClosePullRequest: () => void;
};

export default function HostingPullsScreen({ workspace, initialPullRequestNumber, browserOpen = false, onSelectPullRequest, onClosePullRequest }: Props) {
  const gitlab = workspace.hostingServer?.provider === 'GITLAB';
  const label = gitlab ? 'Merge requests' : 'Pull requests';
  const [items, setItems] = useState<WorkspaceHostingRequestDto[]>([]);
  const [tab, setTab] = useState<PullTab>(gitlab ? 'all' : PULL_TABS[0].key);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasNext, setHasNext] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [monitoring, setMonitoring] = useState<number[]>([]);
  const pages = useRef(1);
  const input = useRef<HTMLInputElement>(null);
  const toolbar = useRef<HTMLElement>(null);
  const [width, setWidth] = useState(Number.POSITIVE_INFINITY);
  const rowsId = useId();
  const query = search.trim().replace(/^!/, '#');
  const terms = parsePullSearch(query);
  const exactNumber = exactPullNumber(query);
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;

  const load = useCallback(async () => {
    try {
      const results = await Promise.all(Array.from({ length: pages.current }, (_, index) => window.bridge.fetchWorkspaceHostingRequests(workspace.id, index + 1, PAGE_SIZE)));
      setItems([...new Map(results.flatMap(result => result.items).map(item => [item.summary.number, item])).values()]);
      setHasNext(results[results.length - 1].hasNext);
      setError(null);
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setLoading(false); }
  }, [workspace.id]);

  useEffect(() => {
    void load();
    const reload = () => { void load(); };
    const unsubscribe = window.bridge.onPullRequestsSynced(reload);
    if (!gitlab) window.addEventListener('bytequay:pr-attention-updated', reload);
    return () => { unsubscribe(); window.removeEventListener('bytequay:pr-attention-updated', reload); };
  }, [gitlab, load]);
  useEffect(() => {
    const node = toolbar.current;
    if (node === null) return;
    const observer = new ResizeObserver(entries => setWidth(entries[0].borderBoxSize[0].inlineSize));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  const selectTab = useCallback((next: PullTab) => { setTab(next); setSearch(''); }, []);
  useEffect(() => {
    if (browserOpen || initialPullRequestNumber !== null) return;
    const keyDown = (event: KeyboardEvent) => {
      if (isFindShortcut(event)) {
        event.preventDefault(); event.stopPropagation(); input.current?.select();
      }
      else if (!gitlab) {
        const next = shortcutTab(event);
        if (next !== null) { event.preventDefault(); selectTab(next); }
      }
    };
    window.addEventListener('keydown', keyDown, true);
    return () => window.removeEventListener('keydown', keyDown, true);
  }, [browserOpen, gitlab, initialPullRequestNumber, selectTab]);

  const refresh = async () => {
    setSyncing(true); setError(null);
    try { await window.bridge.syncPrs(workspace.id); await load(); }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setSyncing(false); }
  };
  const more = async () => {
    setLoadingMore(true);
    try {
      const result = await window.bridge.fetchWorkspaceHostingRequests(workspace.id, pages.current + 1, PAGE_SIZE);
      setItems(current => [...new Map([...current, ...result.items].map(item => [item.summary.number, item])).values()]);
      pages.current += 1; setHasNext(result.hasNext); setError(null);
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setLoadingMore(false); }
  };
  const monitor = async (item: WorkspaceHostingRequestDto) => {
    if (item.github === null) return;
    const id = item.github.id;
    setMonitoring(current => [...current, id]); setError(null);
    try {
      const updated = await window.bridge.setPrMonitored(id, item.github.monitoredAt == null);
      setItems(current => current.map(row => row.github?.id === id ? { ...row, github: updated } : row));
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setMonitoring(current => current.filter(value => value !== id)); }
  };
  const open = (summary: HostingPullRequestSummaryDto) => {
    onSelectPullRequest(summary.number, 'push');
  };
  const projected = items.flatMap(item => item.github === null ? [] : [{ ...item.github, ...item.summary }]);
  const byNumber = new Map(items.map(item => [item.summary.number, item]));
  const visible: { item: WorkspaceHostingRequestDto; row: PullRow | null }[] = gitlab ? []
    : rowsForPullSearch(projected, tab, query).flatMap(row => {
        const item = byNumber.get(row.num); return item === undefined ? [] : [{ item, row }];
      });
  for (const item of items) {
    if (!gitlab && item.github !== null) continue;
    const summary = item.summary;
    const done = summary.state !== 'open';
    const matchesTab = tab === 'done' ? done : !done && (tab === 'all' || tab === 'active' && summary.origin === 'AUTHORED'
      || tab === 'req' && summary.origin === 'REVIEW_REQUESTED');
    if (exactNumber !== null ? summary.number === exactNumber : matchesTab && (terms === null || pullRequestMatchesSearch(summary, terms))) visible.push({ item, row: null });
  }

  return <div className="pulls-page hosting-requests">
    <section className="pulls-list" aria-label={label}>
      <header className="pulls-toolbar" ref={toolbar}>
        {gitlab ? <div className="pulls-toolbar__tabs" role="tablist" aria-label="Merge request filters" onKeyDown={event => {
          const keys: PullTab[] = ['all', 'done'];
          const target = tabKeyTarget(event.key, keys.indexOf(tab), keys.length);
          if (target === null) return;
          event.preventDefault(); selectTab(keys[target]); (event.currentTarget.children[target] as HTMLElement).focus();
        }}>
          {(['all', 'done'] as const).map(key => <button key={key} type="button" className="pulls-tab" role="tab"
            id={`${rowsId}-${key}`} aria-controls={rowsId} aria-selected={tab === key} tabIndex={tab === key ? 0 : -1}
            onClick={() => selectTab(key)}>{key === 'all' ? 'Open' : 'Closed'}</button>)}
        </div> : <PullFilterTabs tab={tab} searching={terms !== null} width={width} panelId={rowsId} onSelect={selectTab}
          counts={PULL_TABS.map(filter => `${rowsForTab(projected, filter.key).length}${hasNext ? '+' : ''}`)} />}
        <div className="pulls-toolbar__spacer" />
        <button type="button" className="pulls-toolbar-button" aria-label={`Sync ${label.toLowerCase()}`} title={`Sync ${label.toLowerCase()}`}
          disabled={loading || syncing || loadingMore} aria-busy={syncing} onClick={() => { void refresh(); }}>
          <span className={`bq-spinner${syncing ? ' is-spinning' : ''}`}><RefreshIcon /></span>
        </button>
      </header>
      <div className="pulls-search">
        <input type="search" ref={input} value={search} onChange={event => setSearch(event.target.value)}
          aria-label="Search loaded requests" placeholder={`Search loaded requests by title, ${gitlab ? '!' : '#'}number or @author`} />
        <span className="pulls-scope" title={`${workspace.hostingServer?.webBaseUrl ?? 'https://github.com'} · ${repository}`}>{repository}</span>
      </div>
      <div className="pulls-list__rows" id={rowsId} role="tabpanel" aria-labelledby={`${rowsId}-${tab}`}>
        {loading && <div className="hp-loading">Loading…</div>}
        {error !== null && <div className="pulls-sync-error" role="alert">{error} <button type="button" onClick={() => { void load(); }}>Retry</button></div>}
        {!loading && visible.length === 0 && <div className="hp-empty">No matching {label.toLowerCase()}. {items.length === 0 && 'Sync to check for requests.'}</div>}
        {visible.map(({ item, row }) => row !== null ? <PullRowItem key={item.summary.number} row={row} wide selected={initialPullRequestNumber === row.num}
          monitoring={monitoring.includes(row.id)} onPick={() => open(item.summary)} onMonitor={() => { void monitor(item); }} />
          : <button key={item.summary.number} type="button" className="pull-row hosting-request-row" onClick={() => open(item.summary)}
            aria-label={`${item.summary.title} ${gitlab ? '!' : '#'}${item.summary.number}`}>
            <span className="pull-row__title">{item.summary.title} <small>{gitlab ? '!' : '#'}{item.summary.number}</small></span>
            <span className="pull-row__meta">{item.summary.draft ? 'Draft' : item.summary.state === 'merged' ? 'Merged' : item.summary.state === 'closed' ? 'Closed' : 'Open'}
              {' · '}{item.summary.author}{' · '}{relativeTime(item.summary.updatedAt)}</span>
            {item.summary.labels.length > 0 && <span className="pull-row__labels">{item.summary.labels.map(label => <span key={label}>{label}</span>)}</span>}
            <span className="hosting-request-action">{gitlab ? 'Open merge request' : 'Open pull request'}</span>
          </button>)}
        {hasNext && <button type="button" className="hosting-load-more" disabled={loadingMore || syncing} onClick={() => { void more(); }}>
          {loadingMore ? 'Loading…' : 'Load more'}</button>}
      </div>
    </section>
    {gitlab && initialPullRequestNumber !== null && <HostingRequestOverview key={initialPullRequestNumber} workspace={workspace} number={initialPullRequestNumber}
      summary={byNumber.get(initialPullRequestNumber)?.summary} onClose={() => { onClosePullRequest(); void load(); }} />}
    {!gitlab && initialPullRequestNumber !== null && <PullsScreen key={initialPullRequestNumber} initialPr={{ repo: repository, number: initialPullRequestNumber }}
      repositoryScope={{ workspaceId: workspace.id, repository }} browserOpen={browserOpen} onClose={() => { onClosePullRequest(); void load(); }} />}
  </div>;
}
