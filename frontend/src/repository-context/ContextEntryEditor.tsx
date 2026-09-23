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
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { X } from 'lucide-react';
import type { ContextScopeDto } from './contracts';
import '../css/repository-context.css';

export type ContextEntryDraft = { scopeId: string; title: string; summary: string; body: string };

function completedDraft(value: ContextEntryDraft, optionalTitle: boolean): ContextEntryDraft {
  if (!optionalTitle || value.title.trim() !== '') return value;
  return { ...value, title: Array.from(value.summary.trim().replace(/\s+/g, ' ')).slice(0, 50).join('').trimEnd() };
}

/** Native modal behavior keeps keyboard focus inside the editor and restores it on close. */
export function ContextDialog({ title, busy, onCancel, children }: {
  title: string; busy: boolean; onCancel: () => void; children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);
  return <dialog ref={dialog} className="rc-dialog" aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); if (!busy) onCancel(); }}>
    <header><h2 id={titleId}>{title}</h2><button type="button" className="rc-close" aria-label="Close editor" disabled={busy} onClick={onCancel}><X size={16} /></button></header>
    {children}
  </dialog>;
}

export default function ContextEntryEditor({ initialValue, scopes, scopeLocked = false, submitDisabled = false, optionalTitle = false, busy, error, onSave, onCancel,
  title = 'Edit note', confirmLabel = 'Save', children, onChange }: {
  initialValue: ContextEntryDraft; scopes: ContextScopeDto[]; scopeLocked?: boolean; submitDisabled?: boolean; optionalTitle?: boolean; busy: boolean; error: string | null;
  onSave: (value: ContextEntryDraft) => void; onCancel: () => void; title?: string; confirmLabel?: string; children?: ReactNode;
  onChange?: (value: ContextEntryDraft) => void;
}) {
  const [draft, setDraft] = useState(initialValue);
  const change = (value: ContextEntryDraft) => { setDraft(value); onChange?.(completedDraft(value, optionalTitle)); };
  const bytes = (value: string) => new TextEncoder().encode(value).length;
  const savedDraft = completedDraft(draft, optionalTitle);
  const invalid = savedDraft.title.trim() === '' || bytes(savedDraft.title) > 200 || draft.summary.trim() === '' || bytes(draft.summary) > 1000
    || draft.body.trim() === '' || bytes(draft.body) > 8192 || !scopes.some(scope => scope.id === draft.scopeId);
  return <ContextDialog title={title} busy={busy} onCancel={onCancel}>
    <form onSubmit={event => { event.preventDefault(); if (!invalid && !busy && !submitDisabled) onSave(savedDraft); }}>
      <div className="rc-dialog-body">
        <p className="rc-muted">{optionalTitle
          ? 'Choose where this lesson applies, then summarize when it matters and what to check. The scope decides when it can match; words in the title and summary help rank it for future reviews.'
          : 'Keep this note specific to the conditions where it applies. Saved notes guide future reviews; they are not verified facts.'}</p>
        {error !== null && <div className="rc-error" role="alert">{error}</div>}
        <label>Applies to<select value={draft.scopeId} disabled={busy || scopeLocked} required onChange={event => change({ ...draft, scopeId: event.target.value })}>
          {draft.scopeId === '' && <option value="" disabled>Choose where this lesson applies</option>}
          {scopes.map(scope => <option key={scope.id} value={scope.id}>{scope.title}{scope.enabled ? '' : ' (disabled)'}</option>)}
        </select></label>
        {scopes.find(scope => scope.id === draft.scopeId)?.match.kind === 'REPOSITORY'
          && <p className="rc-muted">This scope applies across the repository. Choose a component scope for a more specific note.</p>}
        <label>Title<input value={draft.title} disabled={busy} onChange={event => change({ ...draft, title: event.target.value })}
          placeholder={optionalTitle ? 'Optional; use the summary if blank' : undefined} required={!optionalTitle} /></label>
        {optionalTitle && <p className="rc-muted">{savedDraft.title && !draft.title.trim()
          ? `Leave blank to name this lesson “${savedDraft.title}”.`
          : 'Leave blank to name this lesson from the summary.'}</p>}
        <span className={'rc-count' + (bytes(savedDraft.title) > 200 ? ' is-over' : '')}>
          {optionalTitle && !draft.title.trim() ? 'Generated title: ' : ''}{bytes(savedDraft.title)} / 200 bytes
        </span>
        <label>Summary<textarea rows={2} value={draft.summary} disabled={busy} onChange={event => change({ ...draft, summary: event.target.value })}
          placeholder={optionalTitle ? 'When changing …, check …' : undefined} required /></label>
        <span className={'rc-count' + (bytes(draft.summary) > 1000 ? ' is-over' : '')}>{bytes(draft.summary)} / 1,000 bytes</span>
        <label>Details<textarea rows={7} value={draft.body} disabled={busy} onChange={event => change({ ...draft, body: event.target.value })} required /></label>
        <span className={'rc-count' + (bytes(draft.body) > 8192 ? ' is-over' : '')}>{bytes(draft.body)} / 8,192 bytes · Markdown supported</span>
        {children}
      </div>
      <footer><button type="button" className="rc-button" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="submit" className="rc-button is-primary" disabled={busy || invalid || submitDisabled}>{busy ? 'Saving…' : confirmLabel}</button></footer>
    </form>
  </ContextDialog>;
}
