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
import { laneDefaults } from '../../settings/model';
import { useMemo, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import ConversationUtilityTab, { type ConversationUtility } from '../../agents/ConversationUtilityTab';
import { DiscussionsPanel, discussionTurnSummaries } from '../../agents/discussions/DiscussionsPanel';
import { revealPassage } from '../../agents/discussions/DiscussableAnswer';
import { useTaskDiscussions } from '../../agents/discussions/useTaskDiscussions';
import { reviewThreadBridge } from '../PrivateThread';
import SubagentsPanel from '../../agents/SubagentsPanel';
import type { ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import { AgentConversation, ConversationComposer, ConversationQueue, PlanCard, useConversationHistory, type ApprovalDecider, type ConversationHistory, type ConversationQueuedInput } from '../../agents/AgentConversation';
import { currentPlan, projectAgents, projectTranscript, withApprovalAuthority } from '../../agents/transcript';
import type { AgentEventEnvelope } from '../../agents/events';
import type { PendingMessage } from '../../agents/model';
import type { AgentPendingApprovalDto, AgentStreamConnectionState, AgentTurnDto, ModelOptionDto, ModelProviderDto } from '../../types';
import type { AgentReviewDto, ReviewFindingDto, ReviewModelSelection } from './contracts';
import type { LocalReviewThreads } from '../local-review/types';
import { reviewConversationEvents, reviewRecoveryRequired, reviewRequests, reviewRunning } from './model';

const EMPTY_HISTORY: ConversationHistory = { before: null, after: null, loading: false, onLoad: () => {}, onFollow: () => {} };

/** The workspace supplies destinations; the conversation retains its one stream and inspection state. */
export type ReviewConversationPanel = {
  tabTarget: HTMLElement | null;
  bodyTarget: HTMLElement | null;
  active: ConversationUtility | null;
  onSelect: (tab: ConversationUtility) => void;
  onClose: (tab: ConversationUtility) => void;
};

/** Review owns its scoped actions; Tasks owns the shared conversation presentation. */
export default function ReviewActivity({
  review, events, turns, models, providers, onSelectModel, pendingApprovals, now, composer, busy,
  localReview, findingCard, onOpenReview, conversationPanel, composerContext, pendingMessage = null,
  connection = 'open', history = EMPTY_HISTORY, lastSequence = events.at(-1)?.sequence ?? 0,
  reviewSequence = lastSequence,
  onCompose, onSend, onSteer, onQueue, onStop, onDecide, onRemoveQueued, onSteerQueued, onResumeQueue,
}: {
  review: AgentReviewDto;
  localReview?: LocalReviewThreads;
  /** The finding's own card, so a question about it opens on the same card as Summary and Changes. */
  findingCard?: (finding: ReviewFindingDto) => ReactNode;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  conversationPanel?: ReviewConversationPanel;
  /** What the conversation is about, shown above the composer the way a development task shows its pull request. */
  composerContext?: ReactNode;
  events: AgentEventEnvelope[];
  pendingMessage?: PendingMessage | null;
  turns: Record<string, AgentTurnDto>;
  models: ModelOptionDto[];
  providers?: ModelProviderDto[];
  onSelectModel?: (selection: ReviewModelSelection) => void;
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
  const selectedLane = review.task.selectionLaneId ?? review.task.laneId;
  const selectedProvider = providers?.find(provider => provider.id === selectedLane);
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [agentsOpen, setAgentsOpen] = useState(false);
  const [discussionsOpen, setDiscussionsOpen] = useState<boolean | null>(null);
  const [discussionFocus, setDiscussionFocus] = useState<{ id: string } | null>(null);
  const [hoveredDiscussion, setHoveredDiscussion] = useState<string | null>(null);
  const discussionBridge = useMemo(() => reviewThreadBridge(review.id), [review.id]);
  const discussions = useTaskDiscussions({ taskId: review.task.id, workspaceId: review.workspaceId }, {
    active: conversationPanel !== undefined,
    bridge: discussionBridge,
    disabledReason: review.state === 'CLOSED' ? 'This review has ended, so its discussions are read-only.' : null,
  });
  const openDiscussion = (id: string): void => {
    setDiscussionsOpen(true);
    setDiscussionFocus({ id });
    conversationPanel?.onSelect('discussions');
  };
  const [openAgent, setOpenAgent] = useState<string | null>(null);
  const [logChoice, setLogChoice] = useState<string | null>(null);
  const latestTurn = Object.values(turns).at(-1);
  const pendingIds = useMemo(() => new Set(pendingApprovals.map(approval => approval.approvalId)), [pendingApprovals]);
  const requests = useMemo(() => reviewRequests(review), [review]);
  const visibleEvents = useMemo(() => reviewConversationEvents(events, requests, reviewSequence), [events, requests, reviewSequence]);
  const visiblePendingMessage = pendingMessage !== null && visibleEvents.some(event => event.type === 'user_message' && event.sequence > pendingMessage.after)
    ? null : pendingMessage;
  const scroll = useConversationHistory(visibleEvents, latestTurn?.endedAt, history);
  const atLatest = history.after === null && (scroll.following || (events.at(-1)?.sequence ?? 0) >= lastSequence);
  const projected = useMemo(() => withApprovalAuthority(projectTranscript(visibleEvents, null, turns, history.before === null, atLatest), pendingIds),
    [visibleEvents, turns, history.before, atLatest, pendingIds]);
  const agents = useMemo(() => review.task.nestedAgents ? projectAgents(events, review.activeTurn?.id ?? null, history.after === null) : [],
    [review.task.nestedAgents, events, review.activeTurn?.id, history.after]);
  const showingAgent = conversationPanel?.bodyTarget != null && conversationPanel.active === 'agents';
  const agentTranscript = useMemo(() => !showingAgent || openAgent === null ? []
    : withApprovalAuthority(projectTranscript(events, openAgent, turns, history.before === null, history.after === null), pendingIds),
  [showingAgent, openAgent, events, turns, history.before, history.after, pendingIds]);
  const agentLabels = useMemo(() => Object.fromEntries(agents.map(agent => [agent.threadId, agent.label])), [agents]);
  const discussionTurns = useMemo(() => discussionTurnSummaries(projected), [projected]);
  const openSubagent = (threadId: string): void => {
    setOpenAgent(threadId);
    setAgentsOpen(true);
    conversationPanel?.onSelect('agents');
  };
  const toggleActivity = (id: string, open: boolean): void => setActivityChoice(choices => ({ ...choices, [id]: open }));
  const running = reviewRunning(review);
  const [planOpen, setPlanOpen] = useState(true);
  const plan = running && history.after === null ? currentPlan(projected) : null;
  const recovery = reviewRecoveryRequired(review);
  const waiting = review.pendingApprovals > 0 || pendingApprovals.length > 0;
  const ready = composer.trim() !== '' && !busy && !recovery && review.state !== 'CLOSED' && connection === 'open';
  const send = (): void => { scroll.followSend(); onSend(); };
  const steer = (): void => { scroll.changeFollowing(true); onSteer(); };

  return (
    <>
    {conversationPanel !== undefined && <>
      {conversationPanel.tabTarget !== null && createPortal(<>
        {review.task.nestedAgents && agentsOpen && <ConversationUtilityTab tab="agents" count={agents.length}
          active={conversationPanel.active === 'agents'} onSelect={() => conversationPanel.onSelect('agents')}
          onClose={() => { setAgentsOpen(false); setOpenAgent(null); conversationPanel.onClose('agents'); }} />}
        {(discussionsOpen ?? discussions.discussions.length > 0) && <ConversationUtilityTab tab="discussions" count={discussions.discussions.length}
          active={conversationPanel.active === 'discussions'} onSelect={() => conversationPanel.onSelect('discussions')}
          onClose={() => { setDiscussionsOpen(false); conversationPanel.onClose('discussions'); }} />}
      </>, conversationPanel.tabTarget)}
      {conversationPanel.bodyTarget !== null && conversationPanel.active === 'agents' && createPortal(
        <SubagentsPanel agents={agents} openAgent={openAgent} expiredChildren={[]} deleteAfter={null}
          transcript={agentTranscript}
          now={now} repository={review.repo} activityChoice={activityChoice} onOpenAgent={openSubagent} onCloseAgent={() => setOpenAgent(null)}
          onToggleActivity={toggleActivity} onDecide={onDecide} logChoice={logChoice} onToggleLog={setLogChoice}
          historyUnloaded={history.before !== null || history.after !== null} historyLoading={history.loading}
          onLoadEarlier={() => scroll.loadHistory('older')} />, conversationPanel.bodyTarget)}
      {conversationPanel.bodyTarget !== null && conversationPanel.active === 'discussions' && createPortal(
        <DiscussionsPanel discussions={discussions} repository={review.repo} focus={discussionFocus}
          turns={discussionTurns} onJump={revealPassage} onHover={setHoveredDiscussion} />, conversationPanel.bodyTarget)}
    </>}
    <AgentConversation
      turns={projected} repository={review.repo} durableTurns={turns} models={models} now={now} scroll={scroll} workspaceId={review.workspaceId}
      discussions={discussions.discussions} discussionBusy={discussions.busy}
      onSelectPassage={conversationPanel === undefined || discussions.disabledReason !== null ? undefined
        : async (passage, question) => { const discussion = await discussions.open({ ...passage, question }); openDiscussion(discussion.id); }}
      onOpenDiscussion={openDiscussion} focusedDiscussion={hoveredDiscussion} onHoverDiscussion={setHoveredDiscussion}
      localReview={localReview} reviewRequests={findingCard === undefined ? requests : requests.map(request => {
        const target = request.target;
        const finding = target?.kind === 'finding' ? review.findings.find(item => item.id === target.findingId) : undefined;
        return finding === undefined ? request : { ...request, card: findingCard(finding) };
      })} onOpenReview={onOpenReview}
      connection={connection} running={running} activeTurn={review.activeTurn} waiting={waiting} pendingMessage={visiblePendingMessage}
      activityChoice={activityChoice} turnChoice={turnChoice} pendingApprovals={pendingApprovals}
      generatedTurnIds={new Set(review.byteQuayTurnIds)} label="Review agent conversation"
      onToggleTurn={(id, open) => setTurnChoice(choices => ({ ...choices, [id]: open }))}
      agentLabels={agentLabels} onOpenAgent={conversationPanel !== undefined && review.task.nestedAgents ? openSubagent : undefined}
      onToggleActivity={toggleActivity}
      onDecide={onDecide}>
      {plan !== null && plan.steps.length > 0 && <PlanCard steps={plan.steps} open={planOpen} onToggle={() => setPlanOpen(open => !open)} repository={review.repo} />}
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
      {composerContext}
      {review.state !== 'CLOSED' && !recovery && (
        <ConversationComposer
          value={composer} onChange={onCompose} onSend={send} onStop={onStop} onSteer={steer} onQueue={onQueue}
          running={running} sending={busy} disabled={busy || connection === 'connecting'}
          sendEnabled={ready && !running && review.task.mutationClaim === null}
          steerEnabled={ready && running && review.task.steering && review.activeTurn?.state === 'RUNNING'}
          queueEnabled={ready && running && review.activeTurn !== null}
          steerTitle={review.task.steering ? 'Sends into the running turn now' : 'This provider cannot take input into a running turn'}
          connection={connection} models={providers === undefined ? models : selectedProvider?.models ?? []} model={review.task.model} provider={selectedProvider?.label ?? review.task.laneLabel}
          effort={review.task.effort} serviceTier={review.task.serviceTier}
          providers={providers} laneId={selectedLane}
          onSelectProvider={onSelectModel === undefined ? undefined : laneId => {
            const defaults = laneDefaults(providers ?? [], laneId);
            onSelectModel({ laneId, model: defaults.model, effort: defaults.effort || null, serviceTier: null });
          }}
          onSelectModel={onSelectModel === undefined ? undefined : (model, effort, serviceTier) => onSelectModel({ laneId: selectedLane, model, effort, serviceTier })} />
      )}
    </AgentConversation>
    </>
  );
}
