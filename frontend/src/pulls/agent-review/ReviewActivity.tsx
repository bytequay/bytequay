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
import { useState } from 'react';
import type { ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import { AgentConversation, ConversationComposer, ConversationQueue, useConversationHistory, type ApprovalDecider, type ConversationHistory, type ConversationQueuedInput } from '../../agents/AgentConversation';
import { projectTranscript, withApprovalAuthority } from '../../agents/transcript';
import type { AgentEventEnvelope } from '../../agents/events';
import type { PendingMessage } from '../../agents/model';
import type { AgentPendingApprovalDto, AgentStreamConnectionState, AgentTurnDto, ModelOptionDto } from '../../types';
import type { AgentReviewDto } from './contracts';
import type { LocalReviewThreads } from '../local-review/types';
import { reviewRecoveryRequired, reviewRequests, reviewRunning } from './model';

const EMPTY_HISTORY: ConversationHistory = { before: null, after: null, loading: false, onLoad: () => {}, onFollow: () => {} };

/** Review owns its scoped actions; Tasks owns the shared conversation presentation. */
export default function ReviewActivity({
  review, events, turns, models, pendingApprovals, now, composer, busy,
  localReview, onOpenReview, pendingMessage = null,
  connection = 'open', history = EMPTY_HISTORY, lastSequence = events.at(-1)?.sequence ?? 0,
  onCompose, onSend, onSteer, onQueue, onStop, onDecide, onRemoveQueued, onSteerQueued,
}: {
  review: AgentReviewDto;
  localReview?: LocalReviewThreads;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  events: AgentEventEnvelope[];
  pendingMessage?: PendingMessage | null;
  turns: Record<string, AgentTurnDto>;
  models: ModelOptionDto[];
  pendingApprovals: AgentPendingApprovalDto[];
  now: number;
  composer: string;
  busy: boolean;
  connection?: AgentStreamConnectionState;
  history?: ConversationHistory;
  lastSequence?: number;
  onCompose: (text: string) => void;
  onSend: () => void;
  onSteer: () => void;
  onQueue: () => void;
  onStop: () => void;
  onDecide: ApprovalDecider;
  onRemoveQueued: (queuedInputId: string) => void;
  onSteerQueued?: (queuedInputId: string) => void;
}) {
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const latestTurn = Object.values(turns).at(-1);
  const scroll = useConversationHistory(events, latestTurn?.endedAt, history);
  const pendingIds = new Set(pendingApprovals.map(approval => approval.approvalId));
  const projected = withApprovalAuthority(projectTranscript(events, null, turns, history.before === null, history.after === null && (scroll.following || (events.at(-1)?.sequence ?? 0) >= lastSequence)), pendingIds);
  const running = reviewRunning(review);
  const recovery = reviewRecoveryRequired(review);
  const waiting = review.pendingApprovals > 0 || pendingApprovals.length > 0;
  const ready = composer.trim() !== '' && !busy && !recovery && review.state !== 'CLOSED' && connection === 'open';
  const send = (): void => { scroll.followSend(); onSend(); };
  const steer = (): void => { scroll.changeFollowing(true); onSteer(); };

  return (
    <AgentConversation
      turns={projected} repository={review.repo} durableTurns={turns} models={models} now={now} scroll={scroll}
      localReview={localReview} reviewRequests={reviewRequests(review)} onOpenReview={onOpenReview}
      connection={connection} running={running} activeTurn={review.activeTurn} waiting={waiting} pendingMessage={pendingMessage}
      activityChoice={activityChoice} turnChoice={turnChoice} pendingApprovals={pendingApprovals}
      generatedTurnIds={new Set(review.byteQuayTurnIds)} label="Review agent conversation"
      onToggleTurn={(id, open) => setTurnChoice(choices => ({ ...choices, [id]: open }))}
      onToggleActivity={(id, open) => setActivityChoice(choices => ({ ...choices, [id]: open }))}
      onDecide={onDecide}>
      <ConversationQueue
        queue={review.queuedInputIds.map((id): ConversationQueuedInput => {
          const message = review.messages.find(message => message.queuedInputId === id);
          return { id, state: null, preview: message?.body ?? 'Queued message', turnId: message?.turnId };
        })}
        running={running} idle={!running && !recovery} resumeBlocked={recovery}
        steerEnabled={!busy && !recovery && connection === 'open' && review.task.steering && review.activeTurn?.state === 'RUNNING'}
        onSteer={onSteerQueued}
        onRemove={onRemoveQueued} />
      {recovery && <div className="agt-notice" role="status">
        Recovery required. The last turn ended, but runtime cleanup is unverified. New messages wait until cleanup is verified.
      </div>}
      {review.state !== 'CLOSED' && !recovery && (
        <ConversationComposer
          value={composer} onChange={onCompose} onSend={send} onStop={onStop} onSteer={steer} onQueue={onQueue}
          running={running} sending={busy} disabled={busy || connection === 'connecting'}
          sendEnabled={ready && !running && review.task.mutationClaim === null}
          steerEnabled={ready && running && review.task.steering && review.activeTurn?.state === 'RUNNING'}
          queueEnabled={ready && running && review.activeTurn !== null}
          steerTitle={review.task.steering ? 'Sends into the running turn now' : 'This provider cannot take input into a running turn'}
          connection={connection} models={models} model={review.task.model} provider={review.task.laneLabel} />
      )}
    </AgentConversation>
  );
}
