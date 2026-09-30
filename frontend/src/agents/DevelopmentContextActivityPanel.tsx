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

import { useEffect, useState, type FormEvent } from 'react';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { CONTEXT_CHANGED } from '../repository-context/reviewCount';
import type { Bridge, DevelopmentContextActivityDto } from '../types';

type ActivityBridge = Pick<Bridge, 'getAgentTaskRepositoryContext'>;
type Card = DevelopmentContextActivityDto['cards'][number];
type Reporter = { taskId: string; workspaceId: string };

function label(card: Card): string {
  if (card.kind === 'OFFER') {
    if (card.entries.length === 0) return 'No notes selected';
    return card.entries.some(entry => entry.included) ? 'Notes offered' : 'Note pointers offered';
  }
  if (card.kind === 'SEARCH') return 'Agent searched notes';
  return 'Exact note lookup';
}

function delivery(card: Card): string {
  if (card.delivery === 'CONFIRMED') {
    if (card.kind === 'OFFER') {
      if (card.entries.length === 0) return 'Turn accepted; no note provided';
      if (card.entries.every(entry => entry.included)) return 'Full note text provided';
      return card.entries.some(entry => entry.included) ? 'Note text and pointers delivered' : 'Pointers delivered';
    }
    if (card.kind === 'SEARCH') return card.entries.length > 0 ? 'Results delivered' : 'No match delivered';
    return 'Note delivered';
  }
  if (card.delivery === 'PREPARED') return 'Prepared; delivery unconfirmed';
  if (card.delivery === 'UNCERTAIN') return 'Delivery uncertain';
  return 'Delivery unknown';
}

/** The user's Wrong on a note version this task received in full or opened; the reason is optional. */
function ReportWrong({ reporter, entry }: { reporter: Reporter; entry: Card['entries'][number] }) {
  const [form, setForm] = useState<{ reason: string; busy: boolean; error: string | null } | null>(null);
  const [done, setDone] = useState(false);
  if (done) return <small role="status">Reported. Agents that open this note see the report.</small>;
  if (form === null) {
    return <button type="button" className="agt-link agt-context__wrong" aria-label={`Report ${entry.title || entry.id} v${entry.version} as wrong`}
      onClick={() => setForm({ reason: '', busy: false, error: null })}>Wrong</button>;
  }
  const submit = (event: FormEvent): void => {
    event.preventDefault();
    setForm({ ...form, busy: true, error: null });
    void Promise.resolve().then(() => window.bridge.reportRepositoryContextNote(reporter.workspaceId,
      { taskId: reporter.taskId, entryId: entry.id, entryVersion: entry.version, reason: form.reason.trim() }))
      .then(() => {
        setDone(true);
        window.dispatchEvent(new CustomEvent(CONTEXT_CHANGED, { detail: reporter.workspaceId }));
      })
      .catch(cause => setForm({ ...form, busy: false, error: remoteErrorMessage(cause) }));
  };
  return <form className="agt-context__report" onSubmit={submit}>
    <input className="bq-input" value={form.reason} maxLength={300} placeholder="What’s wrong? (optional)" aria-label="Why the note is wrong (optional)"
      disabled={form.busy} onChange={event => setForm({ ...form, reason: event.target.value })} />
    <button type="submit" className="bq-button" disabled={form.busy}>Report</button>
    <button type="button" className="bq-button bq-button--ghost" disabled={form.busy} onClick={() => setForm(null)}>Cancel</button>
    {form.error !== null && <small role="alert">{form.error}</small>}
  </form>;
}

/** With a reporter, each note version the task received in full or opened offers Wrong; search hits and pointers were never read. */
export function ActivityCard({ card, reporter }: { card: Card; reporter?: Reporter }) {
  return <li className="agt-context-card">
    <div className="agt-context-card__heading">
      <strong>{label(card)}</strong>
      <span>{delivery(card)}</span>
    </div>
    {card.kind === 'SEARCH' && (card.query || card.paths.length > 0) && <p className="agt-context-card__hint">
      {card.query && <>Topic: {card.query}</>}
      {card.paths.length > 0 && <> · Paths: {card.paths.join(', ')}</>}
    </p>}
    {card.entries.length > 0 ? <ul className="agt-context-card__entries">
      {card.entries.map(entry => <li key={`${entry.id}:${entry.version}`}>
        <strong>{entry.title || entry.id}</strong> <span>v{entry.version} · {entry.kind.toLowerCase()}</span>
        {reporter !== undefined && (card.kind === 'READ' || (card.kind === 'OFFER' && entry.included)) && <ReportWrong reporter={reporter} entry={entry} />}
        {entry.matchReasons.length > 0 && <small>Matched: {entry.matchReasons.join('; ')}</small>}
        {card.kind === 'OFFER' && !entry.included && <small>Exact-read pointer; full note not included</small>}
      </li>)}
    </ul> : <p className="agt-context-card__hint">{card.kind === 'SEARCH' ? 'No matching notes in this search.' : 'No notes selected.'}</p>}
    {card.omittedCount > 0 && <p className="agt-context-card__hint">{card.omittedCount} more matched notes omitted by the size limit.</p>}
    {card.scopeStates.filter(scope => scope.missingKnowledge).map(scope =>
      <p className="agt-context-card__hint" key={scope.scopeId}>Missing knowledge for {scope.scopeId}.</p>)}
    {card.warnings.map(warning => <p className="agt-context-card__hint" key={warning}>{warning}</p>)}
    <small>Notebook revision {card.notebookRevision} · {new Date(card.createdAt).toLocaleString()}</small>
  </li>;
}

/** Task-visible receipt list. It deliberately renders metadata only, never saved note bodies. */
export default function DevelopmentContextActivityPanel({ taskId, workspaceId, refreshKey, bridge }: {
  taskId: string; workspaceId: string; refreshKey: string; bridge: ActivityBridge;
}) {
  const [activity, setActivity] = useState<DevelopmentContextActivityDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    let active = true;
    void bridge.getAgentTaskRepositoryContext(taskId, workspaceId)
      .then(value => { if (active) { setActivity(value); setError(null); } })
      .catch(() => { if (active) setError('Could not load repository note activity.'); });
    return () => { active = false; };
  }, [bridge, taskId, workspaceId, refreshKey, retry, open]);

  return <section className="agt-context" aria-label="Repository note activity">
    <details onToggle={event => setOpen(event.currentTarget.open)}>
      <summary>Repository notes{activity !== null && activity.cards.length > 0 ? ` · ${activity.cards.length} activities` : ''}</summary>
      {open && activity === null && error === null && <p>Loading repository note activity…</p>}
      {error !== null && <p role="alert">{error} <button type="button" onClick={() => setRetry(value => value + 1)}>Retry</button></p>}
      {activity !== null && <>
        {activity.cards.length === 0 && <p>No repository note activity has been saved for this task.</p>}
        {activity.cards.length > 0 && <ol className="agt-context__cards">{activity.cards.map(card =>
          <ActivityCard card={card} reporter={{ taskId, workspaceId }} key={card.reference} />)}</ol>}
        {activity.hasMore && <p>Showing the latest 100 activities.</p>}
        {activity.usageTruncated && <p>Some delivery states may be unknown because this view reads only the latest usage facts.</p>}
      </>}
    </details>
  </section>;
}
