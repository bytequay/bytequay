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

import React, { useState } from 'react';
import { ArrowDown, ArrowUp, Circle, CircleCheck, CircleDotDashed, CircleSlash, ListTree, LoaderCircle, Play, Plus, RotateCcw, Search, SquareTerminal, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { CardActions, CardAge, IconButton, ItemRow, LinkKey, Pill } from '../cards/Card';
import type { EpicChildDto, EpicRecordDto, GoalItemDto, WorkItemDecision, WorkItemDto } from '../goals/contracts';
import { openGoalItem, startTicket } from '../goals/GoalLayer';
import { Candidate, useCandidates } from '../goals/GoalPanel';
import { GOAL_SOURCES, goalRef, isOpen, itemNeedsYou, progressWidths, refKey, summarize, waitingOn } from '../goals/model';
import type { Goals } from '../goals/useGoals';
import { zoomReference } from '../githubReference';
import { statusPill, type AttentionItem, type AttentionTone } from '../home/attention';
import { jiraIssueLocation } from '../jira/contracts';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import { EpicGlyph } from './EpicDrawer';
import { epicIdentity, epicWork, joinEpic, orderEpic, removeFromEpic, useEpicRecord } from './epics';
import '../css/epic-view.css';

type Decision = Omit<WorkItemDecision, 'operationId' | 'expectedRevision'>;
/** The Epic's child a row's work came from, as the source last reported it, or one the source no longer reports. */
type Upstream = { child: EpicChildDto; former: boolean };

const UPSTREAM: Record<EpicChildDto['kind'], string> = { ISSUE: 'GitHub', JIRA: 'Jira' };

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
 * One Epic's work over the page, the only place it is added, ordered and decided: quick add takes a title, Add existing finds an issue,
 * ticket, pull request or task, and each row says where the work stands in one short detail.
 */
export default function EpicView({ epic, workspaceId, repository, work, needsYou, goals, epicTitle, onOpenTask, onStartIssue, onStartWork, onClose }: {
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
  /** Opens the issue's task composer with an instruction the user reviews and sends; `replaces` names the ended attempt. */
  onStartIssue: (issue: number, instruction: string, replaces?: string) => void;
  /** Opens the task composer for work without an issue or ticket; the task it creates is the work's attempt. */
  onStartWork: (itemId: string, draft: string) => void;
  onClose: () => void;
}): React.JSX.Element {
  const zoom = useZoom(onClose);
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
  const [dropping, setDropping] = useState<WorkItemDto | null>(null);
  const [reason, setReason] = useState('');
  const own = goalRef(epic);
  const candidates = useCandidates({ items }, goals.search, adding);
  const found = candidates.results.filter((item) => own === null || refKey(item) !== refKey(own));
  const children = useEpicRecord(workspaceId, epicId, epic.jira?.connectionId ?? null);
  const record = children.record;
  const unsynced = record === null ? [] : record.children.filter((child) => !record.synced.includes(child.identity));

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
  /** How the work starts: through its issue's or ticket's composer, or the task composer with its title; null when it cannot start here. */
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
    if (ticket !== undefined) return () => startTicket(ticket.key, instruction, replaces);
    return () => onStartWork(item.id, item.title);
  };
  const status = epic.jira ? statusPill(epic.jira.status) : { name: 'Open', tone: 'green' as const };
  const sourceName = epic.jira ? epic.jira.key : `#${epic.number}`;

  return (
    <>
      {!zoom.full && <ZoomScrim onClose={zoom.close} />}
      <section className={`${zoom.cardClass} epic-view`} aria-label={`Epic: ${epic.title}`}>
        <header className="epic-view__head">
          <EpicGlyph />
          <h2 className="epic-view__title" title={epic.title}>{epic.title}</h2>
          <button type="button" className="epic-view__source" title={`Open ${sourceName}`}
            onClick={() => zoomReference(epic.jira ? { kind: 'jira', ...jiraIssueLocation(epic.jira) } : { kind: 'issue', repo: epic.repo, number: epic.number, workspaceId })}>
            <LinkKey source={epic.jira ? 'jira' : 'issue'} name={sourceName} size={13} />
          </button>
          <Pill tone={status.tone}>{status.name}</Pill>
          <ZoomActions {...zoom} label="Epic" className="card-iconbtn" />
        </header>
        <div className="epic-view__body">
          <div className="epic-view__progress">
            {summary.total > 0 && (
              <div className="epic-progress" aria-hidden="true">
                <span className="is-done" style={{ width: widths.finished }} />
                <span className="is-working" style={{ width: widths.working }} />
              </div>
            )}
            <div className="epic-view__counts">
              {summary.total === 0 ? <span>No work yet</span>
                : summary.finished === summary.total ? <span className="is-ready">All {summary.total} done · ready to conclude</span>
                  : <>
                    <span className="is-strong">{summary.finished} of {summary.total} done</span>
                    {summary.working > 0 && <span>{summary.working} working</span>}
                    {summary.idle > 0 && <span>{summary.idle} planned</span>}
                  </>}
              {summary.dropped > 0 && <span className="is-muted">{summary.dropped} dropped</span>}
            </div>
          </div>
          {record?.offered === true && (
            <div className="epic-view__found">
              <ListTree size={14} aria-hidden="true" />
              <span className="epic-view__found-count" title={unsynced.map((child) => `${child.label} ${child.title}`).join('\n')}>
                Found in {epic.jira ? 'Jira' : 'GitHub'} · {unsynced.length}
              </span>
              <button type="button" className="epic-view__sync" disabled={busy} onClick={() => { void run(children.sync); }}>Sync</button>
              <button type="button" className="epic-view__link" disabled={busy} onClick={() => { void run(children.dismiss); }}>Not now</button>
            </div>
          )}
          {notice !== null && <div className="epic-view__notice" role="status">{notice}</div>}
          {error !== null && <div className="epic-view__error" role="alert">{error}</div>}
          {shown.length > 0 && (
            <div className="item-list" role="list" aria-label="Work">
              {shown.map((item) => (
                <div role="listitem" key={item.id}>
                  <WorkRow item={item} items={items} upstream={upstreamOf(item, record)} needsYou={needsYou} workspaceId={workspaceId} busy={busy} onRun={starter(item)}
                    first={item === shown[0]}
                    last={item === shown[shown.length - 1]} onOpenTask={onOpenTask} onDecide={(decision) => decide(item, decision)}
                    onMove={(offset) => move(item, offset)} onDrop={() => { setReason(''); setDropping(item); }}
                    onRemove={() => { void run(() => removeFromEpic(workspaceId, item.id)); }} />
                </div>
              ))}
            </div>
          )}
          <form className="epic-view__new" onSubmit={(event) => {
            event.preventDefault();
            const name = title.trim();
            if (name === '' || busy) return;
            void run(async () => {
              await goals.createWork({ id: crypto.randomUUID(), goalId: null, epicId, title: name, kind: 'DEVELOP', acceptanceCriteria: '', completionRule: 'MERGED_DELIVERY' });
              setTitle('');
            });
          }}>
            <Plus size={14} aria-hidden="true" />
            <input aria-label="New work item" placeholder="New work item" value={title} maxLength={500} onChange={(event) => setTitle(event.target.value)} />
            <button type="button" className="epic-view__link" aria-expanded={adding} onClick={() => setAdding((value) => !value)}>
              <Search size={13} aria-hidden="true" />Add existing
            </button>
          </form>
          {adding && (
            <div className="goal-search epic-view__search">
              <label className="goal-search__field">
                <Search size={14} aria-hidden="true" />
                <input autoFocus aria-label="Find an issue, ticket, pull request or task" placeholder="Find an issue, ticket, pull request or task"
                  value={candidates.query} onChange={(event) => candidates.setQuery(event.target.value)} />
              </label>
              {found.map((item) => <Candidate key={refKey(item)} item={item} onAdd={add} />)}
            </div>
          )}
          {dropped.length > 0 && (
            <details className="epic-view__dropped">
              <summary>{dropped.length} dropped</summary>
              <div className="item-list" role="list" aria-label="Dropped work">
                {dropped.map((item) => (
                  <div role="listitem" key={item.id}>
                    <WorkRow item={item} items={items} upstream={upstreamOf(item, record)} needsYou={needsYou} workspaceId={workspaceId} busy={busy} onRun={null}
                      first last onOpenTask={onOpenTask}
                      onDecide={(decision) => decide(item, decision)} onMove={() => {}} onDrop={() => {}}
                      onRemove={() => { void run(() => removeFromEpic(workspaceId, item.id)); }} />
                  </div>
                ))}
              </div>
            </details>
          )}
        </div>
      </section>
      {dropping !== null && (
        <ConfirmDialog title={`Drop "${dropping.title}"?`} confirmLabel="Drop" destructive onCancel={() => setDropping(null)} onConfirm={() => {
          const item = dropping;
          setDropping(null);
          decide(item, { action: 'DROP', detail: reason.trim() === '' ? 'Not needed' : reason.trim(), releaseDependents: true });
        }}>
          <p>Dropped work stays in the Epic, counted apart from its progress. Work waiting on it stops waiting.</p>
          <input className="bq-confirm-dialog__choice" aria-label="Reason" placeholder="Reason (optional)" value={reason} onChange={(event) => setReason(event.target.value)} />
        </ConfirmDialog>
      )}
    </>
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

const STATE_WORDS: Record<WorkItemDto['state'], string> = { PLANNED: 'Planned', WORKING: 'Working', DONE: 'Done', DROPPED: 'Dropped' };

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
  const source = item.links.find((link) => link.kind === 'ISSUE' || link.kind === 'JIRA');
  const primary = source ?? item.links.find((link) => link.kind === 'TASK') ?? item.links.find((link) => link.kind === 'PULL_REQUEST');
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
      onOpen={() => { if (primary !== undefined) openGoalItem(primary, workspaceId, onOpenTask); }}
      icon={<StateIcon item={item} running={detail?.spinning === true} />}
      title={item.title}
      marks={source === undefined ? undefined : <LinkKey source={GOAL_SOURCES[source.kind]} name={source.label} size={12} />}
      meta={<>
        <span className="epic-row__state" title={words}>{STATE_WORDS[item.state]}</span>
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

function StateIcon({ item, running }: { item: WorkItemDto; running: boolean }): React.JSX.Element {
  const props = { size: 15, strokeWidth: 1.9, 'aria-hidden': true } as const;
  if (item.state === 'DONE') return <CircleCheck className="epic-state is-done" {...props} />;
  if (item.state === 'DROPPED') return <CircleSlash className="epic-state is-dropped" {...props} />;
  if (item.state === 'WORKING') return running ? <LoaderCircle className="epic-state is-working agt-spin" {...props} /> : <CircleDotDashed className="epic-state is-working" {...props} />;
  return <Circle className="epic-state is-planned" {...props} />;
}
