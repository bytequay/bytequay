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
import { Check } from 'lucide-react';
import { Fragment, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ActivityIcon, memberLabel } from '../../agents/AgentConversation';
import type { AgentEventEnvelope } from '../../agents/events';
import { readRecentAgentHistory } from '../../agents/model';
import { formatDuration, projectTranscript, turnElapsedMs, type ActivityMember, type TranscriptTurn } from '../../agents/transcript';
import { Chevron } from '../../Chevron';
import { plural } from '@bytequay/ui/displayText';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type { AgentReviewDto, ReviewRunDto } from './contracts';
import { completeCoverage, evidenceGroups, provenanceFacts, reviewPasses, reviewProgress, reviewRunning, reviewScope, shortPath, shortSha } from './model';
import { evidenceTarget } from './ReviewEvidenceLinks';

export const PASSES = [
  ['Investigation', 'Map the change, record candidates'],
  ['Evidence collection', 'Gather context for each claim'],
  ['Validation', 'Accept or reject candidates'],
] as const;

const VISIBLE_ACTIONS = 6;

type ReviewHistory = { events: AgentEventEnvelope[]; complete: boolean };

/** The newest pages of the review task's own history, then only the events after them while the review runs. */
function useReviewHistory(reviewId: string | null, taskId: string | null, live: boolean): ReviewHistory {
  const [history, setHistory] = useState<ReviewHistory>({ events: [], complete: true });
  useEffect(() => {
    if (reviewId === null || taskId === null) return;
    let current = true;
    let reading = false;
    let latest: number | null = null;
    const readHistory = (id: string, before?: string, after?: string) => window.bridge.readAgentReviewHistory(reviewId, id, before, after);
    const read = () => {
      if (reading) return;
      reading = true;
      // Starting inside a promise turns a bridge without history into a rejection; a background read fails silently.
      void Promise.resolve()
        .then(async () => {
          if (latest === null) {
            const { page, earlier } = await readRecentAgentHistory({ readAgentHistory: readHistory }, taskId);
            if (!current) return;
            latest = page.events.at(-1)?.sequence ?? 0;
            setHistory({ events: [...earlier, page].flatMap(item => item.events), complete: (earlier[0] ?? page).beforeCursor === null });
            return;
          }
          // The newest page holds whole command and tool outputs, often megabytes; a poll reads only what came after it.
          // ponytail: keeps every event since the first read; drop old ones if a single review's history outgrows memory.
          const seen = latest;
          const newer = (await readHistory(taskId, undefined, String(seen))).events.filter(event => event.sequence > seen);
          if (!current || newer.length === 0) return;
          latest = newer[newer.length - 1].sequence;
          setHistory(shown => ({ ...shown, events: [...shown.events, ...newer] }));
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

export type ReviewRunActivity = ReturnType<typeof reviewActivity> & { complete: boolean };

/** The run's activity, which the Activity tab lists and counts; history is read only while the tab is shown. */
export function useReviewActivity(review: AgentReviewDto | null, run: ReviewRunDto | null, shown: boolean, live: boolean): ReviewRunActivity {
  const history = useReviewHistory(shown ? review?.id ?? null : null, review?.task.id ?? null, shown && live);
  const turnIds = [...run?.turnIds ?? [], ...(review?.activeTurn == null ? [] : [review.activeTurn.id])].join(' ');
  const activity = useMemo(() => reviewActivity(history.events, new Set(turnIds.split(' '))), [history.events, turnIds]);
  return { ...activity, complete: history.complete };
}

function since(at: string | null, now: number): string {
  if (at === null) return '';
  const seconds = Math.floor((now - Date.parse(at)) / 1000);
  return seconds < 60 ? `${Math.max(0, seconds)}s ago` : relativeTime(at, { now });
}

/** The Activity tab: where the run's passes stand, what it covered, and what the reviewer just did. */
export function ReviewActivityPanel({ review, run, now, activity, warnings, children }: {
  review: AgentReviewDto; run: ReviewRunDto; now: number; activity: ReviewRunActivity;
  /** What the run could not verify, above everything else. */
  warnings?: ReactNode;
  /** The run's details, after its actions. */
  children?: ReactNode;
}) {
  const running = reviewRunning(review);
  const passes = reviewPasses(run);
  const current = passes.indexOf('active');
  // The compact fact reads "2/5" while this run owns the explanation, and a plain commit count when an earlier run does.
  const commits = reviewProgress(review, true)?.facts[0] ?? '';
  const gaps = reviewProgress(review)?.gaps ?? [];
  const complete = completeCoverage(run);
  const [all, setAll] = useState(false);
  const { turns, actions } = activity;
  const clock = running ? now : Date.now();
  const worked = turns.reduce((total, turn) => total + (turnElapsedMs(turn, clock) ?? 0), 0);
  return <div className="agr-activity" aria-label="Review activity">
    {warnings}
    <ol className="agr-run__passes">
      {PASSES.map(([label, hint], index) => <li key={label} className={`is-${passes[index]}`} aria-current={index === current ? 'step' : undefined}>
        <span className="agr-run__dot">{passes[index] === 'done' ? <Check size={11} strokeWidth={3} aria-hidden="true" />
          : index === current && running ? <span className="agr-spinner" aria-hidden="true" /> : index + 1}</span>
        <strong>{label}</strong>
        <span>{index === 0 && current === 0 ? `${plural(run.commits.length, 'commit')} · ${plural(run.changedFiles, 'file')}` : hint}</span>
      </li>)}
    </ol>
    {(!running || gaps.length > 0) && <p className="agr-run__facts">
      {!running && <>
        <span>Scope <strong>{reviewScope(run)}</strong></span>
        <span>{commits.includes('/') ? 'Commits explained' : 'Commits'} <strong>{commits.replace('/', ' of ')}</strong></span>
        <span>Coverage <strong className={complete ? 'is-complete' : 'is-open'}>{complete ? 'Complete' : 'Not recorded'}</strong></span>
      </>}
      {gaps.map(gap => <span key={gap} className="is-gap">{gap}</span>)}
      {!running && actions.length > 0 && <span>{activity.complete
        ? `${plural(actions.length, 'action')} · ${plural(turns.length, 'turn')} · ${formatDuration(worked)}` : `latest ${plural(actions.length, 'action')}`}</span>}
    </p>}
    {actions.length === 0 ? running && <p className="agr-run__empty">No actions yet.</p> : <ol className="agr-run__actions">
      {(all ? actions : actions.slice(0, VISIBLE_ACTIONS)).map(({ member, at }, index) => {
        const live = running && index === 0 && member.kind !== 'file' && (member.state === 'STARTED' || member.state === 'PROGRESS');
        const label = memberLabel(member);
        return <li key={member.id} className={live ? 'is-live' : undefined}>
          {live ? <span className="agr-spinner" aria-hidden="true" /> : <ActivityIcon member={member} />}
          <span title={label}>{label}</span>
          <time>{live ? 'now' : since(at, clock)}</time>
        </li>;
      })}
    </ol>}
    {actions.length > VISIBLE_ACTIONS && <button type="button" className="agr-link agr-run__more" aria-expanded={all} onClick={() => setAll(value => !value)}>
      {all ? 'Show less' : `Show all ${actions.length} actions`}
    </button>}
    {children}
  </div>;
}

/** The Evidence tab: what the reviewer read, by where it came from. */
export function ReviewEvidencePanel({ review, run }: { review: AgentReviewDto; run: ReviewRunDto }) {
  const groups = evidenceGroups(run.evidence, review.number === null ? null : `${review.repo}#${review.number}`);
  return <div className="agr-evidence-list" aria-label="Context and evidence">
    <p className="agr-review__caption">{plural(run.evidence.length, 'reference')} · frozen at head {shortSha(run.headSha)}</p>
    {groups.length === 0 && <p className="agr-run__empty">No context or validation evidence was recorded.</p>}
    {groups.map(group => <details key={group.subject} className="agr-run__group" open>
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
  </div>;
}
