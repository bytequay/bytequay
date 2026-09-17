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
import Avatar from '../Avatar';
import type { AgentTaskDto, AgentTurnDto, Bridge, DevelopmentLearningSummaryDto } from '../types';
import MarkdownBody from '../pulls/MarkdownBody';
import { ReviewThreadCard, ReviewThreadMessage } from '../pulls/ReviewThreadCard';
import ReviewComposer from '../pulls/agent-review/ReviewComposer';
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

function LearningCard({ card, turn, repository, disabledReason, onAsk }: {
  card: ReturnType<typeof developmentLearningCards>[number]; turn: AgentTurnDto; repository: string;
  disabledReason: string | null; onAsk: (body: string) => Promise<void>;
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
  return <article className={`agr-learning-card agr-learning-card--${card.id === 'purpose' ? 'purpose' : 'commit'}`} data-learning-card={card.id}>
    <ReviewThreadCard resolved={false} privateLabel="Private"
      location={<span className="pr-local-review__location agr-learning-card__title">{card.id === 'purpose' ? card.title : 'Review & discussion'}</span>}
      controls={<footer className={composing ? 'agr-learning-card__reply' : undefined}>
        {composing ? <ReviewComposer repository={repository} value={body} autoFocus busy={busy} disabledReason={disabledReason}
          ariaLabel={`Ask the agent about ${card.title.toLowerCase()}`} placeholder="Ask for an explanation or confirmation…"
          primaryLabel="Ask agent" onChange={setBody} onSubmit={() => { void send(); }} onCancel={close} />
          : <button type="button" className="pr-thread-reply__open" disabled={disabledReason !== null} title={disabledReason ?? undefined}
            onClick={() => setComposing(true)}>Ask agent</button>}
        {error !== null && <span className="pr-thread-reply__error" role="alert">{error}</span>}
      </footer>}>
      <ReviewThreadMessage timestamp={turn.endedAt ?? turn.createdAt}
        author={<><Avatar login="Agent" size={22} allowGitHubFallback={false} /><strong>Agent</strong></>}>
        <MarkdownBody body={card.body} repository={repository} />
      </ReviewThreadMessage>
    </ReviewThreadCard>
  </article>;
}

/** Reads the kept report, independently of the conversation's bounded history window. */
export default function DevelopmentLearningPanel({ task, repository, refreshKey, refining, disabledReason, localReview, bridge }: {
  task: AgentTaskDto; repository: string; refreshKey: number; refining: boolean; disabledReason: string | null;
  localReview: LearningThreads; bridge: LearningBridge;
}) {
  const [turn, setTurn] = useState<AgentTurnDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
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
  const threads = localReview.threads.filter(thread => thread.learningAnchor != null);
  const cards = summary == null ? [] : developmentLearningCards(summary);
  const previous = threads.filter(thread => thread.learningAnchor?.turnId !== turn?.id
    || !cards.some(card => card.id === thread.learningAnchor?.cardId));
  const actions = { ...localReview, disabledReason };
  const renderCard = (card: ReturnType<typeof developmentLearningCards>[number]) => {
    if (turn === null) return null;
    const discussions = threads.filter(thread => thread.learningAnchor?.turnId === turn.id && thread.learningAnchor.cardId === card.id);
    return discussions.length > 0 ? <article className={`agr-learning-card agr-learning-card--${card.id === 'purpose' ? 'purpose' : 'commit'}`} data-learning-card={card.id}>
      {discussions.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...actions} />)}
    </article> : <LearningCard key={`${turn.id}:${card.id}`} card={card} turn={turn} repository={repository} disabledReason={disabledReason}
      onAsk={async body => {
        const current = await bridge.getAgentTask(task.id, task.workspaceId);
        await localReview.createLearning({ taskId: task.id, workspaceId: task.workspaceId, summaryTurnId: turn.id, cardId: card.id,
          body, expectedTaskVersion: current.version });
      }} />;
  };
  return <section className="agr-learning-cards" aria-label="Development learning summary">
    {refining && <p className="agt-note" role="status">Refining summary… Updating the intention and commit explanations.</p>}
    {error !== null && <p className="agt-note" role="alert">{error} <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button></p>}
    {summary == null || turn === null ? <p className="agr-card__empty" role="status">
      {loading ? 'Loading learning summary…' : refining ? 'The learning cards will appear when this step finishes.'
        : 'After a turn changes code or commits, the agent will refine the summary here automatically.'}
    </p> : <>
      <div className="agr-learning-cards__scope">{summary.commits.length} {summary.commits.length === 1 ? 'commit' : 'commits'} · Updated {new Date(turn.endedAt ?? turn.createdAt).toLocaleString()}</div>
      {cards[0] !== undefined && renderCard(cards[0])}
      {cards.length > 1 && <section className="agr-commit-section" aria-label="Commit implementation">
        <div className="agr-commit-section__head"><h2>Commit implementation</h2></div>
        <ol className="agr-commit-list">{cards.slice(1).map((card, index) => <li key={card.id}>
          <h3 className="agr-commit-list__title">{summary.commits[index].title} <code>{summary.commits[index].sha.slice(0, 7)}</code></h3>
          {renderCard(card)}
        </li>)}</ol>
      </section>}
    </>}
    {previous.length > 0 && <details className="agr-learning-history"><summary>Earlier summary discussions · {previous.length}</summary>
      {previous.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...actions} />)}
    </details>}
  </section>;
}
