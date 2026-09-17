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
import { useCallback, useEffect, useRef, useState } from 'react';
import { AgentConversation, useConversationHistory } from '../agents/AgentConversation';
import { projectTranscript, withApprovalAuthority } from '../agents/transcript';
import { ContextDialog } from '../repository-context/ContextEntryEditor';
import { parseStartContextBuildRequest, type ContextBuildHistoryDto, type ContextBuildViewDto, type RepositoryNotebookDto,
  type StartContextBuildRequest } from '../repository-context/contracts';
import type { AgentApprovalDecision, AgentHistoryPageDto, AgentPendingApprovalDto, AgentRuntimeStopImpactDto, AgentTurnDto, ModelSettingsDto } from '../types';
import { providerAvailable, providerRemedy } from './model';
import { settingsRoute } from './routes';
import RepositoryContextDiscovery from './RepositoryContextDiscovery';

type Build = NonNullable<ContextBuildViewDto['build']>;
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const pendingKey = (workspaceId: string) => `repository-context-build:${workspaceId}`;

function retainedRequest(workspaceId: string): StartContextBuildRequest | null {
  const saved = sessionStorage.getItem(pendingKey(workspaceId));
  if (saved === null) return null;
  try { return parseStartContextBuildRequest(JSON.parse(saved)); }
  catch { return null; }
}

function historyInputs(prs: string, issues: string): ContextBuildHistoryDto[] {
  const parse = (value: string, kind: ContextBuildHistoryDto['kind']) => value.trim() === '' ? [] : value.trim().split(/[\s,]+/).map(part => {
    if (!/^#?[1-9][0-9]*$/.test(part)) throw new Error('Use positive PR or issue numbers separated by commas or spaces.');
    const number = Number(part.replace('#', ''));
    if (!Number.isSafeInteger(number) || number > 2147483647) throw new Error('A PR or issue number is too large.');
    return { kind, number };
  });
  const result = [...parse(prs, 'PR'), ...parse(issues, 'ISSUE')];
  if (result.length > 10) throw new Error('Choose at most 10 PRs and issues in total.');
  if (new Set(result.map(item => `${item.kind}:${item.number}`)).size !== result.length) throw new Error('Each PR or issue can be selected only once.');
  return result;
}

function buildLabel(build: Build, canStart: boolean): string {
  if (build.task === null) return canStart ? 'Could not start' : 'Preparing task';
  if (build.task.mutationClaim === 'RECOVERY') return 'Needs recovery';
  if (build.task.state === 'COMPLETED') return 'Completed';
  if (build.task.state === 'FAILED') return 'Failed';
  if (build.pendingApprovals > 0) return 'Approval needed';
  if (build.activeTurn?.cancellationRequestedAt != null) return 'Stopping';
  return build.activeTurn === null ? 'Preparing or finishing' : 'Learning';
}

export default function RepositoryContextBuild({ workspaceId, notebook, onNotebookChange, disabled }: {
  workspaceId: string; notebook: RepositoryNotebookDto; onNotebookChange: (value: RepositoryNotebookDto) => void; disabled: boolean;
}) {
  const [view, setView] = useState<ContextBuildViewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(() => retainedRequest(workspaceId));
  const [inspectedPending, setInspectedPending] = useState(false);
  const [form, setForm] = useState(false);
  const [selected, setSelected] = useState<string[]>(['project']);
  const [prs, setPrs] = useState('');
  const [issues, setIssues] = useState('');
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [checking, setChecking] = useState(false);
  const [discovering, setDiscovering] = useState(false);
  const [discoveryReload, setDiscoveryReload] = useState(false);
  const [unappliedSuggestions, setUnappliedSuggestions] = useState(false);
  const [activity, setActivity] = useState(false);
  const [impact, setImpact] = useState<AgentRuntimeStopImpactDto | null>(null);
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const notebookRef = useRef(notebook);
  const onNotebookChangeRef = useRef(onNotebookChange);
  const observedResult = useRef('');
  notebookRef.current = notebook;
  onNotebookChangeRef.current = onNotebookChange;

  const apply = useCallback(async (value: ContextBuildViewDto) => {
    if (!mounted.current) return;
    setView(value);
    const request = retainedRequest(workspaceId);
    if (request !== null && value.build?.requestKey === request.requestKey) {
      sessionStorage.removeItem(pendingKey(workspaceId));
      setPending(null);
    }
    const resultIdentity = JSON.stringify([value.buildRequestSequence, value.build?.task?.state, value.build?.results]);
    if (value.notebookRevision !== notebookRef.current.revision || (value.build?.results.length && observedResult.current !== resultIdentity)) {
      const saved = await window.bridge.getRepositoryContext(workspaceId);
      if (mounted.current) { onNotebookChangeRef.current(saved); observedResult.current = resultIdentity; }
    }
  }, [workspaceId]);
  const inspect = useCallback(async () => {
    const value = await window.bridge.getRepositoryContextBuild(workspaceId);
    await apply(value);
    if (mounted.current) setInspectedPending(true);
  }, [apply, workspaceId]);
  useEffect(() => {
    mounted.current = true;
    void inspect().catch(failure => { if (mounted.current) setError(failureText(failure)); });
    return () => { mounted.current = false; };
  }, [inspect]);
  const build = view?.build ?? null;
  const running = build !== null && (build.task === null ? view?.canStart === false : build.task.state === 'OPEN');
  useEffect(() => {
    if (!running) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try { await inspect(); }
      catch (failure) { if (active) setError('Task status could not be refreshed. ' + failureText(failure)); }
      if (active) timer = setTimeout(() => void poll(), 2000);
    };
    timer = setTimeout(() => void poll(), 2000);
    return () => { active = false; clearTimeout(timer); };
  }, [inspect, running]);

  const perform = async (operation: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(null);
    try { await operation(); }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  };
  const checkModel = async () => {
    setChecking(true);
    try { const found = await window.bridge.getModelSettings(workspaceId); if (mounted.current) setModels(found); }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { if (mounted.current) setChecking(false); }
  };
  const start = async (request: StartContextBuildRequest) => {
    sessionStorage.setItem(pendingKey(workspaceId), JSON.stringify(request));
    setPending(request);
    setInspectedPending(false);
    const value = await window.bridge.startRepositoryContextBuild(workspaceId, request);
    await apply(value);
    if (mounted.current) { setForm(false); setActivity(true); }
  };
  const scopeReason = (id: string): string | null => {
    if (!notebook.scopes.find(scope => scope.id === id)?.enabled) return 'Scope is disabled';
    if (notebook.suppressedKnowledgeIds.includes('knowledge:' + id)) return 'Note was removed; reset removal first';
    const entry = notebook.entries.find(entry => entry.kind === 'KNOWLEDGE' && entry.scopeId === id);
    if (entry?.origin === 'USER') return 'User-managed note is preserved';
    return entry?.enabled === false ? 'Note is disabled' : null;
  };
  const reviewModel = models?.agents.find(agent => agent.type === 'REVIEW');
  const provider = models?.providers.find(item => item.id === reviewModel?.configId);
  const modelReady = provider !== undefined && providerAvailable(provider.readiness) && provider.models.some(model => model.id === reviewModel?.model);
  const blocker = models === null ? 'Checking the workspace Review model…' : provider === undefined ? 'The workspace Review provider is unavailable.'
    : !providerAvailable(provider.readiness) ? providerRemedy(provider.readiness, provider.id).title
      : !modelReady ? 'The configured Review model is unavailable.' : null;
  let history: ContextBuildHistoryDto[] = [];
  let historyError: string | null = null;
  try { history = historyInputs(prs, issues); }
  catch (failure) { historyError = failureText(failure); }
  const selectionValid = selected.length > 0 && selected.every(id => scopeReason(id) === null);
  const newDisabled = disabled || busy || view === null || !view.canStart || pending !== null;

  return <section className="rc-build" aria-label="Learn repository context">
    <div className="rc-actions"><button type="button" className="rc-button is-primary" disabled={newDisabled}
      onClick={() => { setError(null); setSelected(scopeReason('project') === null ? ['project'] : []); setForm(true); void checkModel(); }}>Learn / Refresh</button>
      <button type="button" className="rc-button" disabled={busy} onClick={() => void perform(inspect)}>Inspect latest task</button></div>
    <p className="rc-muted">Learn from repository files, directory structure, repository documentation and registered documentation. You can also select merged PRs and closed issues.</p>
    {error !== null && !form && <div className="rc-error" role="alert">{error}</div>}
    {pending !== null && !busy && <div className="rc-error" role="alert">
      The start response is unconfirmed. Inspect the latest task or retry this same request; no new request will be created.
      <div className="rc-actions"><button type="button" className="rc-button" onClick={() => void perform(() => start(pending))}>Retry same request</button>
        {inspectedPending && view !== null && view.canStart && view.buildRequestSequence >= pending.expectedBuildSequence && view.build?.requestKey !== pending.requestKey
          && <button type="button" className="rc-button" onClick={() => { sessionStorage.removeItem(pendingKey(workspaceId)); setPending(null); }}>Discard unconfirmed request</button>}</div>
      {inspectedPending && <p>Discarding an unconfirmed request does not cancel an accepted running task.</p>}
    </div>}
    {view === null && <p className="rc-muted">Loading learning status…</p>}
    {build !== null && <article className="rc-note rc-build-result">
      <div className="rc-note-head"><strong>Repository learning · {buildLabel(build, view?.canStart ?? false)}</strong><small>{build.task?.model ?? 'Configured Review model'}</small></div>
      <p>{build.scopeIds.map(id => notebook.scopes.find(scope => scope.id === id)?.title ?? id).join(', ')}</p>
      {build.task?.terminalReason && <p>{build.task.terminalReason}</p>}
      {build.task !== null && build.task.state !== 'OPEN' && build.task.worktreeRemovedAt === null
        && <p className="rc-muted">Clean up the retained worktree before starting another learning task.</p>}
      {build.results.map(result => <details key={result.attemptId + ':' + result.inputFingerprint.scopeId}>
        <summary>{notebook.scopes.find(scope => scope.id === result.inputFingerprint.scopeId)?.title ?? result.inputFingerprint.scopeId}
          {' · '}{({ READY: 'Note saved', EMPTY: 'No useful note found', FAILED: 'Failed' })[result.outcome]}</summary>
        <p>{result.explanation}</p><p>{result.checkedReads.length} captured sources. Saved notes and their sources appear below.</p>
      </details>)}
      {build.gaps.length > 0 && <details><summary>Limitations · {build.gaps.length}</summary><ul>{build.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul></details>}
      <div className="rc-actions">
        {running && build.task !== null && <button type="button" className="rc-button" disabled={busy || build.activeTurn?.cancellationRequestedAt != null}
          onClick={() => void perform(async () => apply(await window.bridge.stopRepositoryContextBuild(workspaceId, build.taskId)))}>Stop learning</button>}
        {build.task?.mutationClaim === 'RECOVERY' && <button type="button" className="rc-button" disabled={busy}
          onClick={() => void perform(async () => { const value = await window.bridge.getRepositoryContextBuildStopImpact(workspaceId, build.taskId);
            if (value.taskId !== build.taskId) throw new Error('The runtime belongs to another task.'); setImpact(value); })}>Stop remaining processes</button>}
        {build.task?.state === 'OPEN' && build.activeTurn === null && <button type="button" className="rc-button" disabled={busy}
          onClick={() => void perform(async () => apply(await window.bridge.failRepositoryContextBuild(workspaceId, build.taskId, 'User ended repository context learning.')))}>Mark failed</button>}
        {build.task !== null && build.task.state !== 'OPEN' && build.task.worktreeRemovedAt === null && <button type="button" className="rc-button" disabled={busy}
          onClick={() => void perform(async () => apply(await window.bridge.cleanupRepositoryContextBuild(workspaceId, build.taskId)))}>Clean up worktree</button>}
      </div>
      <details open={activity || build.pendingApprovals > 0} onToggle={event => setActivity(event.currentTarget.open)}><summary>Task activity and messages</summary>
        {(activity || build.pendingApprovals > 0) && build.task !== null && <BuildTranscript key={build.taskId} build={build} onChange={inspect} />}
      </details>
      <details><summary>Task details</summary><p>Task: <code>{build.taskId}</code></p><p>Started: {build.requestedAt}</p>
        <p>Baseline: <code>{build.baselineSha}</code></p>{build.task !== null && <p>Worktree: <code>{build.task.worktreePath}</code></p>}
        <p>{build.task?.worktreeRemovedAt === null ? 'Worktree is retained.' : build.task?.worktreeRemovedAt
          ? 'Worktree cleaned up; its generated branch is retained.' : 'Task worktree has not been reported.'}</p></details>
    </article>}
    {form && <ContextDialog title="Learn / Refresh repository context" busy={busy || discovering} onCancel={() => setForm(false)}>
      <form onSubmit={event => { event.preventDefault(); if (!selectionValid || historyError !== null || !modelReady || view === null || pending !== null || discovering || discoveryReload || unappliedSuggestions) return;
        void perform(() => start({ requestKey: crypto.randomUUID(), expectedRevision: notebook.revision,
          expectedBuildSequence: view.buildRequestSequence, scopeIds: selected, history })); }}>
        <div className="rc-dialog-body"><p className="rc-muted">Repository files, directory structure, repository documentation and registered documentation are always included. Learning starts only when you click Start learning.</p>
          {error !== null && <div className="rc-error" role="alert">{error}</div>}
          <RepositoryContextDiscovery workspaceId={workspaceId} notebook={notebook} scopeIds={selected} history={historyError === null ? history : null}
            disabled={busy || pending !== null} requiresReload={discoveryReload} onBusy={setDiscovering} onSelectionChange={setUnappliedSuggestions} onRequiresReload={setDiscoveryReload}
            onNotebookChange={onNotebookChange} onUse={(scopes, mergedHistory) => {
              setSelected(value => [...new Set([...value, ...scopes])]);
              setPrs(mergedHistory.filter(item => item.kind === 'PR').map(item => item.number).join(', '));
              setIssues(mergedHistory.filter(item => item.kind === 'ISSUE').map(item => item.number).join(', '));
            }} />
          <fieldset disabled={busy || discovering || pending !== null}><legend>Knowledge scopes</legend>{notebook.scopes.map(scope => <label key={scope.id}>
            <input type="checkbox" checked={selected.includes(scope.id)} disabled={scopeReason(scope.id) !== null}
              onChange={event => setSelected(event.target.checked ? [...selected, scope.id] : selected.filter(id => id !== scope.id))} />
            {scope.title}{scopeReason(scope.id) !== null && <span className="rc-muted"> · {scopeReason(scope.id)}</span>}</label>)}</fieldset>
          <label>Merged PR numbers (optional)<input value={prs} disabled={busy || discovering || pending !== null} placeholder="123, 456" onChange={event => setPrs(event.target.value)} /></label>
          <label>Closed issue numbers (optional)<input value={issues} disabled={busy || discovering || pending !== null} placeholder="789" onChange={event => setIssues(event.target.value)} /></label>
          <p className="rc-muted">Up to 10 from this repository. Merge or closure alone does not prove a fix. No history or website crawl is started.</p>
          {historyError !== null && <p className="rc-error" role="alert">{historyError}</p>}
          <p>Review model: {reviewModel === undefined ? 'Loading…' : `${reviewModel.label} · ${reviewModel.modelLabel}${reviewModel.effort ? ' · ' + reviewModel.effort : ''}`}</p>
          {blocker !== null && <p className="rc-muted">{blocker}</p>}
          <div className="rc-actions"><button type="button" className="rc-button" disabled={checking || busy} onClick={() => void checkModel()}>Check provider</button>
            <a href={settingsRoute('ws-agents', workspaceId)}>Workspace agent settings</a></div>
          <p className="rc-muted">Runs as a visible task you can stop. User-managed notes and removed or disabled knowledge are preserved. Review use can remain off.</p>
        </div><footer><button type="button" className="rc-button" disabled={busy || discovering} onClick={() => setForm(false)}>Cancel</button>
          <button type="submit" className="rc-button is-primary" disabled={busy || discovering || discoveryReload || unappliedSuggestions || checking || !selectionValid || historyError !== null || !modelReady || pending !== null}>
            {busy ? 'Starting…' : 'Start learning'}</button></footer>
      </form>
    </ContextDialog>}
    {impact !== null && <ContextDialog title="Stop learning processes" busy={busy} onCancel={() => setImpact(null)}>
      <div className="rc-dialog-body"><p>Stop the remaining provider runtime for this learning task. Its saved notes and worktree are retained.</p>
        <p>{impact.liveProcess ? 'A provider process is still running.' : 'No live provider process was reported.'}</p></div>
      <footer><button type="button" className="rc-button" disabled={busy} onClick={() => setImpact(null)}>Cancel</button>
        <button type="button" className="rc-button is-danger" disabled={busy} onClick={() => void perform(async () => {
          await apply(await window.bridge.stopRepositoryContextBuildRuntime(workspaceId, impact.taskId, impact.generation)); setImpact(null);
        })}>Stop processes</button></footer>
    </ContextDialog>}
  </section>;
}

function BuildTranscript({ build, onChange }: { build: Build; onChange: () => Promise<void> }) {
  const [page, setPage] = useState<AgentHistoryPageDto>({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 });
  const [turns, setTurns] = useState<Record<string, AgentTurnDto>>({});
  const [approvals, setApprovals] = useState<AgentPendingApprovalDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [activity, setActivity] = useState<Record<string, boolean>>({});
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const following = useRef(true);
  const mounted = useRef(true);
  const loadingRef = useRef(false);
  const deciding = useRef(false);
  const activeTurnId = build.activeTurn?.id ?? null;
  const running = build.task?.state === 'OPEN';
  const load = useCallback(async (before?: string, after?: string, history = true) => {
    if (loadingRef.current) return;
    loadingRef.current = true; setLoading(true);
    try {
      const [found, pending]: [AgentHistoryPageDto | null, AgentPendingApprovalDto[]] = await Promise.all([
        history ? window.bridge.readAgentHistory(build.taskId, before, after) : Promise.resolve(null),
        activeTurnId === null ? Promise.resolve([]) : window.bridge.readAgentPendingApprovals(build.taskId, activeTurnId)]);
      const ids = new Set(found?.events.flatMap(event => event.turnId === null ? [] : [event.turnId]) ?? []);
      const durable = await Promise.all([...ids].map(id => window.bridge.getAgentTurn(id)));
      if (mounted.current) {
        if (found !== null) { setPage(found); setTurns(Object.fromEntries(durable.map(turn => [turn.id, turn]))); }
        setApprovals(pending); setError(null);
      }
    }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { loadingRef.current = false; if (mounted.current) setLoading(false); }
  }, [activeTurnId, build.taskId]);
  useEffect(() => {
    mounted.current = true;
    void load();
    const timer = running ? setInterval(() => void load(undefined, undefined, following.current), 2000) : null;
    return () => { mounted.current = false; if (timer !== null) clearInterval(timer); };
  }, [load, running]);
  const scroll = useConversationHistory(page.events, null, { before: page.beforeCursor, after: page.afterCursor, loading,
    onFollow: value => { following.current = value; }, onLoad: direction => { following.current = direction === 'latest';
      void load(direction === 'older' ? page.beforeCursor ?? undefined : undefined, direction === 'newer' ? page.afterCursor ?? undefined : undefined); } });
  const projected = withApprovalAuthority(projectTranscript(page.events, null, turns, page.beforeCursor === null, page.afterCursor === null),
    new Set(approvals.map(approval => approval.approvalId)));
  const decide = async (id: string, decision: AgentApprovalDecision) => {
    if (deciding.current) return;
    deciding.current = true;
    try { await window.bridge.resolveAgentApproval(id, decision); await load(); await onChange(); }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { deciding.current = false; }
  };
  return <div className="rc-build-transcript">
    {error !== null && <div className="rc-error" role="alert">Activity could not be refreshed. {error}<button type="button" className="rc-button" onClick={() => void load()}>Retry activity</button></div>}
    <AgentConversation turns={projected} durableTurns={turns} models={[]} now={Date.now()} scroll={scroll} connection={error === null ? 'open' : 'reconnecting'}
      running={running} activeTurn={build.activeTurn} waiting={approvals.length > 0} pendingApprovals={approvals} activityChoice={activity} turnChoice={expanded}
      onToggleTurn={(id, open) => setExpanded(value => ({ ...value, [id]: open }))} onToggleActivity={(id, open) => setActivity(value => ({ ...value, [id]: open }))}
      onDecide={(id, decision) => void decide(id, decision)} label="Repository learning transcript">{null}</AgentConversation>
  </div>;
}
