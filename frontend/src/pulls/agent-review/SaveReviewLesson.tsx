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
import ContextEntryEditor, { type ContextEntryDraft } from '../../repository-context/ContextEntryEditor';
import type { RepositoryNotebookDto, SaveRepositoryContextLessonRequest } from '../../repository-context/contracts';
import type { AgentReviewDto, ReviewMessageDto, ReviewRunDto } from './contracts';

function alreadySaved(notebook: RepositoryNotebookDto, request: SaveRepositoryContextLessonRequest): boolean {
  const expected = new Set([`message:${request.messageId}`, ...request.evidenceRefs.map(ref => `evidence:${ref}`)]);
  return notebook.entries.some(entry => {
    if (entry.kind !== 'MEMORY' || entry.scopeId !== request.scopeId || entry.title !== request.title
      || entry.summary !== request.summary || entry.body !== request.body || entry.sourceIds.length !== expected.size) return false;
    const captured = entry.sourceIds.map(id => notebook.sourceEvidence.find(source => source.id === id)?.reference);
    if (captured.some(source => source?.kind !== 'REVIEW' || source.reviewId !== request.reviewId || source.runId !== request.runId)) return false;
    const keys = new Set(captured.map(source => source?.kind === 'REVIEW'
      ? source.messageId !== null ? `message:${source.messageId}` : `evidence:${source.evidenceRef}` : ''));
    return keys.size === expected.size && [...keys].every(key => expected.has(key));
  });
}

/** A saved discussion is the source; the user supplies the conditional lesson and its scope. */
export default function SaveReviewLesson({ review, run, message }: { review: AgentReviewDto; run: ReviewRunDto; message: ReviewMessageDto }) {
  const [notebook, setNotebook] = useState<RepositoryNotebookDto | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reloadNeeded, setReloadNeeded] = useState(false);
  const [saved, setSaved] = useState(false);
  const [evidenceRefs, setEvidenceRefs] = useState<string[]>([]);
  const pending = useRef<SaveRepositoryContextLessonRequest | null>(null);
  const latestDraft = useRef<ContextEntryDraft | null>(null);
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const load = async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true); setError(null);
    try {
      const latest = await window.bridge.getRepositoryContext(review.workspaceId);
      if (!alive.current) return;
      if (pending.current !== null && alreadySaved(latest, pending.current)) {
        const accepted = pending.current;
        const draft = latestDraft.current;
        const changed = editing && draft !== null && (draft.scopeId !== accepted.scopeId || draft.title !== accepted.title
          || draft.summary !== accepted.summary || draft.body !== accepted.body || evidenceRefs.length !== accepted.evidenceRefs.length
          || evidenceRefs.some(ref => !accepted.evidenceRefs.includes(ref)));
        if (changed) {
          setNotebook(latest); setReloadNeeded(false);
          setNotice('The earlier lesson was saved. Your newer edits are still a draft. Saving again will create a separate lesson.');
        }
        else { setSaved(true); setEditing(false); }
        pending.current = null;
      }
      else {
        setNotebook(latest); setReloadNeeded(false); setEditing(true); setNotice(null);
      }
    }
    catch (cause) { if (alive.current) setError(cause instanceof Error ? cause.message : 'Could not load the repository notebook.'); }
    finally { inFlight.current = false; if (alive.current) setBusy(false); }
  };
  const save = async (draft: ContextEntryDraft) => {
    if (notebook === null || inFlight.current || reloadNeeded || message.runId !== run.id) return;
    const request: SaveRepositoryContextLessonRequest = { ...draft, expectedRevision: notebook.revision,
      reviewId: review.id, runId: run.id, messageId: message.id, evidenceRefs };
    if (alreadySaved(notebook, request)) { setSaved(true); setEditing(false); return; }
    latestDraft.current = draft;
    pending.current = request;
    inFlight.current = true;
    setBusy(true); setError(null);
    try {
      await window.bridge.saveRepositoryContextLesson(review.workspaceId, request);
      if (alive.current) { setSaved(true); setEditing(false); pending.current = null; }
    }
    catch (cause) {
      if (alive.current) {
        setError(cause instanceof Error ? cause.message : 'Could not save the lesson.');
        setReloadNeeded(true);
      }
    }
    finally { inFlight.current = false; if (alive.current) setBusy(false); }
  };

  if (saved) return <span className="agr-lesson-action" role="status">Lesson saved</span>;
  return <>
    <span className="agr-lesson-action">
      <button type="button" disabled={busy} onClick={() => { void load(); }}>{busy && !editing ? 'Loading notebook…' : 'Save lesson'}</button>
      {!editing && error !== null && <span role="alert">{error}</span>}
    </span>
    {editing && notebook !== null && <ContextEntryEditor title="Save lesson" confirmLabel="Save lesson"
      initialValue={{ scopeId: '', title: '', summary: '', body: message.body }} scopes={notebook.scopes}
      busy={busy} error={error} submitDisabled={reloadNeeded} onChange={draft => { latestDraft.current = draft; }}
      onCancel={() => { setEditing(false); setError(null); }} onSave={draft => { void save(draft); }}>
      <p className="rc-muted">Source: this saved private discussion from run {run.ordinal}. The original message is retained with the lesson.</p>
      {notice !== null && <p role="status">{notice}</p>}
      {run.evidence.length > 0 && <fieldset className="agr-lesson-evidence" disabled={busy}>
        <legend>Supporting evidence · {evidenceRefs.length} of 7 selected</legend>
        {run.evidence.map(item => <label key={item.ref}>
          <input type="checkbox" checked={evidenceRefs.includes(item.ref)} disabled={!evidenceRefs.includes(item.ref) && evidenceRefs.length >= 7}
            onChange={event => setEvidenceRefs(current => event.target.checked ? [...current, item.ref] : current.filter(ref => ref !== item.ref))} />
          <span><code>{item.ref}</code> · {item.source}</span>
        </label>)}
      </fieldset>}
      {reloadNeeded && <div className="agr-lesson-reload">
        <span>Your draft is kept. Reload the notebook, then review and save again.</span>
        <button type="button" className="rc-button" disabled={busy} onClick={() => { void load(); }}>Reload notebook</button>
      </div>}
    </ContextEntryEditor>}
  </>;
}
