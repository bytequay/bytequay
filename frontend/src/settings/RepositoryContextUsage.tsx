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
import { useEffect, useRef, useState } from 'react';
import type { ContextUsageActivitiesDto, ContextUsageOverviewDto, ContextUsageWindow, RepositoryNotebookDto } from '../repository-context/contracts';
import { workspaceAgentTaskRoute } from '../workspaces/model';

const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const when = (value: string) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};
const taskKind = { DEVELOPMENT: 'Development', PR_REVIEW: 'PR review' } as const;
const noteKind = { KNOWLEDGE: 'Knowledge', MEMORY: 'Saved lesson' } as const;
const preparation = { NOT_PREPARED: 'Not prepared', EMPTY: 'No useful note found', FAILED: 'Preparation failed', READY: 'Prepared' } as const;
const knowledge = { PRESENT: 'Present', MISSING: 'Missing', DISABLED: 'Disabled' } as const;
const delivery = { PREPARED: 'Prepared only', CONFIRMED: 'Confirmed', UNCERTAIN: 'Uncertain' } as const;

export default function RepositoryContextUsage({ workspaceId, notebook }: { workspaceId: string; notebook: RepositoryNotebookDto }) {
  const [visible, setVisible] = useState(false);
  const [usageWindow, setUsageWindow] = useState<ContextUsageWindow>('30d');
  const [refresh, setRefresh] = useState(0);
  return <section className="rc-usage" aria-label="Repository context usage">
    <div className="setw-section"><span className="setw-section-title">Usage</span>
      <button className="rc-button" type="button" aria-expanded={visible} onClick={() => setVisible(value => !value)}>
        {visible ? 'Hide usage' : 'View usage'}
      </button></div>
    <p className="rc-muted">See which saved notes were selected, provided, found, opened or cited in retained tasks and reviews.</p>
    {visible && <>
      <div className="rc-actions" role="group" aria-label="Usage period">
        <button className="rc-button" type="button" aria-pressed={usageWindow === '30d'} onClick={() => setUsageWindow('30d')}>Last 30 days</button>
        <button className="rc-button" type="button" aria-pressed={usageWindow === 'all'} onClick={() => setUsageWindow('all')}>All retained</button>
        <button className="rc-button" type="button" onClick={() => setRefresh(value => value + 1)}>Refresh usage</button>
      </div>
      <UsageResults key={`${workspaceId}:${usageWindow}:${notebook.revision}:${refresh}`} workspaceId={workspaceId} notebook={notebook} usageWindow={usageWindow} />
    </>}
  </section>;
}

function UsageResults({ workspaceId, notebook, usageWindow }: { workspaceId: string; notebook: RepositoryNotebookDto; usageWindow: ContextUsageWindow }) {
  const [pages, setPages] = useState<ContextUsageOverviewDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const live = useRef(true);
  const pending = useRef(false);
  useEffect(() => {
    live.current = true;
    let active = true;
    setLoading(true);
    setError(null);
    window.bridge.getRepositoryContextUsage(workspaceId, usageWindow, 0)
      .then(value => { if (active) setPages([value]); })
      .catch(failure => { if (active) setError(failureText(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; live.current = false; };
  }, [workspaceId, usageWindow, attempt]);
  const first = pages[0];
  const last = pages.at(-1);
  const loadMore = async () => {
    if (pending.current || last === undefined) return;
    pending.current = true; setLoading(true); setError(null);
    try {
      const next = await window.bridge.getRepositoryContextUsage(workspaceId, usageWindow, pages.length);
      if (live.current) setPages(current => [...current, next]);
    }
    catch (failure) { if (live.current) setError(failureText(failure)); }
    finally { pending.current = false; if (live.current) setLoading(false); }
  };
  return <div className="rc-usage__results">
    {loading && first === undefined && <p role="status">Loading recorded usage…</p>}
    {error !== null && <div className="rc-error" role="alert">{error}{first === undefined && <button className="rc-button" type="button"
      onClick={() => setAttempt(value => value + 1)}>Try again</button>}</div>}
    {first !== undefined && <>
      <p className="rc-muted">{usageWindow === '30d' ? 'Last 30 days' : 'All retained'} · Recorded delivery and reads in retained tasks
        {first.trackingSince === null ? '.' : <> since {when(first.trackingSince)}.</>}</p>
      {first.trackingSince === null ? <p>No tracked tasks yet. Usage tracking starts with the first eligible task or review.</p> : <>
        <dl className="rc-usage__summary">
          <div><dt>Development tasks</dt><dd>{first.summary.eligibleDevelopmentTasks}</dd></div>
          <div><dt>PR review runs</dt><dd>{first.summary.eligibleReviewRuns}</dd></div>
          <div><dt>Context off</dt><dd>{first.summary.contextOffTasks}</dd></div>
          <div><dt>Tasks provided or opened a note</dt><dd>{first.summary.tasksProvidedOrOpened}</dd></div>
          <div><dt>Active notes without confirmed delivery or open</dt><dd>{first.summary.activeNotesWithoutConfirmedDeliveryOrOpen}</dd></div>
          <div><dt>Scopes requested while missing</dt><dd>{first.summary.scopesDemandedWhileMissing}</dd></div>
          <div><dt>Still missing after a request</dt><dd>{first.summary.scopesStillMissingAfterDemand}</dd></div>
          <div><dt>Searches with no matching scope</dt><dd>{first.summary.searchesWithNoMatchingScope}</dd></div>
          <div><dt>Searches with a scope but no matching note</dt><dd>{first.summary.searchesWithScopeButNoNote}</dd></div>
        </dl>
        {first.byTaskAndNoteKind.length > 0 && <details className="rc-usage__breakdown"><summary>By task and note type</summary><ul>
          {first.byTaskAndNoteKind.map(row => <li key={`${row.taskKind}:${row.noteKind}`}>{taskKind[row.taskKind]} · {noteKind[row.noteKind]}:
            {' '}{row.providedOrOpenedTasks} tasks provided or opened, {row.foundTasks} found, {row.openedTasks} opened</li>)}
        </ul></details>}
        <h3>Saved notes</h3>
        {pages.every(page => page.entries.length === 0) && <p>No note activity was recorded in this period.</p>}
        {pages.flatMap(page => page.entries).map(entry => <UsageEntry key={entry.entryId} workspaceId={workspaceId} usageWindow={usageWindow}
          notebook={notebook} entry={entry} />)}
        <h3>Requested knowledge that was missing</h3>
        {pages.every(page => page.scopeGaps.length === 0) && <p>No missing-scope requests were recorded in this period.</p>}
        {pages.flatMap(page => page.scopeGaps).map(gap => {
          const scope = notebook.scopes.find(item => item.id === gap.scopeId);
          return <article className="rc-usage__gap" key={gap.scopeId}>
            <strong>{scope?.title ?? gap.scopeId}</strong><span>{gap.demandingTasks} {gap.demandingTasks === 1 ? 'task or review' : 'tasks or reviews'} requested it while knowledge was missing.</span>
            <span>Now: {knowledge[gap.currentKnowledge]} · Last preparation: {preparation[gap.currentPreparation]}</span>
            <details><summary>What those requests saw</summary><ul>{Object.entries(gap.observedPreparationCounts).filter(([, count]) => count > 0)
              .map(([state, count]) => <li key={state}>{preparation[state as keyof typeof preparation]}: {count}</li>)}</ul></details>
          </article>;
        })}
        {(last?.hasMoreEntries || last?.hasMoreScopeGaps) && <button className="rc-button" type="button" disabled={loading} onClick={() => void loadMore()}>
          {loading ? 'Loading…' : 'Load more usage'}</button>}
      </>}
      <p className="rc-muted">These records show delivery and reads. They do not show whether a note was understood or improved a result.</p>
    </>}
  </div>;
}

function UsageEntry({ workspaceId, notebook, usageWindow, entry }: { workspaceId: string; notebook: RepositoryNotebookDto;
  usageWindow: ContextUsageWindow; entry: ContextUsageOverviewDto['entries'][number] }) {
  const [open, setOpen] = useState(false);
  const [pages, setPages] = useState<ContextUsageActivitiesDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef(true);
  const pending = useRef(false);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const scope = notebook.scopes.find(item => item.id === entry.scopeId);
  const removed = entry.currentVersion === null;
  const disabled = entry.currentEnabled === false || scope?.enabled === false;
  const load = async (page: number) => {
    if (pending.current) return;
    pending.current = true; setLoading(true); setError(null);
    try {
      const result = await window.bridge.getRepositoryContextEntryUsage(workspaceId, entry.entryId, usageWindow, page);
      if (live.current) setPages(previous => page === 0 ? [result] : [...previous, result]);
    }
    catch (failure) { if (live.current) setError(failureText(failure)); }
    finally { pending.current = false; if (live.current) setLoading(false); }
  };
  const toggle = () => {
    setOpen(value => !value);
    if (!open && pages.length === 0) void load(0);
  };
  const counts = entry.currentVersionCounts;
  return <article className="rc-usage__entry">
    <div className="rc-note-head"><div><strong>{entry.title}</strong><small>{noteKind[entry.kind]} · {scope?.title ?? entry.scopeId} ·
      {' '}{removed ? 'Removed' : disabled ? 'Disabled' : `Current version ${entry.currentVersion}`}</small></div></div>
    {!removed && <><p>Current version: {counts.selectedTasks} selected · {counts.providedTasks} provided · {counts.foundTasks} found
      {' · '}{counts.openedTasks} opened · {counts.citedReviews} cited in reviews</p>
      {counts.searchHits > 0 && <p className="rc-muted">{counts.searchHits} search hits across recorded queries.</p>}</>}
    <p className="rc-muted">All retained versions: {entry.allVersionCounts.providedTasks} provided · {entry.allVersionCounts.foundTasks} found
      {' · '}{entry.allVersionCounts.openedTasks} opened. Last confirmed use: {entry.lastConfirmedUseAt === null ? 'None recorded' : when(entry.lastConfirmedUseAt)}.</p>
    <button className="rc-button" type="button" aria-expanded={open} onClick={toggle}>{open ? 'Hide activity' : 'View activity'}</button>
    {open && <div className="rc-usage__activities">
      {loading && pages.length === 0 && <p role="status">Loading note activity…</p>}
      {error !== null && <p className="rc-error" role="alert">{error}</p>}
      {pages.length > 0 && pages.every(page => page.activities.length === 0) && <p>No activity for this note in this period.</p>}
      {pages.flatMap(page => page.activities).map((activity, index) => <div className="rc-usage__activity" key={`${activity.receiptRef}:${activity.stage}:${index}`}>
        <strong>{activity.stage === 'PROVIDED' && activity.delivery !== 'CONFIRMED' ? delivery[activity.delivery] :
          activity.stage.charAt(0) + activity.stage.slice(1).toLowerCase()}</strong>
        <span>{taskKind[activity.taskKind]} · note version {activity.entryVersion} · {when(activity.occurredAt)}</span>
        <span>Delivery: {delivery[activity.delivery]}</span>
        <a href={workspaceAgentTaskRoute(workspaceId, activity.taskId)}>Open task</a>
        <details><summary>Saved receipt</summary><code>{activity.receiptRef}</code>
          {activity.reviewRunId !== null && <p>Review run: <code>{activity.reviewRunId}</code></p>}
          {activity.turnId !== null && <p>Turn: <code>{activity.turnId}</code></p>}</details>
      </div>)}
      {pages.at(-1)?.hasMore && <button className="rc-button" type="button" disabled={loading} onClick={() => void load(pages.length)}>
        {loading ? 'Loading…' : 'Load more activity'}</button>}
    </div>}
  </article>;
}
