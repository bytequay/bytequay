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
import { useEffect, useRef, useState } from 'react';
import type { ContextBuildHistoryDto, ContextDiscoveryDto, ContextSourceEvidenceDto, RepositoryNotebookDto } from '../repository-context/contracts';

const failureText = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);
const historyKey = (item: ContextBuildHistoryDto) => `${item.kind}:${item.number}`;

export default function RepositoryContextDiscovery({ workspaceId, notebook, scopeIds, history, disabled, requiresReload, onBusy, onSelectionChange, onRequiresReload, onNotebookChange, onUse }: {
  workspaceId: string; notebook: RepositoryNotebookDto; scopeIds: string[]; history: ContextBuildHistoryDto[] | null; disabled: boolean; requiresReload: boolean;
  onBusy: (busy: boolean) => void; onSelectionChange: (selected: boolean) => void; onRequiresReload: (required: boolean) => void; onNotebookChange: (notebook: RepositoryNotebookDto) => void;
  onUse: (scopeIds: string[], history: ContextBuildHistoryDto[]) => void;
}) {
  const [query, setQuery] = useState('');
  const [includeRemote, setIncludeRemote] = useState(false);
  const [preview, setPreview] = useState<ContextDiscoveryDto | null>(null);
  const [chosenScopes, setChosenScopes] = useState<string[]>([]);
  const [chosenSources, setChosenSources] = useState<string[]>([]);
  const [chosenHistory, setChosenHistory] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const pending = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const selectedHistory = preview?.history.filter(item => chosenHistory.includes(historyKey(item))).map(({ kind, number }) => ({ kind, number })) ?? [];
  const combinedHistory = [...new Map([...(history ?? []), ...selectedHistory].map(item => [historyKey(item), item])).values()];
  const stale = preview !== null && preview.notebookRevision !== notebook.revision;
  const queryValid = new TextEncoder().encode(query.trim()).length <= 100 && /^[\p{L}\p{N} _./-]*$/u.test(query);
  const hasSelection = chosenScopes.length + chosenSources.length + chosenHistory.length > 0;
  useEffect(() => { onSelectionChange(hasSelection); return () => onSelectionChange(false); }, [hasSelection, onSelectionChange]);
  const blocked = disabled || busy;
  const perform = async (action: () => Promise<void>) => {
    if (pending.current) return;
    pending.current = true; setBusy(true); onBusy(true); setError(null); setStatus('');
    try { await action(); }
    catch (failure) { if (mounted.current) setError(failureText(failure)); }
    finally { pending.current = false; onBusy(false); if (mounted.current) setBusy(false); }
  };
  const clearSelection = () => { setChosenScopes([]); setChosenSources([]); setChosenHistory([]); };
  const find = async () => {
    const result = await window.bridge.discoverRepositoryContext(workspaceId, { expectedRevision: notebook.revision, scopeIds, query: query.trim(), includeRemote });
    if (mounted.current) { setPreview(result); clearSelection(); }
  };
  const useSelected = async () => {
    if (preview === null || stale || requiresReload || history === null || combinedHistory.length > 10) return;
    if (chosenScopes.length + chosenSources.length > 0) {
      try {
        const saved = await window.bridge.applyRepositoryContextDiscovery(workspaceId, { expectedRevision: preview.notebookRevision, baselineSha: preview.baselineSha,
          repositoryFingerprint: preview.repositoryFingerprint, query: preview.query, scopeIds: chosenScopes, sourceIds: chosenSources });
        if (!mounted.current) return;
        onNotebookChange(saved);
      }
      catch (failure) { onRequiresReload(true); throw failure; }
    }
    onUse(chosenScopes, combinedHistory);
    setPreview(null); clearSelection();
    setStatus('Selected suggestions applied. Review the choices below, then start learning when ready.');
  };
  const toggle = (values: string[], id: string, checked: boolean) => checked ? [...values, id] : values.filter(value => value !== id);
  return <section className="rc-discovery" aria-label="Repository suggestions">
    <h3>Find useful starting points</h3>
    <p className="rc-muted">Suggest scopes and documentation from repository files, and related merged PRs or closed issues. Nothing is selected or learned automatically.</p>
    <label>Suggestion focus (optional)<input value={query} disabled={blocked} maxLength={100} placeholder="Iceberg" onChange={event => setQuery(event.target.value)} /></label>
    <label><input type="checkbox" checked={includeRemote} disabled={blocked} onChange={event => setIncludeRemote(event.target.checked)} />Include GitHub search</label>
    <p className="rc-muted">By default, use local repository and saved history only. GitHub search is bounded and may find closed issues missing from the local cache.</p>
    {!queryValid && <p className="rc-error" role="alert">Use up to 100 bytes of letters, numbers, spaces, underscores, dots, slashes or hyphens.</p>}
    <button type="button" className="rc-button" disabled={blocked || requiresReload || !queryValid} onClick={() => void perform(find)}>{busy ? 'Working…' : 'Find suggestions'}</button>
    {error !== null && <p className="rc-error" role="alert">{error}</p>}
    {requiresReload && <div className="rc-error" role="alert"><p>Applying suggestions was not confirmed. Reload the saved notebook to inspect what was saved before trying again. Learning has not started.</p>
      <button type="button" className="rc-button" disabled={blocked} onClick={() => void perform(async () => {
        const saved = await window.bridge.getRepositoryContext(workspaceId);
        if (mounted.current) { onNotebookChange(saved); onRequiresReload(false); setPreview(null); clearSelection();
          setStatus('Notebook reloaded. Saved configuration is shown below; find suggestions again to make another selection.'); }
      })}>Reload saved notebook</button></div>}
    {status !== '' && <p className="rc-status" role="status">{status}</p>}
    {preview !== null && <div className="rc-discovery-preview">
      <p className="rc-muted">Checked {preview.inspected.directories} directories, {preview.inspected.files} files, {preview.inspected.pullRequests} PRs and {preview.inspected.issues} issues.
        {preview.query !== '' && <> Focus: {preview.query}.</>}</p>
      <details><summary>Checked baseline and limits</summary><p><code>{preview.baselineSha}</code></p>
        {preview.gaps.length > 0 ? <ul>{preview.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul> : <p>No additional gaps were reported for this bounded selection.</p>}</details>
      {stale && <p className="rc-error" role="alert">The notebook changed. Find suggestions again before applying this selection.</p>}
      {preview.scopes.length + preview.sources.length + preview.history.length === 0 && <p>No suggestions found in the checked sources.</p>}
      {preview.scopes.length > 0 && <fieldset disabled={blocked || stale || requiresReload}><legend>Suggested scopes</legend>{preview.scopes.map(item => <div key={item.scope.id}>
        <label><input type="checkbox" checked={chosenScopes.includes(item.scope.id)} onChange={event => setChosenScopes(toggle(chosenScopes, item.scope.id, event.target.checked))} />{item.scope.title}</label>
        <p className="rc-muted">{item.scope.match.kind === 'SELECTED' ? [...item.scope.match.pathPrefixes, ...item.scope.match.terms].join(' · ') : 'Repository-wide'}</p>
        <SuggestionEvidence reasons={item.reasons} evidence={item.evidence} />
      </div>)}</fieldset>}
      {preview.sources.length > 0 && <fieldset disabled={blocked || stale || requiresReload}><legend>Suggested documentation</legend>{preview.sources.map(item => <div key={item.source.id}>
        <label><input type="checkbox" checked={chosenSources.includes(item.source.id)} onChange={event => setChosenSources(toggle(chosenSources, item.source.id, event.target.checked))} />{item.source.url}</label>
        <p className="rc-muted">{item.source.kind === 'DOCUMENTATION_ROOT' ? 'Allows selected pages under this documentation root.' : 'Allows only this exact page.'}</p>
        <SuggestionEvidence reasons={item.reasons} evidence={item.evidence} />
      </div>)}</fieldset>}
      {preview.history.length > 0 && <fieldset disabled={blocked || stale || requiresReload}><legend>Suggested history</legend>{preview.history.map(item => <div key={historyKey(item)}>
        <label><input type="checkbox" checked={chosenHistory.includes(historyKey(item))} onChange={event => setChosenHistory(toggle(chosenHistory, historyKey(item), event.target.checked))} />{item.kind} #{item.number}: {item.title}</label>
        <p className="rc-muted">{item.state} · Outcome: {item.outcome.toLowerCase() === 'not_planned' ? 'Not planned' : item.outcome || 'Unknown'} · Updated {item.updatedAt}</p>
        <p>{item.reasons.join(' · ')}</p><details><summary>Source details</summary><p>{item.url}</p><p>{item.provenance}</p></details>
      </div>)}<p className="rc-muted">Merge or closure alone does not prove that a fix works in the current code.</p></fieldset>}
      {history === null && <p className="rc-error" role="alert">Correct the manual history numbers below before using suggestions.</p>}
      {combinedHistory.length > 10 && <p className="rc-error" role="alert">Choose at most 10 PRs and issues in total, including manual numbers.</p>}
      {hasSelection && <p className="rc-muted">Selected: scopes {chosenScopes.length}, documentation sources {chosenSources.length}, history items {selectedHistory.length}.
        {' '}Use selected suggestions or clear these choices before starting learning.</p>}
      <button type="button" className="rc-button" disabled={blocked || requiresReload || stale || !hasSelection || history === null || combinedHistory.length > 10}
        onClick={() => void perform(useSelected)}>Use selected suggestions</button>
    </div>}
  </section>;
}

function SuggestionEvidence({ reasons, evidence }: { reasons: string[]; evidence: ContextSourceEvidenceDto }) {
  return <><p>{reasons.join(' · ')}</p><details><summary>Repository evidence</summary><p>{evidence.provenance}</p>
    <pre className="rc-saved-version">{evidence.excerpt}</pre>{evidence.truncated && <p className="rc-muted">This excerpt is incomplete.</p>}
  </details></>;
}
