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
import { CircleCheck } from 'lucide-react';
import { SourceIcon } from '../cards/Card';
import { remoteErrorMessage } from '../remoteError';
import type { AgendaTargetDto, AssistantItemDto } from '../types';

/**
 * A new GitHub issue the assistant drafted. The user edits its title and description, and Create makes it a local Epic and publishes
 * it to the repository as an issue, the way a work item is pushed to GitHub. The assistant creates nothing; only this click writes.
 */
export default function DraftCard({ item, created, onCreated, onOpen }: {
  item: AssistantItemDto;
  created: boolean;
  onCreated: () => void;
  onOpen: (target: AgendaTargetDto) => void;
}): React.JSX.Element | null {
  const [title, setTitle] = useState(item.text);
  const [body, setBody] = useState(item.draft?.body ?? '');
  const [busy, setBusy] = useState(false);
  const [made, setMade] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const draft = item.draft;
  if (draft === null) return null;
  if (created) {
    return (
      <div className="assistant-draft is-created">
        <CircleCheck size={14} aria-hidden="true" />
        <span className="assistant-draft__done">Created on GitHub as an Epic</span>
        <span className="assistant-draft__repo">{draft.repository}</span>
      </div>
    );
  }
  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      // The work item's id is the draft's, so a click retried after a failure, even once the panel closed, finds the work it made instead of making more.
      const work = (await window.bridge.listWorkItems(draft.workspaceId)).find(each => each.id === draft.workItemId)
        ?? await window.bridge.createWorkItem(draft.workspaceId, { id: draft.workItemId, goalId: null, epicId: null, title: title.trim(), kind: 'DEVELOP',
          acceptanceCriteria: body.trim(), completionRule: 'MERGED_DELIVERY' });
      // The issue is pushed from the work item as made, so later edits would not reach it.
      setMade(true);
      await window.bridge.setLocalWorkItemEpic(draft.workspaceId, work.id, true);
      if (!work.links.some(link => link.kind === 'ISSUE')) {
        await window.bridge.publishWorkItem(draft.workspaceId, work.id, { source: 'GITHUB', expectedRevision: work.revision });
      }
      await window.bridge.recordAssistantDraft(item.id);
      onCreated();
    }
    catch (cause) {
      setError(remoteErrorMessage(cause));
    }
    finally {
      setBusy(false);
    }
  };
  const work: AgendaTargetDto = { kind: 'WORK', workspaceId: draft.workspaceId, repo: null, number: null, id: draft.workItemId, connectionId: null, cloudId: null };
  return (
    <form className="assistant-draft" aria-label="New GitHub issue as an Epic" onSubmit={event => { event.preventDefault(); void create(); }}>
      <div className="assistant-draft__head">
        <SourceIcon source="issue" tinted />
        <span>New issue as an Epic</span>
        <span className="assistant-spacer" />
        <span className="assistant-draft__repo">{draft.repository}</span>
      </div>
      <input className="assistant-draft__title" aria-label="Issue title" value={title} maxLength={200} disabled={busy || made} onChange={event => setTitle(event.target.value)} />
      <textarea className="assistant-draft__body" aria-label="Issue description" value={body} rows={4} disabled={busy || made} onChange={event => setBody(event.target.value)} />
      {error !== null && <p className="assistant-error" role="alert">{error}</p>}
      <div className="assistant-draft__actions">
        <span className="assistant-draft__note">{made ? 'Saved as a local Epic' : 'Creates the issue on GitHub'}</span>
        <span className="assistant-spacer" />
        {/* A push whose outcome is unknown is linked or pushed again from the work item's own page. */}
        {made && error !== null && <button type="button" className="assistant-button" onClick={() => onOpen(work)}>Open work item</button>}
        <button type="submit" className="assistant-button is-primary" disabled={busy || title.trim() === ''}>{busy ? 'Creating…' : 'Create issue'}</button>
      </div>
    </form>
  );
}
