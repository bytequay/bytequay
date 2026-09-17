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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { WorkspaceDto } from '../types';
import ContextEntryEditor, { ContextDialog, type ContextEntryDraft } from '../repository-context/ContextEntryEditor';
import type { ContextClaimDto, ContextEntryDto, ContextScopeConfigurationDto, ContextScopeDto, ContextSourceConfigurationDto, ContextSourceDto, RepositoryNotebookDto } from '../repository-context/contracts';
import { Badge, SettingsCard, SettingsRow, Toggle } from './primitives';
import RepositoryContextBuild from './RepositoryContextBuild';

type Editor = { kind: 'entry'; entry: ContextEntryDto | null; initial: ContextEntryDraft; claims: ContextClaimDto[] }
  | { kind: 'source'; source: ContextSourceDto | null } | { kind: 'scope'; scope: ContextScopeDto | null };
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const lines = (text: string) => [...new Set(text.split('\n').map(line => line.trim()).filter(Boolean))];

export default function RepositoryContextPage({ workspace }: { workspace: WorkspaceDto }) {
  const [notebook, setNotebook] = useState<RepositoryNotebookDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [needsReload, setNeedsReload] = useState(false);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [removing, setRemoving] = useState<ContextEntryDto | null>(null);
  const pending = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    window.bridge.getRepositoryContext(workspace.id).then(value => { if (active) setNotebook(value); })
      .catch(failure => { if (active) setError(failureText(failure)); });
    return () => { active = false; mounted.current = false; };
  }, [workspace.id]);

  const reload = async () => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      const value = await window.bridge.getRepositoryContext(workspace.id);
      if (!mounted.current) return;
      setNotebook(value);
      setNeedsReload(false);
      setError(null);
      setStatus('Saved notebook reloaded. Your open draft is unchanged; review it before saving.');
    }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { pending.current = false; if (mounted.current) setBusy(false); }
  };

  const mutate = async (operation: (revision: number) => Promise<RepositoryNotebookDto>, message: string, close = false) => {
    if (notebook === null || pending.current || needsReload) return;
    pending.current = true;
    setBusy(true);
    setError(null);
    setStatus('');
    try {
      const value = await operation(notebook.revision);
      if (!mounted.current) return;
      setNotebook(value);
      setStatus(message);
      if (close) { setEditor(null); setRemoving(null); }
    }
    catch (failure) {
      if (mounted.current) { setError(failureText(failure)); setNeedsReload(true); }
    }
    finally { pending.current = false; if (mounted.current) setBusy(false); }
  };

  const closeEditor = () => { if (!busy) setEditor(null); };
  const reloadControl = <div className="rc-actions"><button className="rc-button" type="button" disabled={busy} onClick={() => void reload()}>Reload saved notebook</button>
    {needsReload && <span className="rc-muted">Your changes have not been retried. Reload before saving again.</span>}</div>;
  const availableScopes = notebook?.scopes.filter(scope => !notebook.entries.some(entry => entry.id === 'knowledge:' + scope.id)
    && !notebook.suppressedKnowledgeIds.includes('knowledge:' + scope.id)) ?? [];
  const disabled = busy || needsReload;
  const discoveryEvidence = (reference: string | null) => {
    if (reference === null) return null;
    const evidence = notebook?.sourceEvidence.find(source => source.id === reference);
    return <details><summary>Why this was suggested</summary>{evidence === undefined ? <p>Saved discovery evidence is unavailable.</p>
      : <><p>{evidence.provenance}</p><pre className="rc-saved-version">{evidence.excerpt}</pre>{evidence.truncated && <p>Evidence excerpt is incomplete.</p>}</>}</details>;
  };
  const currentEntry = editor?.kind === 'entry' && editor.entry !== null ? notebook?.entries.find(entry => entry.id === editor.entry?.id) : undefined;
  const sourceLinksChanged = editor?.kind === 'entry' && editor.entry !== null && currentEntry !== undefined
    && JSON.stringify([currentEntry.sourceIds, currentEntry.claims.map(claim => [claim.basis, claim.sourceIds])])
      !== JSON.stringify([editor.entry.sourceIds, editor.entry.claims.map(claim => [claim.basis, claim.sourceIds])]);
  const openNote = (entry: ContextEntryDto | null) => {
    const scopeId = entry?.scopeId ?? availableScopes[0]?.id;
    if (scopeId === undefined) return;
    setStatus('');
    setEditor({ kind: 'entry', entry, claims: entry?.claims ?? [], initial: entry ?? { scopeId, title: '', summary: '', body: '' } });
  };

  const entryRows = (kind: ContextEntryDto['kind']) => notebook?.entries.filter(entry => entry.kind === kind).map(entry => {
    const scope = notebook.scopes.find(item => item.id === entry.scopeId);
    return <article key={entry.id} className="rc-note">
      <div className="rc-note-head"><div><strong>{entry.title}</strong><small>{scope?.title ?? entry.scopeId} · {entry.origin === 'USER' ? 'Saved by you' : 'Generated'} · Version {entry.version}</small></div>
        <Badge>{!entry.enabled || scope?.enabled === false ? 'Excluded' : 'Available for selection'}</Badge></div>
      <p>{entry.summary}</p>
      <details><summary>Details and sources</summary><pre>{entry.body}</pre>
        {entry.claims.length > 0 && <ul>{entry.claims.map((claim, index) => <li key={index}>{claim.text} <span className="rc-muted">({claim.basis.toLowerCase().replaceAll('_', ' ')})</span></li>)}</ul>}
        {entry.sourceIds.length === 0 && <p className="rc-muted">No supporting source is attached.</p>}
        {entry.sourceIds.map(id => {
          const source = notebook.sourceEvidence.find(value => value.id === id);
          return source === undefined ? <p key={id}>Source unavailable: {id}</p> : <details key={id}><summary>{source.provenance}</summary><pre>{source.excerpt}</pre></details>;
        })}
      </details>
      <div className="rc-actions"><button className="rc-button" disabled={disabled} onClick={() => openNote(entry)}>Edit</button>
        <button className="rc-button" disabled={disabled} onClick={() => void mutate(revision => window.bridge.setRepositoryContextEntryEnabled(workspace.id,
          { expectedRevision: revision, entryId: entry.id, enabled: !entry.enabled }), entry.enabled ? 'Note disabled for future reviews.' : 'Note enabled for future reviews.')}>{entry.enabled ? 'Disable' : 'Enable'}</button>
        <button className="rc-button is-danger" disabled={disabled} onClick={() => setRemoving(entry)}>Remove</button></div>
    </article>;
  });

  return <div className="setw-page rc-page">
    <div className="setw-eyebrow">{workspace.selectedOwner}/{workspace.selectedRepo}</div><h1>Repository context</h1>
    <p className="rc-muted">Knowledge and lessons saved for this workspace, with the sources behind them.</p>
    {editor === null && removing === null && error !== null && <div className="rc-error" role="alert">{error}{reloadControl}</div>}
    {status !== '' && <div className="rc-status" role="status">{status}</div>}
    {notebook === null ? <div className="setw-row-empty">{error === null ? 'Loading repository context…' : 'The notebook could not be loaded.'}</div> : <>
      <SettingsCard><SettingsRow title="Use repository context in reviews" subtitle="Applies to new review runs. Turning it off keeps your notes and past review records.">
        <Toggle on={notebook.useInReviews} disabled={disabled} label="Use repository context in reviews" onChange={enabled => void mutate(revision =>
          window.bridge.configureRepositoryContext(workspace.id, { expectedRevision: revision, useInReviews: enabled }), enabled ? 'Repository context enabled for new runs.' : 'Repository context disabled for new runs.')} />
      </SettingsRow></SettingsCard>
      <RepositoryContextBuild workspaceId={workspace.id} notebook={notebook} onNotebookChange={setNotebook} disabled={disabled} />
      {notebook.activePreparation !== null && <div className="rc-status" role="status">Shared preparation is claimed for {notebook.activePreparation.inputs
        .map(input => notebook.scopes.find(scope => scope.id === input.scopeId)?.title ?? input.scopeId).join(', ')}. Saved notes remain available.
        <details><summary>Preparation owner</summary><p>Task: {notebook.activePreparation.ownerTaskId}</p>
          <p>Task activity determines whether the agent is running, waiting or stopped.</p></details></div>}
      <div className="setw-section"><span className="setw-section-title">Knowledge</span><button className="rc-button" disabled={disabled || availableScopes.length === 0} onClick={() => openNote(null)}>Add note</button></div>
      <SettingsCard>{notebook.entries.some(entry => entry.kind === 'KNOWLEDGE') ? entryRows('KNOWLEDGE') : <div className="setw-row-empty">No saved knowledge notes. Preparation results below show whether sources have been checked.</div>}</SettingsCard>
      <div className="setw-section"><span className="setw-section-title">Saved lessons</span></div>
      <SettingsCard>{notebook.entries.some(entry => entry.kind === 'MEMORY') ? entryRows('MEMORY') : <div className="setw-row-empty">Use Save lesson on a private review discussion to keep a useful conclusion and its evidence.</div>}</SettingsCard>
      <div className="setw-section"><span className="setw-section-title">Matching scopes</span><button className="rc-button" disabled={disabled} onClick={() => setEditor({ kind: 'scope', scope: null })}>Add scope</button></div>
      <p className="rc-muted">Scopes choose where knowledge and lessons apply. Project overview is a scope; its saved note appears under Knowledge.</p>
      <SettingsCard>{notebook.scopes.map(scope => <SettingsRow key={scope.id} title={scope.title} badge={!scope.enabled ? <Badge>Disabled</Badge> : undefined}
        subtitle={<>{scope.match.kind === 'REPOSITORY' ? 'Applies across this repository' : [...scope.match.pathPrefixes, ...scope.match.terms].join(' · ')}
          {discoveryEvidence(scope.discoveryEvidenceRef)}
          {!notebook.entries.some(entry => entry.kind === 'KNOWLEDGE' && entry.scopeId === scope.id) && <div>No saved knowledge note for this scope.</div>}
          {scope.preparation === null ? <div>No completed preparation recorded.</div> : <details><summary>Last preparation: {{ READY: 'Note prepared', EMPTY: 'No useful note found', FAILED: 'Failed' }[scope.preparation.outcome]}</summary>
            <p>{scope.preparation.explanation}</p><p>{scope.preparation.checkedReads.length} source reads retained. This result applies to the checked inputs; it does not verify the note against a new review.</p>
          </details>}</>}>
        <button className="rc-button" disabled={disabled} onClick={() => setEditor({ kind: 'scope', scope })}>Edit</button>
        <button className="rc-button" disabled={disabled} onClick={() => void mutate(revision => window.bridge.configureRepositoryContext(workspace.id,
          { expectedRevision: revision, scopes: notebook.scopes.map(value => value.id === scope.id ? { ...value, enabled: !value.enabled } : value) }), 'Scope updated for future reviews.')}>{scope.enabled ? 'Disable' : 'Enable'}</button>
      </SettingsRow>)}</SettingsCard>
      <div className="setw-section"><span className="setw-section-title">Documentation sources</span><button className="rc-button" disabled={disabled} onClick={() => setEditor({ kind: 'source', source: null })}>Add source</button></div>
      <SettingsCard>{notebook.sources.length === 0 && <div className="setw-row-empty">Add a documentation website or page. Saving a link does not fetch it.</div>}
        {notebook.sources.map(source => <SettingsRow key={source.id} title={source.kind === 'DOCUMENTATION_ROOT' ? 'Documentation website' : 'Single page'}
          subtitle={<>{source.url}{discoveryEvidence(source.discoveryEvidenceRef)}</>}>
          <button className="rc-button" disabled={disabled} onClick={() => setEditor({ kind: 'source', source })}>Edit</button>
          <button className="rc-button is-danger" disabled={disabled} onClick={() => void mutate(revision => window.bridge.configureRepositoryContext(workspace.id,
            { expectedRevision: revision, sources: notebook.sources.filter(value => value.id !== source.id).map(({ id, kind, url }) => ({ id, kind, url })) }), 'Documentation source removed. Captured review evidence is kept.')}>Remove</button>
        </SettingsRow>)}</SettingsCard>
      {notebook.suppressedKnowledgeIds.length > 0 && <><div className="setw-section"><span className="setw-section-title">Removed knowledge</span></div>
        <SettingsCard>{notebook.suppressedKnowledgeIds.map(id => {
          const scope = notebook.scopes.find(value => 'knowledge:' + value.id === id);
          return <SettingsRow key={id} title={scope?.title ?? id} subtitle="Removal prevents this note from being recreated. Reset allows adding it again; it does not restore deleted text.">
            <button className="rc-button" disabled={disabled || scope === undefined} onClick={() => scope && void mutate(revision => window.bridge.resetRepositoryContext(workspace.id,
              { expectedRevision: revision, scopeIds: [scope.id] }), 'Removal reset. You can add a new note for this scope.')}>Reset removal</button></SettingsRow>;
        })}</SettingsCard></>}
      <p className="rc-muted">Reviews can prepare reusable knowledge for configured scopes and provide saved context to the agent. Learn / Refresh starts an explicit learning task. Opening this page does not start learning.</p>
      {editor?.kind === 'entry' && <ContextEntryEditor initialValue={editor.initial} scopes={editor.entry === null ? availableScopes : notebook.scopes}
        scopeLocked={editor.entry !== null} busy={busy} error={error} submitDisabled={needsReload || sourceLinksChanged || editor.claims.some(claim => claim.text.trim() === '' || new TextEncoder().encode(claim.text).length > 2000)
          || (editor.entry !== null && !notebook.entries.some(entry => entry.id === editor.entry?.id))} title={editor.entry === null ? 'Add knowledge note' : 'Edit note'} onCancel={closeEditor}
        onSave={draft => void mutate(revision => window.bridge.editRepositoryContextEntry(workspace.id, { expectedRevision: revision, ...draft,
          id: editor.entry?.id ?? 'knowledge:' + draft.scopeId, claims: editor.claims, sourceIds: editor.entry?.sourceIds ?? [] }), 'Note saved for future reviews.', true)}>
        {editor.entry?.origin === 'GENERATED' && <p className="rc-muted">Saving makes this a user-managed note.</p>}
        {sourceLinksChanged && <p className="rc-error" role="alert">The saved sources or claim structure changed. Copy any draft text you want to keep, then cancel and reopen this note to edit its latest sources.</p>}
        {editor.claims.length > 0 && <details open><summary>Supporting claims</summary>{editor.claims.map((claim, index) => <label key={index}>
          {claim.basis.toLowerCase().replaceAll('_', ' ')}<textarea rows={2} disabled={busy} value={claim.text} onChange={event => setEditor({ ...editor,
            claims: editor.claims.map((value, position) => position === index ? { ...value, text: event.target.value } : value) })} />
        </label>)}<p className="rc-muted">Existing source links are kept. Each claim can contain up to 2,000 bytes.</p></details>}
        {status !== '' && <p className="rc-muted">{status}</p>}
        {editor.entry !== null && <details><summary>Compare saved version</summary><pre className="rc-saved-version">{(() => {
          const saved = notebook.entries.find(entry => entry.id === editor.entry?.id);
          return saved === undefined ? 'This note has been removed. Cancel to return to the notebook.' : [saved.title, saved.summary, saved.body, ...saved.claims.map(claim => claim.text)].join('\n\n');
        })()}</pre></details>}{reloadControl}
      </ContextEntryEditor>}
      {editor?.kind === 'source' && <SourceEditor source={editor.source} busy={busy} error={error} needsReload={needsReload} onCancel={closeEditor} reloadControl={reloadControl}
        onSave={source => void mutate(revision => window.bridge.configureRepositoryContext(workspace.id, { expectedRevision: revision,
          sources: [...notebook.sources.filter(value => value.id !== source.id).map(({ id, kind, url }) => ({ id, kind, url })), source] }), 'Documentation source saved.', true)} />}
      {editor?.kind === 'scope' && <ScopeEditor scope={editor.scope} busy={busy} error={error} needsReload={needsReload} onCancel={closeEditor} reloadControl={reloadControl}
        onSave={scope => void mutate(revision => window.bridge.configureRepositoryContext(workspace.id, { expectedRevision: revision,
          scopes: [...notebook.scopes.filter(value => value.id !== scope.id), { ...scope, enabled: notebook.scopes.find(value => value.id === scope.id)?.enabled ?? true }] }), 'Matching scope saved.', true)} />}
      {removing !== null && <ContextDialog title="Remove saved note?" busy={busy} onCancel={() => setRemoving(null)}><form onSubmit={event => { event.preventDefault();
        void mutate(revision => window.bridge.removeRepositoryContextEntry(workspace.id, { expectedRevision: revision, entryId: removing.id }), 'Note removed. Previous review records are unchanged.', true);
      }}><div className="rc-dialog-body"><p>Remove “{removing.title}” from future reviews?</p>
        <p className="rc-muted">Past review records stay available.{removing.kind === 'KNOWLEDGE' ? ' This scope stays excluded from recreation until you reset its removal.' : ' The saved lesson will be deleted.'}</p>
        {error && <div className="rc-error" role="alert">{error}{reloadControl}</div>}</div><footer><button type="button" className="rc-button" disabled={busy} onClick={() => setRemoving(null)}>Cancel</button>
          <button className="rc-button is-danger" type="submit" disabled={disabled}>Remove note</button></footer></form></ContextDialog>}
    </>}
  </div>;
}

type ConfigurationEditorProps = { busy: boolean; error: string | null; needsReload: boolean; onCancel: () => void; reloadControl: ReactNode };

function SourceEditor({ source, busy, error, needsReload, onCancel, reloadControl, onSave }: ConfigurationEditorProps & {
  source: ContextSourceDto | null; onSave: (source: ContextSourceConfigurationDto) => void;
}) {
  const [id] = useState(() => source?.id ?? 'docs:' + crypto.randomUUID());
  const [url, setUrl] = useState(source?.url ?? '');
  const [kind, setKind] = useState<ContextSourceDto['kind']>(source?.kind ?? 'DOCUMENTATION_ROOT');
  return <ContextDialog title={source === null ? 'Add documentation source' : 'Edit documentation source'} busy={busy} onCancel={onCancel}>
    <form onSubmit={event => { event.preventDefault(); if (!busy && !needsReload) onSave({ id, kind, url: url.trim() }); }}><div className="rc-dialog-body">
      <p className="rc-muted">Allow selected reads from a documentation website or one exact page. Saving does not fetch the link.</p>
      {error && <div className="rc-error" role="alert">{error}</div>}
      <label>Source type<select value={kind} disabled={busy} onChange={event => setKind(event.target.value as ContextSourceDto['kind'])}>
        <option value="DOCUMENTATION_ROOT">Documentation website</option><option value="DOCUMENTATION_PAGE">Single page</option></select></label>
      <label>HTTPS address<input type="url" required placeholder="https://trino.io/docs/current/" value={url} disabled={busy} onChange={event => setUrl(event.target.value)} /></label>
      <p className="rc-muted">Use a public HTTPS address without a query or fragment. Website addresses must end in /.</p>
      {needsReload && reloadControl}</div><footer><button type="button" className="rc-button" disabled={busy} onClick={onCancel}>Cancel</button>
        <button className="rc-button is-primary" type="submit" disabled={busy || needsReload || !url.trim().startsWith('https://')}>{busy ? 'Saving…' : 'Save source'}</button></footer></form>
  </ContextDialog>;
}

function ScopeEditor({ scope, busy, error, needsReload, onCancel, reloadControl, onSave }: ConfigurationEditorProps & {
  scope: ContextScopeDto | null; onSave: (scope: ContextScopeConfigurationDto) => void;
}) {
  const [id] = useState(() => scope?.id ?? 'scope:' + crypto.randomUUID());
  const [title, setTitle] = useState(scope?.title ?? '');
  const [paths, setPaths] = useState(scope?.match.kind === 'SELECTED' ? scope.match.pathPrefixes.join('\n') : '');
  const [terms, setTerms] = useState(scope?.match.kind === 'SELECTED' ? scope.match.terms.join('\n') : '');
  const project = scope?.match.kind === 'REPOSITORY';
  const invalid = title.trim() === '' || new TextEncoder().encode(title).length > 200 || (!project && lines(paths).length + lines(terms).length === 0);
  return <ContextDialog title={scope === null ? 'Add matching scope' : 'Edit matching scope'} busy={busy} onCancel={onCancel}>
    <form onSubmit={event => { event.preventDefault(); if (!busy && !needsReload && !invalid) onSave({ id, title: title.trim(), enabled: scope?.enabled ?? true,
      match: project ? { kind: 'REPOSITORY' } : { kind: 'SELECTED', pathPrefixes: lines(paths), terms: lines(terms) } }); }}><div className="rc-dialog-body">
      {error && <div className="rc-error" role="alert">{error}</div>}<label>Name<input required value={title} disabled={busy} onChange={event => setTitle(event.target.value)} /></label>
      {project ? <p className="rc-muted">The project scope applies across the repository.</p> : <><p className="rc-muted">A matching changed path or term can select notes in this scope.</p>
        <label>Directory prefixes<textarea rows={3} placeholder="plugin/trino-iceberg/" value={paths} disabled={busy} onChange={event => setPaths(event.target.value)} /></label>
        <p className="rc-muted">One repository-relative directory per line, ending in /. Up to 20 prefixes.</p>
        <label>Terms<textarea rows={3} placeholder="Iceberg" value={terms} disabled={busy} onChange={event => setTerms(event.target.value)} /></label>
        <p className="rc-muted">One term per line. Up to 20 terms; at least one prefix or term is required.</p></>}
      {needsReload && reloadControl}</div><footer><button type="button" className="rc-button" disabled={busy} onClick={onCancel}>Cancel</button>
        <button className="rc-button is-primary" type="submit" disabled={busy || needsReload || invalid}>{busy ? 'Saving…' : 'Save scope'}</button></footer></form>
  </ContextDialog>;
}
