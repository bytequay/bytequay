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

import { useEffect, useState } from 'react';
import { useAsyncAction } from '../useAsyncAction';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type { AgentTaskDto, AgentTurnDto, Bridge, DevelopmentLearningSummaryDto } from '../types';
import MarkdownBody from '../pulls/MarkdownBody';
import ReviewCommentIndex from '../pulls/ReviewCommentIndex';
import ReviewComposer from '../pulls/agent-review/ReviewComposer';
import { explanationRun, latestRun } from '../pulls/agent-review/model';
import { ChangedItem, WhatChanged } from '../pulls/agent-review/SummaryTimeline';
import { TaskReviewSection, useTaskReview, type TaskReviewOptions } from '../pulls/agent-review/TaskReviewSummary';
import LocalReviewThreadCard from '../pulls/local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto } from '../pulls/local-review/types';
import type { useLocalReviewThreads } from '../pulls/local-review/useLocalReviewThreads';

export function developmentLearningCards(summary: DevelopmentLearningSummaryDto): { id: string; title: string; body: string }[] {
  return [
    { id: 'purpose', title: 'Change intention', body: summary.purpose },
    ...summary.commits.map(commit => ({ id: `commit:${commit.sha}`, title: `${commit.sha.slice(0, 7)} · ${commit.title}`, body: commit.summary })),
  ];
}

type LearningBridge = Pick<Bridge, 'getAgentTask'> & Partial<Pick<Bridge, 'getDevelopmentLearning'>>;
type LearningThreads = ReturnType<typeof useLocalReviewThreads>;

function LearningItem({ card, title, sha, turn, repository, disabledReason, onAsk, onSelectCommit, discussions, threads }: {
  card: ReturnType<typeof developmentLearningCards>[number]; title: string; sha?: string; turn: AgentTurnDto; repository: string;
  disabledReason: string | null; onAsk: (body: string) => Promise<void>; onSelectCommit?: (sha: string) => void;
  /** The questions asked about this item, each with its answers. */
  discussions: LocalReviewThreadDto[]; threads: LearningThreads;
}) {
  const [composing, setComposing] = useState(false);
  const [body, setBody] = useState('');
  const { busy, error, setError, run } = useAsyncAction(cause => cause instanceof Error ? cause.message : 'Could not send your question.');
  const close = () => { setComposing(false); setBody(''); setError(null); };
  const send = async () => {
    if (busy || disabledReason || !body.trim()) return;
    return run(async () => {
      await onAsk(body.trim()); close();
    });
  };
  return <div className="agr-summary-card" data-learning-card={card.id}>
    <ChangedItem title={title} sha={sha} byline={`Developer · ${relativeTime(turn.endedAt ?? turn.createdAt)} · Private`}
      onViewChanges={sha !== undefined && onSelectCommit !== undefined ? () => onSelectCommit(sha) : undefined}
      ask={!composing && <button type="button" className="agr-link" disabled={disabledReason !== null} title={disabledReason ?? undefined}
        onClick={() => setComposing(true)}>Ask agent</button>}>
      <MarkdownBody body={card.body} repository={repository} />
    </ChangedItem>
    {composing && <ReviewComposer repository={repository} value={body} autoFocus busy={busy} disabledReason={disabledReason}
      ariaLabel={`Ask the agent about ${card.title.toLowerCase()}`} placeholder="Ask for an explanation or confirmation…"
      primaryLabel="Ask agent" onChange={setBody} onSubmit={send} onCancel={close} />}
    {error !== null && <span className="lrc__error" role="alert">{error}</span>}
    {discussions.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...threads} />)}
  </div>;
}

function commitCount(count: number): string {
  return `${count} ${count === 1 ? 'commit' : 'commits'}`;
}

/**
 * A development task's Summary: what changed, as the kept report's intention and commits with the one picked from them,
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
  // What changed explains the change whoever wrote it: until the task writes its own summary, the reviewer's is shown.
  const reviewed = reviewer.review === null ? null : explanationRun(reviewer.review) ?? latestRun(reviewer.review);
  const borrowed = !loading && (summary == null || turn === null) && reviewed?.summary != null;
  return <ReviewCommentIndex className="agr-summary-page" aria-label="Development summary">
    {error !== null && <p className="agt-note" role="alert">{error} <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button></p>}
    {!borrowed && <WhatChanged selected={shown} onSelect={setSelected}
      items={summary == null || turn === null ? [] : [{ id: 'purpose', label: 'Intention' },
        ...summary.commits.map((commit, index) => ({ id: cards[index + 1].id, label: commit.title, ordinal: index + 1 }))]}
      meta={<>
        {refining && <span className="agr-changed__refining" role="status"><span className="agr-spinner" aria-hidden="true" />
          Refining summary{reviewer.source === null ? '' : ` for ${reviewer.source.headSha.slice(0, 7)}`}…</span>}
        {summary != null && written !== null && <span title={`Summary written ${new Date(written).toLocaleString()}`}>updated {relativeTime(written)}</span>}
        {last !== undefined && <span>{commitCount(summary?.commits.length ?? 0)} through {onSelectCommit
          ? <button type="button" className="agr-link" title="Open this commit's diff in Changes"
            onClick={event => { event.preventDefault(); onSelectCommit(last.sha); }}>{last.sha.slice(0, 7)}</button>
          : <code>{last.sha.slice(0, 7)}</code>}</span>}
        {(summary == null || turn === null) && <span role="status">
          {loading ? 'Loading learning summary…' : refining ? 'The learning cards will appear when this step finishes.'
            : 'After a turn changes code or commits, the agent will refine the summary here automatically.'}
        </span>}
      </>}>
      {/* Unselected items stay mounted, so a question being written survives switching to another. */}
      {summary != null && turn !== null && cards.map((card, index) => <div key={card.id} hidden={shown !== card.id}>
        <LearningItem card={card} turn={turn} repository={repository} disabledReason={disabledReason} onSelectCommit={onSelectCommit}
          title={index === 0 ? card.title : summary.commits[index - 1].title} sha={index === 0 ? undefined : summary.commits[index - 1].sha}
          discussions={discussionsOf(card.id)} threads={actions}
          onAsk={async body => {
            const current = await bridge.getAgentTask(task.id, task.workspaceId);
            await localReview.createLearning({ taskId: task.id, workspaceId: task.workspaceId, summaryTurnId: turn.id, cardId: card.id,
              body, expectedTaskVersion: current.version });
          }} />
      </div>)}
      {previous.length > 0 && <details className="agr-learning-history"><summary>Earlier summary discussions · {previous.length}</summary>
        {previous.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...actions} />)}
      </details>}
    </WhatChanged>}
    {review !== undefined && <TaskReviewSection options={review} state={reviewer} explain={borrowed} />}
  </ReviewCommentIndex>;
}
