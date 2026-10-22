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

import { clock, shortDate } from '@bytequay/ui/displayText';
import { CircleCheck, CircleX } from 'lucide-react';
import { Chevron } from '../Chevron';
import type { AgentChildSummaryDto } from '../types';
import { AgentMark } from './AgentMark';
import { BlockView, Glyph, Spinner, type ApprovalDecider } from './AgentConversation';
import { renderMarkdown } from './markdown';
import { activeAgents, agentDisplayName, formatDuration, type AgentNode, type TranscriptTurn } from './transcript';

/** How long an agent worked, live while it runs and fixed once it ends. */
function agentElapsed(agent: AgentNode, now: number): string | null {
  if (agent.startedAt === null || agent.status === 'unknown') return null;
  const end = agent.endedAt === null ? now : Date.parse(agent.endedAt);
  return formatDuration(Math.max(0, end - Date.parse(agent.startedAt)));
}

function AgentRow({ agent, now, onOpen }: { agent: AgentNode; now: number; onOpen: () => void }): React.JSX.Element {
  return (
    <button type="button" className="agt-panel__row" onClick={onOpen} title="Open this agent">
      <AgentMark name={agent.label} />
      <span className="agt-panel__name">
        <span className="agt-panel__label">{agentDisplayName(agent.label)}</span>
        {agent.purpose !== null && <span className="agt-panel__purpose">{agent.purpose}</span>}
      </span>
      {agent.status === 'running' ? <Spinner /> : <span className="agt-spacer" />}
      {agent.status === 'unknown' && <span className="agt-chip">historical activity</span>}
      {agent.status === 'failed' && <span className="agt-chip is-warn">failed</span>}
      <span className="agt-mono agt-muted">{agentElapsed(agent, now) ?? ''}</span>
      <Chevron />
    </button>
  );
}

/** The task's existing agent inspector, shared by development and review conversations. */
export default function SubagentsPanel({
  agents, openAgent, expiredChildren, deleteAfter, transcript, now, repository,
  activityChoice, onOpenAgent, onCloseAgent, onToggleActivity, onDecide,
  logChoice, onToggleLog, historyUnloaded, onLoadEarlier, historyLoading,
}: {
  agents: AgentNode[];
  openAgent: string | null;
  expiredChildren: AgentChildSummaryDto[];
  deleteAfter: string | null;
  transcript: TranscriptTurn[];
  now: number;
  repository: string | null;
  activityChoice: Record<string, boolean>;
  onOpenAgent: (threadId: string) => void;
  onCloseAgent: () => void;
  onToggleActivity: (id: string, open: boolean) => void;
  onDecide: ApprovalDecider;
  logChoice: string | null;
  onToggleLog: (choice: string) => void;
  historyUnloaded: boolean;
  onLoadEarlier: () => void;
  historyLoading: boolean;
}): React.JSX.Element {
  const active = activeAgents(agents);
  const done = agents.filter((agent) => agent.status !== 'running');
  const labels = Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label]));
  if (openAgent !== null) {
    const agent = agents.find((node) => node.threadId === openAgent);
    const expired = expiredChildren.find((child) => child.threadId === openAgent);
    const activityExpired = agent === undefined && expired !== undefined && !historyUnloaded;
    const blocks = transcript.flatMap((turn) => turn.blocks);
    const label = agent?.label ?? expired?.label ?? 'Agent';
    const result = agent?.result ?? expired?.result ?? null;
    const endedAt = agent?.endedAt ?? expired?.endedAt ?? null;
    const startedAt = agent?.startedAt ?? expired?.startedAt ?? null;
    const worked = startedAt === null || agent?.status === 'unknown' ? null : formatDuration(Math.max(0, (endedAt === null ? now : Date.parse(endedAt)) - Date.parse(startedAt)));
    const running = agent?.status === 'running';
    // A live agent's log is what the user came to watch; a finished one folds to its result.
    const logOpen = logChoice === openAgent ? true : logChoice === `!${openAgent}` ? false : running;
    return (
      <section aria-label={`Agent: ${agentDisplayName(label)}`}>
        <div className="agt-panel__head">
          <button type="button" className="agt-back" onClick={onCloseAgent} aria-label="Back to all subagents">‹</button>
          <AgentMark name={label} muted={agent === undefined} />
          <strong className={`agt-panel__label${agent === undefined ? ' is-muted' : ''}`}>{agentDisplayName(label)}</strong>
          {running
            ? <Spinner />
            : (
              <span className={`agt-panel__outcome${agent?.status === 'failed' ? ' is-failed' : agent === undefined ? ' is-expired' : ' is-done'}`}>
                {agent?.status === 'failed' ? <CircleX aria-hidden="true" /> : <CircleCheck aria-hidden="true" />}
                <span>{agent?.status === 'unknown' ? 'Historical activity' : agent?.status === 'failed' ? 'Failed' : agent === undefined ? `Done${endedAt === null ? '' : ` · ${shortDate(endedAt)}`}` : 'Done'}</span>
              </span>
            )}
        </div>
        {worked !== null && blocks.length > 0 && (
          <button
            type="button"
            className="agt-panel__worked"
            aria-expanded={logOpen}
            title="Show or hide this agent's log"
            onClick={() => onToggleLog(logOpen ? `!${openAgent}` : openAgent)}
          >
            {running ? 'Working for ' : 'Worked for '}{worked}{endedAt === null ? '' : ` · finished ${clock(endedAt)}`}
            <Chevron open={logOpen} size={11} />
          </button>
        )}
        {blocks.length === 0 && (historyUnloaded || activityExpired) && (
          <div className="agt-panel__expired">
            <div className="agt-panel__expired-title">{historyUnloaded ? 'Activity is not loaded' : 'Activity expired'}</div>
            {historyUnloaded ? <button type="button" className="agt-action" disabled={historyLoading} onClick={onLoadEarlier}>Load earlier activity</button> : <div className="agt-panel__expired-body">
              {deleteAfter === null
                ? 'Detailed steps were removed at the end of the retention window. The result is kept.'
                : `Detailed steps were removed on ${shortDate(deleteAfter, true)} at the end of the retention window. The result is kept.`}
            </div>}
          </div>
        )}
        {logOpen && blocks.length > 0 && (
          <>
            {agent?.backfilling === true && (
              <div className="agt-note">Recovering this agent&apos;s activity from its history…</div>
            )}
            <div className="agt-panel__log">
              {blocks.map((block) => (
                <BlockView
                  key={block.id}
                  block={block}
                  repository={repository}
                  activityChoice={activityChoice}
                  agentLabels={labels}
                  onToggleActivity={onToggleActivity}
                  onDecide={onDecide}
                  onOpenAgent={onOpenAgent}
                />
              ))}
            </div>
          </>
        )}
        {result !== null && (
          <>
            <div className="agt-label agt-panel__section">Result{activityExpired ? ' — kept' : ''}</div>
            <div className="agt-panel__result">{renderMarkdown(result, repository)}</div>
          </>
        )}
        {!running && worked !== null && (
          <div className="agt-panel__finished">
            <Glyph icon={CircleCheck} className="is-ok" />
            {blocks.length === 0 && endedAt !== null ? `Finished ${shortDate(endedAt)} · ${worked}` : worked}
          </div>
        )}
      </section>
    );
  }
  return (
    <section aria-label="Subagents">
      {historyUnloaded && <div className="agt-note">Showing subagents in loaded activity. Earlier or newer pages may contain others.</div>}
      <div className="agt-label agt-panel__section">{historyUnloaded ? 'Active in this window' : 'Active'} · {active.length}</div>
      {active.length === 0
        ? <div className="agt-muted agt-panel__empty">{historyUnloaded ? 'No active subagents in loaded activity' : 'No active subagents'}</div>
        : active.map((agent) => (
          <AgentRow key={agent.threadId} agent={agent} now={now} onOpen={() => onOpenAgent(agent.threadId)} />
        ))}
      {(done.length > 0 || expiredChildren.length > 0) && (
        <div className="agt-label agt-panel__section">{done.some((agent) => agent.status === 'unknown') ? 'Earlier activity' : 'Done'} · {done.length + expiredChildren.length}</div>
      )}
      {done.map((agent) => (
        <AgentRow key={agent.threadId} agent={agent} now={now} onOpen={() => onOpenAgent(agent.threadId)} />
      ))}
      {expiredChildren.map((child) => (
        <button
          type="button"
          className="agt-panel__row"
          key={child.threadId}
          onClick={() => onOpenAgent(child.threadId)}
          title="Open this agent"
        >
          <AgentMark name={child.label} muted />
          <span className="agt-panel__name"><span className="agt-panel__label is-muted">{agentDisplayName(child.label)}</span></span>
          <span className="agt-chip">{historyUnloaded ? 'not loaded' : 'expired'}</span>
          <span className="agt-spacer" />
          {child.endedAt !== null && <span className="agt-mono">{shortDate(child.endedAt)}</span>}
          <Chevron />
        </button>
      ))}
    </section>
  );
}
