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
import { Fragment, useEffect, useMemo, useState } from 'react';
import { Activity, BookOpen, Check, CircleAlert } from 'lucide-react';
import { ActivityIcon, memberLabel } from '../../agents/AgentConversation';
import type { AgentEventEnvelope } from '../../agents/events';
import { readRecentAgentHistory } from '../../agents/model';
import { formatDuration, projectTranscript, turnElapsedMs, type ActivityMember, type TranscriptTurn } from '../../agents/transcript';
import { Chevron } from '../../Chevron';
import { relativeTime } from '../../relativeTime';
import type { AgentReviewDto, ReviewRunDto } from './contracts';
import { evidenceTarget } from './ReviewEvidenceLinks';
import { completeCoverage, evidenceGroups, provenanceFacts, reviewElapsed, reviewPasses, reviewProgress, reviewRunning, reviewScope, reviewStatus, shortPath, shortSha } from './model';

const PASSES = [
  ['Investigation', 'Map the change, record candidates'],
  ['Evidence collection', 'Gather context for each claim'],
  ['Validation', 'Accept or reject candidates'],
] as const;

const VISIBLE_ACTIONS = 5;

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

function ProgressCard({ review, run, now }: { review: AgentReviewDto; run: ReviewRunDto; now: number }) {
  const running = reviewRunning(review);
  const status = reviewStatus(review);
  const passes = reviewPasses(run);
  const current = passes.indexOf('active');
  const elapsed = reviewElapsed(review, now);
  // The compact fact reads "2/5" while this run owns the explanation, and a plain commit count when an earlier run does.
  const commits = reviewProgress(review, true)?.facts[0] ?? '';
  const gaps = reviewProgress(review)?.gaps ?? [];
  const complete = completeCoverage(run);
  return <details className="agr-run" aria-label="Review progress" open>
    <summary className="agr-run__head">
      <Chevron />
      <strong>Review progress</strong>
      <span className={`pull-detail__review-chip is-${status.tone}`}>{status.label === 'Reviewing' && current >= 0 ? PASSES[current][0] : status.label}</span>
      <span className="agr-spacer" />
      {elapsed !== null && <span className="agr-run__elapsed">{elapsed}</span>}
    </summary>
    <ol className="agr-run__passes">
      {PASSES.map(([label, hint], index) => <li key={label} className={`is-${passes[index]}`} aria-current={index === current ? 'step' : undefined}>
        <span className="agr-run__dot">{passes[index] === 'done' ? <Check size={11} strokeWidth={3} aria-hidden="true" />
          : index === current && running ? <span className="agr-spinner" aria-hidden="true" /> : index + 1}</span>
        <strong>{label}</strong>
        <span>{index === 0 && current === 0 ? `${plural(run.commits.length, 'commit')} · ${plural(run.changedFiles, 'file')}` : hint}</span>
      </li>)}
    </ol>
    {gaps.length > 0 && <div className="agr-progress__gaps">{gaps.map(gap => <span key={gap}>{gap}</span>)}</div>}
    <dl className="agr-run__facts">
      <div><dt>Scope</dt><dd>{reviewScope(run)}</dd></div>
      <div><dt>{commits.includes('/') ? 'Commits explained' : 'Commits'}</dt><dd>{commits.replace('/', ' of ')}</dd></div>
      <div><dt>Evidence</dt><dd>{plural(run.evidence.length, 'reference')}</dd></div>
      <div><dt>Coverage</dt><dd className={complete ? 'is-complete' : 'is-open'}>{complete ? 'Complete' : running ? 'Not recorded yet' : 'Not recorded'}</dd></div>
    </dl>
  </details>;
}

type ReviewHistory = { events: AgentEventEnvelope[]; complete: boolean };

/** The newest pages of the review task's own history, read again while the review runs. */
function useReviewHistory(reviewId: string, taskId: string, live: boolean): ReviewHistory {
  const [history, setHistory] = useState<ReviewHistory>({ events: [], complete: true });
  useEffect(() => {
    let current = true;
    let reading = false;
    const read = () => {
      if (reading) return;
      reading = true;
      // Starting inside a promise turns a bridge without history into a rejection; a background read fails silently.
      void Promise.resolve()
        .then(() => readRecentAgentHistory({ readAgentHistory: (id, before, after) => window.bridge.readAgentReviewHistory(reviewId, id, before, after) }, taskId))
        .then(({ page, earlier }) => {
          if (current) setHistory({ events: [...earlier, page].flatMap(item => item.events), complete: (earlier[0] ?? page).beforeCursor === null });
        })
        .catch(() => {})
        .finally(() => { reading = false; });
    };
    read();
    if (!live) return () => { current = false; };
    // ponytail: a two-second poll; follow the review's stream instead if the list has to move with every event.
    const timer = window.setInterval(read, 2000);
    return () => { current = false; window.clearInterval(timer); };
  }, [reviewId, taskId, live]);
  return history;
}

/** What the agent did in the given turns, newest first, each with the time it started. */
export function reviewActivity(events: AgentEventEnvelope[], turnIds: ReadonlySet<string>): {
  turns: TranscriptTurn[];
  actions: Array<{ member: ActivityMember; at: string | null }>;
} {
  const own = events.filter(event => event.turnId !== null && turnIds.has(event.turnId));
  // A member keeps the id of the event that started it.
  const started = new Map(own.map(event => [event.eventId, event.occurredAt]));
  const turns = projectTranscript(own);
  const members = turns.flatMap(turn => turn.blocks).flatMap(block => block.kind === 'activity' ? block.members : []);
  return { turns, actions: members.reverse().map(member => ({ member, at: started.get(member.id) ?? null })) };
}

function since(at: string | null, now: number): string {
  if (at === null) return '';
  const seconds = Math.floor((now - Date.parse(at)) / 1000);
  return seconds < 60 ? `${Math.max(0, seconds)}s ago` : relativeTime(at, { now });
}

function ActivityCard({ review, run, now, visible }: { review: AgentReviewDto; run: ReviewRunDto; now: number; visible: boolean }) {
  const running = reviewRunning(review);
  const history = useReviewHistory(review.id, review.task.id, running && visible);
  const [all, setAll] = useState(false);
  const turnIds = [...run.turnIds, ...(review.activeTurn === null ? [] : [review.activeTurn.id])].join(' ');
  const { turns, actions } = useMemo(() => reviewActivity(history.events, new Set(turnIds.split(' '))), [history.events, turnIds]);
  if (actions.length === 0 && !running) return null;
  const clock = running ? now : Date.now();
  const worked = turns.reduce((total, turn) => total + (turnElapsedMs(turn, clock) ?? 0), 0);
  return <details className="agr-run" aria-label="Review activity" open>
    <summary className="agr-run__head is-ruled">
      <Chevron />
      <Activity size={15} aria-hidden="true" />
      <strong>Activity</strong>
      {actions.length > 0 && <span className="agr-run__meta">{history.complete
        ? `${plural(actions.length, 'action')} · ${plural(turns.length, 'turn')} · worked ${formatDuration(worked)}` : `latest ${plural(actions.length, 'action')}`}</span>}
      <span className="agr-spacer" />
      {running && <span className="agr-run__live">Live</span>}
    </summary>
    {actions.length === 0 ? <p className="agr-run__empty">No actions yet.</p> : <ol className="agr-run__actions">
      {(all ? actions : actions.slice(0, VISIBLE_ACTIONS)).map(({ member, at }, index) => {
        const live = running && index === 0 && member.kind !== 'file' && (member.state === 'STARTED' || member.state === 'PROGRESS');
        const label = memberLabel(member);
        return <li key={member.id} className={live ? 'is-live' : undefined}>
          <ActivityIcon member={member} />
          <span title={label}>{label}</span>
          <time>{live ? 'now' : since(at, clock)}</time>
        </li>;
      })}
    </ol>}
    {actions.length > VISIBLE_ACTIONS && <button type="button" className="agr-run__more" aria-expanded={all} onClick={() => setAll(value => !value)}>
      {all ? 'Show less' : `Show all ${actions.length} actions`}
    </button>}
  </details>;
}

function EvidenceCard({ review, run }: { review: AgentReviewDto; run: ReviewRunDto }) {
  const complete = completeCoverage(run);
  const groups = evidenceGroups(run.evidence, review.number === null ? null : `${review.repo}#${review.number}`);
  return <details className="agr-run" aria-label="Context and evidence" open>
    <summary className="agr-run__head is-ruled">
      <Chevron />
      <BookOpen size={15} aria-hidden="true" />
      <strong>Context &amp; evidence</strong>
      <span className="agr-run__meta">{plural(run.evidence.length, 'reference')} · frozen at head {shortSha(run.headSha)}</span>
    </summary>
    {groups.length === 0 && <p className="agr-run__empty">No context or validation evidence was recorded.</p>}
    {groups.map((group, index) => <details key={group.subject} className="agr-run__group" open={index === 0}>
      <summary>
        <Chevron />
        <strong>{group.title}</strong>
        {group.subject !== '' && <code title={group.subject}>{shortPath(group.subject)}</code>}
        <span className="agr-spacer" />
        <span className="agr-run__count">{group.items.length}</span>
      </summary>
      <div className="agr-run__items">
        {/* One name per run keeps a single reference open; an evidence link opens its target the same way. */}
        {group.items.map(({ evidence, label, detail }) => {
          const { notes, facts } = provenanceFacts(evidence.provenance);
          return <details key={evidence.ref} className="agr-run__item" name={`review-evidence-${run.id}`} id={evidenceTarget(run.id, evidence.ref)} tabIndex={-1}>
            <summary title={evidence.source}>
              <span className="agr-card__refs">{evidence.ref}</span>
              <span>{label}</span>
              <span className="agr-run__detail">{detail}</span>
            </summary>
            <div className="agr-run__item-body">
              {notes !== '' && <p>{notes}</p>}
              {facts.length > 0 && <dl>{facts.map(([name, value], order) => <Fragment key={order}><dt>{name}</dt><dd>{value}</dd></Fragment>)}</dl>}
              <pre className="agr-evidence__excerpt">{evidence.excerpt}</pre>
              {evidence.explanation && <div className="agr-evidence__why">{evidence.explanation}</div>}
            </div>
          </details>;
        })}
      </div>
    </details>)}
    <footer className={`agr-run__foot${complete ? ' is-complete' : ''}`}>
      {complete ? <Check size={13} aria-hidden="true" /> : <CircleAlert size={13} aria-hidden="true" />}
      {complete ? 'Complete coverage was recorded for the review scope.'
        : reviewRunning(review) ? 'Complete coverage has not been recorded yet.' : 'Complete coverage has not been recorded.'}
    </footer>
  </details>;
}

/** A review on a task's Summary timeline: where its passes stand, what the reviewer just did, and what it has read. */
export default function ReviewRunCards({ review, run, now, visible = true }: {
  review: AgentReviewDto; run: ReviewRunDto; now: number;
  /** False while the page is hidden behind another tab; the activity list then stops re-reading history. */
  visible?: boolean;
}) {
  return <>
    <ProgressCard review={review} run={run} now={now} />
    <ActivityCard review={review} run={run} now={now} visible={visible} />
    <EvidenceCard review={review} run={run} />
  </>;
}
