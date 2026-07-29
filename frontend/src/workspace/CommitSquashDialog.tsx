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
import type { EditableCommit } from './commitRewrite';
import { SquashIcon } from './CommitEditorUi';

export type SquashRequest = {
  ids: string[];
  /** The commit the result lands on — the drop target when the squash
   *  came from a drag, otherwise the oldest of the selection. */
  anchorId: string;
  subject: string;
  body: string;
};

export default function CommitSquashDialog({
  request,
  participants,
  onCancel,
  onConfirm,
}: {
  request: SquashRequest;
  /** The participating commits in list order (newest first). */
  participants: EditableCommit[];
  onCancel: () => void;
  onConfirm: (subject: string, body: string) => void;
}) {
  const [subject, setSubject] = useState(request.subject);
  const [body, setBody] = useState(request.body);
  const anchor = participants.find(c => c.id === request.anchorId);

  return (
    <div className="wu-modal-backdrop wu-modal-backdrop--centered" role="presentation"
      onMouseDown={onCancel}>
      <section className="wu-ce-squash-modal" role="dialog" aria-modal="true"
        aria-label={`Squash ${participants.length} commits`}
        onMouseDown={event => event.stopPropagation()}>
        <header>
          <span className="wu-ce-squash-modal__icon" aria-hidden><SquashIcon /></span>
          <h2>Squash {participants.length} commits</h2>
          <span className="wu-row-spacer" />
          <button type="button" onClick={onCancel} aria-label="Close">×</button>
        </header>
        <div className="wu-ce-squash-modal__body">
          <ul>
            {participants.map(commit => (
              <li key={commit.id}>
                <code>{commit.shortSha}</code>
                <span>{commit.subject}</span>
                {commit.id === request.anchorId && <i>LANDS HERE</i>}
              </li>
            ))}
          </ul>
          <label>
            <span>Summary</span>
            <input autoFocus value={subject} placeholder="Commit title"
              onChange={event => setSubject(event.target.value)} />
          </label>
          <label>
            <span>Description</span>
            <textarea value={body} rows={6} placeholder="Combined description"
              onChange={event => setBody(event.target.value)} />
          </label>
          <small>
            {anchor === undefined ? '' : `lands at ${anchor.shortSha}’s position · `}
            nothing is rewritten until you hit Rewrite history.
          </small>
          <button type="button" className="wu-ce-squash-confirm"
            onClick={() => onConfirm(subject, body)}>
            Squash {participants.length} commits
          </button>
        </div>
      </section>
    </div>
  );
}
