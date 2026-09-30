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
import { useEffect, useState } from 'react';
import { FileText } from 'lucide-react';
import { Card, CardAge, ItemRow, Pill } from '../cards/Card';
import { workspaceRoute } from '../workspaces/model';
import type { ContextEntryDto, RepositoryNotebookDto } from './contracts';
import { kindLabel, noteArea, notesSavedBy, plural } from './model';
import { CONTEXT_CHANGED, type ContextReview } from './reviewCount';
import '../css/repository-context.css';

const open = (workspaceId: string) => { window.location.hash = workspaceRoute(workspaceId, 'context'); };

/** Home's Needs attention row for source proposals and reports on notes the user wrote; it opens the Overview, where Needs you lists them. */
export function ContextReviewRow({ workspaceId, review }: { workspaceId: string; review: ContextReview }) {
  const title = `${[review.sources ? plural(review.sources, 'source') : '', review.reports ? plural(review.reports, 'reported note') : ''].filter(Boolean).join(' · ')} to review`;
  const why = review.reports ? 'Agents can’t change a note you wrote; you decide whether it holds' : 'Learning reads a proposed source only after you allow it';
  return <div role="listitem">
    <ItemRow className="is-context" label={`Repository context: ${title}`} onOpen={() => open(workspaceId)}
      icon={<span className="card-src is-context"><FileText size={16} strokeWidth={1.9} aria-hidden="true" /></span>}
      title={title}
      marks={<Pill outline>Needs review</Pill>}
      meta={<><span>Repository context</span><span className="item-row__sep">·</span><span className="item-row__muted">{why}</span></>}
      age={review.latestAt === null ? null : <CardAge at={review.latestAt} />} />
  </div>;
}

/** Notes this task saved, read from the task recorded on each note's current version. */
export function TaskContextNotes({ workspaceId, taskId, refreshKey }: { workspaceId: string; taskId: string; refreshKey: string }) {
  const [found, setFound] = useState<{ notebook: RepositoryNotebookDto; notes: ContextEntryDto[] } | null>(null);
  useEffect(() => {
    let live = true;
    // The card is secondary to the transcript: any failed read, even a synchronous one, just leaves it out.
    const load = (): void => void Promise.resolve().then(() => Promise.all([window.bridge.getRepositoryContext(workspaceId),
      window.bridge.getRepositoryContextTimes(workspaceId)]))
      .then(([notebook, times]) => { if (live) setFound({ notebook, notes: notesSavedBy(notebook, times, taskId) }); })
      .catch(() => {});
    const onChange = (event: Event) => { if (!(event instanceof CustomEvent) || event.detail === workspaceId) load(); };
    load();
    window.addEventListener(CONTEXT_CHANGED, onChange);
    return () => { live = false; window.removeEventListener(CONTEXT_CHANGED, onChange); };
  }, [refreshKey, taskId, workspaceId]);
  if (found === null || found.notes.length === 0) return null;
  return <>{found.notes.map(entry => {
    const area = noteArea(found.notebook, entry.scopeId);
    return <Card className="rcx-note" key={entry.id} label={`Note saved to Repository context: ${entry.title}`} onOpen={() => open(workspaceId)}>
      <div className="rcx-note__head"><FileText size={14} strokeWidth={1.9} aria-hidden="true" />
        <span className="rcx-note__label">Saved to Repository context</span>
        <Pill tone={entry.kind === 'MEMORY' ? 'purple' : 'neutral'}>{kindLabel(entry.kind)}</Pill></div>
      <div className="rcx-note__body">
        <div className="rcx-note__title">{entry.title}</div>
        <div className="rcx-note__summary">{entry.summary.replaceAll('`', '')}</div>
        <div className="rcx-note__meta">Applies to {area.paths.map(path => <code className="rcp-code" key={path}>{path}</code>)} · {plural(entry.sourceIds.length, 'source')}</div>
      </div>
    </Card>;
  })}</>;
}
