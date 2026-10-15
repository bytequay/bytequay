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

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Diff } from 'lucide-react';
import Avatar from '../Avatar';
import { relativeTime } from '../relativeTime';
import type { AgentTaskDto, AgentTurnDto, Bridge, DevelopmentLearningSummaryDto } from '../types';
import MarkdownBody from '../pulls/MarkdownBody';
import ReviewCommentIndex from '../pulls/ReviewCommentIndex';
import ReviewComposer from '../pulls/agent-review/ReviewComposer';
import { SummaryOutlineItem } from '../pulls/agent-review/SummaryTimeline';
import { TaskReviewControls, TaskReviewSection, useTaskReview, type TaskReviewOptions } from '../pulls/agent-review/TaskReviewSummary';
import LocalCard, { LocalCardMessage, SummaryCardTitle } from '../pulls/local-review/LocalCard';
import LocalReviewThreadCard from '../pulls/local-review/LocalReviewThreadCard';
import type { useLocalReviewThreads } from '../pulls/local-review/useLocalReviewThreads';

export function developmentLearningCards(summary: DevelopmentLearningSummaryDto): { id: string; title: string; body: string }[] {
  return [
    { id: 'purpose', title: 'Change intention', body: summary.purpose },
    ...summary.commits.map(commit => ({ id: `commit:${commit.sha}`, title: `${commit.sha.slice(0, 7)} · ${commit.title}`, body: commit.summary })),
  ];
}

type LearningBridge = Pick<Bridge, 'getAgentTask'> & Partial<Pick<Bridge, 'getDevelopmentLearning'>>;
type LearningThreads = ReturnType<typeof useLocalReviewThreads>;

function LearningCard({ card, sha, turn, repository, disabledReason, onAsk, badges }: {
  card: ReturnType<typeof developmentLearningCards>[number]; sha?: string; turn: AgentTurnDto; repository: string;
  disabledReason: string | null; onAsk: (body: string) => Promise<void>; badges?: ReactNode;
}) {
  const [composing, setComposing] = useState(false);
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const close = () => { setComposing(false); setBody(''); setError(null); };
  const send = async () => {
    if (busy || disabledReason || !body.trim()) return;
    setBusy(true);
    setError(null);
    try { await onAsk(body.trim()); close(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not send your question.'); }
    finally { setBusy(false); }
  };
  return <LocalCard tone={sha === undefined ? 'purpose' : 'commit'} title={<SummaryCardTitle sha={sha} />} badges={badges}
    controls={<footer className={`lrc__foot${composing ? ' is-composing' : ''}`}>
      {composing ? <ReviewComposer repository={repository} value={body} autoFocus busy={busy} disabledReason={disabledReason}
        ariaLabel={`Ask the agent about ${card.title.toLowerCase()}`} placeholder="Ask for an explanation or confirmation…"
        primaryLabel="Ask agent" onChange={setBody} onSubmit={send} onCancel={close} />
        : <button type="button" className="lrc__ask" disabled={disabledReason !== null} title={disabledReason ?? undefined}
          onClick={() => setComposing(true)}>Ask agent</button>}
      {error !== null && <span className="lrc__error" role="alert">{error}</span>}
    </footer>}>
    <LocalCardMessage timestamp={turn.endedAt ?? turn.createdAt} author="Developer"
      avatar={<Avatar login="Agent" size={22} allowGitHubFallback={false} />}>
      <MarkdownBody body={card.body} repository={repository} />
    </LocalCardMessage>
  </LocalCard>;
}

function commitCount(count: number): string {
  return `${count} ${count === 1 ? 'commit' : 'commits'}`;
}

/**
 * A development task's Summary: an outline of the kept report's intention and commits beside the card picked from it,
 * then the task's own reviewer when the page offers one. Reads the kept report,
 * independently of the conversation's bounded history window.
 */
export default function DevelopmentLearningPanel({ task, repository, refreshKey, refining, disabledReason, localReview, bridge, onSelectCommit, review }: {
  task: AgentTaskDto; repository: string; refreshKey: number; refining: boolean; disabledReason: string | null;
  localReview: LearningThreads; bridge: LearningBridge;
  onSelectCommit?: (sha: string) => void;
  /** Offers the task's own reviewer; its status and start action sit in the strip above the report. */
  review?: TaskReviewOptions;
}) {
  const [turn, setTurn] = useState<AgentTurnDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const reviewer = useTaskReview(task, review);
  const reviewSection = useRef<HTMLDivElement>(null);
  const read = bridge.getDevelopmentLearning;
  useEffect(() => {
    let current = true;
    setLoading(true);
    void Promise.resolve().then(() => read?.(task.id, task.workspaceId) ?? null)
      .then(result => { if (current) { setTurn(result); setError(null); } })
      .catch(cause => { if (current) setError(cause instanceof Error ? cause.message : 'Could not load the learning summary.'); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [read, task.id, task.workspaceId, task.version, task.developmentContinuationUnfinished, refreshKey, retry]);
  const summary = turn?.developmentResult?.learningSummary;
  const written = turn === null ? null : turn.endedAt ?? turn.createdAt;
  const threads = localReview.threads.filter(thread => thread.learningAnchor != null);
  const cards = summary == null ? [] : developmentLearningCards(summary);
  const previous = threads.filter(thread => thread.learningAnchor?.turnId !== turn?.id
    || !cards.some(card => card.id === thread.learningAnchor?.cardId));
  const actions = { ...localReview, disabledReason };
  const last = summary?.commits.at(-1);
  const discussionsOf = (cardId: string) => threads.filter(thread => thread.learningAnchor?.turnId === turn?.id && thread.learningAnchor?.cardId === cardId);
  const [selected, setSelected] = useState('purpose');
  const shown = cards.some(card => card.id === selected) ? selected : 'purpose';
  // A link to a summary discussion opens the card it belongs to; earlier reports' discussions stay in their own list.
  const focus = review?.focusReviewCard;
  const [followed, setFollowed] = useState<typeof focus>(null);
  const target = focus?.target;
  const linked = target?.kind === 'local' ? threads.find(thread => thread.id === target.threadId && thread.learningAnchor?.turnId === turn?.id) : undefined;
  if (focus !== followed && linked?.learningAnchor != null) {
    setFollowed(focus);
    setSelected(linked.learningAnchor.cardId);
  }
  const outlineItem = (card: ReturnType<typeof developmentLearningCards>[number], label: string, ordinal?: number) =>
    <SummaryOutlineItem key={card.id} label={label} ordinal={ordinal} current={card.id === shown} onSelect={() => setSelected(card.id)}
      messages={discussionsOf(card.id).reduce((count, thread) => count + thread.messages.length, 0)} />;
  const renderCard = (card: ReturnType<typeof developmentLearningCards>[number], sha?: string) => {
    if (turn === null) return null;
    const discussions = discussionsOf(card.id);
    // The card's header carries the commit's diff link, since the outline already names the commit.
    const viewChanges = sha !== undefined && onSelectCommit !== undefined ? <button type="button" className="agr-explanation__diff"
      title="Open this commit's diff in Changes" onClick={event => { event.preventDefault(); onSelectCommit(sha); }}>
      <Diff size={14} aria-hidden="true" />View changes</button> : undefined;
    return <article className="agr-summary-card" data-learning-card={card.id}>
      {discussions.length > 0 ? discussions.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...actions} badges={viewChanges} />)
        : <LearningCard key={`${turn.id}:${card.id}`} card={card} sha={sha} turn={turn} repository={repository} disabledReason={disabledReason} badges={viewChanges}
          onAsk={async body => {
            const current = await bridge.getAgentTask(task.id, task.workspaceId);
            await localReview.createLearning({ taskId: task.id, workspaceId: task.workspaceId, summaryTurnId: turn.id, cardId: card.id,
              body, expectedTaskVersion: current.version });
          }} />}
    </article>;
  };
  return <ReviewCommentIndex className="agr-summary-page" aria-label="Development summary">
    <div className="agr-summary-strip">
      <div className="agr-summary-strip__facts">
        {refining && <span className="agr-summary-strip__refining" role="status"><span className="agr-spinner" aria-hidden="true" />
          Refining summary{reviewer.source === null ? '' : ` for ${reviewer.source.headSha.slice(0, 7)}`}…</span>}
        {summary != null && written !== null && <span title={`Summary written ${new Date(written).toLocaleString()}`}>
          Summary updated <strong>{relativeTime(written)}</strong></span>}
        {last !== undefined && <span>covers <strong>{commitCount(summary?.commits.length ?? 0)}</strong> through {onSelectCommit
          ? <button type="button" className="agr-summary-strip__sha" title="Open this commit's diff in Changes"
            onClick={() => onSelectCommit(last.sha)}>{last.sha.slice(0, 7)}</button>
          : <code>{last.sha.slice(0, 7)}</code>}</span>}
        {(summary == null || turn === null) && <span role="status">
          {loading ? 'Loading learning summary…' : refining ? 'The learning cards will appear when this step finishes.'
            : 'After a turn changes code or commits, the agent will refine the summary here automatically.'}
        </span>}
      </div>
      {review !== undefined && <TaskReviewControls state={reviewer}
        onShowReview={() => reviewSection.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })} />}
    </div>
    {error !== null && <p className="agt-note" role="alert">{error} <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button></p>}
    {(summary != null && turn !== null || previous.length > 0) && <div className="agr-summary-outline">
      {summary != null && turn !== null && <nav className="agr-summary-nav" aria-label="Summary outline">
        {outlineItem(cards[0], 'Intention')}
        {summary.commits.length > 0 && <span className="agr-summary-nav__heading">Commits</span>}
        {summary.commits.map((commit, index) => outlineItem(cards[index + 1], commit.title, index + 1))}
      </nav>}
      {/* Unselected cards stay mounted, so a question being written survives switching to another card. */}
      <div className="agr-summary-reader">
        {summary != null && turn !== null && <>
          <div hidden={shown !== 'purpose'}>{renderCard(cards[0])}</div>
          {summary.commits.map((commit, index) => <div key={commit.sha} hidden={shown !== cards[index + 1].id}>
            {renderCard(cards[index + 1], commit.sha)}
          </div>)}
        </>}
        {previous.length > 0 && <details className="agr-learning-history"><summary>Earlier summary discussions · {previous.length}</summary>
          {previous.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...actions} />)}
        </details>}
      </div>
    </div>}
    {review !== undefined && <div className="agr-timeline agr-summary-page__review" ref={reviewSection}>
      <TaskReviewSection options={review} state={reviewer} />
    </div>}
  </ReviewCommentIndex>;
}
