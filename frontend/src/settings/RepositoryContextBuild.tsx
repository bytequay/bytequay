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
import { parseStartContextDiscoveryRequest, type ContextBuildViewDto,
  parseStartContextLearningRequest, parseContinueContextLearningRequest, parseLearnContextMergedCandidateRequest,
  type ContextDiscoveryReportDto, type ContextLearningPlanDto, type ContextMergedCandidatesDto, type LearnContextMergedCandidateRequest,
  type ContinueContextLearningRequest, type RepositoryNotebookDto, type StartContextDiscoveryRequest,
  type StartContextLearningRequest } from '../repository-context/contracts';
import type { AgentApprovalDecision, AgentHistoryPageDto, AgentPendingApprovalDto, AgentRuntimeStopImpactDto, AgentTurnDto, ModelSettingsDto } from '../types';
import { providerAvailable, providerRemedy } from './model';
import { settingsRoute } from './routes';

type Build = NonNullable<ContextBuildViewDto['build']>;
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const discoveryPendingKey = (workspaceId: string) => `repository-context-discover:${workspaceId}`;
const learningPendingKey = (workspaceId: string) => `repository-context-learn:${workspaceId}`;
const continuationPendingKey = (workspaceId: string) => `repository-context-continue:${workspaceId}`;
const candidatePendingKey = (workspaceId: string) => `repository-context-candidate:${workspaceId}`;

function retainedDiscoveryRequest(workspaceId: string): StartContextDiscoveryRequest | null {
  const saved = sessionStorage.getItem(discoveryPendingKey(workspaceId));
  if (saved === null) return null;
  try { return parseStartContextDiscoveryRequest(JSON.parse(saved)); }
  catch { return null; }
}
function retainedLearningRequest(workspaceId: string): StartContextLearningRequest | null {
  const saved = sessionStorage.getItem(learningPendingKey(workspaceId));
  if (saved === null) return null;
  try { return parseStartContextLearningRequest(JSON.parse(saved)); }
  catch { return null; }
}
function retainedContinuationRequest(workspaceId: string): ContinueContextLearningRequest | null {
  const saved = sessionStorage.getItem(continuationPendingKey(workspaceId));
  if (saved === null) return null;
  try { return parseContinueContextLearningRequest(JSON.parse(saved)); }
  catch { return null; }
}
function retainedCandidateRequest(workspaceId: string): LearnContextMergedCandidateRequest | null {
  const saved = sessionStorage.getItem(candidatePendingKey(workspaceId));
  if (saved === null) return null;
  try { return parseLearnContextMergedCandidateRequest(JSON.parse(saved)); }
  catch { return null; }
}

function buildLabel(build: Build, canStart: boolean): string {
  if (build.task === null) return canStart ? 'Could not start' : 'Preparing task';
  if (build.task.mutationClaim === 'RECOVERY') return 'Needs recovery';
  if (build.task.state === 'COMPLETED') return 'Completed';
  if (build.task.state === 'FAILED') return 'Failed';
  if (build.pendingApprovals > 0) return 'Approval needed';
  if (build.activeTurn?.cancellationRequestedAt != null) return 'Stopping';
  return build.activeTurn === null ? 'Preparing or finishing' : build.mode === 'DISCOVER' ? 'Discovering' : 'Learning';
}

export default function RepositoryContextBuild({ workspaceId, notebook, onNotebookChange, disabled }: {
  workspaceId: string; notebook: RepositoryNotebookDto; onNotebookChange: (value: RepositoryNotebookDto) => void; disabled: boolean;
}) {
  const [view, setView] = useState<ContextBuildViewDto | null>(null);
  const [report, setReport] = useState<ContextDiscoveryReportDto | null | undefined>(undefined);
  const [plan, setPlan] = useState<ContextLearningPlanDto | null | undefined>(undefined);
  const [candidates, setCandidates] = useState<ContextMergedCandidatesDto | null>(null);
  const [candidateError, setCandidateError] = useState<string | null>(null);
  const [selectedTopics, setSelectedTopics] = useState<string[]>([]);
  const [sourceToAllow, setSourceToAllow] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [discoveryPending, setDiscoveryPending] = useState(() => retainedDiscoveryRequest(workspaceId));
  const [learningPending, setLearningPending] = useState(() => retainedLearningRequest(workspaceId));
  const [continuationPending, setContinuationPending] = useState(() => retainedContinuationRequest(workspaceId));
  const [candidatePending, setCandidatePending] = useState(() => retainedCandidateRequest(workspaceId));
  const [inspectedPending, setInspectedPending] = useState(false);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [checking, setChecking] = useState(false);
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
    const discoveryRequest = retainedDiscoveryRequest(workspaceId);
    if (discoveryRequest !== null && value.build?.requestKey === discoveryRequest.requestKey) {
      sessionStorage.removeItem(discoveryPendingKey(workspaceId));
      setDiscoveryPending(null);
    }
    const learningRequest = retainedLearningRequest(workspaceId);
    if (learningRequest !== null && value.build?.requestKey === learningRequest.requestKey) {
      sessionStorage.removeItem(learningPendingKey(workspaceId));
      setLearningPending(null);
    }
    const continuationRequest = retainedContinuationRequest(workspaceId);
    if (continuationRequest !== null && value.build?.requestKey === continuationRequest.requestKey) {
      sessionStorage.removeItem(continuationPendingKey(workspaceId));
      setContinuationPending(null);
    }
    const candidateRequest = retainedCandidateRequest(workspaceId);
    if (candidateRequest !== null && value.build?.requestKey === candidateRequest.requestKey) {
      sessionStorage.removeItem(candidatePendingKey(workspaceId));
      setCandidatePending(null);
    }
    const resultIdentity = JSON.stringify([value.buildRequestSequence, value.build?.task?.state, value.build?.results]);
    if (value.notebookRevision !== notebookRef.current.revision || (value.build?.results.length && observedResult.current !== resultIdentity)) {
      const saved = await window.bridge.getRepositoryContext(workspaceId);
      if (mounted.current) { onNotebookChangeRef.current(saved); observedResult.current = resultIdentity; }
    }
  }, [workspaceId]);
  const inspect = useCallback(async () => {
    const [value, savedReport, savedPlan] = await Promise.all([window.bridge.getRepositoryContextBuild(workspaceId),
      window.bridge.getRepositoryContextDiscoveryReport(workspaceId), window.bridge.getRepositoryContextLearningPlan(workspaceId)]);
    if (mounted.current) { setReport(savedReport); setPlan(savedPlan); }
    await apply(value);
    if (mounted.current) setInspectedPending(true);
  }, [apply, workspaceId]);
  useEffect(() => {
    mounted.current = true;
    void inspect().catch(failure => { if (mounted.current) setError(failureText(failure)); });
    void window.bridge.getModelSettings(workspaceId).then(found => { if (mounted.current) setModels(found); })
      .catch(failure => { if (mounted.current) setError(failureText(failure)); });
    void window.bridge.getRepositoryContextMergedCandidates(workspaceId).then(found => { if (mounted.current) setCandidates(found); })
      .catch(failure => { if (mounted.current) setCandidateError(failureText(failure)); });
    return () => { mounted.current = false; };
  }, [inspect, workspaceId]);
  useEffect(() => setSelectedTopics([]), [report?.id]);
  useEffect(() => setSourceToAllow(''), [plan?.id]);
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
  const startDiscovery = async (request: StartContextDiscoveryRequest) => {
    sessionStorage.setItem(discoveryPendingKey(workspaceId), JSON.stringify(request));
    setDiscoveryPending(request);
    setInspectedPending(false);
    const value = await window.bridge.startRepositoryContextDiscovery(workspaceId, request);
    await apply(value);
    if (mounted.current) setActivity(true);
  };
  const startLearning = async (request: StartContextLearningRequest) => {
    sessionStorage.setItem(learningPendingKey(workspaceId), JSON.stringify(request));
    setLearningPending(request);
    setInspectedPending(false);
    await apply(await window.bridge.startRepositoryContextLearning(workspaceId, request));
    await inspect();
    if (mounted.current) setActivity(true);
  };
  const continueLearning = async (request: ContinueContextLearningRequest) => {
    sessionStorage.setItem(continuationPendingKey(workspaceId), JSON.stringify(request));
    setContinuationPending(request);
    setInspectedPending(false);
    await apply(await window.bridge.continueRepositoryContextLearning(workspaceId, request));
    await inspect();
    if (mounted.current) setActivity(true);
  };
  const learnCandidate = async (request: LearnContextMergedCandidateRequest) => {
    sessionStorage.setItem(candidatePendingKey(workspaceId), JSON.stringify(request));
    setCandidatePending(request);
    setInspectedPending(false);
    await apply(await window.bridge.learnRepositoryContextMergedCandidate(workspaceId, request));
    await inspect();
    if (mounted.current) setActivity(true);
  };
  const reviewModel = models?.agents.find(agent => agent.type === 'REVIEW');
  const provider = models?.providers.find(item => item.id === reviewModel?.configId);
  const modelReady = provider !== undefined && providerAvailable(provider.readiness) && provider.models.some(model => model.id === reviewModel?.model);
  const blocker = models === null ? 'Checking the workspace Review model…' : provider === undefined ? 'The workspace Review provider is unavailable.'
    : !providerAvailable(provider.readiness) ? providerRemedy(provider.readiness, provider.id).title
      : !modelReady ? 'The configured Review model is unavailable.' : null;
  const newDisabled = disabled || busy || view === null || !view.canStart || discoveryPending !== null
    || learningPending !== null || continuationPending !== null || candidatePending !== null;
  const unfinishedPlan = plan?.topics.some(topic => topic.status === 'PENDING') ?? false;
  const currentTopic = plan?.topics.find(topic => topic.status === 'PENDING');
  const additionalSources = currentTopic === undefined ? [] : notebook.sources.filter(source => !currentTopic.sourceIds.includes(source.id));
  const selectedAdditionalSource = additionalSources.find(source => source.id === sourceToAllow);
  const selectedSources = report?.sources.filter(source => report.topics.some(topic => selectedTopics.includes(topic.id)
    && topic.sourceIds.includes(source.id))) ?? [];
  const fence = () => {
    if (view === null) throw new Error('Task status is unavailable. Inspect the latest task before starting.');
    return { expectedRevision: notebook.revision, expectedBuildSequence: view.buildRequestSequence };
  };

  return <section className="rc-build" aria-label="Learn repository context">
    <div className="rc-actions"><button type="button" className="rc-button is-primary" disabled={newDisabled || !modelReady || report === undefined || unfinishedPlan}
      onClick={() => void perform(() => startDiscovery({ requestKey: crypto.randomUUID(), ...fence() }))}>
      {report === null ? 'Start discovery' : 'Refresh discovery'}</button>
      {notebook.pendingDrafts.length > 0 && <button type="button" className="rc-button"
        onClick={() => document.getElementById('repository-context-drafts')?.scrollIntoView({ behavior: 'smooth' })}>Review drafts</button>}
      <button type="button" className="rc-button" disabled={busy} onClick={() => void perform(inspect)}>Inspect latest task</button></div>
    <p className="rc-muted">Discovery surveys repository structure and selected files in a bounded task. Opening this page does not start an agent.</p>
    <p className="rc-muted">Review model: {reviewModel === undefined ? 'Checking…' : `${reviewModel.label} · ${reviewModel.modelLabel}`}
      {blocker !== null && <> · {blocker} <a href={settingsRoute('ws-agents', workspaceId)}>Workspace agent settings</a></>}</p>
    <button type="button" className="rc-button" disabled={checking || busy} onClick={() => void checkModel()}>
      {checking ? 'Checking model…' : 'Check model'}</button>
    {error !== null && <div className="rc-error" role="alert">{error}</div>}
    {discoveryPending !== null && !busy && <div className="rc-error" role="alert">
      The discovery start response is unconfirmed. Inspect the latest task or retry this same request; no new request will be created.
      <div className="rc-actions"><button type="button" className="rc-button" onClick={() => void perform(() => startDiscovery(discoveryPending))}>Retry same request</button>
        {inspectedPending && view !== null && view.canStart && view.buildRequestSequence >= discoveryPending.expectedBuildSequence
          && view.build?.requestKey !== discoveryPending.requestKey
          && <button type="button" className="rc-button" onClick={() => { sessionStorage.removeItem(discoveryPendingKey(workspaceId)); setDiscoveryPending(null); }}>Discard unconfirmed request</button>}</div>
      {inspectedPending && <p>Discarding an unconfirmed request does not cancel an accepted running task.</p>}
    </div>}
    {learningPending !== null && !busy && <div className="rc-error" role="alert">
      The learning start response is unconfirmed. Inspect the latest task or retry this same request.
      <div className="rc-actions"><button type="button" className="rc-button" onClick={() => void perform(() => startLearning(learningPending))}>Retry same request</button>
        {inspectedPending && view !== null && view.canStart && view.buildRequestSequence >= learningPending.expectedBuildSequence
          && view.build?.requestKey !== learningPending.requestKey
          && <button type="button" className="rc-button" onClick={() => { sessionStorage.removeItem(learningPendingKey(workspaceId)); setLearningPending(null); }}>Discard unconfirmed request</button>}</div>
    </div>}
    {continuationPending !== null && !busy && <div className="rc-error" role="alert">
      The continuation response is unconfirmed. Inspect the latest task or retry this same request.
      <div className="rc-actions"><button type="button" className="rc-button" onClick={() => void perform(() => continueLearning(continuationPending))}>Retry same request</button>
        {inspectedPending && view !== null && view.canStart && view.buildRequestSequence >= continuationPending.expectedBuildSequence
          && view.build?.requestKey !== continuationPending.requestKey
          && <button type="button" className="rc-button" onClick={() => { sessionStorage.removeItem(continuationPendingKey(workspaceId)); setContinuationPending(null); }}>Discard unconfirmed request</button>}</div>
    </div>}
    {candidatePending !== null && !busy && <div className="rc-error" role="alert">
      The merged-work learning response is unconfirmed. Inspect the latest task or retry this same request.
      <div className="rc-actions"><button type="button" className="rc-button" onClick={() => void perform(() => learnCandidate(candidatePending))}>Retry same request</button>
        {inspectedPending && view !== null && view.canStart && view.buildRequestSequence >= candidatePending.expectedBuildSequence
          && view.build?.requestKey !== candidatePending.requestKey
          && <button type="button" className="rc-button" onClick={() => { sessionStorage.removeItem(candidatePendingKey(workspaceId)); setCandidatePending(null); }}>Discard unconfirmed request</button>}</div>
    </div>}
    {view === null && <p className="rc-muted">Loading learning status…</p>}
    {build !== null && <article className="rc-note rc-build-result">
      <div className="rc-note-head"><strong>Repository {build.mode === 'DISCOVER' ? 'discovery' : 'learning'} · {buildLabel(build, view?.canStart ?? false)}</strong><small>{build.task?.model ?? 'Configured Review model'}</small></div>
      {build.scopeIds.length > 0 && <p>{build.scopeIds.map(id => notebook.scopes.find(scope => scope.id === id)?.title ?? id).join(', ')}</p>}
      {build.task?.terminalReason && <p>{build.task.terminalReason}</p>}
      {build.task !== null && build.task.state !== 'OPEN' && build.task.worktreeRemovedAt === null
        && <p className="rc-muted">Clean up the retained worktree before starting another learning task.</p>}
      {build.results.map(result => <details key={result.attemptId + ':' + result.inputFingerprint.scopeId}>
        <summary>{notebook.scopes.find(scope => scope.id === result.inputFingerprint.scopeId)?.title ?? result.inputFingerprint.scopeId}
          {' · '}{({ READY: 'Draft prepared', EMPTY: 'No useful note found', FAILED: 'Failed' })[result.outcome]}</summary>
        <p>{result.explanation}</p><p>{result.checkedReads.length} captured sources. Review pending drafts and their sources below.</p>
      </details>)}
      {build.gaps.length > 0 && <details><summary>Limitations · {build.gaps.length}</summary><ul>{build.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul></details>}
      <div className="rc-actions">
        {running && build.task !== null && <button type="button" className="rc-button" disabled={busy || build.activeTurn?.cancellationRequestedAt != null}
          onClick={() => void perform(async () => apply(await window.bridge.stopRepositoryContextBuild(workspaceId, build.taskId)))}>
          Stop {build.mode === 'DISCOVER' ? 'discovery' : 'learning'}</button>}
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
    {report !== undefined && (report === null ? <p className="rc-muted">No saved discovery report yet. Start discovery to get proposed areas and sources.</p>
      : <article className="rc-note rc-build-result" aria-label="Saved discovery report">
        <div className="rc-note-head"><strong>Suggested areas · {report.partial ? 'Partial survey' : 'Saved survey'}</strong>
          <small>{report.repository.owner}/{report.repository.repo}</small></div>
        <p>{report.outline}</p>
        <p className="rc-muted">Area suggestions cite repository reads. Source paths and URLs are agent suggestions; inspect them before approval. No knowledge note is active yet.</p>
        {report.topics.length === 0 && <p>No supported topics were proposed yet.</p>}
        {report.topics.map(topic => <div key={topic.id} className="rc-discovery-topic">
          <label><input type="checkbox" checked={selectedTopics.includes(topic.id)} disabled={unfinishedPlan || newDisabled}
            onChange={event => setSelectedTopics(current => event.target.checked ? [...current, topic.id] : current.filter(id => id !== topic.id))} />
            {topic.title}</label>
          <p>{topic.question}</p>
          <details><summary>Why this area was suggested</summary><p>{topic.why}</p>
            {topic.paths.length > 0 && <p>Repository areas: {topic.paths.join(', ')}</p>}
            {topic.sourceIds.length > 0 && <ul>{topic.sourceIds.map(id => { const source = report.sources.find(value => value.id === id);
              return <li key={id}>{source?.title ?? id}: {source?.location ?? 'Source unavailable'}</li>; })}</ul>}
            {topic.evidenceReadIds.map(id => { const evidence = report.evidence.find(value => value.readId === id);
              return evidence === undefined ? null : <details key={id}><summary>{evidence.path}:{evidence.startLine}–{evidence.endLine}</summary>
                <pre className="rc-saved-version">{evidence.excerpt}</pre>{evidence.truncated && <p>Excerpt is incomplete.</p>}</details>; })}
          </details>
        </div>)}
        {selectedTopics.length > 0 && !unfinishedPlan && <div className="rc-discovery-selection">
          <strong>Suggested sources to approve</strong>
          <p>Repository areas: {[...new Set(report.topics.filter(topic => selectedTopics.includes(topic.id)).flatMap(topic => topic.paths))].join(', ') || 'Project files cited by the selected topics'}</p>
          {selectedSources.length === 0 ? <p>No documentation site was proposed for these topics.</p>
            : <ul>{selectedSources.map(source => <li key={source.id}>Suggested {source.kind === 'DOCUMENTATION_ROOT' ? 'documentation website' :
              source.kind === 'DOCUMENTATION_PAGE' ? 'documentation page' : 'repository file'}: {source.location}
              <details><summary>Why this source was suggested</summary><p>{source.why}</p>
                {source.evidenceReadIds.map(id => { const evidence = report.evidence.find(value => value.readId === id);
                  return evidence === undefined ? null : <details key={id}><summary>Cited read: {evidence.path}:{evidence.startLine}–{evidence.endLine}</summary>
                    <pre className="rc-saved-version">{evidence.excerpt}</pre></details>; })}</details></li>)}</ul>}
          {selectedSources.length > 0 && <p className="rc-muted">The cited reads support the suggestion but do not verify that the exact path or URL appears in an excerpt. Learn selected approves these displayed destinations.</p>}
          <p className="rc-muted">One topic runs at a time. Each run allows up to 40 source operations and 10 documentation opens; approved links can reach two hops.</p>
          <button type="button" className="rc-button is-primary" disabled={newDisabled || !modelReady || selectedTopics.length === 0}
            onClick={() => void perform(() => startLearning({ requestKey: crypto.randomUUID(), ...fence(),
              reportId: report.id, topicIds: selectedTopics }))}>Learn selected</button>
        </div>}
        {unfinishedPlan && <p className="rc-muted">Finish or continue the current learning plan before starting another selection.</p>}
        {(report.gaps.length > 0 || report.remaining.length > 0) && <details><summary>Limits and remaining work</summary>
          {report.gaps.length > 0 && <ul>{report.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul>}
          {report.remaining.length > 0 && <><p>Not yet surveyed:</p><ul>{report.remaining.map((item, index) => <li key={index}>{item}</li>)}</ul></>}</details>}
        <details><summary>Report source version</summary><p>Baseline: <code>{report.baselineSha}</code></p><p>Saved: {report.createdAt}</p></details>
      </article>)}
    {plan !== undefined && plan !== null && <article className="rc-note rc-build-result" aria-label="Learning progress">
      <div className="rc-note-head"><strong>Learning progress</strong><small>{plan.topics.filter(topic => topic.status === 'COMPLETED').length} of {plan.topics.length} topics completed</small></div>
      <p className="rc-muted">Accepted sources stay tied to {plan.repository.owner}/{plan.repository.repo} at <code>{plan.baselineSha}</code>.</p>
      {plan.topics.map(topic => <details key={topic.id}><summary>{topic.title} · {topic.status === 'COMPLETED' ? 'Completed' : 'Pending'}</summary>
        <p>{topic.remainingQuestion || topic.question}</p><p>Repository areas: {topic.paths.join(', ') || 'Project overview'}</p></details>)}
      {plan.documentInventory.sources.length > 0 && <details><summary>Documentation inventory · {plan.documentInventory.items.filter(item => item.status === 'READ').length} opened</summary>
        <p className="rc-muted">Opened means a bounded page excerpt was read. It does not mean the whole page was understood.</p>
        <ul>{plan.documentInventory.items.map(item => <li key={item.sourceId + ':' + item.resolvedUrl}>
          {item.status === 'READ' ? 'Opened' : item.status === 'PENDING' ? 'Pending' : item.status === 'FAILED' ? 'Failed' : 'Excluded'}:
          {' '}{item.resolvedUrl}{item.detail && ` · ${item.detail}`}</li>)}</ul>
        {plan.documentInventory.truncated && <p>More links were found than this bounded inventory could retain.</p>}
      </details>}
      {unfinishedPlan && plan.documentInventory.suggestedExpansionUrls.length > 0 && <div className="rc-discovery-selection">
        <strong>Suggested external sources</strong>
        <p className="rc-muted">These links were found on an opened page but are outside the approved source. A URL ending in / becomes a website root with links up to two hops; any other URL stays a single page. Approval adds the displayed URL to the current question without starting a task.</p>
        <ul>{plan.documentInventory.suggestedExpansionUrls.map(url => <li key={url}>
          {url.endsWith('/') ? 'Suggested website root' : 'Suggested single page'}: {url}{' '}
          <button type="button" className="rc-button" disabled={newDisabled} onClick={() => void perform(async () => {
            setPlan(await window.bridge.allowRepositoryContextLearningSource(workspaceId, { planId: plan.id, suggestedUrl: url, ...fence() }));
            await inspect();
          })}>Allow this source</button></li>)}</ul>
      </div>}
      {plan.gaps.length > 0 && <details><summary>Learning limitations</summary><ul>{plan.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul></details>}
      {unfinishedPlan && <div className="rc-actions"><button type="button" className="rc-button is-primary" disabled={newDisabled || !modelReady}
        onClick={() => void perform(() => continueLearning({ requestKey: crypto.randomUUID(), ...fence(), planId: plan.id }))}>Continue learning</button>
        <span className="rc-muted">Review drafts below before approving them. Approval does not start another run.</span></div>}
      {unfinishedPlan && <details><summary>Allow another documentation source</summary>
        {additionalSources.length === 0 ? <p>Add a documentation page or website under Advanced configuration to expand this question.</p>
          : <><label>Registered source<select value={sourceToAllow} onChange={event => setSourceToAllow(event.target.value)}>
            <option value="">Choose a source</option>{additionalSources.map(source => <option key={source.id} value={source.id}>{source.url}</option>)}
          </select></label>
            {selectedAdditionalSource !== undefined && <p>{selectedAdditionalSource.kind === 'DOCUMENTATION_ROOT' ? 'Documentation website' : 'Single page'}:
              {' '}{selectedAdditionalSource.url}</p>}
            <p className="rc-muted">This adds only the selected registered source to the current question. New links under a website can reach two hops. Approval does not start a task.</p>
            <button type="button" className="rc-button" disabled={newDisabled || selectedAdditionalSource === undefined}
              onClick={() => void perform(async () => {
                setPlan(await window.bridge.allowRepositoryContextLearningSource(workspaceId, { planId: plan.id, sourceId: sourceToAllow,
                  ...fence() }));
                setSourceToAllow(''); await inspect();
              })}>Allow source for current question</button></>}
      </details>}
      {unfinishedPlan && <details><summary>Plan options</summary><p>Discard the remaining questions and source inventory. Saved notes and drafts remain.</p>
        <button type="button" className="rc-button" disabled={newDisabled} onClick={() => void perform(async () => {
          setPlan(await window.bridge.discardRepositoryContextLearningPlan(workspaceId, { planId: plan.id, ...fence() }));
          await inspect();
        })}>Discard remaining plan</button></details>}
    </article>}
    <details className="rc-advanced"><summary>Merged work candidates{candidates !== null ? ` · ${candidates.candidates.length}` : ''}</summary>
      <p className="rc-muted">A confirmed merge can suggest a learning question. Select a PR to start one bounded task; its merge and checks do not prove every claim.</p>
      <button type="button" className="rc-button" disabled={busy} onClick={() => void perform(async () => {
        setCandidates(await window.bridge.getRepositoryContextMergedCandidates(workspaceId)); setCandidateError(null);
      })}>Refresh candidates</button>
      {candidateError !== null && <p className="rc-error" role="alert">Candidates could not be loaded. {candidateError}</p>}
      {candidates === null && candidateError === null && <p className="rc-muted">Loading retained merged work…</p>}
      {candidates !== null && candidates.candidates.length === 0 && <p className="rc-muted">No confirmed merged development work is available here yet.</p>}
      {candidates?.candidates.map(candidate => <article key={candidate.id} className="rc-note rc-build-result">
        <div className="rc-note-head"><strong>PR #{candidate.number} · {candidate.title}</strong><small>{candidate.repo} · merged {candidate.mergedAt}</small></div>
        <p><a href={candidate.url}>{candidate.url}</a></p>
        {candidate.taskTitle !== null && <p>Development task: {candidate.taskTitle}</p>}
        <details><summary>Retained bundle and limitations</summary>
          {candidate.taskResult !== null && <p>Task result: {candidate.taskResult}</p>}
          {candidate.learningSummary !== null && <><p>Change intention: {candidate.learningSummary.purpose}</p>
            {candidate.learningSummary.combinedResult !== null && <p>Development summary: {candidate.learningSummary.combinedResult}</p>}
            {candidate.learningSummary.evidence && <p>Retained evidence: {candidate.learningSummary.evidence}</p>}
            {candidate.learningSummary.commits.length > 0 && <ul>{candidate.learningSummary.commits.map(commit =>
              <li key={commit.sha}>{commit.sha.slice(0, 7)} · {commit.title}: {commit.summary}</li>)}</ul>}
            {candidate.learningSummary.limitations.length > 0 && <ul>{candidate.learningSummary.limitations.map((gap, index) => <li key={index}>{gap}</li>)}</ul>}</>}
          {candidate.issue !== null && <p>Linked {candidate.issue.kind}: {candidate.issue.key} · {candidate.issue.title} · {candidate.issue.context}</p>}
          {candidate.review !== null && <><p>Review: {candidate.review.combinedResult}</p>
            {!candidate.review.coverageComplete && <p>Review coverage was incomplete.</p>}
            {[...candidate.review.omissions, ...candidate.review.warnings].map((gap, index) => <p key={index}>{gap}</p>)}</>}
          {candidate.checks.length > 0 && <ul>{candidate.checks.map((check, index) =>
            <li key={index}>{check.name}: {check.status}{check.conclusion && ` · ${check.conclusion}`}</li>)}</ul>}
          {candidate.gaps.map((gap, index) => <p key={index}>{gap}</p>)}
        </details>
        <button type="button" className="rc-button" disabled={newDisabled || !modelReady || unfinishedPlan}
          onClick={() => void perform(() => learnCandidate({ candidateId: candidate.id, requestKey: crypto.randomUUID(), ...fence() }))}>
          Learn from this PR</button>
      </article>)}
      {candidates !== null && candidates.gaps.length > 0 && <details><summary>Candidate coverage limits</summary>
        <ul>{candidates.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul></details>}
    </details>
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
