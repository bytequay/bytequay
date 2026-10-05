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
import { ArrowDown, ArrowUp, Circle, CircleCheck, CircleDotDashed, CircleSlash, LoaderCircle, Plus, RotateCcw, Search, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { CardActions, CardAge, IconButton, ItemRow, LinkKey, Pill } from '../cards/Card';
import type { GoalItemDto, WorkItemDecision, WorkItemDto } from '../goals/contracts';
import { openGoalItem } from '../goals/GoalLayer';
import { Candidate, useCandidates } from '../goals/GoalPanel';
import { GOAL_SOURCES, goalRef, isOpen, itemNeedsYou, progressWidths, refKey, summarize, waitingOn } from '../goals/model';
import type { Goals } from '../goals/useGoals';
import { zoomReference } from '../githubReference';
import { statusPill, type AttentionItem, type AttentionTone } from '../home/attention';
import { jiraIssueLocation } from '../jira/contracts';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import { EpicGlyph } from './EpicDrawer';
import { epicIdentity, epicWork, joinEpic, orderEpic, removeFromEpic } from './epics';
import '../css/epic-view.css';

type Decision = Omit<WorkItemDecision, 'operationId' | 'expectedRevision'>;

/**
 * One Epic's work over the page, the only place it is added, ordered and decided: quick add takes a title, Add existing finds an issue,
 * ticket, pull request or task, and each row says where the work stands in one short detail.
 */
export default function EpicView({ epic, workspaceId, work, needsYou, goals, epicTitle, onOpenTask, onClose }: {
  epic: AttentionItem;
  workspaceId: string;
  /** Every work item of the workspace; the view shows the Epic's. */
  work: WorkItemDto[];
  needsYou: ReadonlySet<string>;
  goals: Pick<Goals, 'createWork' | 'decideWork' | 'search'>;
  /** Names another Epic, for the notice when work moves here from it. */
  epicTitle: (epicId: string) => string | null;
  onOpenTask: (taskId: string) => void;
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
          {notice !== null && <div className="epic-view__notice" role="status">{notice}</div>}
          {error !== null && <div className="epic-view__error" role="alert">{error}</div>}
          {shown.length > 0 && (
            <div className="item-list" role="list" aria-label="Work">
              {shown.map((item) => (
                <div role="listitem" key={item.id}>
                  <WorkRow item={item} items={items} needsYou={needsYou} workspaceId={workspaceId} busy={busy} first={item === shown[0]}
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
                    <WorkRow item={item} items={items} needsYou={needsYou} workspaceId={workspaceId} busy={busy} first last onOpenTask={onOpenTask}
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

function WorkRow({ item, items, needsYou, workspaceId, busy, first, last, onOpenTask, onDecide, onMove, onDrop, onRemove }: {
  item: WorkItemDto;
  items: WorkItemDto[];
  needsYou: ReadonlySet<string>;
  workspaceId: string;
  busy: boolean;
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
  const words = [STATE_WORDS[item.state], detail?.text].filter((part) => part !== undefined).join(' · ');
  return (
    <ItemRow className={`epic-row is-${item.state.toLowerCase()}`} label={`${item.title}: ${words}`}
      onOpen={() => { if (primary !== undefined) openGoalItem(primary, workspaceId, onOpenTask); }}
      icon={<StateIcon item={item} running={detail?.spinning === true} />}
      title={item.title}
      marks={source === undefined ? undefined : <LinkKey source={GOAL_SOURCES[source.kind]} name={source.label} size={12} />}
      meta={<>
        <span className="epic-row__state" title={words}>{STATE_WORDS[item.state]}</span>
        {detail !== null && <Pill tone={detail.tone} spinning={detail.spinning}>{detail.text}</Pill>}
        {detail?.accept === true && (
          <button type="button" className="epic-row__accept" disabled={busy} onClick={(event) => {
            event.stopPropagation();
            onDecide({ action: 'ACCEPT', detail: 'Accepted in the Epic view', acceptanceCriteria: item.acceptanceCriteria.trim() === '' ? item.title : undefined });
          }}>Accept</button>
        )}
      </>}
      actions={(
        <CardActions label={`Change ${item.title}`}>
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
