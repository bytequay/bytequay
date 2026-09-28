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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import AppSidebar from '../../AppSidebar';
import type { AgentPendingApprovalDto, Bridge, WorkspaceDto } from '../../types';
import type { AgentEventEnvelope } from '../../agents/events';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { ApplyContextDiscoveryRequest, ContextBuildViewDto, ContextDiscoveryDto, ContextEntryDto, ContextScopeDto, ContextSourceDto, ContextUsageOverviewDto, DiscoverRepositoryContextRequest, RepositoryNotebookDto, StartContextBuildRequest } from '../../repository-context/contracts';
import SettingsWindow from '../SettingsWindow';
import '../../css/base.css';
import '../../css/settings-window.css';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
const workspaces = [
  { id: 'ws-1', name: 'Trino', selectedOwner: 'trinodb', selectedRepo: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' },
  { id: 'ws-2', name: 'Iceberg', selectedOwner: 'apache', selectedRepo: 'iceberg', monitoredOwner: 'apache', monitoredRepo: 'iceberg' },
] as WorkspaceDto[];
const eventSource: AgentPendingApprovalDto['source'] = { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null,
  providerTurnId: null, providerItemId: null, providerEventId: null };

function notebook(second = false): RepositoryNotebookDto {
  return {
    schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, activePreparation: null,
    sources: [{ id: 'docs-trino', kind: 'DOCUMENTATION_ROOT', url: 'https://trino.io/docs/current/', origin: 'USER', discoveryEvidenceRef: null }],
    scopes: [
      { id: 'project', title: 'Project overview', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null, discoveryEvidenceRef: null },
      { id: 'path:plugin/trino-iceberg/', title: 'Iceberg connector',
        match: { kind: 'SELECTED', pathPrefixes: ['plugin/trino-iceberg/'], terms: ['Iceberg'] }, enabled: true, preparation: null, discoveryEvidenceRef: null },
    ],
    entries: [
      { id: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: second ? 'Second workspace notes' : 'Project architecture',
        summary: 'A short overview of query planning, workers and connector boundaries.',
        body: 'Trino coordinates distributed queries across workers. Connector implementations provide access to external data. Verify these notes against the reviewed version.',
        claims: [{ text: 'Connector contracts can differ by release.', basis: 'INFERENCE', sourceIds: ['source-doc'] }],
        sourceIds: ['source-doc'], origin: 'GENERATED', enabled: true, version: 2 },
      { id: 'lesson:field-ids', kind: 'MEMORY', scopeId: 'path:plugin/trino-iceberg/', title: 'Resolve nested fields within their parent',
        summary: 'Apply this lesson when nested structs reuse a leaf name.',
        body: 'Inspect the parent struct before selecting a leaf field ID. Keep a regression example with duplicate leaf names.',
        claims: [{ text: 'The lesson applies to nested field lookup.', basis: 'USER_LESSON', sourceIds: ['source-review'] }],
        sourceIds: ['source-review'], origin: 'USER', enabled: true, version: 3 },
    ],
    pendingDrafts: [], suppressedKnowledgeIds: [],
    sourceEvidence: [
      { id: 'source-doc', reference: { kind: 'DOCUMENT', sourceId: 'docs-trino', url: 'https://trino.io/docs/current/overview/concepts.html' },
        provenance: 'Selected Trino documentation page', excerpt: 'Coordinators and workers participate in query execution.',
        capture: 'BYTEQUAY_READ', retrievedAt: '2026-09-01T00:00:00Z', contentHash: 'a'.repeat(64), version: null, truncated: false },
      { id: 'source-review', reference: { kind: 'REVIEW', reviewId: 'review', runId: 'older-run', evidenceRef: null, messageId: 'discussion' },
        provenance: 'User-saved private review discussion', excerpt: 'Keep the nested lookup scoped to its parent.',
        capture: 'BYTEQUAY_READ', retrievedAt: '2026-09-01T00:00:00Z', contentHash: 'b'.repeat(64), version: null, truncated: false },
    ],
  };
}

function usage(window: ContextUsageOverviewDto['window']): ContextUsageOverviewDto {
  return { trackingSince: '2026-09-01T00:00:00Z', window, coverage: 'RETAINED_TASKS_AND_REVIEWS',
    summary: { eligibleDevelopmentTasks: 2, eligibleReviewRuns: 1, contextOffTasks: 1, tasksProvidedOrOpened: 2,
      activeNotesWithoutConfirmedDeliveryOrOpen: 1, scopesDemandedWhileMissing: 1, scopesStillMissingAfterDemand: 1,
      searchesWithNoMatchingScope: 1, searchesWithScopeButNoNote: 2 },
    byTaskAndNoteKind: [{ taskKind: 'DEVELOPMENT', noteKind: 'MEMORY', providedOrOpenedTasks: 1, foundTasks: 1, openedTasks: 1 }],
    entries: [{ entryId: 'lesson:field-ids', kind: 'MEMORY', scopeId: 'path:plugin/trino-iceberg/', title: 'Resolve nested fields within their parent', currentVersion: 3, currentEnabled: true,
      currentVersionCounts: { selectedTasks: 1, providedTasks: 1, searchHits: 1, foundTasks: 1, openedTasks: 1, citedReviews: 0 },
      allVersionCounts: { providedTasks: 2, foundTasks: 1, openedTasks: 1 }, lastConfirmedUseAt: '2026-09-02T00:00:00Z' }],
    scopeGaps: [{ scopeId: 'path:plugin/trino-iceberg/', demandingTasks: 1,
      observedPreparationCounts: { NOT_PREPARED: 1, EMPTY: 0, FAILED: 0, READY: 0 },
      currentPreparation: 'NOT_PREPARED', currentKnowledge: 'MISSING' }], hasMoreEntries: false, hasMoreScopeGaps: false };
}

type Mutation = { action: string; workspaceId: string; request: { expectedRevision: number } };
function discovery(workspaceId: string, revision: number): ContextDiscoveryDto {
  const evidence: ContextDiscoveryDto['scopes'][number]['evidence'] = { id: 'discovered-readme', reference: { kind: 'REPOSITORY_FILE', repositoryId: 42,
    repository: { owner: 'trinodb', repo: 'trino' }, path: 'plugin/trino-hive/README.md', commit: 'a'.repeat(40) }, capture: 'BYTEQUAY_READ',
    excerpt: 'Hive connector. Documentation: https://hive.apache.org/docs/', provenance: 'Repository README at the frozen baseline',
    retrievedAt: '2026-09-01T00:00:00Z', contentHash: 'b'.repeat(64), version: 'a'.repeat(40), truncated: false };
  return { workspaceId, notebookRevision: revision, baselineSha: 'a'.repeat(40), repositoryFingerprint: 'c'.repeat(64), query: 'Hive',
    scopes: [{ scope: { id: 'path:plugin/trino-hive/', title: 'Hive connector', match: { kind: 'SELECTED', pathPrefixes: ['plugin/trino-hive/'], terms: ['Hive'] },
      enabled: true, preparation: null, discoveryEvidenceRef: evidence.id }, evidence, reasons: ['Module directory with a README'] }],
    sources: [{ source: { id: 'docs-hive', kind: 'DOCUMENTATION_ROOT', url: 'https://hive.apache.org/docs/', origin: 'GENERATED', discoveryEvidenceRef: evidence.id },
      evidence, reasons: ['Explicit HTTPS documentation link in the README'] }],
    history: [{ kind: 'PR', number: 88, title: 'Fix Hive partition lookup', url: 'https://github.com/trinodb/trino/pull/88', state: 'MERGED', outcome: 'merged',
      updatedAt: '2026-09-01T00:00:00Z', reasons: ['Hive title matches selected focus'], provenance: 'Saved PR data; detail has not been refreshed' },
    { kind: 'ISSUE', number: 89, title: 'Hive partition edge case', url: 'https://github.com/trinodb/trino/issues/89', state: 'CLOSED', outcome: 'not_planned',
      updatedAt: '2026-09-01T00:00:00Z', reasons: ['Hive title matches selected focus'], provenance: 'Bounded GitHub issue search result' }],
    inspected: { directories: 12, files: 4, pullRequests: 20, issues: 5 }, gaps: ['Only a bounded selection was checked; older history may be absent.'] };
}
function backend() {
  sessionStorage.clear();
  const saved = new Map(workspaces.map(workspace => [workspace.id, notebook(workspace.id === 'ws-2')]));
  const builds = new Map<string, ContextBuildViewDto>(workspaces.map((workspace): [string, ContextBuildViewDto] => [workspace.id,
    { buildRequestSequence: 0, notebookRevision: 7, canStart: true, build: null }]));
  const starts: Array<{ workspaceId: string; request: StartContextBuildRequest }> = [];
  const controls: Array<{ action: string; taskId: string; generation?: string }> = [];
  const reads: string[] = [];
  const writes: Mutation[] = [];
  const discoveries: DiscoverRepositoryContextRequest[] = [];
  const applications: ApplyContextDiscoveryRequest[] = [];
  const usageReads: Array<{ workspaceId: string; window: '30d' | 'all'; page: number }> = [];
  const activityReads: Array<{ workspaceId: string; entryId: string; window: '30d' | 'all'; page: number }> = [];
  const delayed = new Map<string, (notebook: RepositoryNotebookDto) => void>();
  const state = { saved, reads, writes, delayed, builds, starts, controls, discoveries, applications, usageReads, activityReads,
    discoveryFailure: false, emptyDiscovery: false,
    loseApply: false, readFailure: false, holdWorkspace: '', unavailable: false,
    loseStartBefore: false, loseStartAfter: false, approvals: [] as AgentPendingApprovalDto[], events: [] as AgentEventEnvelope[] };
  const mutate = <T extends { expectedRevision: number }>(action: string, workspaceId: string, request: T,
    change: (current: RepositoryNotebookDto) => RepositoryNotebookDto): Promise<RepositoryNotebookDto> => {
    writes.push({ action, workspaceId, request: structuredClone(request) });
    const current = saved.get(workspaceId);
    assert(current !== undefined, 'Unknown fixture workspace');
    if (request.expectedRevision !== current.revision) return Promise.reject(new Error('Notebook revision changed; reload before saving.'));
    const changed = { ...change(current), revision: current.revision + 1 };
    saved.set(workspaceId, changed);
    return Promise.resolve(structuredClone(changed));
  };
  const bridge = {
    getAppVersion: async () => ({ version: '0.4.0' }),
    getAppPreferences: async () => ({ taskRetentionDays: 7, recentRetentionDays: 14, refreshIntervalMinutes: 6, theme: 'SYSTEM' as const }),
    getWorkspaceSettings: async () => ({ remotes: [], commitsTarget: null, agents: [], githubToken: null }),
    getRepositoryContext: async (workspaceId: string) => {
      reads.push(workspaceId);
      if (state.readFailure) throw new Error('Saved notebook could not be read');
      if (state.holdWorkspace === workspaceId) return new Promise<RepositoryNotebookDto>(resolve => delayed.set(workspaceId, resolve));
      const current = saved.get(workspaceId);
      assert(current !== undefined, 'Notebook workspace must exist');
      return structuredClone(current);
    },
    getRepositoryContextUsage: async (workspaceId, window, page) => {
      usageReads.push({ workspaceId, window, page });
      return structuredClone(usage(window));
    },
    getRepositoryContextEntryUsage: async (workspaceId, entryId, window, page) => {
      activityReads.push({ workspaceId, entryId, window, page });
      return { entryId, activities: [{ taskId: 'task-1', reviewRunId: null, turnId: 'turn-1', taskKind: 'DEVELOPMENT' as const,
        entryVersion: 3, stage: 'OPENED' as const, delivery: 'CONFIRMED' as const, occurredAt: '2026-09-02T00:00:00Z', receiptRef: 'context-read:1' },
      { taskId: 'task-2', reviewRunId: 'review-run-1', turnId: null, taskKind: 'PR_REVIEW' as const,
        entryVersion: 2, stage: 'CITED' as const, delivery: 'CONFIRMED' as const, occurredAt: '2026-09-01T00:00:00Z', receiptRef: 'review:1' }],
      hasMore: false };
    },
    getModelSettings: async () => {
      const catalog = await createPreviewBridge('ws-1').getModelSettings();
      return { ...catalog, providers: catalog.providers.map(provider => ({ ...provider,
        readiness: state.unavailable ? 'AUTHENTICATION_REQUIRED' as const : provider.readiness })) };
    },
    discoverRepositoryContext: async (workspaceId, request) => {
      discoveries.push(structuredClone(request));
      if (state.discoveryFailure) throw new Error('Sync this workspace before finding suggestions.');
      const current = saved.get(workspaceId); assert(current !== undefined, 'Notebook exists');
      const preview = discovery(workspaceId, current.revision);
      return { ...preview, query: request.query, ...(state.emptyDiscovery ? { scopes: [], sources: [], history: [] } : {}) };
    },
    applyRepositoryContextDiscovery: async (workspaceId, request) => {
      applications.push(structuredClone(request));
      const current = saved.get(workspaceId); assert(current !== undefined, 'Notebook exists');
      if (request.expectedRevision !== current.revision) throw new Error('Notebook changed; reload before applying.');
      const preview = discovery(workspaceId, current.revision);
      const chosenScopes = preview.scopes.filter(item => request.scopeIds.includes(item.scope.id));
      const chosenSources = preview.sources.filter(item => request.sourceIds.includes(item.source.id));
      const updated: RepositoryNotebookDto = { ...current, revision: current.revision + 1, scopes: [...current.scopes, ...chosenScopes.map(item => item.scope)],
        sources: [...current.sources, ...chosenSources.map(item => item.source)], sourceEvidence: [...current.sourceEvidence,
          ...new Map([...chosenScopes, ...chosenSources].map(item => [item.evidence.id, item.evidence])).values()] };
      saved.set(workspaceId, updated);
      const build = builds.get(workspaceId); assert(build !== undefined, 'Builder exists'); builds.set(workspaceId, { ...build, notebookRevision: updated.revision });
      if (state.loseApply) { state.loseApply = false; throw new Error('Apply response was lost'); }
      return structuredClone(updated);
    },
    getRepositoryContextBuild: async (workspaceId: string) => {
      const value = builds.get(workspaceId); assert(value !== undefined, 'Builder belongs to workspace'); return structuredClone(value);
    },
    startRepositoryContextBuild: async (workspaceId, request) => {
      starts.push({ workspaceId, request: structuredClone(request) });
      if (state.loseStartBefore) { state.loseStartBefore = false; throw new Error('Start response was lost'); }
      const current = builds.get(workspaceId); assert(current !== undefined, 'Builder workspace exists');
      if (current.build?.requestKey === request.requestKey) return structuredClone(current);
      assert(current.canStart && request.expectedBuildSequence === current.buildRequestSequence, 'Start must use displayed build sequence');
      assert(request.expectedRevision === saved.get(workspaceId)?.revision, 'Start must use displayed notebook revision');
      const value: ContextBuildViewDto = { buildRequestSequence: current.buildRequestSequence + 1, notebookRevision: request.expectedRevision, canStart: false,
        build: { requestKey: request.requestKey, taskId: 'build-task', baselineSha: 'a'.repeat(40), scopeIds: request.scopeIds, history: request.history,
          requestedAt: '2026-09-01T00:00:00Z', task: { id: 'build-task', workspaceId, state: 'OPEN', version: 1, laneId: 'codex', model: 'gpt-5.6-codex',
            mutationClaim: 'TURN', terminalReason: null, worktreePath: '/tmp/bytequay-fixture/learning-worktree', worktreeRemovedAt: null },
          activeTurn: { id: 'build-turn', state: 'RUNNING', startedAt: '2026-09-01T00:00:01Z', cancellationRequestedAt: null },
          pendingApprovals: state.approvals.length, results: [], gaps: [] } };
      builds.set(workspaceId, value);
      if (state.loseStartAfter) { state.loseStartAfter = false; throw new Error('Start response was lost'); }
      return structuredClone(value);
    },
    stopRepositoryContextBuild: async (workspaceId, taskId) => {
      controls.push({ action: 'stop', taskId });
      const current = builds.get(workspaceId); assert(current?.build?.taskId === taskId && current.build.activeTurn !== null, 'Stop must name current builder');
      const value = { ...current, build: { ...current.build, activeTurn: { ...current.build.activeTurn, cancellationRequestedAt: '2026-09-01T00:00:10Z' } } };
      builds.set(workspaceId, value); return structuredClone(value);
    },
    getRepositoryContextBuildStopImpact: async (_workspaceId, taskId) => {
      controls.push({ action: 'impact', taskId }); return { taskId, generation: 'generation-1', activeTurnId: null, liveProcess: true };
    },
    stopRepositoryContextBuildRuntime: async (workspaceId, taskId, generation) => {
      controls.push({ action: 'stop-runtime', taskId, generation });
      const current = builds.get(workspaceId); assert(current !== undefined, 'Builder exists'); return structuredClone(current);
    },
    readAgentHistory: async () => ({ events: structuredClone(state.events), beforeCursor: null, afterCursor: null, latestSequence: state.events.at(-1)?.sequence ?? 0 }),
    readAgentPendingApprovals: async () => structuredClone(state.approvals),
    getAgentTurn: async (turnId: string) => {
      const completed = builds.get('ws-1')?.build?.task?.state === 'COMPLETED';
      return { ...await createPreviewBridge('ws-1').getAgentTurn(turnId), id: turnId, taskId: 'build-task', state: completed ? 'COMPLETED' : 'RUNNING',
        startedAt: '2026-09-01T00:00:01Z', endedAt: completed ? '2026-09-01T00:00:20Z' : null };
    },
    resolveAgentApproval: async (approvalId, decision) => {
      state.approvals = state.approvals.filter(approval => approval.approvalId !== approvalId);
      const current = builds.get('ws-1'); assert(current?.build !== null && current !== undefined, 'Builder exists');
      builds.set('ws-1', { ...current, build: { ...current.build, pendingApprovals: state.approvals.length } });
      return createPreviewBridge('ws-1').resolveAgentApproval(approvalId, decision);
    },
    configureRepositoryContext: (workspaceId, request) => mutate('configure', workspaceId, request, current => ({
      ...current, useInReviews: request.useInReviews ?? current.useInReviews,
      useInDevelopment: request.useInDevelopment ?? current.useInDevelopment,
      scopes: request.scopes?.map((scope): ContextScopeDto => ({ ...scope, preparation: current.scopes.find(value => value.id === scope.id)?.preparation ?? null, discoveryEvidenceRef: null })) ?? current.scopes,
      sources: request.sources?.map((source): ContextSourceDto => ({ ...source, origin: 'USER', discoveryEvidenceRef: null })) ?? current.sources,
    })),
    editRepositoryContextEntry: (workspaceId, request) => mutate('edit', workspaceId, request, current => {
      const previous = current.entries.find(entry => entry.id === request.id);
      const entry: ContextEntryDto = { id: request.id, kind: previous?.kind ?? 'KNOWLEDGE', scopeId: request.scopeId,
        title: request.title, summary: request.summary, body: request.body, claims: request.claims, sourceIds: request.sourceIds,
        origin: 'USER', enabled: previous?.enabled ?? true, version: current.revision + 1 };
      return { ...current, entries: previous === undefined ? [...current.entries, entry] : current.entries.map(value => value.id === entry.id ? entry : value) };
    }),
    editRepositoryContextDraft: (workspaceId, request) => mutate('draft-edit', workspaceId, request, current => ({
      ...current, pendingDrafts: current.pendingDrafts.map(draft => draft.id === request.draftId ? { ...draft,
        entry: { ...draft.entry, title: request.title, summary: request.summary, body: request.body, claims: request.claims, origin: 'USER' } } : draft),
    })),
    approveRepositoryContextDraft: (workspaceId, request) => mutate('draft-approve', workspaceId, request, current => {
      const draft = current.pendingDrafts.find(value => value.id === request.draftId);
      assert(draft !== undefined, 'Draft exists');
      const previous = current.entries.find(entry => entry.id === draft.entry.id);
      assert((previous?.version ?? null) === draft.replacesVersion, 'Approval must keep the existing note version');
      return { ...current, entries: previous === undefined ? [...current.entries, draft.entry] : current.entries.map(entry => entry.id === draft.entry.id ? draft.entry : entry),
        pendingDrafts: current.pendingDrafts.filter(value => value.id !== request.draftId) };
    }),
    dismissRepositoryContextDraft: (workspaceId, request) => mutate('draft-dismiss', workspaceId, request, current => ({
      ...current, pendingDrafts: current.pendingDrafts.filter(draft => draft.id !== request.draftId),
    })),
    setRepositoryContextEntryEnabled: (workspaceId, request) => mutate('enabled', workspaceId, request, current => ({
      ...current, entries: current.entries.map(entry => entry.id === request.entryId ? { ...entry, enabled: request.enabled } : entry),
    })),
    removeRepositoryContextEntry: (workspaceId, request) => mutate('remove', workspaceId, request, current => ({
      ...current, entries: current.entries.filter(entry => entry.id !== request.entryId),
      suppressedKnowledgeIds: current.entries.find(entry => entry.id === request.entryId)?.kind === 'KNOWLEDGE'
        ? [...current.suppressedKnowledgeIds, request.entryId] : current.suppressedKnowledgeIds,
    })),
    resetRepositoryContext: (workspaceId, request) => mutate('reset', workspaceId, request, current => ({
      ...current, suppressedKnowledgeIds: current.suppressedKnowledgeIds.filter(id => !request.scopeIds.some(scope =>
        id === 'knowledge:' + scope || id.startsWith('knowledge:' + scope + ':'))),
    })),
  } satisfies Pick<Bridge, 'getAppVersion' | 'getAppPreferences' | 'getWorkspaceSettings' | 'getRepositoryContext' | 'getRepositoryContextUsage'
    | 'getRepositoryContextEntryUsage' | 'configureRepositoryContext'
    | 'editRepositoryContextEntry' | 'editRepositoryContextDraft' | 'approveRepositoryContextDraft' | 'dismissRepositoryContextDraft'
    | 'setRepositoryContextEntryEnabled' | 'removeRepositoryContextEntry' | 'resetRepositoryContext' | 'getModelSettings'
    | 'getRepositoryContextBuild' | 'startRepositoryContextBuild' | 'stopRepositoryContextBuild' | 'getRepositoryContextBuildStopImpact'
    | 'stopRepositoryContextBuildRuntime' | 'readAgentHistory' | 'readAgentPendingApprovals' | 'getAgentTurn' | 'resolveAgentApproval'
    | 'discoverRepositoryContext' | 'applyRepositoryContextDiscovery'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  return state;
}

function button(scope: Element, label: string): HTMLButtonElement {
  const found = [...scope.querySelectorAll<HTMLButtonElement>('button')]
    .find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}
function field(scope: Element, label: string): HTMLInputElement | HTMLTextAreaElement {
  const control = [...scope.querySelectorAll('label')].find(item => item.firstChild?.textContent === label)?.querySelector('input, textarea');
  assert(control instanceof HTMLInputElement || control instanceof HTMLTextAreaElement, `Missing field: ${label}`);
  return control;
}
async function fill(scope: Element, label: string, value: string) {
  const input = field(scope, label);
  const prototype = input instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
}
async function choose(scope: Element, label: string) {
  const control = [...scope.querySelectorAll('label')].find(item => item.textContent?.trim() === label)?.querySelector('input[type=checkbox]');
  assert(control instanceof HTMLInputElement && !control.disabled, `Missing available choice: ${label}`);
  control.click(); await settle();
}
function fixture(state: ReturnType<typeof backend>) {
  const host = document.createElement('div');
  host.className = 'app-shell';
  host.style.cssText = 'display:flex;height:100vh;width:100vw;overflow:hidden';
  document.body.append(host);
  const root = createRoot(host);
  const render = (workspaceId: string) => {
    const workspace = workspaces.find(item => item.id === workspaceId);
    assert(workspace !== undefined, 'Workspace must exist');
    flushSync(() => root.render(<>
      <AppSidebar page="settings" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}}
        canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}} workspace={null} workspaceTab="home" onWorkspaceTab={() => {}}
        settings={{ section: 'ws-context', workspaces, workspaceId, onSection: () => {}, onWorkspace: render, onExit: () => {} }} />
      <main className="app-content"><SettingsWindow section="ws-context" workspace={workspace} onWorkspacesChanged={() => {}} /></main>
    </>));
  };
  render('ws-1');
  return {
    state, host, text: () => host.textContent ?? '',
    async workspace(id: string) { render(id); await settle(); },
    dialog() { const found = host.querySelector('dialog'); assert(found !== null && found.open, 'Accessible editor must be open'); return found; },
    note(title: string) {
      const found = [...host.querySelectorAll('.rc-note')].find(item => item.querySelector('strong')?.textContent === title);
      assert(found !== undefined, `Missing note: ${title}`); return found;
    },
    row(title: string) {
      const found = [...host.querySelectorAll('.setw-row')].find(item => item.querySelector('.setw-row-title')?.textContent?.startsWith(title));
      assert(found !== undefined, `Missing row: ${title}`); return found;
    },
    async advanced() {
      const details = host.querySelector<HTMLDetailsElement>('.rc-advanced');
      assert(details !== null, 'Advanced configuration must be available');
      if (!details.open) { details.querySelector('summary')?.click(); await settle(); }
      assert(details.open, 'Advanced configuration must open');
      return details;
    },
    async click(label: string, scope: Element = host) {
      const control = button(scope, label); assert(!control.disabled, `Button ${label} must be enabled`); control.click(); await settle();
    },
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

type View = ReturnType<typeof fixture>;
async function draft(view: View, title: string, body = 'Use the scoped contract and check the source version.') {
  const dialog = view.dialog();
  await fill(dialog, 'Title', title);
  await fill(dialog, 'Summary', 'A conditional note for this component.');
  await fill(dialog, 'Details', body);
  return dialog;
}

export async function run(): Promise<Array<{ name: string; error?: string }>> {
  const results: Array<{ name: string; error?: string }> = [];
  const check = async (name: string, test: (view: View) => Promise<void>, prepare?: (state: ReturnType<typeof backend>) => void) => {
    const state = backend(); prepare?.(state);
    const view = fixture(state);
    try { await settle(); await settle(); await test(view); results.push({ name }); }
    catch (failure) { results.push({ name, error: failure instanceof Error ? failure.message : String(failure) }); }
    finally { view.dispose(); }
  };

  await check('Opening is read-only and the default-on toggle retains saved notes', async view => {
    assert(view.state.reads.length === 1 && Number(view.state.writes.length) === 0, 'Opening must only read the notebook');
    assert(view.state.usageReads.length === 0, 'Usage details load on demand');
    assert(button(view.host, 'Use repository context in reviews').getAttribute('aria-checked') === 'true', 'Initial preference must be on');
    assert(button(view.host, 'Use repository context in development').getAttribute('aria-checked') === 'true', 'Development preference must be on');
    assert(view.host.querySelector<HTMLDetailsElement>('.rc-advanced')?.open === false, 'Advanced configuration starts collapsed');
    assert(view.state.starts.length === 0, 'Opening settings must not start learning');
    await view.click('Use repository context in reviews');
    assert(view.state.writes.length === 1 && view.state.writes[0].request.expectedRevision === 7, 'Toggle must use displayed revision');
    assert(!view.state.saved.get('ws-1')?.useInReviews && view.state.saved.get('ws-1')?.entries.length === 2, 'Off retains both saved notes');
    assert(view.state.saved.get('ws-1')?.useInDevelopment === true, 'Review setting must not change the development setting');
    assert(view.text().includes('Project architecture'), 'Notes stay visible after disabling');
    assert(button(view.host, 'Use repository context in reviews').getAttribute('aria-checked') === 'false', 'Saved off state must render');
    await view.click('Use repository context in development');
    assert(Number(view.state.writes.length) === 2 && view.state.writes[1].request.expectedRevision === 8, 'Development toggle must use the updated revision');
    assert(view.state.saved.get('ws-1')?.useInDevelopment === false && view.state.saved.get('ws-1')?.useInReviews === false,
      'Development setting must change independently and retain notes');
  });

  await check('Usage shows recorded stages, missing requests and note activity without a quality claim', async view => {
    await view.click('View usage');
    assert(view.state.usageReads.length === 1 && view.state.usageReads[0].workspaceId === 'ws-1', 'Usage reads the selected workspace');
    assert(view.text().includes('Searches with no matching scope') && view.text().includes('Searches with a scope but no matching note'),
      'Distinct no-hit search reasons remain visible');
    assert(view.text().includes('Requested knowledge that was missing') && view.text().includes('Iceberg connector'),
      'Scope demand appears beside current state');
    assert(view.text().includes('Current version: 1 selected · 1 provided · 1 found · 1 opened'), 'Stage counts remain distinct');
    assert(view.text().includes('They do not show whether a note was understood or improved a result.'), 'Usage does not claim quality');
    assert(view.state.activityReads.length === 0, 'Entry activity loads only when opened');
    await view.click('View activity');
    assert(Number(view.state.activityReads.length) === 1 && view.state.activityReads[0].entryId === 'lesson:field-ids', 'Activity remains tied to the selected note');
    assert(view.text().includes('Opened') && view.host.querySelector('.rc-usage__activity a')?.getAttribute('href')?.includes('task-1'),
      'Activity links to its actual task');
    assert(view.text().includes('Cited') && view.text().includes('Review run: review-run-1'), 'Review activity retains its run identity');
    await view.click('All retained');
    assert(Number(view.state.usageReads.length) === 2 && view.state.usageReads[1].window === 'all', 'Switching period loads that window');
    await view.workspace('ws-2');
    if ([...view.host.querySelectorAll('button')].some(item => item.textContent?.trim() === 'View usage')) await view.click('View usage');
    assert(Number(view.state.usageReads.length) === 3 && view.state.usageReads[2].workspaceId === 'ws-2', 'Open Usage follows workspace changes');
  });

  await check('An empty Project overview scope is not presented as generated knowledge', async view => {
    assert(view.text().includes('No saved knowledge notes.'), 'Knowledge remains explicitly empty');
    await view.advanced();
    assert(view.row('Project overview').textContent?.includes('No completed preparation recorded.'), 'Missing note and missing preparation remain separate facts');
    assert(view.state.writes.length === 0, 'Explaining an empty scope must not start preparation');
  }, state => {
    const current = state.saved.get('ws-1');
    assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, entries: [] });
  });

  await check('Learning includes essential sources, starts once with explicit history, and can be stopped', async view => {
    await view.click('Use repository context in reviews');
    await view.click('Learn / Refresh');
    const dialog = view.dialog();
    assert(dialog.textContent?.includes('Repository files, directory structure, repository documentation and registered documentation are always included.') === true,
      'Essential inputs must be visible and always included');
    const choices = [...dialog.querySelectorAll<HTMLInputElement>('fieldset input[type=checkbox]')];
    assert(choices.length === 2 && choices[0].checked && !choices[1].checked, 'Only scope choices exist, with Project overview selected');
    assert(view.state.starts.length === 0, 'Opening the dialog does not launch an agent');
    await fill(dialog, 'Merged PR numbers (optional)', '#123, 456');
    await fill(dialog, 'Closed issue numbers (optional)', '789');
    const start = button(dialog, 'Start learning'); start.click(); start.click(); await settle(); await settle();
    assert(Number(view.state.starts.length) === 1, 'Repeated click starts only one request');
    const request = view.state.starts[0].request;
    assert(request.expectedRevision === 8 && request.expectedBuildSequence === 0 && request.scopeIds.join() === 'project', 'Start uses displayed scope and revision');
    assert(JSON.stringify(request.history) === JSON.stringify([{ kind: 'PR', number: 123 }, { kind: 'PR', number: 456 }, { kind: 'ISSUE', number: 789 }]), 'Explicit history is typed and bounded');
    assert(!('sourceKinds' in request) && !('provider' in request) && !('prompt' in request), 'Essential inputs and Review profile cannot be replaced by the renderer');
    assert(view.text().includes('Repository learning · Learning'), 'Running learning is visible even when review context is off');
    await view.click('Stop learning');
    assert(view.state.controls[0].action === 'stop' && view.state.controls[0].taskId === 'build-task', 'Stop targets the current builder');
    assert(view.text().includes('Repository learning · Stopping') && button(view.host, 'Stop learning').disabled, 'Cancellation is distinct from completion');
    const value = view.state.builds.get('ws-1'); assert(value?.build?.task !== null && value?.build !== null && value !== undefined, 'Builder exists');
    view.state.builds.set('ws-1', { ...value, canStart: false, build: { ...value.build, activeTurn: null,
      task: { ...value.build.task, state: 'COMPLETED', mutationClaim: null } } });
    await view.click('Inspect latest task');
    assert(button(view.host, 'Learn / Refresh').disabled && !button(view.host, 'Clean up worktree').disabled
      && view.text().includes('Clean up the retained worktree before starting another learning task.'), 'Retained worktree blocks a new task with a visible remedy');
    view.state.builds.set('ws-1', { ...value, canStart: true, build: { ...value.build, activeTurn: null,
      task: { ...value.build.task, state: 'COMPLETED', mutationClaim: null, worktreeRemovedAt: '2026-09-01T00:00:20Z' } } });
    await view.click('Inspect latest task');
    assert(view.text().includes('Repository learning · Completed') && view.text().includes('Worktree cleaned up'), 'Completed task and cleanup facts remain visible');
    assert(Number(view.state.starts.length) === 1, 'Inspecting terminal state never starts another task');
  });

  await check('Suggestion discovery and use are explicit and preserve manual learning inputs', async view => {
    await view.click('Learn / Refresh');
    assert(view.state.discoveries.length === 0 && view.state.applications.length === 0 && view.state.starts.length === 0, 'Opening cannot discover, apply or learn');
    const dialog = view.dialog();
    await fill(dialog, 'Merged PR numbers (optional)', '17, 88');
    await fill(dialog, 'Suggestion focus (optional)', 'Hive');
    await view.click('Find suggestions', dialog);
    assert(view.state.discoveries[0].includeRemote === false && view.state.discoveries[0].scopeIds.join() === 'project', 'Default discovery is local with current configured scope');
    const preview = dialog.querySelector('.rc-discovery-preview'); assert(preview !== null, 'Preview is visible');
    assert([...preview.querySelectorAll<HTMLInputElement>('input')].every(input => !input.checked), 'Suggestions must begin unselected');
    assert(preview.textContent?.includes('12 directories, 4 files, 20 PRs and 5 issues') && preview.textContent?.includes('Only a bounded selection'), 'Counts and gaps stay visible');
    await choose(preview, 'Hive connector'); await choose(preview, 'https://hive.apache.org/docs/'); await choose(preview, 'PR #88: Fix Hive partition lookup');
    assert(button(dialog, 'Start learning').disabled, 'Unapplied suggestion choices cannot silently be omitted by Start');
    await view.click('Use selected suggestions', dialog);
    assert(Number(view.state.applications.length) === 1 && view.state.applications[0].sourceIds.join() === 'docs-hive', 'Only chosen configuration is sent for server validation');
    assert(view.state.applications[0].repositoryFingerprint === 'c'.repeat(64), 'Apply echoes the exact server-observed repository fingerprint');
    assert(field(dialog, 'Merged PR numbers (optional)').value === '17, 88', 'Manual history is preserved and suggested history deduplicated');
    assert(view.state.starts.length === 0 && view.text().includes('Why this was suggested'), 'Applying saves provenance but never starts learning');
    const choices = [...dialog.querySelectorAll('fieldset label')];
    assert(choices.some(label => label.textContent?.includes('Hive connector') && label.querySelector<HTMLInputElement>('input')?.checked), 'Applied scope joins existing selected scope');
    await view.click('Start learning', dialog);
    assert(view.state.starts[0].request.expectedRevision === 8 && view.state.starts[0].request.scopeIds.join() === 'project,path:plugin/trino-hive/', 'Learning uses saved revision and selected scopes');
  });

  await check('History-only suggestions require no notebook write and GitHub search is explicit', async view => {
    await view.click('Learn / Refresh'); const dialog = view.dialog();
    await choose(dialog, 'Include GitHub search'); await view.click('Find suggestions', dialog);
    assert(view.state.discoveries[0].includeRemote, 'GitHub search is explicit');
    assert(dialog.textContent?.includes('Not planned') && dialog.textContent?.includes('does not prove'), 'Closed issue outcome remains qualified');
    await choose(dialog, 'ISSUE #89: Hive partition edge case'); await view.click('Use selected suggestions', dialog);
    assert(view.state.applications.length === 0 && view.state.writes.length === 0 && view.state.starts.length === 0, 'History selection does not configure or start');
    assert(field(dialog, 'Closed issue numbers (optional)').value === '89', 'Chosen history enters the existing input');
  });

  await check('Suggestions enforce the combined history bound and show empty coverage honestly', async view => {
    await view.click('Learn / Refresh'); const dialog = view.dialog();
    await fill(dialog, 'Merged PR numbers (optional)', '1 2 3 4 5 6 7 8 9 10'); await view.click('Find suggestions', dialog);
    await choose(dialog, 'PR #88: Fix Hive partition lookup');
    assert(button(dialog, 'Use selected suggestions').disabled && dialog.textContent?.includes('including manual numbers'), 'Manual and suggested references share the limit');
    view.state.emptyDiscovery = true; await view.click('Find suggestions', dialog);
    assert(dialog.textContent?.includes('No suggestions found in the checked sources.') && dialog.textContent?.includes('Only a bounded selection'), 'Empty search is qualified by coverage');
    assert(view.state.starts.length === 0, 'Finding suggestions never starts learning');
  });

  await check('Lost apply responses require an explicit reload instead of retrying the mutation', async view => {
    await view.click('Learn / Refresh'); const dialog = view.dialog(); await view.click('Find suggestions', dialog);
    await choose(dialog, 'Hive connector'); view.state.loseApply = true; await view.click('Use selected suggestions', dialog);
    assert(button(dialog, 'Start learning').disabled && button(dialog, 'Use selected suggestions').disabled, 'Unconfirmed apply blocks a blind retry and learning');
    await view.click('Reload saved notebook', dialog);
    assert(view.state.applications.length === 1 && view.state.starts.length === 0, 'Reload inspects only');
    assert(dialog.textContent?.includes('Hive connector') && dialog.querySelector('.rc-discovery-preview') === null, 'Saved configuration is available and stale suggestions are discarded');
  });

  await check('A stale suggestion apply preserves the notebook and requires a fresh preview', async view => {
    await view.click('Learn / Refresh'); const dialog = view.dialog(); await view.click('Find suggestions', dialog); await choose(dialog, 'Hive connector');
    const saved = view.state.saved.get('ws-1'); assert(saved !== undefined, 'Notebook exists'); view.state.saved.set('ws-1', { ...saved, revision: 8 });
    await view.click('Use selected suggestions', dialog);
    assert(dialog.textContent?.includes('Notebook changed') && view.state.saved.get('ws-1')?.scopes.length === 2, 'Stale apply cannot overwrite existing config');
    await view.click('Reload saved notebook', dialog);
    assert(view.state.applications.length === 1 && view.state.starts.length === 0, 'Conflict does not automatically retry or start');
  });

  await check('Discovery failures do not fabricate results or launch learning', async view => {
    await view.click('Learn / Refresh'); await fill(view.dialog(), 'Suggestion focus (optional)', 'repo:unrelated');
    assert(button(view.dialog(), 'Find suggestions').disabled && view.state.discoveries.length === 0, 'Query cannot inject GitHub search operators');
    await fill(view.dialog(), 'Suggestion focus (optional)', 'Hive'); await view.click('Find suggestions', view.dialog());
    assert(view.dialog().textContent?.includes('Sync this workspace') && view.dialog().querySelector('.rc-discovery-preview') === null, 'Provisioning errors remain explicit');
    assert(view.state.applications.length === 0 && view.state.starts.length === 0, 'Failure has no implicit apply or start');
  }, state => { state.discoveryFailure = true; });

  await check('Readiness and history limits block submission without starting a task', async view => {
    await view.click('Learn / Refresh');
    assert(button(view.dialog(), 'Start learning').disabled && view.dialog().textContent?.includes('Sign in'), 'Unavailable Review provider blocks learning');
    view.state.unavailable = false;
    await view.click('Check provider', view.dialog());
    await fill(view.dialog(), 'Merged PR numbers (optional)', '1 2 3 4 5 6 7 8 9 10 11');
    assert(button(view.dialog(), 'Start learning').disabled, 'Too much history blocks submission');
    await fill(view.dialog(), 'Merged PR numbers (optional)', '1 1');
    assert(button(view.dialog(), 'Start learning').disabled, 'Duplicate history blocks submission');
    await fill(view.dialog(), 'Merged PR numbers (optional)', '1');
    assert(!button(view.dialog(), 'Start learning').disabled && view.state.starts.length === 0, 'Rechecking readiness does not start learning');
  }, state => { state.unavailable = true; });

  await check('Unconfirmed starts retain the same request after closing and reopening settings', async view => {
    await view.click('Learn / Refresh');
    await view.click('Start learning', view.dialog());
    await view.click('Cancel', view.dialog());
    assert(view.text().includes('start response is unconfirmed'), 'Lost response is not success or empty context');
    await view.workspace('ws-2'); await view.workspace('ws-1');
    assert(view.text().includes('Retry same request'), 'Pending request survives settings remount');
    await view.click('Inspect latest task');
    assert(view.state.starts.length === 1, 'Inspection never resends');
    await view.click('Retry same request');
    assert(Number(view.state.starts.length) === 2 && JSON.stringify(view.state.starts[0]) === JSON.stringify(view.state.starts[1]), 'Explicit retry preserves the exact body and key');
    assert(view.text().includes('Repository learning · Learning'), 'Confirmed retry exposes the existing task');
  }, state => { state.loseStartBefore = true; });

  await check('A lost accepted response is reconciled without another start', async view => {
    await view.click('Learn / Refresh'); await view.click('Start learning', view.dialog()); await view.click('Cancel', view.dialog());
    await view.click('Inspect latest task');
    assert(view.state.starts.length === 1 && view.text().includes('Repository learning · Learning'), 'Existing accepted task is discovered');
    assert(!view.text().includes('Retry same request'), 'Matching receipt clears uncertainty');
  }, state => { state.loseStartAfter = true; });

  await check('A rejected request can be discarded only after inspecting current state', async view => {
    await view.click('Learn / Refresh'); await view.click('Start learning', view.dialog()); await view.click('Cancel', view.dialog());
    assert(!view.text().includes('Discard unconfirmed request'), 'No discard without inspection after the failure');
    await view.click('Inspect latest task'); await view.click('Discard unconfirmed request');
    assert(!button(view.host, 'Learn / Refresh').disabled && view.state.starts.length === 1, 'Discard unlocks a new deliberate request without sending it');
  }, state => { state.loseStartBefore = true; });

  await check('Protected notes remain intact while their scope can learn another topic', async view => {
    await view.click('Learn / Refresh');
    const choices = [...view.dialog().querySelectorAll<HTMLInputElement>('fieldset input[type=checkbox]')];
    assert(choices.length === 2 && choices.every(choice => !choice.disabled), 'A protected note does not block its whole scope');
    await view.click('Start learning', view.dialog());
    assert(view.state.starts.length === 1 && view.state.starts[0].request.scopeIds.join() === 'project', 'Learning may add another topic in a selected scope');
    const current = view.state.saved.get('ws-1');
    assert(current?.entries.find(entry => entry.id === 'knowledge:project')?.origin === 'USER'
      && current.suppressedKnowledgeIds.includes('knowledge:path:plugin/trino-iceberg/'), 'Existing protected note identities remain unchanged');
  }, state => {
    const current = state.saved.get('ws-1'); assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, entries: current.entries.map(entry => entry.kind === 'KNOWLEDGE' ? { ...entry, origin: 'USER' } : entry),
      suppressedKnowledgeIds: ['knowledge:path:plugin/trino-iceberg/'] });
  });

  await check('Learning messages and current approvals use the existing task transcript', async view => {
    await view.click('Learn / Refresh'); await view.click('Start learning', view.dialog()); await settle();
    assert(view.text().includes('Approval needed') && view.text().includes('Checking connector contracts.'), 'Task messages and pending approval are visible');
    await view.click('Allow once: Read selected file');
    assert(view.state.approvals.length === 0 && view.state.starts.length === 1, 'Approval resolves on the existing task without resending learning');
  }, state => {
    state.events = [{ schemaVersion: 2, eventId: 'commentary', sequence: 1, taskId: 'build-task', turnId: 'build-turn', source: eventSource,
      occurredAt: '2026-09-01T00:00:02Z', type: 'assistant_message', payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'Checking connector contracts.' } }];
    state.approvals = [{ approvalId: 'read-file', taskId: 'build-task', turnId: 'build-turn', source: eventSource,
      occurredAt: '2026-09-01T00:00:03Z', title: 'Read selected file', detail: 'Inspect README.md', approvalKind: 'COMMAND',
      scope: ['README.md'], availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true }];
  });

  await check('Process recovery requires the inspected generation before stopping the runtime', async view => {
    await view.click('Learn / Refresh'); await view.click('Start learning', view.dialog());
    const current = view.state.builds.get('ws-1'); assert(current?.build?.task !== null && current?.build !== null && current !== undefined, 'Builder exists');
    view.state.builds.set('ws-1', { ...current, build: { ...current.build, activeTurn: null, task: { ...current.build.task, mutationClaim: 'RECOVERY' } } });
    await view.click('Inspect latest task'); await view.click('Stop remaining processes');
    assert([...view.host.querySelectorAll('.rc-build-result details:last-child code')].some(element => element.textContent === '/tmp/bytequay-fixture/learning-worktree'),
      'Task details expose the worktree path as read-only text for recovery');
    assert(view.state.controls.length === 1 && view.state.controls[0].action === 'impact', 'Inspection alone must not stop a runtime');
    await view.click('Stop processes', view.dialog());
    assert(view.state.controls[1].action === 'stop-runtime' && view.state.controls[1].generation === 'generation-1', 'Confirmed stop sends the inspected generation');
  });

  await check('Empty preparation results refresh the notebook even without a content revision change', async view => {
    await view.click('Learn / Refresh'); await view.click('Start learning', view.dialog());
    const current = view.state.builds.get('ws-1'); const saved = view.state.saved.get('ws-1');
    assert(current?.build?.task !== null && current?.build !== null && current !== undefined && saved !== undefined, 'Builder and notebook exist');
    const result: NonNullable<ContextScopeDto['preparation']> = { attemptId: 'learning-empty', inputFingerprint: { scopeId: 'project', inputs: {}, digest: 'sha256:empty', gaps: [] },
      outcome: 'EMPTY', noteIds: [], checkedReads: [], explanation: 'The inspected source yielded no useful note.' };
    view.state.saved.set('ws-1', { ...saved, scopes: saved.scopes.map(scope => scope.id === 'project' ? { ...scope, preparation: result } : scope) });
    view.state.builds.set('ws-1', { ...current, canStart: true, build: { ...current.build, activeTurn: null, results: [result],
      task: { ...current.build.task, state: 'COMPLETED', mutationClaim: null, worktreeRemovedAt: '2026-09-01T00:00:20Z' } } });
    await view.click('Inspect latest task');
    assert(view.text().includes('Last preparation: No useful note found'), 'Preparation metadata is refreshed even at unchanged content revision');
    assert(view.text().includes('Project architecture'), 'An empty result does not replace existing knowledge');
  });

  await check('Failed provisioning shows a failed start without a phantom running task', async view => {
    assert(view.text().includes('Repository learning · Could not start'), 'Missing task with canStart is a failed provision');
    assert(![...view.host.querySelectorAll('button')].some(control => control.textContent === 'Stop learning'), 'An absent task has no Stop action');
    assert(!button(view.host, 'Learn / Refresh').disabled && view.state.starts.length === 0, 'A new explicit start remains available');
  }, state => state.builds.set('ws-1', { buildRequestSequence: 1, notebookRevision: 7, canStart: true, build: {
    requestKey: 'failed-request', taskId: 'missing-task', baselineSha: 'a'.repeat(40), scopeIds: ['project'], history: [], requestedAt: '2026-09-01T00:00:00Z',
    task: null, activeTurn: null, pendingApprovals: 0, results: [], gaps: ['Task provisioning failed.'] } }));

  await check('Source edits keep source identity and revision', async view => {
    await view.advanced();
    await view.click('Edit', view.row('Documentation website'));
    await fill(view.dialog(), 'HTTPS address', 'https://trino.io/docs/current/connector/');
    await view.click('Save source', view.dialog());
    const saved = view.state.saved.get('ws-1');
    assert(saved?.sources[0].id === 'docs-trino' && saved.sources[0].url === 'https://trino.io/docs/current/connector/', 'Source identity and edited URL must persist');
    assert(view.state.writes[0].request.expectedRevision === 7 && view.host.querySelector('dialog') === null, 'Source edit uses revision and closes after success');
  });

  await check('Scope conflict reload preserves the draft and a concurrent disable', async view => {
    await view.advanced();
    await view.click('Edit', view.row('Iceberg connector'));
    await fill(view.dialog(), 'Name', 'Iceberg table contracts');
    const current = view.state.saved.get('ws-1');
    assert(current !== undefined, 'Notebook exists');
    view.state.saved.set('ws-1', { ...current, revision: 8, scopes: current.scopes.map(scope => scope.id === 'project' ? scope : { ...scope, enabled: false }) });
    await view.click('Save scope', view.dialog());
    assert(view.dialog().textContent?.includes('Notebook revision changed'), 'Concurrent change must be surfaced');
    assert(button(view.dialog(), 'Save scope').disabled, 'No retry until reload');
    await view.click('Reload saved notebook', view.dialog());
    assert(field(view.dialog(), 'Name').value === 'Iceberg table contracts' && view.state.writes.length === 1, 'Reload retains draft without saving');
    await view.click('Save scope', view.dialog());
    const scope = view.state.saved.get('ws-1')?.scopes.find(scope => scope.id === 'path:plugin/trino-iceberg/');
    assert(scope?.title === 'Iceberg table contracts' && scope.enabled === false, 'Editing title must not undo concurrent disable');
    assert(view.state.writes[1].request.expectedRevision === 8, 'Retry uses newly displayed revision');
  });

  await check('Adding another note in an existing scope sends one explicit save', async view => {
    await view.click('Add note');
    const dialog = await draft(view, 'Iceberg metadata rules');
    const save = button(dialog, 'Save'); save.click(); save.click(); await settle();
    assert(view.state.writes.length === 1 && view.state.writes[0].request.expectedRevision === 7, 'Double click must make one save');
    const added = view.state.saved.get('ws-1')?.entries.find(entry => entry.title === 'Iceberg metadata rules');
    assert(added?.id.startsWith('knowledge:project:') && added.origin === 'USER' && added.scopeId === 'project',
      'New note must have its own ID in the chosen scope while preserving the overview');
  });

  await check('Editing a proposed revision keeps the active note until explicit approval', async view => {
    const proposed = view.note('Proposed project revision');
    assert(view.note('Project architecture').textContent?.includes('A short overview'), 'Old note stays active while draft is pending');
    assert(proposed.textContent?.includes('Awaiting approval'), 'Draft has visible pending status');
    proposed.querySelector('details')?.setAttribute('open', '');
    assert(proposed.textContent?.includes('Selected Trino documentation page'), 'Draft shows retained source provenance');
    await view.click('Edit draft', proposed);
    await fill(view.dialog(), 'Summary', 'Revised conditional summary.');
    await view.click('Save draft', view.dialog());
    assert(view.state.writes.map(write => write.action).join() === 'draft-edit', 'Saving edits cannot activate draft');
    assert(view.state.saved.get('ws-1')?.pendingDrafts[0].entry.origin === 'USER', 'Editing marks proposed prose as user-managed');
    assert(view.note('Project architecture').textContent?.includes('A short overview'), 'Active entry remains unchanged after draft edit');
    await view.click('Approve and use', view.note('Proposed project revision'));
    const saved = view.state.saved.get('ws-1');
    assert(saved?.pendingDrafts.length === 0 && saved.entries.find(entry => entry.id === 'knowledge:project')?.summary === 'Revised conditional summary.',
      'Approval activates exactly the reviewed draft');
    assert(view.state.writes.map(write => write.action).join() === 'draft-edit,draft-approve', 'Activation needs a separate user action');
  }, state => {
    const current = state.saved.get('ws-1'); assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, pendingDrafts: [{ id: 'draft-project', entry: { ...current.entries[0], title: 'Proposed project revision',
      summary: 'Proposed project summary.', version: 8 }, replacesVersion: 2, baselineSha: 'a'.repeat(40), explanation: 'Compared source files.' }] });
  });

  await check('Dismissing a draft leaves the active note untouched', async view => {
    await view.click('Dismiss draft', view.note('Proposed project revision'));
    const saved = view.state.saved.get('ws-1');
    assert(saved?.pendingDrafts.length === 0 && saved.entries[0].title === 'Project architecture', 'Dismiss changes only pending draft');
    assert(view.state.writes.map(write => write.action).join() === 'draft-dismiss', 'Dismiss is one explicit action');
  }, state => {
    const current = state.saved.get('ws-1'); assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, pendingDrafts: [{ id: 'draft-project', entry: { ...current.entries[0], title: 'Proposed project revision', version: 8 },
      replacesVersion: 2, baselineSha: 'a'.repeat(40), explanation: 'Compared source files.' }] });
  });

  await check('An outdated proposed revision cannot replace a newer active note', async view => {
    const proposed = view.note('Outdated project revision');
    assert(proposed.textContent?.includes('active note changed'), 'Stale draft explains the conflict');
    assert(button(proposed, 'Approve and use').disabled, 'Stale draft cannot be approved');
    assert(view.state.writes.length === 0, 'Inspecting the stale draft does not mutate knowledge');
  }, state => {
    const current = state.saved.get('ws-1'); assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, pendingDrafts: [{ id: 'draft-stale', entry: { ...current.entries[0], title: 'Outdated project revision', version: 8 },
      replacesVersion: 1, baselineSha: 'a'.repeat(40), explanation: 'Compared older source files.' }] });
  });

  await check('A failed note save keeps the draft across reload and uses the new revision only on explicit retry', async view => {
    await view.click('Edit', view.note('Project architecture'));
    await draft(view, 'Corrected project architecture', 'My draft must survive a concurrent update.');
    const current = view.state.saved.get('ws-1');
    assert(current !== undefined, 'Notebook exists');
    view.state.saved.set('ws-1', { ...current, revision: 8, entries: current.entries.map(entry => entry.id === 'knowledge:project'
      ? { ...entry, body: 'New saved content from another editor.', version: 8 } : entry) });
    await view.click('Save', view.dialog());
    assert(field(view.dialog(), 'Details').value === 'My draft must survive a concurrent update.', 'Failed save retains draft');
    assert(button(view.dialog(), 'Save').disabled, 'Failed save must require reload');
    await view.click('Reload saved notebook', view.dialog());
    assert(field(view.dialog(), 'Details').value === 'My draft must survive a concurrent update.', 'Reload retains draft');
    assert(view.dialog().textContent?.includes('New saved content from another editor.'), 'User can compare the newly saved version');
    assert(view.state.writes.length === 1, 'Reload must not automatically retry');
    await view.click('Save', view.dialog());
    assert(view.state.writes[1].request.expectedRevision === 8, 'Explicit retry uses displayed current revision');
    assert(view.state.saved.get('ws-1')?.entries[0].origin === 'USER', 'Editing generated note creates user ownership');
  });

  await check('Knowledge removal and explicit reset preserve absence without recreating deleted text', async view => {
    await view.click('Remove', view.note('Project architecture'));
    await view.click('Remove note', view.dialog());
    assert(view.state.saved.get('ws-1')?.suppressedKnowledgeIds.includes('knowledge:project'), 'Removal retains suppression');
    await view.advanced();
    await view.click('Reset scope removals');
    const saved = view.state.saved.get('ws-1');
    assert(saved?.suppressedKnowledgeIds.length === 0 && !saved.entries.some(entry => entry.id === 'knowledge:project'), 'Reset does not recreate deleted content');
    assert(view.state.writes.map(write => write.action).join(',') === 'remove,reset', 'Only explicit mutations occur');
  });

  await check('Load failure is an error, never an empty notebook', async view => {
    assert(view.text().includes('Saved notebook could not be read'), 'Actual read error must be shown');
    assert(!view.text().includes('No saved knowledge notes'), 'Unreadable notebook must not appear empty');
    assert(view.state.writes.length === 0, 'Failed loading never resets saved content');
  }, state => { state.readFailure = true; });

  await check('Switching workspace ignores a previous outstanding notebook read', async view => {
    await view.workspace('ws-2');
    assert(view.text().includes('Second workspace notes'), 'New workspace notebook must load');
    const resolve = view.state.delayed.get('ws-1');
    assert(resolve !== undefined, 'Initial workspace read is pending');
    resolve(notebook()); await settle();
    assert(view.text().includes('Second workspace notes') && !view.text().includes('Project architecture'), 'Old workspace response cannot replace current notebook');
    await view.click('Use repository context in reviews');
    assert(view.state.writes[0].workspaceId === 'ws-2', 'Subsequent write stays scoped to selected workspace');
  }, state => { state.holdWorkspace = 'ws-1'; });

  await check('Preparation results remain distinct from missing notes and task execution status', async view => {
    await view.advanced();
    assert(view.text().includes('Last preparation: No useful note found'), 'An empty checked result must be visible');
    assert(view.text().includes('Last preparation: Failed'), 'A failed attempt must remain distinct from empty');
    assert(view.text().includes('Shared preparation is claimed for Project overview'), 'Current ownership must be shown without claiming runtime progress');
    assert(view.text().includes('Project architecture'), 'An empty refresh must not hide the retained note');
    await view.click('Edit', view.row('Iceberg connector'));
    await fill(view.dialog(), 'Name', 'Iceberg source contracts');
    await view.click('Save scope', view.dialog());
    assert(view.state.saved.get('ws-1')?.scopes.find(scope => scope.id === 'path:plugin/trino-iceberg/')?.preparation?.outcome === 'FAILED',
      'A configuration edit must retain the latest completed outcome');
  }, state => {
    const current = state.saved.get('ws-1');
    assert(current !== undefined, 'Notebook exists');
    const input = { scopeId: 'project', inputs: { 'git:README.md': '100644 blob ' + 'a'.repeat(40) }, digest: 'sha256:' + 'a'.repeat(64), gaps: [] as string[] };
    state.saved.set('ws-1', { ...current, activePreparation: { attemptId: 'active', requestKey: 'request', requestDigest: 'sha256:' + 'b'.repeat(64),
      ownerTaskId: 'task', ownerTurnId: 'turn', ownerTurnKey: 'turn-key', reviewId: 'review', runId: 'run', baseContentRevision: 7, inputs: [input] },
    scopes: current.scopes.map((scope): ContextScopeDto => ({ ...scope, preparation: scope.id === 'project'
      ? { attemptId: 'empty', inputFingerprint: input, outcome: 'EMPTY', noteIds: [], explanation: 'The checked README was empty.',
          checkedReads: [{ kind: 'REPOSITORY_FILE', id: 'context-read:blank', reference: { kind: 'REPOSITORY_FILE', repositoryId: 42,
            repository: { owner: 'trinodb', repo: 'trino' }, path: 'README.md', commit: 'a'.repeat(40) }, blobId: 'a'.repeat(40),
          readAt: '2026-09-01T00:00:00Z', startLine: 1, endLine: 0, totalLines: 0, utf8Bytes: 0, truncated: false, evidenceRef: null }] }
      : { attemptId: 'failed', inputFingerprint: { ...input, scopeId: scope.id, digest: null, gaps: ['Source unavailable'] },
          outcome: 'FAILED', noteIds: [], checkedReads: [], explanation: 'The source could not be read.' } })) });
  });

  await check('UTF-8 body limits prevent oversize saves', async view => {
    await view.click('Add note');
    const dialog = await draft(view, 'Byte-limited note', '😀'.repeat(2049));
    assert(button(dialog, 'Save').disabled, '8196 UTF-8 bytes must not be saved');
    await fill(dialog, 'Details', '😀'.repeat(2048));
    assert(!button(dialog, 'Save').disabled, '8192 UTF-8 bytes fit the limit');
    assert(view.state.writes.length === 0, 'Typing never saves automatically');
  });
  return results;
}

let displayed: View | null = null;
/** A persistent offline fixture for wide, narrow and native-editor visual checks. */
export async function show(mode: 'page' | 'advanced' | 'editor' | 'draft' | 'learn' | 'learning' | 'completed' = 'page'): Promise<void> {
  displayed?.dispose();
  const state = backend();
  if (mode === 'draft') {
    const current = state.saved.get('ws-1'); assert(current !== undefined, 'Notebook exists');
    state.saved.set('ws-1', { ...current, pendingDrafts: [{ id: 'draft-project', entry: { ...current.entries[0], title: 'Updated project architecture',
      summary: 'Check connector boundaries against the current implementation.', version: 8 }, replacesVersion: 2,
      baselineSha: 'a'.repeat(40), explanation: 'Proposed from inspected source files.' }] });
  }
  state.events = [
    { schemaVersion: 2, eventId: 'request', sequence: 1, taskId: 'build-task', turnId: 'build-turn', source: eventSource,
      occurredAt: '2026-09-01T00:00:00Z', type: 'user_message', payload: { deliveryKind: 'TURN', attachments: [], text: 'Learn the project architecture from repository files and selected documentation.' } },
    { schemaVersion: 2, eventId: 'progress', sequence: 2, taskId: 'build-task', turnId: 'build-turn', source: eventSource,
      occurredAt: '2026-09-01T00:00:02Z', type: 'assistant_message', payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'I found the coordinator, worker and connector modules. I’m checking their source contracts before saving a reusable overview.' } },
  ];
  displayed = fixture(state);
  await settle(); await settle();
  if (mode === 'advanced') { (await displayed.advanced()).scrollIntoView(); await settle(); }
  if (mode === 'editor') {
    await displayed.click('Edit', displayed.note('Project architecture'));
    await draft(displayed, 'Project architecture');
  }
  if (mode === 'learn' || mode === 'learning' || mode === 'completed') {
    await displayed.click('Learn / Refresh');
    if (mode === 'learn') await fill(displayed.dialog(), 'Merged PR numbers (optional)', '30280');
    else {
      await displayed.click('Start learning', displayed.dialog());
      if (mode === 'completed') {
        const current = state.builds.get('ws-1'); assert(current?.build?.task !== null && current?.build !== null && current !== undefined, 'Builder exists');
        state.events.push({ schemaVersion: 2, eventId: 'answer', sequence: 3, taskId: 'build-task', turnId: 'build-turn', source: eventSource,
          occurredAt: '2026-09-01T00:00:20Z', type: 'assistant_message', payload: { messageKind: 'ANSWER', phase: 'FINAL',
            text: 'Saved a project overview with the inspected source references. Connector-specific behavior still needs verification for each review.' } });
        state.builds.set('ws-1', { ...current, canStart: true, build: { ...current.build, activeTurn: null, task: { ...current.build.task,
          state: 'COMPLETED', mutationClaim: null, terminalReason: 'Selected knowledge preparation completed.', worktreeRemovedAt: '2026-09-01T00:00:21Z' },
          results: [{ attemptId: 'prepared', outcome: 'READY', inputFingerprint: { scopeId: 'project', inputs: {}, digest: 'sha256:captured', gaps: [] },
            noteIds: ['knowledge:project'], checkedReads: [], explanation: 'Saved the overview with source references; applicability remains unverified.' }],
          gaps: ['Some documentation describes a later release. Check the dependency version before applying its guidance.'] } });
        await displayed.click('Inspect latest task'); await settle();
      }
    }
  }
}
