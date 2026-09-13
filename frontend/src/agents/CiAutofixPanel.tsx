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

import React, { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import type { AgentApprovalDecision, AgentDevelopmentCiDto, AgentPendingApprovalDto, CiAutofixRunDto, PullRequestDetailDto } from '../types';
import { AgentConversation, useConversationHistory } from './AgentConversation';
import { agentTaskReducer, historyAfter, historyBefore, initialAgentTaskState, readRecentAgentHistory, startAgentConversationSession, unloadedTurnIds, type AgentBridgePort } from './model';
import { projectTranscript, withApprovalAuthority } from './transcript';
import { samePullRequest } from './TaskPullRequestPanel';

export function ciActivity(ci: Pick<AgentDevelopmentCiDto, 'status' | 'activity' | 'attemptsUsed' | 'maxAttempts' | 'finishReason' | 'canMessage'>): string {
  if (ci.status === 'FINISHED') {
    switch (ci.finishReason) {
      case 'GREEN': return 'CI passed';
      case 'CANCELLED': return 'CI Autofix stopped';
      case 'ERROR': return 'CI Autofix failed';
      case 'MAX_ATTEMPTS': return 'CI repair limit reached';
      case 'AUTHORIZATION_EXPIRED': return 'CI Autofix authorization expired';
      case 'STALE_HEAD': return 'CI Autofix stopped: PR branch changed';
      default: return 'CI Autofix ended';
    }
  }
  if (ci.status === 'PAUSED') return ci.canMessage ? 'CI needs your input' : 'CI Autofix paused';
  switch (ci.activity) {
    case 'FIXING': return `Fixing CI ${ci.attemptsUsed}/${ci.maxAttempts}`;
    case 'REBASING': return 'Rebasing CI changes';
    case 'PUSHING': return 'Pushing CI changes';
    case 'RERUNNING_CI': return 'Rerunning CI';
    case 'INSPECTING_GIT': return 'Checking CI changes';
    case 'PREPARING_PUSH': return 'Preparing CI push';
    case 'PREPARING_AGENT': return 'Preparing CI agent';
    default: return 'Waiting for CI';
  }
}

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error)).replace(/\s+/g, ' ').slice(0, 240);

type CiCheckDetails = Pick<PullRequestDetailDto, 'repo' | 'number' | 'ciStatus' | 'unavailableSections'>;

function CiRunSummary({ ci, checks, recordedFailure }: {
  ci: AgentDevelopmentCiDto; checks?: CiCheckDetails | null; recordedFailure?: string | null;
}) {
  const finished = ci.status === 'FINISHED';
  const matchingChecks = checks != null && samePullRequest(ci.pullRequestUrl, checks) ? checks : null;
  const checkLabel = matchingChecks === null ? null : matchingChecks.unavailableSections.includes('checks') ? 'Unavailable'
    : { PASSING: 'Passed', FAILING: 'Failing', PENDING: 'In progress', NONE: 'No checks reported' }[matchingChecks.ciStatus];
  return <>
    {finished && checkLabel !== null && <span className="agt-ci-checks" role="status">Latest known PR checks: <strong>{checkLabel}</strong></span>}
    {finished && <span>Previous CI Autofix run</span>}
    <strong>{ciActivity(ci)}</strong>
    <span>{ci.attemptsUsed}/{ci.maxAttempts} repair attempts · {ci.rerunsUsed}/3 reruns</span>
    {finished ? <>
      <span>This run has ended and is no longer monitoring CI.</span>
      {(ci.summary || recordedFailure) && <details className="agt-ci-history">
        <summary>Run details</summary>
        {ci.summary && <p>{errorText(ci.summary)}</p>}
        {recordedFailure && <p>Recorded failure: {recordedFailure}</p>}
      </details>}
    </> : ci.summary !== null && <span>{errorText(ci.summary)}</span>}
  </>;
}

export function DevelopmentCiStatus({ ci, active, error, recordedFailure, checks, onOpen }: {
  ci: AgentDevelopmentCiDto; active: boolean; error?: string | null; recordedFailure?: string | null; checks?: CiCheckDetails | null; onOpen?: () => void;
}) {
  return <div className="agt-development-ci" aria-label="CI Autofix status">
    <CiRunSummary ci={ci} checks={checks} recordedFailure={recordedFailure} />
    <div className="agt-development-ci__actions">
      <a href={ci.pullRequestUrl}>PR #{ci.pullRequestNumber}</a>
      {onOpen !== undefined && <button type="button" className="agt-action" onClick={onOpen}>{ci.status === 'FINISHED' ? 'View CI Autofix history' : 'Open CI Autofix'}</button>}
    </div>
    {active && <span>CI Autofix is running · Stop to start over</span>}
    {error && <span className="agt-development-ci__error">{errorText(error)}</span>}
  </div>;
}

/** Keeps repair replies in the CI service's scope while the source composer remains mounted. */
export function CiAutofixPanel({ ci, checks, parentTaskId, workspaceId, active, bridge, width, onClose, onStop }: {
  ci: AgentDevelopmentCiDto; parentTaskId: string; workspaceId: string; active: boolean;
  checks?: CiCheckDetails | null;
  bridge: AgentBridgePort; width: number; onClose: () => void; onStop: () => void;
}) {
  const [run, setRun] = useState<CiAutofixRunDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const acceptRun = useCallback((value: CiAutofixRunDto) => {
    if (value.id !== ci.runId || value.parentTaskId !== parentTaskId || value.workspaceId !== workspaceId) {
      throw new Error('CI run does not belong to this development task');
    }
    setRun(current => current !== null && current.version > value.version ? current : value);
  }, [ci.runId, parentTaskId, workspaceId]);
  useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      setLoading(true);
      try {
        const result = await bridge.getCiAutofixRun(ci.runId);
        if (!live) return;
        acceptRun(result);
        setError(null);
      } catch (failure) { if (live) setError(errorText(failure)); }
      finally {
        if (live) {
          setLoading(false);
          if (active) timer = setTimeout(() => { void read(); }, 5000);
        }
      }
    };
    void read();
    return () => { live = false; clearTimeout(timer); };
  }, [bridge, ci.runId, active, acceptRun, refresh]);
  const status = run === null ? ci : { ...ci, ...run, maxAttempts: run.authorization.maxAttempts };
  return <aside className="agt-ci-panel" style={{ width }} aria-label="CI Autofix run">
    <div className="agt-ci-panel__head"><strong>CI Autofix</strong><button type="button" className="agt-action" onClick={onClose}>Close CI</button></div>
    <div className="agt-ci-panel__summary">
      <CiRunSummary ci={status} checks={checks} />
      <a href={ci.pullRequestUrl}>PR #{ci.pullRequestNumber}</a>
      {active && <button type="button" className="agt-action" onClick={onStop}>Stop automation</button>}
      {error !== null && <>
        <span className="agt-development-ci__error" role="alert">Could not load CI Autofix details. {error}</span>
        <button type="button" className="agt-action" disabled={loading} onClick={() => setRefresh(value => value + 1)}>{loading ? 'Loading CI…' : 'Retry loading CI'}</button>
      </>}
    </div>
    {run?.agentTaskId != null
      ? <CiConversation key={run.agentTaskId} taskId={run.agentTaskId} run={run} bridge={bridge}
          ownerActive={active} canMessage={active && error === null && ci.canMessage && run.canMessage} onRun={acceptRun} />
      : error === null && <p className="agt-muted">{run === null ? 'Loading CI Autofix…'
        : run.status === 'FINISHED' ? 'No CI agent task was started.'
          : active && run.status === 'ACTIVE' ? 'Preparing the CI agent task…' : 'No CI agent task is available.'}</p>}
  </aside>;
}

function CiConversation({ taskId, run, canMessage, ownerActive, bridge, onRun }: {
  taskId: string; run: CiAutofixRunDto; canMessage: boolean; ownerActive: boolean; bridge: AgentBridgePort; onRun: (run: CiAutofixRunDto) => void;
}) {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const stateRef = useRef(state);
  stateRef.current = state;
  const [pendingApprovals, setPendingApprovals] = useState<AgentPendingApprovalDto[]>([]);
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [historyLoading, setHistoryLoading] = useState(false);
  const historyBusy = useRef(false);
  const following = useRef(true);
  const sending = useRef(false);
  const request = useRef<{ text: string; id: string } | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    const session = startAgentConversationSession(bridge, taskId, dispatch, () => mounted.current, () => following.current);
    session.catch(error => { if (mounted.current) dispatch({ type: 'notice/changed', notice: errorText(error) }); });
    return () => { mounted.current = false; void session.then(stop => stop(), () => {}); };
  }, [bridge, taskId]);
  const loadHistory = useCallback(async (direction: 'older' | 'newer' | 'latest') => {
    if (historyBusy.current) return;
    const current = stateRef.current;
    const before = direction === 'older' ? historyBefore(current) : null;
    const after = direction === 'newer' ? historyAfter(current) : null;
    if (direction !== 'latest' && before === null && after === null) return;
    historyBusy.current = true;
    setHistoryLoading(true);
    try {
      const result = direction === 'latest' ? await readRecentAgentHistory(bridge, taskId)
        : { page: await bridge.readAgentHistory(taskId, before ?? undefined, after ?? undefined) };
      if (mounted.current) dispatch({ type: 'history/loaded', ...result, direction });
    } catch (error) { if (mounted.current) dispatch({ type: 'notice/changed', notice: errorText(error) }); }
    finally { historyBusy.current = false; if (mounted.current) setHistoryLoading(false); }
  }, [bridge, taskId]);
  const unloaded = unloadedTurnIds(state).join(',');
  const latestId = state.latestTurnId ?? run.currentTurnId;
  useEffect(() => {
    let live = true;
    const ids = new Set(unloaded === '' ? [] : unloaded.split(','));
    if (latestId !== null) ids.add(latestId);
    void Promise.all([...ids].map(id => bridge.getAgentTurn(id))).then(turns => {
      if (!live) return;
      turns.forEach(turn => dispatch({ type: turn.id === latestId ? 'turn/changed' : 'turn/loaded', turn }));
    }).catch(error => { if (live) dispatch({ type: 'notice/changed', notice: errorText(error) }); });
    return () => { live = false; };
  }, [bridge, unloaded, latestId, state.lifecycleEpoch]);
  useEffect(() => {
    let live = true;
    if (latestId === null) { setPendingApprovals([]); return; }
    void bridge.readAgentPendingApprovals(taskId, latestId).then(value => { if (live) setPendingApprovals(value); })
      .catch(error => { if (live) dispatch({ type: 'notice/changed', notice: errorText(error) }); });
    return () => { live = false; };
  }, [bridge, taskId, latestId, state.lifecycleEpoch]);
  const scroll = useConversationHistory(state.events, state.turn?.endedAt, {
    before: historyBefore(state), after: historyAfter(state), loading: historyLoading,
    onLoad: direction => { void loadHistory(direction); },
    onFollow: value => { following.current = value; if (value) void loadHistory('latest'); },
  });
  const actionableApprovals = ownerActive ? pendingApprovals : [];
  const projected = withApprovalAuthority(projectTranscript(state.events, null, state.turns, historyBefore(state) === null,
    historyAfter(state) === null && (scroll.following || (state.events.at(-1)?.sequence ?? 0) >= state.lastSequence)), new Set(actionableApprovals.map(item => item.approvalId)));
  const activeTurn = state.turn;
  const running = activeTurn?.state === 'RUNNING' || activeTurn?.state === 'ACCEPTED';
  const send = async () => {
    const text = stateRef.current.composer.trim();
    if (!canMessage || sending.current || text === '' || stateRef.current.connection !== 'open') return;
    sending.current = true;
    dispatch({ type: 'composer/sending', sending: true });
    if (request.current?.text !== text) request.current = { text, id: crypto.randomUUID() };
    try {
      const result = await bridge.sendCiAutofixMessage(run.id, text, request.current.id);
      if (!mounted.current) return;
      onRun(result);
      request.current = null;
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'notice/changed', notice: null });
      scroll.changeFollowing(true);
      await loadHistory('latest');
    } catch (error) { if (mounted.current) dispatch({ type: 'notice/changed', notice: errorText(error) }); }
    finally { sending.current = false; if (mounted.current) dispatch({ type: 'composer/sending', sending: false }); }
  };
  const decide = async (approvalId: string, decision: AgentApprovalDecision) => {
    if (!ownerActive) return;
    try {
      await bridge.resolveAgentApproval(approvalId, decision);
      if (!mounted.current || latestId === null) return;
      setPendingApprovals(await bridge.readAgentPendingApprovals(taskId, latestId));
    } catch (error) { if (mounted.current) dispatch({ type: 'notice/changed', notice: errorText(error) }); }
  };
  return <AgentConversation turns={projected} durableTurns={state.turns} models={[]} now={Date.now()} scroll={scroll}
    connection={state.connection} running={running} activeTurn={activeTurn} waiting={actionableApprovals.length > 0}
    activityChoice={activityChoice} turnChoice={turnChoice} pendingApprovals={actionableApprovals} label="CI repair conversation"
    onToggleTurn={(id, open) => setTurnChoice(values => ({ ...values, [id]: open }))}
    onToggleActivity={(id, open) => setActivityChoice(values => ({ ...values, [id]: open }))}
    onDecide={(id, decision) => { void decide(id, decision); }}>
    {state.notice !== null && <div className="agt-development-ci__error" role="alert">{state.notice}</div>}
    <form className="agt-ci-reply" onSubmit={event => { event.preventDefault(); void send(); }}>
      <label htmlFor={`ci-reply-${run.id}`}>Reply to the CI agent</label>
      <textarea id={`ci-reply-${run.id}`} className="agt-compose__input" value={state.composer} rows={3} disabled={state.sending}
        onChange={event => dispatch({ type: 'composer/changed', text: event.target.value })} />
      <button type="submit" className="agt-action" disabled={!canMessage || state.sending || running || state.connection !== 'open' || state.composer.trim() === ''}>
        {state.sending ? 'Sending…' : 'Send to CI agent'}
      </button>
      {!canMessage && <small>{ownerActive ? 'This CI run cannot accept a message right now.' : 'Automation has ended. This conversation is read-only.'}</small>}
    </form>
  </AgentConversation>;
}
