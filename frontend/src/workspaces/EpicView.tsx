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

import React, { useCallback, useState } from 'react';
import { ArrowDown, ArrowUp, ArrowUpRight, Check, ChevronRight, Circle, CircleCheck, CircleDotDashed, CircleSlash, ListTree, LoaderCircle, LockKeyhole, Play, Plus,
  RotateCcw, Search, Sparkles, SquareTerminal, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { CardActions, CardAge, IconButton, ItemRow, LinkKey, LocalBadge, Pill, SourceIcon } from '../cards/Card';
import type { EpicChildDto, EpicProposalDto, EpicRecordDto, GoalItemDto, GoalItemKind, WorkItemDecision, WorkItemDto } from '../goals/contracts';
import { openGoalItem, startTicket } from '../goals/GoalLayer';
import { Candidate, useCandidates } from '../goals/GoalPanel';
import { GOAL_SOURCES, goalRef, isOpen, itemNeedsYou, progressWidths, refKey, summarize, waitingOn } from '../goals/model';
import MarkdownBody from '../pulls/MarkdownBody';
import type { Goals } from '../goals/useGoals';
import type { AttentionItem, AttentionTone } from '../home/attention';
import { EpicGlyph } from './EpicDrawer';
import { epicIdentity, epicWork, joinEpic, orderEpic, removeFromEpic, startEpicWork, useEpicGoals, useEpicLend, useEpicRecord } from './epics';
import { notifyIssueDevelopmentChanged } from './issueDevelopment';
import { zoomReference } from '../githubReference';
import '../css/epic-view.css';

type Decision = Omit<WorkItemDecision, 'operationId' | 'expectedRevision'>;
/** The Epic's child a row's work came from, as the source last reported it, or one the source no longer reports. */
type Upstream = { child: EpicChildDto; former: boolean };

const UPSTREAM: Record<EpicChildDto['kind'], string> = { ISSUE: 'GitHub', JIRA: 'Jira' };
const STATE_WORDS: Record<WorkItemDto['state'], string> = { PLANNED: 'Planned', WORKING: 'Working', DONE: 'Done', DROPPED: 'Dropped' };
type Shown = 'ALL' | Exclude<WorkItemDto['state'], 'DROPPED'>;
const SHOWN: Shown[] = ['ALL', 'PLANNED', 'WORKING', 'DONE'];
type Finding = 'ALL' | GoalItemKind;
const FINDING: [Finding, string][] = [['ALL', 'All'], ['ISSUE', 'GitHub issues'], ['JIRA', 'Jira'], ['PULL_REQUEST', 'Pull requests'], ['TASK', 'Tasks']];

/** The issue or ticket the work came from; work without one is Local. */
function sourceOfWork(item: WorkItemDto): GoalItemDto | undefined {
  return item.links.find((link) => link.kind === 'ISSUE' || link.kind === 'JIRA');
}

function upstreamOf(item: WorkItemDto, record: EpicRecordDto | null): Upstream | null {
  if (record === null) return null;
  const sources = new Set(item.links.map((link) => `${link.kind}:${link.key}`));
  const matches = (child: EpicChildDto): boolean => sources.has(`${child.kind}:${child.key}`);
  const child = record.children.find(matches);
  if (child !== undefined) return { child, former: false };
  const former = record.former.find(matches);
  return former === undefined ? null : { child: former, former: true };
}

/**
 * The Work tab wherever an Epic's issue or ticket opens. The workspace on screen lends what needs the user, its Epics' names and its
 * repository, and starts the work its rows run; elsewhere the tab still adds, orders and decides work.
 */
export function EpicWork({ epic, workspaceId, repository, work, onShowSource }: {
  epic: AttentionItem;
  workspaceId: string;
  /** "owner/repo" the workspace's issue composer starts tasks in, when the host knows it. */
  repository: string | null;
  work: WorkItemDto[];
  onShowSource: () => void;
}): React.JSX.Element {
  const lend = useEpicLend(workspaceId);
  const goals = useEpicGoals(workspaceId);
  return <EpicView epic={epic} workspaceId={workspaceId} repository={repository ?? lend.repository ?? ''} work={work} needsYou={lend.needsYou} goals={goals}
    epicTitle={(epicId) => lend.epicTitles.get(epicId) ?? null}
    onOpenTask={(taskId) => zoomReference({ kind: 'task', workspaceId, taskId })}
    onStartIssue={(issue, instruction, replaces) => startEpicWork({ workspaceId, issue, instruction, replaces, send: true })}
    onStartWork={(itemId, message) => startEpicWork({ workspaceId, itemId, draft: message, send: true })}
    onShowSource={onShowSource} />;
}

/**
 * One Epic's work, the only place it is added, ordered and decided; it is the Work tab beside the Epic's issue or ticket. Quick add takes a
 * title and makes Local work, Add existing finds an issue, ticket, pull request or task, and each row says where the work stands in one short
 * detail.
 */
export default function EpicView({ epic, workspaceId, repository, work, needsYou, goals, epicTitle, onOpenTask, onStartIssue, onStartWork, onShowSource }: {
  epic: AttentionItem;
  workspaceId: string;
  /** "owner/repo" the workspace's issue composer starts tasks in. */
  repository: string;
  /** Every work item of the workspace; the view shows the Epic's. */
  work: WorkItemDto[];
  needsYou: ReadonlySet<string>;
  goals: Pick<Goals, 'createWork' | 'decideWork' | 'search'>;
  /** Names another Epic, for the notice when work moves here from it. */
  epicTitle: (epicId: string) => string | null;
  onOpenTask: (taskId: string) => void;
  /** Starts the issue's task with the instruction as its first message; `replaces` names the ended attempt. */
  onStartIssue: (issue: number, instruction: string, replaces?: string) => void;
  /** Starts a task for work without an issue or ticket, with this first message; the task is the work's attempt. */
  onStartWork: (itemId: string, message: string) => void;
  /** Shows the Epic's issue or ticket, in the tab beside this one. */
  onShowSource: () => void;
}): React.JSX.Element {
  const epicId = epicIdentity(epic);
  const items = epicWork(work, epicId);
  const shown = items.filter((item) => item.state !== 'DROPPED');
  const dropped = items.filter((item) => item.state === 'DROPPED');
  const summary = summarize({ items }, needsYou);
  const widths = progressWidths(summary);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [adding, setAdding] = useState(false);
  const [finding, setFinding] = useState<Finding>('ALL');
  const [filter, setFilter] = useState<Shown>('ALL');
  const [picked, setPicked] = useState<string[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [dropping, setDropping] = useState<WorkItemDto | null>(null);
  const [reason, setReason] = useState('');
  const [concluding, setConcluding] = useState(false);
  const [outcome, setOutcome] = useState('');
  const own = goalRef(epic);
  const search = goals.search;
  // Narrowed before the results are cut to the few shown, so a kind with matches never comes back empty.
  const find = useCallback((query: string) => search(query).then((results) => results.filter((item) => finding === 'ALL' || item.kind === finding)), [finding, search]);
  const candidates = useCandidates({ items }, find, adding);
  const found = candidates.results.filter((item) => own === null || refKey(item) !== refKey(own));
  /** The other Epic whose work the candidate already is; adding it moves it here. */
  const elsewhere = (candidate: GoalItemDto): string | null => work.find((item) => item.epicId != null && item.epicId !== epicId
    && item.links.some((link) => refKey(link) === refKey(candidate)))?.epicId ?? null;
  const children = useEpicRecord(workspaceId, epicId, epic.jira?.connectionId ?? null);
  const record = children.record;
  const unsynced = record === null ? [] : record.children.filter((child) => !record.synced.includes(child.identity));
  const concludedAt = record?.concludedAt ?? null;
  const ready = summary.total > 0 && summary.finished === summary.total;
  const sourceName = epic.jira ? epic.jira.key : `#${epic.number}`;
  const listed = shown.filter((item) => filter === 'ALL' || item.state === filter);
  const from = (kind: GoalItemKind | null): number => shown.filter((item) => (sourceOfWork(item)?.kind ?? null) === kind).length;
  const mix = { local: from(null), github: from('ISSUE'), jira: from('JIRA') };
  const proposals = record?.proposals ?? [];
  const chosen = picked.filter((id) => proposals.some((proposal) => proposal.id === id));

  const run = (action: () => Promise<unknown>): Promise<void> => {
    setBusy(true);
    setError(null);
    return action().then((): void => undefined, (failure: unknown): void => {
      setError(failure instanceof Error ? failure.message : 'The work could not be changed');
    }).finally(() => setBusy(false));
  };
  const decide = (item: WorkItemDto, decision: Decision): void => {
    void run(() => goals.decideWork(item, { operationId: crypto.randomUUID(), expectedRevision: item.revision, ...decision }));
  };
  const move = (item: WorkItemDto, offset: number): void => {
    const neighbour = shown[shown.indexOf(item) + offset];
    if (neighbour === undefined) return;
    const ids = items.map((value) => (value.id === item.id ? neighbour.id : value.id === neighbour.id ? item.id : value.id));
    void run(() => orderEpic(workspaceId, epicId, ids));
  };
  const add = (reference: GoalItemDto): Promise<void> => run(async () => {
    const joined = await joinEpic(workspaceId, epicId, reference);
    setNotice(joined.movedFrom === null ? null : `Moved here from ${epicTitle(joined.movedFrom) ?? 'another Epic'}`);
  });
  /** Accepts or dismisses proposals one after another: each answer is the record the next one changes. */
  const settle = (ids: string[], one: (id: string) => Promise<void>, done: string | null): void => {
    void run(() => ids.reduce((before, id) => before.then(() => one(id)), Promise.resolve()).then(() => setNotice(done)));
  };
  const accept = (ids: string[]): void => settle(ids, children.acceptProposal, `Added ${ids.length} Local work item${ids.length === 1 ? '' : 's'} to this Epic.`);
  /** How the work starts, at once and without a composer step: as its issue's or ticket's task, or a task named by its title; null when it cannot start here. */
  const starter = (item: WorkItemDto): (() => void) | null => {
    const last = item.links.filter((link) => link.kind === 'TASK').at(-1);
    const replaces = last?.state === 'ENDED' ? last.key : undefined;
    const instruction = item.acceptanceCriteria.trim() === '' ? item.title : item.acceptanceCriteria;
    const issue = item.links.find((link) => link.kind === 'ISSUE');
    if (issue !== undefined) {
      const hash = issue.key.lastIndexOf('#');
      // The issue composer starts tasks in the workspace's repository; a sub-issue elsewhere opens where it lives.
      return issue.key.slice(0, hash) === repository.toLowerCase() ? () => onStartIssue(Number(issue.key.slice(hash + 1)), instruction, replaces) : null;
    }
    const ticket = item.links.find((link) => link.kind === 'JIRA');
    if (ticket !== undefined) return () => startTicket(ticket.key, instruction, replaces, true);
    return () => onStartWork(item.id, item.title);
  };

  return (
    <section className="epic-view" aria-label={`Epic: ${epic.title}`}>
      <header className="epic-view__head">
        <div className="epic-view__kicker">
          <Pill tone="purple"><EpicGlyph />Epic</Pill>
          <span>Parent of work from Local, GitHub and Jira</span>
          <span className="item-row__sep">·</span>
          <button type="button" className="epic-view__link" title={`Show ${sourceName}`} onClick={onShowSource}>
            {epic.jira ? epic.jira.key : `${epic.repo} #${epic.number}`}<ArrowUpRight size={11} aria-hidden="true" />
          </button>
        </div>
        <h1 className="epic-view__title">{epic.title}</h1>
        {summary.total > 0 && (
          <div className="epic-progress" aria-hidden="true">
            <span className="is-done" style={{ width: widths.finished }} />
            <span className="is-working" style={{ width: widths.working }} />
          </div>
        )}
        <div className="epic-view__stats">
          <div className="epic-view__counts">
            {summary.total === 0 ? <span>No work yet</span>
              : summary.finished === summary.total ? <span className="is-ready">All {summary.total} done{concludedAt === null ? ' · ready to conclude' : ''}</span>
                : <>
                  <span className="is-strong">{summary.finished} of {summary.total} done</span>
                  {summary.working > 0 && <span className="is-working">{summary.working} working</span>}
                  {summary.idle > 0 && <span className="is-planned">{summary.idle} planned</span>}
                </>}
            {summary.dropped > 0 && <span className="is-muted">{summary.dropped} dropped</span>}
            {ready && concludedAt === null && record !== null && (
              <button type="button" className="epic-view__sync epic-view__conclude" disabled={busy} onClick={() => {
                setOutcome(`All ${summary.total} work items done:\n${items.filter((item) => item.state === 'DONE').map((item) => `- ${item.title}`).join('\n')}`);
                setConcluding(true);
              }}>Conclude Epic</button>
            )}
          </div>
          <div className="epic-view__mix" aria-label="Where the work comes from">
            {mix.local > 0 && <span className="is-local"><LockKeyhole size={11} aria-hidden="true" />{mix.local} Local</span>}
            {mix.github > 0 && <span className="card-src is-issue"><SourceIcon source="issue" size={12} />{mix.github} GitHub</span>}
            {mix.jira > 0 && <span className="card-src is-jira"><SourceIcon source="jira" size={12} />{mix.jira} Jira</span>}
          </div>
        </div>
      </header>
      {record !== null && concludedAt !== null && (
        <section className="epic-view__concluded" aria-label="Concluded">
          <div className="epic-view__concluded-head">
            <CircleCheck size={14} aria-hidden="true" />
            <span>Concluded</span>
            <CardAge at={concludedAt} />
            {epic.jira
              ? <button type="button" className="epic-view__link" onClick={onShowSource}>Close {epic.jira.key} from its ticket</button>
              : <button type="button" className="epic-view__link" disabled={busy} onClick={() => {
                void run(async () => {
                  await window.bridge.closeWorkspaceIssue(workspaceId, epic.number);
                  notifyIssueDevelopmentChanged();
                });
              }}>Close #{epic.number} on GitHub</button>}
          </div>
          <p className="epic-view__outcome">{record.outcome}</p>
        </section>
      )}
      {record?.offered === true && (
        <div className="epic-view__found">
          <ListTree size={14} aria-hidden="true" />
          <span className="epic-view__found-count" title={unsynced.map((child) => `${child.label} ${child.title}`).join('\n')}>
            Found in {epic.jira ? 'Jira' : 'GitHub'} · {unsynced.length} {epic.jira ? 'child ticket' : 'sub-issue'}{unsynced.length === 1 ? '' : 's'} of {sourceName}
            {unsynced.length === 1 ? ' isn\'t' : ' aren\'t'} in this Epic's work yet
          </span>
          <button type="button" className="epic-view__sync" disabled={busy} onClick={() => {
            const count = unsynced.length;
            void run(() => children.sync().then(() => setNotice(`Synced ${count} ${epic.jira ? 'child ticket' : 'sub-issue'}${count === 1 ? '' : 's'} from ${epic.jira ? 'Jira' : 'GitHub'}.`)));
          }}>Sync</button>
          <button type="button" className="epic-view__link" disabled={busy} onClick={() => { void run(children.dismiss); }}>Not now</button>
        </div>
      )}
      <div className="epic-view__toolbar">
        <div className="epic-view__filters" role="group" aria-label="Work to show">
          {SHOWN.map((value) => (
            <button type="button" key={value} className={filter === value ? 'is-active' : ''} aria-pressed={filter === value} onClick={() => setFilter(value)}>
              {value === 'ALL' ? 'All' : STATE_WORDS[value]}<span>{value === 'ALL' ? shown.length : shown.filter((item) => item.state === value).length}</span>
            </button>
          ))}
        </div>
        <button type="button" className={`epic-view__tool${adding ? ' is-active' : ''}`} aria-expanded={adding} onClick={() => setAdding((value) => !value)}>
          <Search size={13} aria-hidden="true" />Add existing
        </button>
        {record !== null && (
          <button type="button" className="epic-view__tool is-suggest" disabled={busy || record.breakingDown} onClick={() => { setPicked([]); void run(children.propose); }}>
            <Sparkles size={13} aria-hidden="true" />{record.breakingDown ? 'Suggesting…' : 'Suggest work'}
          </button>
        )}
      </div>
      {adding && (
        <div className="goal-search epic-view__search">
          <label className="goal-search__field">
            <Search size={14} aria-hidden="true" />
            <input autoFocus aria-label="Find an issue, ticket, pull request or task" placeholder="Find an issue, ticket, pull request or task"
              value={candidates.query} onChange={(event) => candidates.setQuery(event.target.value)} />
            <IconButton label="Close Add existing" title="Close" onClick={() => setAdding(false)}><X size={13} /></IconButton>
          </label>
          <div className="epic-view__kinds" role="group" aria-label="Kind of work to find">
            {FINDING.map(([value, label]) => (
              <button type="button" key={value} className={finding === value ? 'is-active' : ''} aria-pressed={finding === value} onClick={() => setFinding(value)}>{label}</button>
            ))}
          </div>
          <div className="epic-view__results">
            {found.map((item) => {
              const other = elsewhere(item);
              return <Candidate key={refKey(item)} item={item} onAdd={add} sub={other === null ? item.status : `In ${epicTitle(other) ?? 'another Epic'}`}
                action={other === null ? 'Add' : 'Move here'} />;
            })}
            {found.length === 0 && (candidates.query.trim() !== '' || finding !== 'ALL')
              && <div className="epic-view__none">Nothing matches. New work you type below is created as Local.</div>}
          </div>
        </div>
      )}
      {notice !== null && <div className="epic-view__notice" role="status">{notice}</div>}
      {error !== null && <div className="epic-view__error" role="alert">{error}</div>}
      <div className="item-list" role="list" aria-label="Work">
        {listed.map((item) => (
          <div role="listitem" key={item.id}>
            <WorkRow item={item} items={items} upstream={upstreamOf(item, record)} needsYou={needsYou} workspaceId={workspaceId} busy={busy} onRun={starter(item)}
              // Work keeps its place in the whole Epic, so it moves only while all of it is listed.
              first={filter !== 'ALL' || item === shown[0]}
              last={filter !== 'ALL' || item === shown[shown.length - 1]} onOpenTask={onOpenTask} onDecide={(decision) => decide(item, decision)}
              onMove={(offset) => move(item, offset)} onDrop={() => { setReason(''); setDropping(item); }}
              onRemove={() => { void run(() => removeFromEpic(workspaceId, item.id).then(() => setNotice('Removed from this Epic. It stays in its own list.'))); }} />
          </div>
        ))}
        {listed.length === 0 && <div className="epic-view__none">No {filter === 'ALL' ? '' : `${STATE_WORDS[filter].toLowerCase()} `}work in this Epic.</div>}
      </div>
      <div className="epic-view__composer">
        <form className="epic-view__new" onSubmit={(event) => {
          event.preventDefault();
          const name = title.trim();
          if (name === '' || busy) return;
          void run(async () => {
            await goals.createWork({ id: crypto.randomUUID(), goalId: null, epicId, title: name, kind: 'DEVELOP', acceptanceCriteria: '', completionRule: 'MERGED_DELIVERY' });
            setTitle('');
            setNotice('Added a Local work item to this Epic.');
          });
        }}>
          <Plus size={14} aria-hidden="true" />
          <LocalBadge />
          <input aria-label="New local work item" placeholder="New local work item" value={title} maxLength={500} onChange={(event) => setTitle(event.target.value)} />
          {title.trim() !== '' && <>
            <span className="epic-view__enter">↵ to add</span>
            <button type="submit" className="epic-view__add" disabled={busy}>Add</button>
          </>}
        </form>
        <span className="epic-view__hint">
          New work here is always Local. Push it to GitHub or Jira later, or use Add existing to bring in an issue, ticket, pull request or task.
        </span>
      </div>
      {record !== null && (record.breakingDown || proposals.length > 0) && (
        <section className="epic-view__proposals" aria-label="Suggested work">
          <div className="epic-view__proposals-head">
            <span className="epic-view__proposals-title">
              <Sparkles size={14} aria-hidden="true" />{record.breakingDown ? 'Suggesting work…' : `Suggested · ${proposals.length}`}
              {record.breakingDown && <LoaderCircle size={13} className="agt-spin" aria-hidden="true" />}
            </span>
            <span className="epic-view__proposals-sub">
              {record.breakingDown ? `Reading ${sourceName} and the work already here` : 'Nothing joins the Epic until you accept it. Accepted work is created as Local.'}
            </span>
            {!record.breakingDown && proposals.length > 0 && <>
              <button type="button" className="epic-view__quiet" onClick={() => setPicked(chosen.length === proposals.length ? [] : proposals.map((proposal) => proposal.id))}>
                {chosen.length === proposals.length ? 'Clear selection' : 'Select all'}
              </button>
              <button type="button" className="epic-view__quiet" disabled={busy}
                onClick={() => settle(chosen.length > 0 ? chosen : proposals.map((proposal) => proposal.id), children.dismissProposal, null)}>
                {chosen.length > 0 ? `Dismiss ${chosen.length}` : 'Dismiss all'}
              </button>
              <button type="button" className="epic-view__add" disabled={busy} onClick={() => accept(chosen.length > 0 ? chosen : proposals.map((proposal) => proposal.id))}>
                <Check size={12} aria-hidden="true" />{chosen.length > 0 ? `Accept ${chosen.length} as Local` : `Accept all ${proposals.length} as Local`}
              </button>
            </>}
          </div>
          {record.breakingDown && [0, 1, 2].map((line) => <div key={line} className="epic-proposal__skeleton" aria-hidden="true"><span /><span /></div>)}
          {proposals.map((proposal) => (
            <Proposal key={proposal.id} proposal={proposal} repository={repository} busy={busy} picked={chosen.includes(proposal.id)} open={expanded === proposal.id}
              onPick={() => setPicked(chosen.includes(proposal.id) ? chosen.filter((id) => id !== proposal.id) : [...chosen, proposal.id])}
              onOpen={() => setExpanded(expanded === proposal.id ? null : proposal.id)}
              onAccept={() => accept([proposal.id])} onDismiss={() => settle([proposal.id], children.dismissProposal, null)} />
          ))}
        </section>
      )}
      {record?.breakdownError != null && <div className="epic-view__error" role="alert">{record.breakdownError}</div>}
      {record?.breakdownNote != null && !record.breakingDown && (
        <div className="epic-view__nothing" role="status"><Sparkles size={13} aria-hidden="true" />{record.breakdownNote}</div>
      )}
      {dropped.length > 0 && (
        <details className="epic-view__dropped">
          <summary><ChevronRight size={12} aria-hidden="true" />{dropped.length} dropped</summary>
          <div className="item-list" role="list" aria-label="Dropped work">
            {dropped.map((item) => (
              <div role="listitem" key={item.id}>
                <div className="item-row epic-row is-dropped">
                  <span className="item-row__icon"><StateIcon item={item} running={false} /></span>
                  <div className="item-row__title">
                    <span title={item.title}>{item.title}</span>
                    <span className="epic-row__reason">· {item.history.filter((event) => event.action === 'DROP').at(-1)?.detail ?? 'Dropped'}</span>
                  </div>
                  <button type="button" className="epic-row__accept" aria-label={`Reopen ${item.title}`} disabled={busy}
                    onClick={() => decide(item, { action: 'REOPEN', detail: 'Reopened in the Epic view' })}>Reopen</button>
                </div>
              </div>
            ))}
          </div>
        </details>
      )}
      {dropping !== null && (
        <ConfirmDialog title={`Drop "${dropping.title}"?`} confirmLabel="Drop" destructive onCancel={() => setDropping(null)} onConfirm={() => {
          const item = dropping;
          setDropping(null);
          decide(item, { action: 'DROP', detail: reason.trim() === '' ? 'Not needed' : reason.trim(), releaseDependents: true });
        }}>
          <p>Dropped work stays in the Epic, counted apart from its progress. Work waiting on it stops waiting.</p>
          <label className="bq-confirm-dialog__choice">
            <span>Reason (optional)</span>
            <input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
        </ConfirmDialog>
      )}
      {concluding && (
        <ConfirmDialog title={`Conclude "${epic.title}"?`} confirmLabel="Conclude" confirmDisabled={outcome.trim() === ''} onCancel={() => setConcluding(false)}
          onConfirm={() => {
            setConcluding(false);
            void run(() => children.conclude(outcome));
          }}>
          <p>All its work is done. Concluding records what the Epic delivered, and the Epic leaves the drawer.</p>
          <label className="bq-confirm-dialog__choice">
            <span>Outcome</span>
            <textarea rows={5} value={outcome} onChange={(event) => setOutcome(event.target.value)} />
          </label>
        </ConfirmDialog>
      )}
    </section>
  );
}

type Detail = { text: string; tone: AttentionTone; spinning?: boolean; accept?: boolean };

/** Where the work stands, in one short detail; its full meaning is in the row's tooltip. */
export function workDetail(item: WorkItemDto, items: WorkItemDto[], needsYou: ReadonlySet<string>): Detail | null {
  const merged = item.links.find((link) => link.kind === 'PULL_REQUEST' && link.state === 'MERGED');
  if (!isOpen(item)) return item.state === 'DONE' && merged !== undefined ? { text: `${merged.label} merged`, tone: 'green' } : null;
  if (merged !== undefined) return { text: `${merged.label} merged`, tone: 'purple', accept: true };
  if (item.links.some((link) => link.kind === 'TASK' && link.status === 'Running')) return { text: 'running', tone: 'blue', spinning: true };
  if (itemNeedsYou(item, needsYou)) return { text: 'waiting on you', tone: 'amber' };
  const review = item.links.find((link) => link.kind === 'PULL_REQUEST' && link.state === 'WORKING');
  if (review !== undefined) return { text: `${review.label} in review`, tone: 'blue' };
  const waits = waitingOn(item, items);
  return waits.length === 0 ? null : { text: `after ${waits[0]}`, tone: 'neutral' };
}

function WorkRow({ item, items, upstream, needsYou, workspaceId, busy, onRun, first, last, onOpenTask, onDecide, onMove, onDrop, onRemove }: {
  item: WorkItemDto;
  items: WorkItemDto[];
  upstream: Upstream | null;
  needsYou: ReadonlySet<string>;
  workspaceId: string;
  busy: boolean;
  /** Starts the work's next attempt; null when it cannot start from here. */
  onRun: (() => void) | null;
  first: boolean;
  last: boolean;
  onOpenTask: (taskId: string) => void;
  onDecide: (decision: Decision) => void;
  onMove: (offset: number) => void;
  onDrop: () => void;
  onRemove: () => void;
}): React.JSX.Element {
  const detail = workDetail(item, items, needsYou);
  const source = sourceOfWork(item);
  const mates = items.filter((other) => other.id !== item.id && other.state !== 'DROPPED' && !item.blockedBy.includes(other.id));
  const blockers = items.filter((other) => item.blockedBy.includes(other.id));
  // An attempt that has not ended is the work's task; another starts only once it ends.
  const task = item.links.filter((link) => link.kind === 'TASK').at(-1);
  const current = task !== undefined && task.state !== 'ENDED' ? task : null;
  const side = upstream === null ? undefined : upstream.former ? 'no longer a child' : `${UPSTREAM[upstream.child.kind]}: ${upstream.child.status}`;
  // Closing upstream never makes work Done on its own; it offers the same one-click Accept a merged PR does.
  const closed = upstream !== null && !upstream.former && upstream.child.closed && isOpen(item) && detail?.accept !== true ? upstream.child : null;
  const words = [STATE_WORDS[item.state], detail?.text, side].filter((part) => part !== undefined).join(' · ');
  return (
    <ItemRow className={`epic-row is-${item.state.toLowerCase()}`} label={`${item.title}: ${words}`}
      onOpen={() => {
        if (source !== undefined) openGoalItem(source, workspaceId, onOpenTask);
        else zoomReference({ kind: 'work', workspaceId, itemId: item.id });
      }}
      icon={<StateIcon item={item} running={detail?.spinning === true} />}
      title={item.title}
      marks={source === undefined ? <LocalBadge /> : <LinkKey source={GOAL_SOURCES[source.kind]} name={source.label} size={12} />}
      meta={<>
        <span className="epic-row__state" title={words}>{STATE_WORDS[item.state]}</span>
        {item.links.filter(link => link.kind === 'TASK').map(link => <button key={link.key} type="button" className="epic-row__task"
          aria-label={`Open task ${link.title}`} onClick={event => { event.stopPropagation(); onOpenTask(link.key); }}>
          <SquareTerminal size={13} aria-hidden="true" /><span>{link.title}</span><span>{link.status}</span>
        </button>)}
        {detail !== null && <Pill tone={detail.tone} spinning={detail.spinning}>{detail.text}</Pill>}
        {side !== undefined && <span className={`epic-row__upstream${upstream?.former === true ? ' is-former' : ''}`}>{side}</span>}
        {closed !== null && (
          <button type="button" className="epic-row__accept" disabled={busy} onClick={(event) => {
            event.stopPropagation();
            onDecide({ action: 'ACCEPT', detail: closed.kind === 'JIRA' ? `Closed in Jira as ${closed.status}` : 'Closed on GitHub', completionRule: 'ACCEPTED_RESULT',
              acceptanceCriteria: item.acceptanceCriteria.trim() === '' ? item.title : undefined });
          }}>Accept</button>
        )}
        {detail?.accept === true && (
          <button type="button" className="epic-row__accept" disabled={busy} onClick={(event) => {
            event.stopPropagation();
            onDecide({ action: 'ACCEPT', detail: 'Accepted in the Epic view', acceptanceCriteria: item.acceptanceCriteria.trim() === '' ? item.title : undefined });
          }}>Accept</button>
        )}
      </>}
      actions={(
        <CardActions label={`Change ${item.title}`}>
          {isOpen(item) && current !== null && (
            <IconButton label={`Open the task for ${item.title}`} title="Open task" onClick={() => onOpenTask(current.key)}><SquareTerminal size={13} /></IconButton>
          )}
          {isOpen(item) && current === null && onRun !== null && (
            <IconButton label={`Run ${item.title}`} title="Run" disabled={busy} onClick={onRun}><Play size={13} /></IconButton>
          )}
          {isOpen(item) && (mates.length > 0 || blockers.length > 0) && (
            <select className="epic-row__wait" aria-label={`What ${item.title} waits on`} value="" disabled={busy} onClick={(event) => event.stopPropagation()}
              onChange={(event) => {
                const [action, id] = event.target.value.split(':');
                if (id !== undefined) onDecide({ action: action === 'unblock' ? 'UNBLOCK' : 'BLOCK', detail: '', blocker: id });
              }}>
              <option value="">Waits on…</option>
              {mates.map((other) => <option key={other.id} value={`block:${other.id}`}>Wait on {other.title}</option>)}
              {blockers.map((other) => <option key={other.id} value={`unblock:${other.id}`}>Stop waiting on {other.title}</option>)}
            </select>
          )}
          {isOpen(item) && !first && <IconButton label={`Move ${item.title} up`} title="Move up" disabled={busy} onClick={() => onMove(-1)}><ArrowUp size={13} /></IconButton>}
          {isOpen(item) && !last && <IconButton label={`Move ${item.title} down`} title="Move down" disabled={busy} onClick={() => onMove(1)}><ArrowDown size={13} /></IconButton>}
          {isOpen(item)
            ? <IconButton label={`Drop ${item.title}`} title="Drop" disabled={busy} onClick={onDrop}><CircleSlash size={13} /></IconButton>
            : <IconButton label={`Reopen ${item.title}`} title="Reopen" disabled={busy} onClick={() => onDecide({ action: 'REOPEN', detail: 'Reopened in the Epic view' })}>
              <RotateCcw size={13} />
            </IconButton>}
          <IconButton label={`Remove ${item.title} from the Epic`} title="Remove from the Epic" tone="danger" disabled={busy} onClick={onRemove}><X size={13} /></IconButton>
        </CardActions>
      )}
      age={<CardAge at={item.updatedAt} />}
    />
  );
}

/**
 * Work the Development model suggests. Folded, it shows its title and what it is done when; open, every part that becomes the Local work
 * item's context once accepted, in the order the work item keeps them.
 */
function Proposal({ proposal, repository, busy, picked, open, onPick, onOpen, onAccept, onDismiss }: {
  proposal: EpicProposalDto;
  /** "owner/repo" the proposal's issue and pull-request numbers link to. */
  repository: string;
  busy: boolean;
  picked: boolean;
  open: boolean;
  onPick: () => void;
  onOpen: () => void;
  onAccept: () => void;
  onDismiss: () => void;
}): React.JSX.Element {
  const parts: [string, string | null][] = [['Background', proposal.background], ['Description', proposal.description], ['Done when', proposal.doneWhen],
    ['Evidence', proposal.evidence], ['Steps to reproduce', proposal.reproduceSteps]];
  return (
    <div className={`epic-proposal${picked ? ' is-picked' : ''}`}>
      <input type="checkbox" aria-label={`Select ${proposal.title}`} checked={picked} onChange={onPick} />
      <button type="button" className="epic-proposal__text" aria-expanded={open} onClick={onOpen}>
        <span className="epic-proposal__title"><ChevronRight size={13} aria-hidden="true" />{proposal.title}</span>
        {!open && <span className="epic-proposal__done">{proposal.doneWhen}</span>}
      </button>
      <span className="epic-proposal__actions">
        <button type="button" className="epic-row__accept" title="Add to this Epic as a Local work item" disabled={busy} onClick={onAccept}>
          Accept as <span className="epic-proposal__local"><LockKeyhole size={10} aria-hidden="true" />Local</span>
        </button>
        <IconButton label={`Dismiss ${proposal.title}`} title="Dismiss" disabled={busy} onClick={onDismiss}><X size={13} /></IconButton>
      </span>
      {open && (
        <div className="epic-proposal__detail">
          <div className="epic-proposal__detail-head"><LocalBadge /><span>Becomes the work item's context, and what the agent reads before it starts.</span></div>
          <div className="epic-proposal__parts">
            {parts.map(([heading, body]) => body !== null && body.trim() !== '' && (
              <div className="epic-proposal__section" key={heading}>
                <h4>{heading}</h4>
                <MarkdownBody body={body} repository={repository === '' ? undefined : repository} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function StateIcon({ item, running }: { item: WorkItemDto; running: boolean }): React.JSX.Element {
  const props = { size: 15, strokeWidth: 1.9, 'aria-hidden': true } as const;
  if (item.state === 'DONE') return <CircleCheck className="epic-state is-done" {...props} />;
  if (item.state === 'DROPPED') return <CircleSlash className="epic-state is-dropped" {...props} />;
  if (item.state === 'WORKING') return running ? <LoaderCircle className="epic-state is-working agt-spin" {...props} /> : <CircleDotDashed className="epic-state is-working" {...props} />;
  return <Circle className="epic-state is-planned" {...props} />;
}
