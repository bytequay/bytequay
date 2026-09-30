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
import { CLAIM_TAG, entryOrigin, entryTimeKey, kindLabel, noteArea, noteDiff, previousVersion, shortDate, sourceView } from './model';
import type { ContextActions, ContextData } from './RepositoryContextPage';

type Edit = { title: string; summary: string; body: string };

const bytes = (value: string) => new TextEncoder().encode(value).length;
const invalid = (edit: Edit) => edit.title.trim() === '' || bytes(edit.title) > 200 || edit.summary.trim() === '' || bytes(edit.summary) > 1000
  || edit.body.trim() === '' || bytes(edit.body) > 8192;

/** One note: its content and evidence, and the explicit actions on it. None of them starts an agent run. */
export default function ContextNoteDrawer({ data, actions, busy, id, onClose }: {
  data: ContextData; actions: ContextActions; busy: boolean; id: string; onClose: () => void;
}) {
  const { notebook, times, workspaceId } = data;
  const [view, setView] = useState<'current' | 'changes' | 'previous'>('current');
  const [editing, setEditing] = useState<Edit | null>(null);
  const [error, setError] = useState<string | null>(null);
  const entry = notebook.entries.find(value => value.id === id);
  const close = <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>;
  if (entry === undefined) {
    return <>
      <div className="rcp-drawer__head"><div className="rcp-drawer__top"><span className="rcp-drawer__name">Note</span><span className="rcp-spacer" />{close}</div></div>
      <div className="rcp-drawer__body"><div className="rcp-empty">This note is no longer in the notebook.</div></div>
    </>;
  }
  const previous = previousVersion(notebook, entry.id);
  const nextVersion = notebook.revision + 1;
  const shown = previous !== undefined && view === 'previous' ? previous : entry;
  const origin = entryOrigin(entry, times, data.build.runs, data.titles);
  const at = times.entries[entryTimeKey(entry)]?.at;
  const area = noteArea(notebook, entry.scopeId);
  const chip = !entry.enabled ? { text: 'Disabled · not available', tone: 'gray' } : { text: 'Available to agents', tone: 'green' };

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
  const toggle = () => act(revision => window.bridge.setRepositoryContextEntryEnabled(workspaceId, { expectedRevision: revision, entryId: entry.id, enabled: !entry.enabled }),
    entry.enabled ? 'Disabled · agents can’t find it · note kept' : 'Enabled · available to agents again', () => {});
  const restore = () => act(revision => window.bridge.restoreRepositoryContextEntry(workspaceId, { expectedRevision: revision, entryId: entry.id }),
    'Previous version restored · agents see it on their next lookup · no agent run', () => setView('current'));
  const save = (edit: Edit) => act(revision => window.bridge.editRepositoryContextEntry(workspaceId, { expectedRevision: revision, id: entry.id, scopeId: entry.scopeId,
    title: edit.title.trim(), summary: edit.summary, body: edit.body, claims: entry.claims, sourceIds: entry.sourceIds }),
  `Saved as v${nextVersion} · agents see it on their next lookup`, () => setEditing(null));
  const usage = data.usage?.entries.find(row => row.entryId === entry.id && row.currentVersion === entry.version);
  const diff = previous !== undefined && view === 'changes' ? noteDiff(previous, entry) : null;
  const sources = shown.sourceIds.map(sourceId => ({ id: sourceId, ...sourceView(notebook.sourceEvidence.find(value => value.id === sourceId), sourceId) }));
  const refs = (sourceIds: string[]) => sourceIds.map(sourceId => shown.sourceIds.indexOf(sourceId) + 1).filter(n => n > 0);

  return <>
    <div className="rcp-drawer__head">
      <div className="rcp-drawer__top">
        <span className={`rcp-chip rcp-tone--${entry.kind === 'MEMORY' ? 'purple' : 'gray'}`}>{kindLabel(entry.kind)}</span>
        <span className={`rcp-chip rcp-tone--${chip.tone}`}>{chip.text}</span>
        <span className="rcp-spacer" />
        {close}
      </div>
      <div className="rcp-note-title">{shown.title}</div>
      <div className="rcp-applies"><span>Notes apply to</span>{area.paths.map(path => <span className="rcp-path-chip" key={path}>{path}</span>)}</div>
      <div className="rcp-origin">From {origin}{at ? ` · ${relativeTime(at, { suffix: false, now: data.now }) === 'now' ? 'just now' : `${relativeTime(at, { suffix: false, now: data.now })} ago`}` : ''}</div>
      {previous !== undefined && editing === null && <div className="rcp-seg rcp-seg--drawer" role="tablist" aria-label="Compare versions">
        {([['current', `Current v${entry.version}`], ['changes', 'Changes'], ['previous', `Previous v${previous.version}`]] as const).map(([tab, label]) =>
          <button type="button" role="tab" key={tab} aria-selected={view === tab} onClick={() => setView(tab)}>{label}</button>)}
      </div>}
    </div>
    <div className="rcp-drawer__body">
      {editing === null ? <>
        {origin.startsWith('Learning from merged PR') && <div className="rcp-caveat">Learned from merged work. A merged PR records a decision, not proof that every claim holds.</div>}
        {diff !== null ? <>
          <div className="rcp-diff-summary">{diff.summary}</div>
          <div className="rcp-diff">{diff.lines.map((line, position) => <div key={position}
            className={`rcp-diff__line${line.sign === '+' ? ' is-add' : line.sign === '−' ? ' is-del' : ''}`}>
            <span className="rcp-diff__sign">{line.sign}</span><span className="rcp-diff__text"><Coded text={line.text} /></span></div>)}</div>
        </> : <NoteContent entry={shown} repository={data.repository} refs={refs} />}
        <div className="rcp-drawer-label">SOURCES · {sources.length}</div>
        <div className="rcp-sources">
          {sources.length === 0 && <div className="rcp-source"><span className="rcp-source__sub">No supporting source is attached.</span></div>}
          {sources.map((source, position) => <div className="rcp-source" key={source.id}>
            <span className="rcp-source__n">[{position + 1}]</span>
            <span className="rcp-source__main"><span className="rcp-source__target">{source.target}</span><span className="rcp-source__sub">{source.kind} · {source.detail}</span></span>
            {source.capture && <span className={`rcp-source__cap${source.agent ? ' is-agent' : ''}`}>{source.capture}</span>}
          </div>)}
        </div>
        <div className="rcp-drawer-label">USAGE · LAST 30 DAYS</div>
        <div className="rcp-usage-line">{usage !== undefined && (usage.foundTasks || usage.openedTasks)
          ? `Found by ${usage.foundTasks} ${usage.foundTasks === 1 ? 'task' : 'tasks'} · opened by ${usage.openedTasks}`
            + (usage.lastConfirmedUseAt ? ` · last opened ${shortDate(usage.lastConfirmedUseAt, data.now)}` : '')
          : 'Not found or opened by any task yet'}</div>
        <div className="rcp-usage-note">Counts record lookups in retained tasks — not whether the note applied or changed a result.{' '}
          <button type="button" className="rcp-link" onClick={() => actions.openDrawer({ kind: 'usage' })}>All usage</button></div>
      </> : <>
        <label className="rcp-field">Title<input value={editing.title} onChange={event => setEditing({ ...editing, title: event.target.value })} /></label>
        <label className="rcp-field">Summary<textarea rows={3} value={editing.summary} onChange={event => setEditing({ ...editing, summary: event.target.value })} /></label>
        <label className="rcp-field">Details<textarea className="is-mono" rows={14} value={editing.body} onChange={event => setEditing({ ...editing, body: event.target.value })} /></label>
        <div className="rcp-hint">“## ” starts a section, “- ” a list item. Sources and supporting claims stay attached to the note.</div>
      </>}
    </div>
    <div className="rcp-drawer__foot">
      {error !== null && <div className="rcp-drawer-error" role="alert">{error}</div>}
      <Availability entry={entry} savedAt={at} now={data.now} />
      {editing !== null ? <div className="rcp-drawer-actions">
        <span className="rcp-drawer-actions__text is-hint">Agents see v{nextVersion} on their next lookup.</span>
        <button type="button" className="rcp-btn" disabled={busy} onClick={() => setEditing(null)}>Cancel</button>
        <button type="button" className="rcp-btn rcp-btn--primary" disabled={busy || invalid(editing)} onClick={() => save(editing)}>Save as v{nextVersion}</button>
      </div> : <div className="rcp-drawer-actions">
        <button type="button" className="rcp-btn" disabled={busy} onClick={toggle}>{entry.enabled ? 'Disable' : 'Enable'}</button>
        <span className="rcp-spacer" />
        {previous !== undefined && <button type="button" className="rcp-btn rcp-btn--secondary" disabled={busy} onClick={restore}>Restore previous version</button>}
        <button type="button" className="rcp-btn rcp-btn--secondary" disabled={busy}
          onClick={() => setEditing({ title: entry.title, summary: entry.summary, body: entry.body })}>Edit</button>
      </div>}
    </div>
  </>;
}

function Availability({ entry, savedAt, now }: { entry: ContextEntryDto; savedAt: string | undefined; now: number }) {
  const [dot, live, text] = !entry.enabled ? ['#8c959f', false, 'Disabled — agents can’t find it. Enable to make it available again.']
    : ['#1a7f37', true, `Available to development and review agents · v${entry.version}${savedAt ? ` · saved ${shortDate(savedAt, now)}` : ''}`];
  return <div className={`rcp-avail${live ? ' is-live' : ''}`}><span className="rcp-avail__dot" style={{ background: dot }} /><span>{text}</span></div>;
}

function NoteContent({ entry, repository, refs }: { entry: ContextEntryDto; repository: string; refs: (ids: string[]) => number[] }) {
  return <>
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
