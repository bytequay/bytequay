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
import { ChevronRight, FileText } from 'lucide-react';
import { Card, CardAge, Pill } from '../cards/Card';
import { workspaceContextDraftRoute, workspaceRoute } from '../workspaces/model';
import type { ContextKnowledgeDraftDto, RepositoryNotebookDto } from './contracts';
import { kindLabel, noteArea, plural } from './model';
import { CONTEXT_CHANGED, type ContextReview } from './reviewCount';
import '../css/repository-context.css';

const open = (workspaceId: string, draftId: string | null) => {
  window.location.hash = draftId === null ? workspaceRoute(workspaceId, 'context') : workspaceContextDraftRoute(workspaceId, draftId);
};

/** Home's Needs attention card for drafts and source proposals; it opens the same review drawer as the page. */
export function ContextReviewCard({ workspaceId, review }: { workspaceId: string; review: ContextReview }) {
  const parts = [review.drafts ? plural(review.drafts, 'draft') : null, review.sources ? plural(review.sources, 'source') : null].filter(part => part !== null);
  const title = `${parts.join(' and ')} to review`;
  return <Card className="atn-card is-context" label={`Repository context: ${title}`} onOpen={() => open(workspaceId, review.firstDraftId)}>
    <div className="atn-card__head">
      <FileText size={14} strokeWidth={1.9} aria-hidden="true" />
      <span className="atn-card__key">Repository context</span>
      <Pill outline>Needs review</Pill>
      <span className="atn-spacer" />
      {review.latestAt !== null && <CardAge at={review.latestAt} />}
      <ChevronRight className="atn-card__chev" size={13} aria-hidden="true" />
    </div>
    <div className="atn-card__body">
      <div className="atn-card__title">{title}</div>
      <div className="atn-card__why"><span className="atn-card__verb">Not available to agents until approved</span></div>
    </div>
  </Card>;
}

/** Drafts this task proposed that still wait for review, read from the recorded proposing task. */
export function TaskContextDrafts({ workspaceId, taskId, refreshKey }: { workspaceId: string; taskId: string; refreshKey: string }) {
  const [found, setFound] = useState<{ notebook: RepositoryNotebookDto; drafts: ContextKnowledgeDraftDto[] } | null>(null);
  useEffect(() => {
    let live = true;
    // The card is secondary to the transcript: any failed read, even a synchronous one, just leaves it out.
    const load = (): void => void Promise.resolve().then(() => Promise.all([window.bridge.getRepositoryContext(workspaceId),
      window.bridge.getRepositoryContextTimes(workspaceId)]))
      .then(([notebook, times]) => {
        if (live) setFound({ notebook, drafts: notebook.pendingDrafts.filter(draft => times.drafts[draft.id]?.taskId === taskId) });
      })
      .catch(() => {});
    const onChange = (event: Event) => { if (!(event instanceof CustomEvent) || event.detail === workspaceId) load(); };
    load();
    window.addEventListener(CONTEXT_CHANGED, onChange);
    return () => { live = false; window.removeEventListener(CONTEXT_CHANGED, onChange); };
  }, [refreshKey, taskId, workspaceId]);
  if (found === null || found.drafts.length === 0) return null;
  return <>{found.drafts.map(draft => {
    const area = noteArea(found.notebook, draft.entry.scopeId);
    const update = draft.replacesVersion !== null;
    return <Card className="rcx-draft" key={draft.id} label={`Draft note for Repository context: ${draft.entry.title}`} onOpen={() => open(workspaceId, draft.id)}>
      <div className="rcx-draft__head"><FileText size={14} strokeWidth={1.9} aria-hidden="true" />
        <span className="rcx-draft__label">Draft note for Repository context</span>
        <Pill tone={draft.entry.kind === 'MEMORY' ? 'purple' : 'neutral'}>{kindLabel(draft.entry.kind)}</Pill>
        <span className="rcp-spacer" />
        <Pill tone="amber">{update ? `Update · v${draft.replacesVersion} stays in use` : 'Draft · not available to agents'}</Pill></div>
      <div className="rcx-draft__body">
        <div className="rcx-draft__title">{draft.entry.title}</div>
        <div className="rcx-draft__summary">{draft.entry.summary.replaceAll('`', '')}</div>
        <div className="rcx-draft__meta">Applies to {area.paths.map(path => <code className="rcp-code" key={path}>{path}</code>)} · {plural(draft.entry.sourceIds.length, 'source')}</div>
        <div className="rcx-draft__actions">
          <button type="button" className="rcp-btn rcp-btn--sm" onClick={event => { event.stopPropagation(); open(workspaceId, draft.id); }}>Review draft</button>
          <span>Opens in Repository context · approving starts no agent run</span>
        </div>
      </div>
    </Card>;
  })}</>;
}
