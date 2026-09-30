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
import { Fragment, useState, type ReactNode } from 'react';
import { Clickable } from '../cards/Card';
import { ConfirmDialog } from '../ConfirmDialog';
import { relativeTime } from '../relativeTime';
import { settingsRoute } from '../settings/routes';
import type { AgentRuntimeStopImpactDto } from '../types';
import type { ContextDiscoveryReportDto } from './contracts';
import { Chevron, Icon, Spinner } from './icons';
import {
  BATCH_RUNS, batchLine, batchRuns, clock, currentRun, entryTimeKey, grader, kindLabel, latestReport, needsTopicReselection, nextQuestion, openCandidates, planCounts, plural, previousVersion,
  reportsNeedingYou, reviewItems, shortDate, shortSha, scopeForTopic, statusCard, topicSummary, topicsOutsidePlan, DOCUMENT_LINK_HOPS, type QuestionRow,
  type StatusAction,
} from './model';
import type { ContextActions, ContextData } from './RepositoryContextPage';

type PendingView = null | { error: string | null; retry: (() => void) | null; dismiss: () => void };

/** Text with `code` spans, as discovery and notes write file and symbol names. */
export function Coded({ text }: { text: string }) {
  return <>{text.split('`').map((part, index) => index % 2 === 1 ? <code key={index} className="rcp-code">{part}</code> : <Fragment key={index}>{part}</Fragment>)}</>;
}

export function RunLine({ children, blocked = false, workspaceId }: { children: ReactNode; blocked?: boolean; workspaceId: string }) {
  return <div className={`rcp-runline${blocked ? ' is-blocked' : ''}`}><Icon name="bolt" size={12} className="rcp-bolt" />
    <span>{children}{blocked && <> · <a href={settingsRoute('ws-agents', workspaceId)}>Workspace agent settings</a></>}</span></div>;
}

export default function ContextOverview({ data, actions, busy, pending }: { data: ContextData; actions: ContextActions; busy: boolean; pending: PendingView }) {
  const { notebook, build, report, planView } = data;
  const plan = planView.plan;
  const running = currentRun(build);
  const [showMoreTopics, setShowMoreTopics] = useState(false);
  const outside = topicsOutsidePlan(report, plan);
  const suggestions = running === null && report !== null && outside.length > 0 && (plan === null || showMoreTopics);
  const candidates = openCandidates(data.candidates, build.runs);
  const moreTopics = plan !== null && report !== null && outside.length > 0;
  return <>
    <StatusCardView data={data} actions={actions} busy={busy} pending={pending} />
    {suggestions && plan === null && <SuggestedTopics data={data} actions={actions} busy={busy} report={report} topics={outside} />}
    <ReviewSection data={data} actions={actions} />
    {plan !== null && plan.topics.length > 0 && <PlanSection data={data} actions={actions} />}
    {(notebook.entries.length > 0 || plan !== null) && <NotesSummary data={data} actions={actions} busy={busy} />}
    {!(plan === null && report !== null && outside.length > 0) && (candidates.length > 0 || moreTopics)
      && <Opportunities data={data} actions={actions} busy={busy} showMoreTopics={showMoreTopics} onMoreTopics={() => setShowMoreTopics(value => !value)} />}
    {suggestions && plan !== null && <SuggestedTopics data={data} actions={actions} busy={busy} report={report} topics={outside} />}
  </>;
}

/* ── Status card ─────────────────────────────────────────────────────── */

function StatusCardView({ data, actions, busy, pending }: { data: ContextData; actions: ContextActions; busy: boolean; pending: PendingView }) {
  const [howOpen, setHowOpen] = useState(false);
  const [impact, setImpact] = useState<AgentRuntimeStopImpactDto | null>(null);
  const [disabling, setDisabling] = useState(false);
  const { notebook, build, workspaceId } = data;
  const card = statusCard({ repository: data.repository, baseline: data.baseline, model: data.model.label, notebook, times: data.times, build,
    report: data.report, planView: data.planView, now: data.now });
  const running = currentRun(build);
  const task = build.build?.task ?? null;
  const blocked = data.model.blocker !== null;
  const canRun = build.canStart && !busy && !blocked && pending === null;
  const run = (action: StatusAction) => {
    switch (action) {
      case 'discover': return actions.discover(false);
      case 'discover-rest': return actions.discover(true);
      case 'continue': return actions.continueLearning();
      case 'disable-notes': return setDisabling(true);
      case 'plan': return actions.openDrawer({ kind: 'plan', tab: 'plan' });
      case 'sources': return actions.openDrawer({ kind: 'plan', tab: 'sources' });
      case 'plan-activity': return actions.openDrawer({ kind: 'plan', tab: 'activity' });
      case 'disc-activity': return actions.openDrawer({ kind: 'discovery', tab: 'activity' });
      case 'run-activity': return running === null ? undefined : actions.openDrawer({ kind: 'run', taskId: running.taskId });
      case 'coverage': return actions.openDrawer({ kind: 'discovery', tab: 'coverage' });
    }
  };
  const button = (spec: NonNullable<typeof card.primary>, primary: boolean) => <button type="button"
    className={`rcp-btn ${primary ? 'rcp-btn--primary' : 'rcp-btn--secondary'}`} disabled={spec.bolt && !canRun} onClick={() => run(spec.action)}>
    {spec.bolt && <Icon name="bolt" size={13} />}{spec.label}</button>;
  const hasBolt = card.primary?.bolt === true || card.secondary?.bolt === true;
  const icon: Record<typeof card.icon, ReactNode> = { spin: <Spinner size={14} />, search: <Icon name="search" />, check: <Icon name="check" size={15} />,
    doc: <Icon name="doc" />, pause: <Icon name="pause" size={15} />, review: <Icon name="review" />, alert: <Icon name="alert" size={15} /> };
  const settle = (operation: () => Promise<void>): void => void actions.perform(operation);
  return <div className="rcp-status" role="status" aria-live="polite">
    <div className="rcp-status__row">
      <span className={`rcp-status__icon rcp-tone--${card.tone}`}>{icon[card.icon]}</span>
      <div className="rcp-status__main">
        <div className="rcp-status__headline">{card.headline}{card.elapsedSince !== null
          && <span className="rcp-status__elapsed">{clock(data.now - Date.parse(card.elapsedSince))}</span>}</div>
        {card.question !== null && <div className="rcp-status__question">“{card.question}”</div>}
        {card.body !== null && <div className="rcp-status__text">{card.body}</div>}
        {(card.feed.length > 0 || card.live !== null) && <div className="rcp-feed">
          {card.feed.map((line, index) => <div className={`rcp-feed__line rcp-tone--${line.tone === 'FAILED' ? 'red' : line.tone === 'LIMIT' ? 'amber' : 'green'}`} key={index}>
            <span className="rcp-feed__mark">{line.tone === 'FAILED' ? '✕' : line.tone === 'LIMIT' ? '■' : '✓'}</span><span className="rcp-feed__text">{line.text}</span></div>)}
          {card.live !== null && <div className="rcp-feed__line is-live"><span className="rcp-feed__mark"><Spinner size={10} /></span><span className="rcp-feed__text">{card.live}</span></div>}
        </div>}
        {card.steps && <div className="rcp-steps">
          <span className="rcp-step"><span className="rcp-step__n">1</span>Discover topics</span><span className="rcp-sep">→</span>
          <span className="rcp-step"><span className="rcp-step__n">2</span>Learn the ones you pick</span><span className="rcp-sep">→</span>
          <span className="rcp-step">Agents can look notes up</span>
        </div>}
        {(card.primary !== null || card.secondary !== null || card.details !== null || card.stop) && <div className="rcp-actions">
          {card.primary !== null && button(card.primary, true)}
          {card.secondary !== null && button(card.secondary, false)}
          {card.details !== null && <button type="button" className="rcp-btn" onClick={() => run(card.details.action)}>{card.details.label}</button>}
          {card.stop && running !== null && <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy || build.build?.activeTurn?.cancellationRequestedAt != null}
            onClick={() => void actions.stop(running)}>Stop</button>}
        </div>}
        {card.runLine !== null && (hasBolt && blocked
          ? <RunLine blocked workspaceId={workspaceId}>{data.model.blocker}</RunLine>
          : <RunLine workspaceId={workspaceId}>{card.runLine}</RunLine>)}
        {card.how && <>
          <button type="button" className="rcp-how" aria-expanded={howOpen} onClick={() => setHowOpen(value => !value)}><Chevron open={howOpen} size={11} />What discovery reads</button>
          {howOpen && <ul className="rcp-how__list">
            <li>Layout, READMEs, build files and selected docs at one recorded revision — not all code.</li>
            <li>Works for any language or build system; the layout it finds is just this repository’s.</li>
            <li>Saves suggestions only. It never writes notes or changes files.</li>
            <li>May stop before surveying everything; anything left is listed.</li>
          </ul>}
        </>}
        {running !== null && (build.build?.pendingApprovals ?? 0) > 0 && <div className="rcp-alert">
          This run is waiting for approval. Learning runs cannot be steered; stop this run to end the pending request.</div>}
        {running === null && task !== null && !build.canStart && <div className="rcp-alert">
          {task.mutationClaim === 'RECOVERY' ? 'The last run’s agent process needs recovery before another run can start.'
            : task.state === 'OPEN' ? 'The last run hasn’t settled yet. It usually finishes on its own.'
              : 'The last run’s worktree is still retained. Clean it up before starting another run; notes are unaffected.'}
          <div className="rcp-actions">
            {task.mutationClaim === 'RECOVERY' && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy} onClick={() => settle(async () => {
              const found = await window.bridge.getRepositoryContextBuildStopImpact(workspaceId, task.id);
              if (found.taskId !== task.id) throw new Error('The runtime belongs to another task.');
              setImpact(found);
            })}>Stop remaining processes</button>}
            {task.state === 'OPEN' && build.build?.activeTurn === null && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy}
              onClick={() => settle(async () => actions.setBuild(await window.bridge.failRepositoryContextBuild(workspaceId, task.id, 'User ended the repository context run.')))}>
              Mark failed</button>}
            {task.state !== 'OPEN' && task.worktreeRemovedAt === null && <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy}
              onClick={() => settle(async () => actions.setBuild(await window.bridge.cleanupRepositoryContextBuild(workspaceId, task.id)))}>Clean up worktree</button>}
          </div>
        </div>}
        {pending !== null && !busy && pending.error !== null && <div className="rcp-alert" role="alert">
          The run’s start wasn’t confirmed: {pending.error} Retrying sends the same request, so it never starts a second run.
          <div className="rcp-actions">
            {pending.retry !== null && <button type="button" className="rcp-btn rcp-btn--sm" onClick={pending.retry}>Retry</button>}
            <button type="button" className="rcp-btn rcp-btn--sm" onClick={pending.dismiss}>Dismiss</button>
          </div>
        </div>}
        {card.foot !== null && <div className="rcp-foot"><span>{card.foot.text}</span>
          {card.foot.link !== null && <button type="button" className="rcp-link" onClick={() => run(card.foot.link.action)}>{card.foot.link.label}</button>}</div>}
        {card.sourceApproval !== null && <div className="rcp-foot2"><span className="rcp-foot2__label">Source approval</span>
          <span className="rcp-foot2__url">Proposed source outside your approved sources: {card.sourceApproval}</span>
          <button type="button" className="rcp-link" onClick={() => run('sources')}>Review source</button></div>}
      </div>
    </div>
    {impact !== null && <ConfirmDialog title="Stop remaining processes" confirmLabel="Stop processes" destructive busy={busy} onCancel={() => setImpact(null)}
      onConfirm={() => settle(async () => {
        actions.setBuild(await window.bridge.stopRepositoryContextBuildRuntime(workspaceId, impact.taskId, impact.generation));
        setImpact(null);
      })}>
      Stop the remaining agent runtime for this run. Its saved notes, progress and worktree are kept. {impact.liveProcess ? 'An agent process is still running.' : 'No live agent process was reported.'}
    </ConfirmDialog>}
    {disabling && card.disable !== null && <ConfirmDialog title="Disable notes from this run?" confirmLabel="Disable notes" busy={busy} onCancel={() => setDisabling(false)}
      onConfirm={() => void actions.mutate(revision => window.bridge.disableRepositoryContextRunNotes(workspaceId, { expectedRevision: revision, taskId: card.disable.taskId }),
        `${plural(card.disable.notes, 'note')} from Run ${card.disable.sequence} disabled · agents can’t find them · no agent run`).then(() => setDisabling(false))}>
      Agents stop finding the {plural(card.disable.notes, 'note')} Run {card.disable.sequence} added that nobody has changed since. Nothing is deleted; you can
      enable {card.disable.notes === 1 ? 'it' : 'them'} again from Notes. No agent run starts.
    </ConfirmDialog>}
  </div>;
}

/* ── Needs you ───────────────────────────────────────────────────────── */

function ReviewSection({ data, actions }: { data: ContextData; actions: ContextActions }) {
  const items = reviewItems(data.planView.plan, data.build.runs);
  const reported = reportsNeedingYou(data.notebook, data.reports);
  if (items.length === 0 && reported.length === 0) return null;
  return <section aria-label="Needs you">
    <div className="rcp-section-head"><span className="rcp-label">NEEDS YOU · {items.length + reported.length}</span></div>
    <div className="rcp-list">
      {items.map(item => <button type="button" key={item.url} className="rcp-review rcp-tone--orange" onClick={() => actions.openDrawer({ kind: 'plan', tab: 'sources' })}>
        <span className="rcp-review__icon"><Icon name="link" /></span>
        <span className="rcp-review__main"><span className="rcp-review__title">{item.url}</span>
          <span className="rcp-review__meta"><span className="rcp-review__kind">Source approval</span><span className="rcp-sep">·</span>
            <span className="rcp-review__origin">{item.origin}</span></span></span>
        <span className="rcp-age">{relativeTime(item.at, { suffix: false, now: data.now })}</span>
        <Icon name="next" size={14} className="rcp-go" />
      </button>)}
      {reported.map(note => {
        const report = note.contested === null ? latestReport(note) : note.grades[0];
        return <button type="button" key={note.entryId} className="rcp-review rcp-tone--amber" onClick={() => actions.openDrawer({ kind: 'note', id: note.entryId })}>
          <span className="rcp-review__icon"><Icon name="alert" /></span>
          <span className="rcp-review__main"><span className="rcp-review__title">{note.title}</span>
            <span className="rcp-review__meta">{note.contested === null
              ? <><span className="rcp-review__kind">Reported wrong</span><span className="rcp-sep">·</span>
                <span className="rcp-review__origin">by {report === undefined ? 'a task' : grader(report, data.titles)}</span></>
              : <><span className="rcp-review__kind">Agents disagree</span><span className="rcp-sep">·</span>
                <span className="rcp-review__origin">“{note.contested.statement}”</span></>}</span></span>
          <span className="rcp-age">{relativeTime(report?.gradedAt, { suffix: false, now: data.now })}</span>
          <Icon name="next" size={14} className="rcp-go" />
        </button>;
      })}
    </div>
  </section>;
}

/* ── Learning plan ───────────────────────────────────────────────────── */

export function NoteApplicability({ paths }: { paths: string[] | null }) {
  return <div className="rcp-note-caption">Notes apply to{' '}{paths === null ? 'not recorded — discover again before learning'
    : paths.length === 0 ? 'Project-wide' : paths.map(path => <span className="rcp-path-chip" key={path}>{path}</span>)}</div>;
}

export function QuestionLine({ row, onOpen }: { row: QuestionRow; onOpen: (noteId: string) => void }) {
  const glyph = row.state === 'RUNNING' ? <Spinner size={11} /> : row.state === 'ANSWERED' ? '✓' : row.state === 'UNFINISHED' ? '◐' : '○';
  return <>
    <span className={`rcp-q__glyph${row.state === 'ANSWERED' ? ' is-done' : row.state === 'UNFINISHED' ? ' is-partial' : ''}`} aria-hidden="true">{glyph}</span>
    <span className="rcp-q__text">{row.text}</span>
    <span className={`rcp-q__meta is-${row.metaTone}`}>{row.meta}</span>
    {row.noteId !== null && <button type="button" className="rcp-link" onClick={() => onOpen(row.noteId)}>Open note</button>}
  </>;
}

function PlanSection({ data, actions }: { data: ContextData; actions: ContextActions }) {
  const plan = data.planView.plan;
  const running = currentRun(data.build);
  const next = nextQuestion(data.planView);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const counts = planCounts(plan);
  const openNote = (id: string) => actions.openDrawer({ kind: 'note', id });
  return <section aria-label="Learning plan">
    <div className="rcp-section-head"><span className="rcp-label">LEARNING PLAN</span>
      <span className="rcp-section-meta">{counts.answered} of {plural(counts.total, 'question')} answered · {counts.total - counts.answered} open</span>
      <span className="rcp-spacer" /><button type="button" className="rcp-link" onClick={() => actions.openDrawer({ kind: 'plan', tab: 'plan' })}>Sources and activity</button></div>
    <div className="rcp-list">
      {plan.topics.map(topic => {
        const summary = topicSummary(topic, data.build.runs, running, data.notebook);
        const current = running?.question?.topicId === topic.id || (running === null && next?.topic.id === topic.id);
        const expanded = open[topic.id] ?? current;
        return <div className="rcp-topic" key={topic.id}>
          <button type="button" className="rcp-topic__head" aria-expanded={expanded} onClick={() => setOpen(value => ({ ...value, [topic.id]: !expanded }))}>
            <Chevron open={expanded} className="rcp-topic__chev" />
            <span className="rcp-topic__main"><span className="rcp-topic__line"><span className="rcp-topic__title">{topic.title}</span>
              {summary.running && <span className="rcp-running"><Spinner size={8} />Running</span>}</span>
              <span className="rcp-topic__meta">{summary.meta}</span></span>
            <span className="rcp-dots" role="img" aria-label={`${summary.answered} of ${plural(topic.questions.length, 'question')} answered`}>
              {summary.rows.map(row => <span key={row.index} className={`rcp-dot${row.state === 'ANSWERED' ? ' is-done' : row.state === 'RUNNING' ? ' is-running'
                : row.state === 'UNFINISHED' ? ' is-partial' : ''}`} />)}</span>
          </button>
          {expanded && <div className="rcp-questions">
            <NoteApplicability paths={topic.notePaths} />
            {summary.rows.map(row => <div className="rcp-q" key={row.index}><QuestionLine row={row} onOpen={openNote} /></div>)}
          </div>}
        </div>;
      })}
    </div>
  </section>;
}

/* ── Notes summary ───────────────────────────────────────────────────── */

function NotesSummary({ data, actions, busy }: { data: ContextData; actions: ContextActions; busy: boolean }) {
  const { notebook, times } = data;
  const entries = notebook.entries;
  const available = entries.filter(entry => entry.enabled).length;
  const knowledge = entries.filter(entry => entry.kind === 'KNOWLEDGE').length;
  const recent = [...entries].sort((left, right) => (times.entries[entryTimeKey(right)]?.at ?? '').localeCompare(times.entries[entryTimeKey(left)]?.at ?? '')).slice(0, 3);
  const viewAll = `View all ${plural(entries.length, 'note')}`;
  const restore = (entryId: string): void => void actions.mutate(revision => window.bridge.restoreRepositoryContextEntry(data.workspaceId, { expectedRevision: revision, entryId }),
    'Previous version restored · agents see it on their next lookup · no agent run');
  return <section aria-label="Notes">
    <div className="rcp-section-head"><span className="rcp-label">NOTES · {entries.length}</span>
      <span className="rcp-section-meta">{available} available to agents · {knowledge} knowledge · {plural(entries.length - knowledge, 'lesson')}</span>
      <span className="rcp-spacer" />
      {entries.length > 0 && <button type="button" className="rcp-link" onClick={() => actions.openTab('notes')}>{viewAll} →</button>}</div>
    {entries.length === 0 ? <div className="rcp-empty">No notes yet. Notes that learning runs and development tasks save appear here, available to development and review agents.</div>
      : <div className="rcp-list">
        <div className="rcp-recent-label">RECENTLY UPDATED</div>
        {recent.map(entry => <Clickable key={entry.id} className="rcp-recent" label={entry.title} onOpen={() => actions.openDrawer({ kind: 'note', id: entry.id })}>
          <span className="rcp-recent__title">{entry.title}</span>
          <span className={`rcp-kind${entry.kind === 'MEMORY' ? ' is-lesson' : ''}`}>{kindLabel(entry.kind)}</span>
          {previousVersion(notebook, entry.id) !== undefined && <button type="button" className="rcp-link" disabled={busy}
            onClick={event => { event.stopPropagation(); restore(entry.id); }}>Restore</button>}
          <span className="rcp-recent__meta">v{entry.version}{times.entries[entryTimeKey(entry)] ? ` · ${shortDate(times.entries[entryTimeKey(entry)].at, data.now)}` : ''}</span>
        </Clickable>)}
        <button type="button" className="rcp-view-all" onClick={() => actions.openTab('notes')}>{viewAll} by area →</button>
      </div>}
  </section>;
}

/* ── Learning opportunities ──────────────────────────────────────────── */

function Opportunities({ data, actions, busy, showMoreTopics, onMoreTopics }: { data: ContextData; actions: ContextActions; busy: boolean;
  showMoreTopics: boolean; onMoreTopics: () => void }) {
  const [open, setOpen] = useState<string | null>(null);
  const candidates = openCandidates(data.candidates, data.build.runs);
  const outside = topicsOutsidePlan(data.report, data.planView.plan);
  const canRun = data.build.canStart && !busy && data.model.blocker === null && currentRun(data.build) === null;
  const revision = data.baseline === null ? 'the current revision' : `${data.baseline.branch}@${shortSha(data.baseline.sha)}`;
  return <section aria-label="Learning opportunities">
    <div className="rcp-section-head"><span className="rcp-label">LEARNING OPPORTUNITIES</span><span className="rcp-spacer" />
      <span className="rcp-section-meta">Found without running a model · nothing starts until you choose</span></div>
    <div className="rcp-list">
      {candidates.map(candidate => {
        const expanded = open === candidate.id;
        const passed = candidate.checks.filter(check => check.conclusion.toLowerCase() === 'success').length;
        const bundle: Array<[string, string]> = [[`PR`, `#${candidate.number} · merged ${shortDate(candidate.mergedAt, data.now)}`],
          ...(candidate.issue === null ? [] : [['Issue', `${candidate.issue.key} · ${candidate.issue.title}`] as [string, string]]),
          ...(candidate.taskTitle === null ? [] : [['Task', `“${candidate.taskTitle}”${candidate.taskResult ? ` · ${candidate.taskResult}` : ''}`] as [string, string]]),
          ...(candidate.review === null ? [] : [['Review', candidate.review.combinedResult] as [string, string]]),
          ...(candidate.checks.length === 0 ? [] : [['Tests', `${plural(candidate.checks.length, 'check')} recorded · ${passed} passed`] as [string, string]])];
        return <div className="rcp-opp" key={candidate.id}>
          <button type="button" className="rcp-opp__head" aria-expanded={expanded} onClick={() => setOpen(expanded ? null : candidate.id)}>
            <span className="rcp-opp__icon rcp-tone--purple"><Icon name="merge" size={15} /></span>
            <span className="rcp-opp__main"><span className="rcp-opp__title">{candidate.title} <span className="rcp-opp__num">#{candidate.number}</span></span>
              <span className="rcp-opp__meta">Merged {relativeTime(candidate.mergedAt, { now: data.now })}{candidate.taskTitle !== null && ` · from task “${candidate.taskTitle}”`}</span></span>
            <span className="rcp-opp__kind">Merged work</span>
            <Chevron open={expanded} className="rcp-topic__chev" />
          </button>
          {expanded && <div className="rcp-opp__body">
            <div className="rcp-mini-label">THE AGENT RECEIVES</div>
            {bundle.map(([key, value]) => <div className="rcp-bundle" key={key}><span className="rcp-bundle__key">{key}</span><span>{value}</span></div>)}
            <div className="rcp-opp__note">A merged PR records a decision, not proof that every conclusion holds. The agent checks against code at {revision} and may save a note, an update, or nothing.</div>
            <div className="rcp-opp__actions">
              <button type="button" className="rcp-btn rcp-btn--secondary rcp-btn--xs" disabled={!canRun} onClick={() => actions.learnCandidate(candidate.id)}>
                <Icon name="bolt" size={12} />Learn from this PR</button>
              <span>{data.model.blocker ?? `Starts 1 learning run · ${data.model.label}`}</span>
            </div>
          </div>}
        </div>;
      })}
      {data.planView.plan !== null && outside.length > 0 && <button type="button" className="rcp-opp-topics" aria-expanded={showMoreTopics} onClick={onMoreTopics}>
        <span className="rcp-opp__icon rcp-tone--blue"><Icon name="doc" size={15} /></span>
        <span className="rcp-opp__main"><span className="rcp-opp__title">{plural(outside.length, 'more suggested topic')}</span>
          <span className="rcp-opp__meta">From discovery · {outside.slice(0, 3).map(topic => topic.title).join(', ')}{outside.length > 3 ? '…' : ''}</span></span>
        <span className="rcp-opp__action">{showMoreTopics ? 'Hide' : 'Choose topics'}</span>
      </button>}
    </div>
  </section>;
}

/* ── Suggested topics and the selection bar ──────────────────────────── */

const SOURCE_KIND: Record<ContextDiscoveryReportDto['sources'][number]['kind'], string> = {
  REPOSITORY_FILE: 'Repository file', DOCUMENTATION_ROOT: 'Web docs root', DOCUMENTATION_PAGE: 'Web page',
};

function sourceBound(kind: ContextDiscoveryReportDto['sources'][number]['kind'], sha: string): string {
  if (kind === 'REPOSITORY_FILE') return `at ${shortSha(sha)}`;
  return kind === 'DOCUMENTATION_ROOT' ? `pages under this path, up to ${DOCUMENT_LINK_HOPS} links from its start page` : 'this page only';
}

function SuggestedTopics({ data, actions, busy, report, topics }: { data: ContextData; actions: ContextActions; busy: boolean;
  report: ContextDiscoveryReportDto; topics: ContextDiscoveryReportDto['topics'] }) {
  const [selected, setSelected] = useState<string[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [excluded, setExcluded] = useState<string[]>([]);
  const [limitsOpen, setLimitsOpen] = useState(false);
  const plan = data.planView.plan;
  const revision = data.baseline === null ? shortSha(report.baselineSha) : `${data.baseline.branch}@${shortSha(report.baselineSha)}`;
  const chosen = topics.filter(topic => selected.includes(topic.id) && topic.notePaths !== null);
  const sourcesOf = (topic: ContextDiscoveryReportDto['topics'][number]) => topic.sourceIds.filter(id => !excluded.includes(id));
  const questions = chosen.reduce((sum, topic) => sum + topic.questions.length, 0);
  const sources = new Set(chosen.flatMap(sourcesOf)).size;
  const newerDiscovery = plan !== null && plan.reportId !== report.id && nextQuestion(data.planView) !== null;
  const needsReselection = needsTopicReselection(plan);
  const canLearn = chosen.length > 0 && data.build.canStart && !busy && data.model.blocker === null && !newerDiscovery && !needsReselection;
  const toggle = (id: string, list: string[], set: (value: string[]) => void) => set(list.includes(id) ? list.filter(value => value !== id) : [...list, id]);
  return <section aria-label="Suggested topics">
    <div className="rcp-section-head"><span className="rcp-label">{plan === null ? 'SUGGESTED TOPICS' : 'SUGGESTED TOPICS NOT IN YOUR PLAN'} · {topics.length}</span>
      <span className="rcp-spacer" /><span className="rcp-section-meta">From discovery at {revision} · {shortDate(report.createdAt, data.now)}</span></div>
    <div className="rcp-list">
      {topics.map(topic => {
        const open = expanded === topic.id;
        const scope = scopeForTopic(data.notebook, topic.notePaths);
        const existing = scope === undefined ? 0 : data.notebook.entries.filter(entry => entry.scopeId === scope.id).length;
        const notePaths = topic.notePaths;
        const areaName = notePaths === null ? 'Note targets need discovery' : notePaths.length === 0 ? 'Project-wide' : scope?.title ?? null;
        const evidence = topic.evidenceReadIds.map(id => report.evidence.find(read => read.readId === id)).filter(read => read !== undefined);
        const checkbox = `rcp-topic-${topic.id}`;
        return <div className="rcp-sugg" key={topic.id}>
          <div className="rcp-sugg__row">
            <input type="checkbox" className="rcp-check" id={checkbox} checked={selected.includes(topic.id)} disabled={notePaths === null}
              onChange={() => toggle(topic.id, selected, setSelected)} />
            <label className="rcp-sugg__label" htmlFor={checkbox}>
              <span className="rcp-sugg__line"><span className="rcp-sugg__title">{topic.title}</span>
                {existing > 0 && <span className="rcp-existing" title="Learning adds notes or updates ones agents wrote; notes you wrote or edited stay as they are">
                  {plural(existing, 'saved note')}</span>}</span>
              <span className="rcp-sugg__meta">{areaName !== null && <><span className="rcp-sugg__area">{areaName}</span>{notePaths !== null && notePaths.length > 0 && <span className="rcp-sep">·</span>}</>}
                {notePaths !== null && notePaths.length > 0 && <span className="rcp-sugg__path">{notePaths[0]}</span>}
                {notePaths !== null && notePaths.length > 1 && <span className="rcp-sugg__more">+{notePaths.length - 1}</span>}</span>
            </label>
            <span className="rcp-sugg__count">{plural(topic.questions.length, 'question')} · {plural(sourcesOf(topic).length, 'source')}</span>
            <button type="button" className="rcp-inspect" aria-expanded={open} aria-label={`${open ? 'Hide details for' : 'Inspect'} ${topic.title}`}
              onClick={() => setExpanded(open ? null : topic.id)}>Inspect<Icon name="down" size={11} /></button>
          </div>
          {open && <div className="rcp-sugg__detail">
            <div>
              <NoteApplicability paths={notePaths} />
              <div className="rcp-mini-label">LEARNING QUESTIONS · ONE PER RUN</div>
              {topic.questions.map((question, index) => <div className="rcp-sugg__q" key={index}><span className="rcp-sugg__qn">{index + 1}</span><span>{question}</span></div>)}
              <div className="rcp-mini-label rcp-mt12">CODE AVAILABLE TO READ</div>
              <div className="rcp-chips">{(topic.paths.length === 0 ? ['/'] : topic.paths).map(path => <span className="rcp-path-chip" key={path}>{path}</span>)}</div>
            </div>
            <div>
              <div className="rcp-mini-label">PROPOSED SOURCES</div>
              {topic.sourceIds.length === 0 && <div className="rcp-why">No external source was proposed; learning reads the code paths at {shortSha(report.baselineSha)}.</div>}
              {topic.sourceIds.map(id => {
                const source = report.sources.find(value => value.id === id);
                if (source === undefined) return null;
                const box = `rcp-src-${topic.id}-${id}`;
                return <div className="rcp-src" key={id}>
                  <input type="checkbox" id={box} checked={!excluded.includes(id)} onChange={() => toggle(id, excluded, setExcluded)} />
                  <label htmlFor={box}><span className="rcp-src__target">{source.location}</span>
                    <span className="rcp-src__sub">{SOURCE_KIND[source.kind]} · {sourceBound(source.kind, report.baselineSha)}</span></label>
                </div>;
              })}
              <div className="rcp-mini-label rcp-mt12">WHY THIS TOPIC</div>
              <div className="rcp-why"><span className="rcp-why__mark">•</span><span><Coded text={topic.why} /></span></div>
              {evidence.map(read => <div className="rcp-why" key={read.readId}><span className="rcp-why__mark">•</span>
                <span>Read <code className="rcp-code">{read.path}</code> · lines {read.startLine}–{read.endLine}</span></div>)}
            </div>
          </div>}
        </div>;
      })}
    </div>
    <div className="rcp-bar">
      <div className="rcp-bar__main">
        <div className="rcp-bar__title">{chosen.length ? `${plural(chosen.length, 'topic')} selected · ${plural(questions, 'question')} · ${plural(sources, 'source')}` : 'Select topics to learn'}</div>
        <div className="rcp-bar__line"><Icon name="bolt" size={12} className="rcp-bolt" />
          <span>{needsReselection ? 'Discard the earlier plan and reselect topics; saved notes are kept'
            : newerDiscovery ? 'Finish or discard the current plan to learn from this newer discovery'
            : data.model.blocker ?? `${plan === null ? 'Saves a learning plan, then runs' : 'Adds them to your plan, then runs'} ${batchLine(batchRuns(plan, questions), null, data.model.label)}`}</span>
          <span className="rcp-sep">·</span>
          <button type="button" className="rcp-link" aria-expanded={limitsOpen} onClick={() => setLimitsOpen(value => !value)}>How runs work</button></div>
      </div>
      <button type="button" className="rcp-btn rcp-btn--primary" disabled={!canLearn} onClick={() => actions.learn(chosen.map(topic => topic.id), excluded)}>
        <Icon name="bolt" size={13} />Learn selected</button>
      {limitsOpen && <div className="rcp-limits" role="dialog" aria-label="How runs work">
        <div className="rcp-limits__title">Each learning run</div>
        <div>The agent chooses how many reads it needs within the approved sources.</div>
        <div>Links stay within approved documentation roots, up to {DOCUMENT_LINK_HOPS} hops.</div>
        <div className="rcp-mt6">Each run uses the configured agent timeout. One click runs each open question in turn, up to {BATCH_RUNS} runs; Stop ends the batch, and saved progress and notes are kept.</div>
      </div>}
    </div>
  </section>;
}
