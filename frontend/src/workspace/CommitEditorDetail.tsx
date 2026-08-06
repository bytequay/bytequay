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
import { useEffect, useMemo, useRef, useState } from 'react';
import { unionCommitFiles } from '../diff/unionCommitFiles';
import CommitFileCard from './CommitFileCard';
import type { LocalCommitFileDto } from '../types';
import type { EditableCommit } from './commitRewrite';
import {
  CheckIcon, CommitAuthorAvatar, SquashIcon, commitDate, selectionColour,
} from './CommitEditorUi';
import { renderMarkdown, type MarkdownRepoContext } from '../markdown';
import { workspaceApi } from './workspaceApi';
import { relative } from './WorkspaceRepoUi';

/** Per-commit file lists for the current selection, same order as
 *  `selected`. Fetched by the parent so a re-render of this pane doesn't
 *  refetch. */
export type SelectionFiles = LocalCommitFileDto[][];

type Props = {
  workspaceId: string;
  selected: EditableCommit[];
  files: SelectionFiles;
  filesLoading: boolean;
  isLocal: boolean;
  editable: boolean;
  draftSubject: string;
  draftBody: string;
  onDraftSubject: (value: string) => void;
  onDraftBody: (value: string) => void;
  onSaveMessage: () => void;
  onRevertMessage: () => void;
  onSelectUpToHead: () => void;
  onOpenSquash: () => void;
  /** Repo the commit messages were written against, so a `#N` in a body
   *  becomes a reference to the right repo's issue. */
  repoContext?: MarkdownRepoContext;
  onOpenIssue?: (issueNumber: number) => void;
};

export default function CommitEditorDetail({
  workspaceId,
  selected,
  files,
  filesLoading,
  isLocal,
  editable,
  draftSubject,
  draftBody,
  onDraftSubject,
  onDraftBody,
  onSaveMessage,
  onRevertMessage,
  onSelectUpToHead,
  onOpenSquash,
  repoContext,
  onOpenIssue,
}: Props) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [bodyEditing, setBodyEditing] = useState(false);
  const head = selected.length === 1 ? selected[0] : null;
  const dirty = head !== null
    && (draftSubject !== head.subject || draftBody !== head.body);

  const merged = useMemo(() => {
    const totals = unionCommitFiles(files, file => file.path);
    // Which of the selected commits touched each path — drives the
    // per-file colour markers in the multi-select header.
    const touchedBy = new Map<string, number[]>();
    files.forEach((commitFiles, index) => {
      for (const file of commitFiles) {
        touchedBy.set(file.path, [...(touchedBy.get(file.path) ?? []), index]);
      }
    });
    return totals.map(file => ({ file, by: touchedBy.get(file.path) ?? [] }));
  }, [files]);

  // Reset expansion when the selection changes — the keys are per-path,
  // and carrying them over would leave unrelated files open.
  const selectionKey = selected.map(c => c.id).join(',');
  useEffect(() => { setOpen({}); }, [selectionKey]);

  // Grow the message box to the body it holds. A fixed three rows hid most
  // of a real commit message behind a scrollbar, and the whole point of
  // this pane is reading the message before rewording it. Measured rather
  // than counting "\n" so soft-wrapped long lines are covered too.
  const bodyBox = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const box = bodyBox.current;
    if (box === null) return;
    box.style.height = 'auto';
    // The cap keeps a pathological message from pushing the file list off
    // screen; past it the textarea scrolls as before.
    box.style.height = `${Math.min(Math.max(box.scrollHeight, 62), 340)}px`;
  }, [draftBody, selectionKey, bodyEditing]);

  // The body reads as rendered markdown and only becomes a textarea once
  // you go to change it — commit messages are markdown on GitHub, and the
  // raw source is only interesting while you are editing it.
  useEffect(() => { setBodyEditing(false); }, [selectionKey]);

  const totalAdds = merged.reduce((n, m) => n + Math.max(m.file.additions, 0), 0);
  const totalDels = merged.reduce((n, m) => n + Math.max(m.file.deletions, 0), 0);
  const anyOpen = merged.some(m => open[m.file.path] === true);

  if (selected.length === 0) {
    return (
      <div className="wu-ce-detail wu-ce-detail--empty">
        <div>
          <strong>Select a commit to see its files and diff</strong>
          <span>Check the boxes — or shift-click — to select a range and review the
            combined diff before squashing.</span>
        </div>
      </div>
    );
  }

  // Range for the diff calls: oldest selected commit's parent → newest.
  // ponytail: a non-contiguous selection's range also covers the commits
  // in between; the per-file markers still say which selected commits
  // touched what. Per-commit diffs, if that ever bites, are one more call.
  const oldest = selected[selected.length - 1];
  const newest = selected[0];
  const base = `${oldest.picks[0]}^`;
  const headSha = newest.picks[newest.picks.length - 1];

  return (
    <div className="wu-ce-detail">
      {head !== null ? (
        <div className="wu-ce-message">
          <div className="wu-ce-message__head">
            <code>{head.shortSha}</code>
            <CommitAuthorAvatar commit={head} size={20} />
            <span>{head.authorName}</span>
            <span>{commitDate(head) === null ? '' : relative(commitDate(head)!)}</span>
            <span className="wu-ce-stat"><b>+{totalAdds}</b> <em>−{totalDels}</em></span>
            {isLocal && <i className="wu-ce-local-badge">LOCAL</i>}
            <span className="wu-row-spacer" />
            <button type="button" onClick={onSelectUpToHead}
              title="Select this commit and everything newer">Select up to HEAD</button>
          </div>
          <input value={draftSubject} aria-label="Commit title" placeholder="Commit title"
            className={dirty ? 'is-dirty' : ''} disabled={!editable}
            onChange={event => onDraftSubject(event.target.value)} />
          {bodyEditing ? (
            <textarea ref={bodyBox} value={draftBody} rows={3} autoFocus
              aria-label="Extended description"
              placeholder="Extended description (optional)"
              className={dirty ? 'is-dirty' : ''}
              onChange={event => onDraftBody(event.target.value)}
              onBlur={() => setBodyEditing(false)} />
          ) : (
            <div
              className={`wu-ce-body wu-markdown${dirty ? ' is-dirty' : ''}`}
              role={editable ? 'button' : undefined}
              tabIndex={editable ? 0 : undefined}
              aria-label={editable ? 'Extended description — click to edit' : 'Extended description'}
              title={editable ? 'Click to edit' : undefined}
              onClick={event => {
                // An issue chip wins over "start editing" — clicking `#123`
                // is a navigation, not a mis-aimed click into the text.
                const issue = (event.target as HTMLElement)
                  .closest<HTMLElement>('[data-issue-number]');
                const number = Number(issue?.dataset.issueNumber);
                if (Number.isFinite(number) && number > 0) {
                  onOpenIssue?.(number);
                  return;
                }
                if (editable) setBodyEditing(true);
              }}
              onKeyDown={editable
                ? event => { if (event.key === 'Enter') setBodyEditing(true); }
                : undefined}
              dangerouslySetInnerHTML={draftBody.trim().length === 0
                ? undefined
                : { __html: renderMarkdown(draftBody, repoContext) }}>
              {draftBody.trim().length === 0
                ? <span className="wu-ce-body__empty">No extended description.</span>
                : undefined}
            </div>
          )}
          <div className="wu-ce-message__foot">
            <small>Reword lands as an empty commit squashed into {head.shortSha} —
              history stays linear.</small>
            <span className="wu-row-spacer" />
            {dirty && (
              <>
                <button type="button" onClick={onRevertMessage}>Revert</button>
                <button type="button" className="wu-ce-save" onClick={onSaveMessage}>
                  <CheckIcon />Save message
                </button>
              </>
            )}
          </div>
        </div>
      ) : (
        <div className="wu-ce-multi">
          <div className="wu-ce-multi__head">
            <strong>{selected.length} commits selected</strong>
            <span>{merged.length} {merged.length === 1 ? 'file' : 'files'} · +{totalAdds} −{totalDels}</span>
            <span className="wu-row-spacer" />
            {editable && (
              <button type="button" className="wu-ce-squash-btn" onClick={onOpenSquash}>
                <SquashIcon />Squash into one
              </button>
            )}
          </div>
          <ul>
            {selected.map((commit, index) => (
              <li key={commit.id}>
                <i style={{ background: selectionColour(index) }} aria-hidden />
                <code>{commit.shortSha}</code>
                <span>{commit.subject}</span>
                <span className="wu-ce-stat">
                  <b>+{commit.additions}</b> <em>−{commit.deletions}</em>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="wu-ce-files__head">
        <strong>{merged.length} changed {merged.length === 1 ? 'file' : 'files'}</strong>
        {selected.length > 1 && <span>combined across the selection</span>}
        <span className="wu-row-spacer" />
        <button type="button" onClick={() => {
          const next: Record<string, boolean> = {};
          for (const m of merged) next[m.file.path] = !anyOpen;
          setOpen(next);
        }}>{anyOpen ? 'Collapse all' : 'Expand all'}</button>
      </div>
      <div className="wu-ce-files">
        {filesLoading && merged.length === 0 && <p className="wu-ce-note">Loading files…</p>}
        {merged.map(({ file, by }) => (
          <CommitFileCard
            key={file.path}
            file={file}
            markers={selected.length > 1 ? by : []}
            open={open[file.path] === true}
            onToggle={() => setOpen(prev => ({ ...prev, [file.path]: prev[file.path] !== true }))}
            fetchPatch={path => workspaceApi.commitRangeDiff(workspaceId, base, headSha, path)
              .then(result => result.patch)}
          />
        ))}
      </div>
    </div>
  );
}

