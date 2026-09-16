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
import type { Bridge, WorkspaceDto } from '../../types';
import type { ContextEntryDto, ContextScopeDto, RepositoryNotebookDto } from '../../repository-context/contracts';
import SettingsWindow from '../SettingsWindow';
import '../../css/base.css';
import '../../css/settings-window.css';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
const workspaces = [
  { id: 'ws-1', name: 'Trino', selectedOwner: 'trinodb', selectedRepo: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' },
  { id: 'ws-2', name: 'Iceberg', selectedOwner: 'apache', selectedRepo: 'iceberg', monitoredOwner: 'apache', monitoredRepo: 'iceberg' },
] as WorkspaceDto[];

function notebook(second = false): RepositoryNotebookDto {
  return {
    schemaVersion: 1, revision: 7, useInReviews: true, activePreparation: null,
    sources: [{ id: 'docs-trino', kind: 'DOCUMENTATION_ROOT', url: 'https://trino.io/docs/current/', origin: 'USER' }],
    scopes: [
      { id: 'project', title: 'Project overview', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null },
      { id: 'path:plugin/trino-iceberg/', title: 'Iceberg connector',
        match: { kind: 'SELECTED', pathPrefixes: ['plugin/trino-iceberg/'], terms: ['Iceberg'] }, enabled: true, preparation: null },
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
    suppressedKnowledgeIds: [],
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

type Mutation = { action: string; workspaceId: string; request: { expectedRevision: number } };
function backend() {
  const saved = new Map(workspaces.map(workspace => [workspace.id, notebook(workspace.id === 'ws-2')]));
  const reads: string[] = [];
  const writes: Mutation[] = [];
  const delayed = new Map<string, (notebook: RepositoryNotebookDto) => void>();
  const state = { saved, reads, writes, delayed, readFailure: false, holdWorkspace: '' };
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
    configureRepositoryContext: (workspaceId, request) => mutate('configure', workspaceId, request, current => ({
      ...current, useInReviews: request.useInReviews ?? current.useInReviews,
      scopes: request.scopes?.map(scope => ({ ...scope, preparation: current.scopes.find(value => value.id === scope.id)?.preparation ?? null })) ?? current.scopes,
      sources: request.sources?.map(source => ({ ...source, origin: 'USER' as const })) ?? current.sources,
    })),
    editRepositoryContextEntry: (workspaceId, request) => mutate('edit', workspaceId, request, current => {
      const previous = current.entries.find(entry => entry.id === request.id);
      const entry: ContextEntryDto = { id: request.id, kind: previous?.kind ?? 'KNOWLEDGE', scopeId: request.scopeId,
        title: request.title, summary: request.summary, body: request.body, claims: request.claims, sourceIds: request.sourceIds,
        origin: 'USER', enabled: previous?.enabled ?? true, version: current.revision + 1 };
      return { ...current, entries: previous === undefined ? [...current.entries, entry] : current.entries.map(value => value.id === entry.id ? entry : value) };
    }),
    setRepositoryContextEntryEnabled: (workspaceId, request) => mutate('enabled', workspaceId, request, current => ({
      ...current, entries: current.entries.map(entry => entry.id === request.entryId ? { ...entry, enabled: request.enabled } : entry),
    })),
    removeRepositoryContextEntry: (workspaceId, request) => mutate('remove', workspaceId, request, current => ({
      ...current, entries: current.entries.filter(entry => entry.id !== request.entryId),
      suppressedKnowledgeIds: current.entries.find(entry => entry.id === request.entryId)?.kind === 'KNOWLEDGE'
        ? [...current.suppressedKnowledgeIds, request.entryId] : current.suppressedKnowledgeIds,
    })),
    resetRepositoryContext: (workspaceId, request) => mutate('reset', workspaceId, request, current => ({
      ...current, suppressedKnowledgeIds: current.suppressedKnowledgeIds.filter(id => !request.scopeIds.some(scope => id === 'knowledge:' + scope)),
    })),
  } satisfies Pick<Bridge, 'getAppVersion' | 'getAppPreferences' | 'getWorkspaceSettings' | 'getRepositoryContext' | 'configureRepositoryContext'
    | 'editRepositoryContextEntry' | 'setRepositoryContextEntryEnabled' | 'removeRepositoryContextEntry' | 'resetRepositoryContext'>;
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
    assert(button(view.host, 'Use repository context in reviews').getAttribute('aria-checked') === 'true', 'Initial preference must be on');
    await view.click('Use repository context in reviews');
    assert(view.state.writes.length === 1 && view.state.writes[0].request.expectedRevision === 7, 'Toggle must use displayed revision');
    assert(!view.state.saved.get('ws-1')?.useInReviews && view.state.saved.get('ws-1')?.entries.length === 2, 'Off retains both saved notes');
    assert(view.text().includes('Project architecture'), 'Notes stay visible after disabling');
    assert(button(view.host, 'Use repository context in reviews').getAttribute('aria-checked') === 'false', 'Saved off state must render');
  });

  await check('Source edits keep source identity and revision', async view => {
    await view.click('Edit', view.row('Documentation website'));
    await fill(view.dialog(), 'HTTPS address', 'https://trino.io/docs/current/connector/');
    await view.click('Save source', view.dialog());
    const saved = view.state.saved.get('ws-1');
    assert(saved?.sources[0].id === 'docs-trino' && saved.sources[0].url === 'https://trino.io/docs/current/connector/', 'Source identity and edited URL must persist');
    assert(view.state.writes[0].request.expectedRevision === 7 && view.host.querySelector('dialog') === null, 'Source edit uses revision and closes after success');
  });

  await check('Scope conflict reload preserves the draft and a concurrent disable', async view => {
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

  await check('Adding a note sends one explicit save with its matching scope', async view => {
    await view.click('Add note');
    const dialog = await draft(view, 'Iceberg metadata rules');
    const save = button(dialog, 'Save'); save.click(); save.click(); await settle();
    assert(view.state.writes.length === 1 && view.state.writes[0].request.expectedRevision === 7, 'Double click must make one save');
    const added = view.state.saved.get('ws-1')?.entries.find(entry => entry.title === 'Iceberg metadata rules');
    assert(added?.id === 'knowledge:path:plugin/trino-iceberg/' && added.origin === 'USER', 'New note must use chosen canonical scope and user ownership');
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
    await view.click('Reset removal');
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
export async function show(mode: 'page' | 'editor' = 'page'): Promise<void> {
  displayed?.dispose();
  displayed = fixture(backend());
  await settle(); await settle();
  if (mode === 'editor') {
    await displayed.click('Edit', displayed.note('Project architecture'));
    await draft(displayed, 'Project architecture');
  }
}
