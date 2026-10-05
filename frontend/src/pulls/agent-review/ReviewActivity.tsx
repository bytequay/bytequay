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
import { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Bot, X } from 'lucide-react';
import SubagentsPanel from '../../agents/SubagentsPanel';
import type { ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import { AgentConversation, ConversationComposer, ConversationQueue, useConversationHistory, type ApprovalDecider, type ConversationHistory, type ConversationQueuedInput } from '../../agents/AgentConversation';
import { projectAgents, projectTranscript, withApprovalAuthority } from '../../agents/transcript';
import type { AgentEventEnvelope } from '../../agents/events';
import type { PendingMessage } from '../../agents/model';
import type { AgentPendingApprovalDto, AgentStreamConnectionState, AgentTurnDto, ModelOptionDto } from '../../types';
import type { AgentReviewDto } from './contracts';
import type { LocalReviewThreads } from '../local-review/types';
import { reviewRecoveryRequired, reviewRequests, reviewRunning } from './model';

const EMPTY_HISTORY: ConversationHistory = { before: null, after: null, loading: false, onLoad: () => {}, onFollow: () => {} };

/** The workspace supplies destinations; the conversation retains its one stream and inspection state. */
export type ReviewAgentPanel = {
  tabTarget: HTMLElement | null;
  bodyTarget: HTMLElement | null;
  active: boolean;
  onSelect: () => void;
  onClose: () => void;
};

/** Review owns its scoped actions; Tasks owns the shared conversation presentation. */
export default function ReviewActivity({
  review, events, turns, models, pendingApprovals, now, composer, busy,
  localReview, onOpenReview, agentPanel, pendingMessage = null,
  connection = 'open', history = EMPTY_HISTORY, lastSequence = events.at(-1)?.sequence ?? 0,
  reviewSequence = lastSequence,
  onCompose, onSend, onSteer, onQueue, onStop, onDecide, onRemoveQueued, onSteerQueued, onResumeQueue,
}: {
  review: AgentReviewDto;
  localReview?: LocalReviewThreads;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  agentPanel?: ReviewAgentPanel;
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
  /** User inputs beyond this point still need their saved review-card identity. */
  reviewSequence?: number;
  onCompose: (text: string) => void;
  onSend: () => void;
  onSteer: () => void;
  onQueue: () => void;
  onStop: () => void;
  onDecide: ApprovalDecider;
  onRemoveQueued: (queuedInputId: string) => void;
  onSteerQueued?: (queuedInputId: string) => void;
  onResumeQueue?: () => void;
}) {
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [agentsOpen, setAgentsOpen] = useState(false);
  const [openAgent, setOpenAgent] = useState<string | null>(null);
  const [logChoice, setLogChoice] = useState<string | null>(null);
  const latestTurn = Object.values(turns).at(-1);
  const pendingIds = new Set(pendingApprovals.map(approval => approval.approvalId));
  const requests = useMemo(() => reviewRequests(review), [review]);
  // History can arrive before a synchronous launch saves its discussion association.
  // Keep that input out of the transcript until it can render directly as its card.
  const visibleEvents = useMemo(() => {
    const requestTurns = new Set(requests.map(request => request.turnId));
    const requestEvents = new Set(requests.map(request => request.messageEventId));
    const visible = events.filter(event => event.type !== 'user_message' || event.sequence <= reviewSequence
      || requestEvents.has(event.eventId) || (event.payload.deliveryKind !== 'STEER' && event.turnId !== null && requestTurns.has(event.turnId)));
    return visible.length === events.length ? events : visible;
  }, [events, requests, reviewSequence]);
  const scroll = useConversationHistory(visibleEvents, latestTurn?.endedAt, history);
  const projected = withApprovalAuthority(projectTranscript(visibleEvents, null, turns, history.before === null, history.after === null && (scroll.following || (events.at(-1)?.sequence ?? 0) >= lastSequence)), pendingIds);
  const agents = review.task.nestedAgents ? projectAgents(events, review.activeTurn?.id ?? null, history.after === null) : [];
  const agentLabels = Object.fromEntries(agents.map(agent => [agent.threadId, agent.label]));
  const openSubagent = (threadId: string): void => {
    setOpenAgent(threadId);
    setAgentsOpen(true);
    agentPanel?.onSelect();
  };
  const toggleActivity = (id: string, open: boolean): void => setActivityChoice(choices => ({ ...choices, [id]: open }));
  const running = reviewRunning(review);
  const recovery = reviewRecoveryRequired(review);
  const waiting = review.pendingApprovals > 0 || pendingApprovals.length > 0;
  const ready = composer.trim() !== '' && !busy && !recovery && review.state !== 'CLOSED' && connection === 'open';
  const send = (): void => { scroll.followSend(); onSend(); };
  const steer = (): void => { scroll.changeFollowing(true); onSteer(); };

  return (
    <>
    {agentPanel !== undefined && review.task.nestedAgents && agentsOpen && <>
      {agentPanel.tabTarget !== null && createPortal(
        <span className={`pull-detail__utility${agentPanel.active ? ' is-active' : ''}`}>
          <button type="button" className={agentPanel.active ? 'is-active' : ''} aria-pressed={agentPanel.active} onClick={agentPanel.onSelect}>
            <Bot aria-hidden="true" /> Subagents <span className="agt-chip">{agents.length}</span>
          </button>
          <button type="button" className="pull-detail__utility-close" aria-label="Close Subagents" title="Close Subagents"
            onClick={() => { setAgentsOpen(false); setOpenAgent(null); agentPanel.onClose(); }}><X aria-hidden="true" /></button>
        </span>, agentPanel.tabTarget)}
      {agentPanel.bodyTarget !== null && agentPanel.active && createPortal(
        <SubagentsPanel agents={agents} openAgent={openAgent} expiredChildren={[]} deleteAfter={null}
          transcript={openAgent === null ? [] : withApprovalAuthority(projectTranscript(events, openAgent, turns, history.before === null, history.after === null), pendingIds)}
          now={now} repository={review.repo} activityChoice={activityChoice} onOpenAgent={openSubagent} onCloseAgent={() => setOpenAgent(null)}
          onToggleActivity={toggleActivity} onDecide={onDecide} logChoice={logChoice} onToggleLog={setLogChoice}
          historyUnloaded={history.before !== null || history.after !== null} historyLoading={history.loading}
          onLoadEarlier={() => scroll.loadHistory('older')} />, agentPanel.bodyTarget)}
    </>}
    <AgentConversation
      turns={projected} repository={review.repo} durableTurns={turns} models={models} now={now} scroll={scroll} workspaceId={review.workspaceId}
      localReview={localReview} reviewRequests={requests} onOpenReview={onOpenReview}
      connection={connection} running={running} activeTurn={review.activeTurn} waiting={waiting} pendingMessage={pendingMessage}
      activityChoice={activityChoice} turnChoice={turnChoice} pendingApprovals={pendingApprovals}
      generatedTurnIds={new Set(review.byteQuayTurnIds)} label="Review agent conversation"
      onToggleTurn={(id, open) => setTurnChoice(choices => ({ ...choices, [id]: open }))}
      agentLabels={agentLabels} onOpenAgent={agentPanel !== undefined && review.task.nestedAgents ? openSubagent : undefined}
      onToggleActivity={toggleActivity}
      onDecide={onDecide}>
      <ConversationQueue
        queue={review.queuedInputIds.map((id): ConversationQueuedInput => {
          const message = review.messages.find(message => message.queuedInputId === id);
          // A turn that did not succeed pauses every waiting message, with one reason.
          return { id, state: review.queuePause === null ? null : 'PAUSED', pauseReason: review.queuePause, preview: message?.body ?? 'Queued message', turnId: message?.turnId };
        })}
        running={running} idle={!running && !recovery} resumeBlocked={recovery}
        steerEnabled={!busy && !recovery && connection === 'open' && review.task.steering && review.activeTurn?.state === 'RUNNING'}
        onSteer={onSteerQueued}
        onRemove={onRemoveQueued}
        onResume={onResumeQueue} />
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
    </>
  );
}
