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
import { useState } from 'react';
import MarkdownBody from '../pulls/MarkdownBody';
import { relativeTime } from '../relativeTime';
import type { ContextEntryDto, RepositoryNotebookDto } from './contracts';
import { Coded } from './ContextOverview';
import { Icon } from './icons';
import {
  CLAIM_TAG, draftOrigin, entryOrigin, entryTimeKey, kindLabel, noteArea, noteDiff, shortDate, shortSha, sourceView,
} from './model';
import type { ContextActions, ContextData } from './RepositoryContextPage';

export type NoteTarget = { kind: 'draft' | 'entry'; id: string };
type Edit = { title: string; summary: string; body: string };

const bytes = (value: string) => new TextEncoder().encode(value).length;
const invalid = (edit: Edit) => edit.title.trim() === '' || bytes(edit.title) > 200 || edit.summary.trim() === '' || bytes(edit.summary) > 1000
  || edit.body.trim() === '' || bytes(edit.body) > 8192;

/** One note or draft: its content and evidence, and the explicit review actions. None of them starts an agent run. */
export default function ContextNoteDrawer({ data, actions, busy, target, onTarget, onClose }: {
  data: ContextData; actions: ContextActions; busy: boolean; target: NoteTarget;
  onTarget: (target: NoteTarget | null) => void; onClose: () => void;
}) {
  const { notebook, times, workspaceId } = data;
  const [view, setView] = useState<'changes' | 'draft' | 'active'>('changes');
  const [editing, setEditing] = useState<Edit | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const draft = target.kind === 'draft' ? notebook.pendingDrafts.find(value => value.id === target.id) : undefined;
  const approved = target.kind === 'entry' ? notebook.entries.find(value => value.id === target.id) : undefined;
  const entry = draft?.entry ?? approved;
  const close = <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>;
  if (entry === undefined) {
    return <>
      <div className="rcp-drawer__head"><div className="rcp-drawer__top"><span className="rcp-drawer__name">Note</span><span className="rcp-spacer" />{close}</div></div>
      <div className="rcp-drawer__body"><div className="rcp-empty">This {target.kind === 'draft' ? 'draft is no longer waiting for review' : 'note is no longer in the notebook'}.</div></div>
    </>;
  }
  const active = draft !== undefined && draft.replacesVersion !== null ? notebook.entries.find(value => value.id === draft.entry.id) : undefined;
  const isUpdate = active !== undefined;
  const nextVersion = notebook.revision + 1;
  const pending = notebook.pendingDrafts;
  const index = draft === undefined ? -1 : pending.findIndex(value => value.id === draft.id);
  const shown = isUpdate && view === 'active' ? active : entry;
  const origin = draft !== undefined ? draftOrigin(draft, times, data.build.runs, data.titles) : entryOrigin(approved, times, data.build.runs, data.titles);
  const at = draft !== undefined ? times.drafts[draft.id]?.at : times.entries[entryTimeKey(approved)]?.at;
  const mergedWork = origin.startsWith('Learning from merged PR');
  const area = noteArea(notebook, entry.scopeId);
  const chip = draft !== undefined ? (isUpdate ? { text: `Update draft · v${active.version} stays in use`, tone: 'amber' } : { text: 'Draft · not available to agents', tone: 'amber' })
    : !approved.enabled ? { text: 'Disabled · not available', tone: 'gray' } : { text: 'Available to agents', tone: 'green' };

  const advance = () => {
    const rest = pending.filter(value => value.id !== draft?.id);
    const next = rest[Math.min(Math.max(index, 0), rest.length - 1)];
    setEditing(null);
    setConfirming(false);
    setView('changes');
    onTarget(next === undefined ? null : { kind: 'draft', id: next.id });
  };
  const act = (operation: (revision: number) => Promise<RepositoryNotebookDto>, message: string, then: () => void): void => void actions.perform(async () => {
    setError(null);
    try {
      const saved = await operation(notebook.revision);
      actions.setNotebook(saved);
      await actions.reload();
      actions.say(message);
      actions.announce();
      then();
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
      await actions.reload().catch(() => {});
    }
  });
  const approveMessage = isUpdate ? `v${nextVersion} approved · replaces v${active.version} for agents · no agent run`
    : 'Approved · available to agents on their next lookup · no agent run';
  const approve = () => act(revision => window.bridge.approveRepositoryContextDraft(workspaceId, { expectedRevision: revision, draftId: draft.id }), approveMessage, advance);
  const dismiss = () => act(revision => window.bridge.dismissRepositoryContextDraft(workspaceId, { expectedRevision: revision, draftId: draft.id }),
    isUpdate ? `Update dismissed · v${active.version} stays in use` : 'Draft dismissed · never shown to agents', advance);
  const toggle = () => act(revision => window.bridge.setRepositoryContextEntryEnabled(workspaceId, { expectedRevision: revision, entryId: approved.id, enabled: !approved.enabled }),
    approved.enabled ? 'Disabled · agents can’t find it · note kept' : 'Enabled · available to agents again', () => {});
  const save = (edit: Edit) => {
    if (draft !== undefined) {
      act(async revision => {
        const edited = await window.bridge.editRepositoryContextDraft(workspaceId, { expectedRevision: revision, draftId: draft.id, title: edit.title.trim(),
          summary: edit.summary, body: edit.body, claims: draft.entry.claims });
        return window.bridge.approveRepositoryContextDraft(workspaceId, { expectedRevision: edited.revision, draftId: draft.id });
      }, approveMessage, advance);
    }
    else {
      act(revision => window.bridge.editRepositoryContextEntry(workspaceId, { expectedRevision: revision, id: approved.id, scopeId: approved.scopeId,
        title: edit.title.trim(), summary: edit.summary, body: edit.body, claims: approved.claims, sourceIds: approved.sourceIds }),
      `Saved as v${nextVersion} · agents see it on their next lookup`, () => setEditing(null));
    }
  };
  const usage = data.usage?.entries.find(row => row.entryId === entry.id && row.currentVersion === entry.version);
  const diff = isUpdate ? noteDiff(active, draft.entry) : null;
  const sources = shown.sourceIds.map(id => ({ id, ...sourceView(notebook.sourceEvidence.find(value => value.id === id), id) }));
  const refs = (sourceIds: string[]) => sourceIds.map(id => shown.sourceIds.indexOf(id) + 1).filter(n => n > 0);

  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top">
        <span className={`rcp-chip rcp-tone--${entry.kind === 'MEMORY' ? 'purple' : 'gray'}`}>{kindLabel(entry.kind)}</span>
        <span className={`rcp-chip rcp-tone--${chip.tone}`}>{chip.text}</span>
        <span className="rcp-spacer" />
        {draft !== undefined && pending.length > 1 && <>
          <span className="rcp-pager">{index + 1} of {pending.length} to review</span>
          <button type="button" className="rcp-pager-btn" aria-label="Previous item" disabled={index <= 0}
            onClick={() => { setEditing(null); setConfirming(false); setView('changes'); onTarget({ kind: 'draft', id: pending[index - 1].id }); }}>‹</button>
          <button type="button" className="rcp-pager-btn" aria-label="Next item" disabled={index >= pending.length - 1}
            onClick={() => { setEditing(null); setConfirming(false); setView('changes'); onTarget({ kind: 'draft', id: pending[index + 1].id }); }}>›</button>
        </>}
        {close}
      </div>
      <div className="rcp-note-title">{shown.title}</div>
      <div className="rcp-applies"><span>Applies to</span>{area.paths.map(path => <span className="rcp-path-chip" key={path}>{path}</span>)}</div>
      <div className="rcp-origin">From {origin}{at ? ` · ${relativeTime(at, { suffix: false, now: data.now }) === 'now' ? 'just now' : `${relativeTime(at, { suffix: false, now: data.now })} ago`}` : ''}</div>
      {isUpdate && editing === null && <div className="rcp-seg rcp-seg--drawer" role="tablist" aria-label="Compare versions">
        {([['changes', 'Changes'], ['draft', `Draft v${nextVersion}`], ['active', `Approved v${active.version}`]] as const).map(([id, label]) =>
          <button type="button" role="tab" key={id} aria-selected={view === id} onClick={() => setView(id)}>{label}</button>)}
      </div>}
    </div>
    <div className="rcp-drawer__body">
      {editing === null ? <>
        {mergedWork && <div className="rcp-caveat">Learned from merged work. A merged PR records a decision, not proof that every claim holds
          {draft !== undefined ? ` — the agent checked these claims against code at ${shortSha(draft.baselineSha)}.` : '.'}</div>}
        {isUpdate && view === 'changes' ? <>
          <div className="rcp-diff-summary">{diff.summary}</div>
          <div className="rcp-diff">{diff.lines.map((line, position) => <div key={position}
            className={`rcp-diff__line${line.sign === '+' ? ' is-add' : line.sign === '−' ? ' is-del' : ''}`}>
            <span className="rcp-diff__sign">{line.sign}</span><span className="rcp-diff__text"><Coded text={line.text} /></span></div>)}</div>
        </> : <NoteContent entry={shown} repository={data.repository} refs={refs} note={isUpdate && view === 'active' ? `Approved v${active.version} — what agents use today` : null} />}
        <div className="rcp-drawer-label">SOURCES · {sources.length}</div>
        <div className="rcp-sources">
          {sources.length === 0 && <div className="rcp-source"><span className="rcp-source__sub">No supporting source is attached.</span></div>}
          {sources.map((source, position) => <div className="rcp-source" key={source.id}>
            <span className="rcp-source__n">[{position + 1}]</span>
            <span className="rcp-source__main"><span className="rcp-source__target">{source.target}</span><span className="rcp-source__sub">{source.kind} · {source.detail}</span></span>
            {source.capture && <span className={`rcp-source__cap${source.agent ? ' is-agent' : ''}`}>{source.capture}</span>}
          </div>)}
        </div>
        {approved !== undefined && <>
          <div className="rcp-drawer-label">USAGE · LAST 30 DAYS</div>
          <div className="rcp-usage-line">{usage !== undefined && (usage.foundTasks || usage.openedTasks)
            ? `Found by ${usage.foundTasks} ${usage.foundTasks === 1 ? 'task' : 'tasks'} · opened by ${usage.openedTasks}`
              + (usage.lastConfirmedUseAt ? ` · last opened ${shortDate(usage.lastConfirmedUseAt, data.now)}` : '')
            : 'Not found or opened by any task yet'}</div>
          <div className="rcp-usage-note">Counts record lookups in retained tasks — not whether the note applied or changed a result.{' '}
            <button type="button" className="rcp-link" onClick={() => actions.openDrawer({ kind: 'usage' })}>All usage</button></div>
        </>}
      </> : <>
        <label className="rcp-field">Title<input value={editing.title} onChange={event => setEditing({ ...editing, title: event.target.value })} /></label>
        <label className="rcp-field">Summary<textarea rows={3} value={editing.summary} onChange={event => setEditing({ ...editing, summary: event.target.value })} /></label>
        <label className="rcp-field">Details<textarea className="is-mono" rows={14} value={editing.body} onChange={event => setEditing({ ...editing, body: event.target.value })} /></label>
        <div className="rcp-hint">“## ” starts a section, “- ” a list item. Sources and supporting claims stay attached to the note.</div>
      </>}
    </div>
    <div className="rcp-drawer__foot">
      {error !== null && <div className="rcp-drawer-error" role="alert">{error}</div>}
      <Availability draft={draft !== undefined} activeVersion={active?.version ?? null} entry={approved} approvedAt={approved ? times.entries[entryTimeKey(approved)]?.at : undefined} now={data.now} />
      {editing !== null ? <div className="rcp-drawer-actions">
        <span className="rcp-drawer-actions__text is-hint">{draft !== undefined ? 'Saving your edit approves it — it becomes available to agents. No agent run.'
          : `Agents see v${nextVersion} on their next lookup.`}</span>
        <button type="button" className="rcp-btn" disabled={busy} onClick={() => setEditing(null)}>Cancel</button>
        <button type="button" className="rcp-btn rcp-btn--primary" disabled={busy || invalid(editing)} onClick={() => save(editing)}>
          {draft !== undefined ? 'Save and approve' : `Save as v${nextVersion}`}</button>
      </div> : confirming && draft !== undefined ? <div className="rcp-drawer-actions">
        <span className="rcp-drawer-actions__text">{isUpdate ? `Dismiss this update? v${active.version} stays in use.` : 'Dismiss this draft? It’s removed from review and never shown to agents.'}</span>
        <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy} onClick={() => setConfirming(false)}>Keep</button>
        <button type="button" className="rcp-btn rcp-btn--sm rcp-btn--solid-danger" disabled={busy} onClick={dismiss}>{isUpdate ? 'Dismiss update' : 'Dismiss'}</button>
      </div> : <div className="rcp-drawer-actions">
        {draft !== undefined && <button type="button" className="rcp-btn rcp-btn--danger" disabled={busy} onClick={() => setConfirming(true)}>{isUpdate ? 'Dismiss update' : 'Dismiss'}</button>}
        {approved !== undefined && <button type="button" className="rcp-btn" disabled={busy} onClick={toggle}>{approved.enabled ? 'Disable' : 'Enable'}</button>}
        <span className="rcp-spacer" />
        <button type="button" className="rcp-btn rcp-btn--secondary" disabled={busy}
          onClick={() => setEditing({ title: entry.title, summary: entry.summary, body: entry.body })}>Edit</button>
        {draft !== undefined && <button type="button" className="rcp-btn rcp-btn--primary" disabled={busy} onClick={approve}>{isUpdate ? `Approve v${nextVersion}` : 'Approve and use'}</button>}
      </div>}
    </div>
  </>;
}

function Availability({ draft, activeVersion, entry, approvedAt, now }: { draft: boolean; activeVersion: number | null; entry: ContextEntryDto | undefined;
  approvedAt: string | undefined; now: number }) {
  const [dot, live, text] = draft ? ['#bf8700', false, activeVersion !== null
    ? `v${activeVersion} stays available to agents until you approve this update. No agent run.` : 'Saved draft — agents can’t see it until you approve. Approving starts no agent run.']
    : !entry.enabled ? ['#8c959f', false, 'Disabled — agents can’t find it. Enable to make it available again.']
      : ['#1a7f37', true, `Available to development and review agents · v${entry.version}${approvedAt ? ` · approved ${shortDate(approvedAt, now)}` : ''}`];
  return <div className={`rcp-avail${live ? ' is-live' : ''}`}><span className="rcp-avail__dot" style={{ background: dot }} /><span>{text}</span></div>;
}

function NoteContent({ entry, repository, refs, note }: { entry: ContextEntryDto; repository: string; refs: (ids: string[]) => number[]; note: string | null }) {
  return <>
    {note !== null && <div className="rcp-view-note">{note}</div>}
    <div className="rcp-summary"><Coded text={entry.summary} /></div>
    {entry.body.trim() !== '' && <div className="rcp-body"><MarkdownBody body={entry.body} repository={repository} /></div>}
    {entry.claims.length > 0 && <>
      <div className="rcp-section-title">Claims</div>
      {entry.claims.map((claim, index) => {
        const tag = CLAIM_TAG[claim.basis];
        return <div className="rcp-claim" key={index}><span className="rcp-claim__mark">•</span>
          <span className="rcp-claim__text"><Coded text={claim.text} />
            <span className={`rcp-claim__tag${tag.tone === 'muted' ? '' : ` is-${tag.tone}`}`}>{tag.label}</span>
            {refs(claim.sourceIds).map(n => <span className="rcp-claim__ref" key={n}>[{n}]</span>)}</span></div>;
      })}
    </>}
  </>;
}
