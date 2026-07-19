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
import type { DiffFileDto } from '../types';

/** The changed-files artifact shared by the trunk, brain milestone feed, and stage log. */
export function TaskChangedFilesCard({ files, onReview, onUndo, verb = 'Changed' }: {
  files: DiffFileDto[];
  onReview?: () => void;
  onUndo?: () => void;
  verb?: 'Changed' | 'Edited';
}) {
  const [expanded, setExpanded] = useState(false);
  if (files.length === 0) return null;

  const additions = files.reduce((total, file) => total + Math.max(0, file.additions), 0);
  const deletions = files.reduce((total, file) => total + Math.max(0, file.deletions), 0);
  const visible = expanded ? files : files.slice(0, 3);
  const hiddenCount = files.length - 3;

  return (
    <div className="workspace-task-files-card">
      <div className="workspace-task-files-card__header">
        <span className="workspace-task-files-card__icon"><FileIcon edited={verb === 'Edited'} /></span>
        <strong>{verb} {files.length} {files.length === 1 ? 'file' : 'files'}</strong>
        <span className="workspace-task-files-card__totals">
          <span>+{additions}</span> <span>−{deletions}</span>
        </span>
        <span className="workspace-task-files-card__grow" />
        {onUndo !== undefined && (
          <button type="button" className="workspace-task-files-card__undo" onClick={onUndo}>
            Undo <UndoIcon />
          </button>
        )}
        {onReview !== undefined && (
          <button type="button" onClick={onReview}>Review</button>
        )}
      </div>
      {visible.map(file => {
        const split = file.filename.lastIndexOf('/');
        const directory = split < 0 ? '' : file.filename.slice(0, split + 1);
        const name = split < 0 ? file.filename : file.filename.slice(split + 1);
        return (
          <div className="workspace-task-files-card__file" key={file.filename}>
            <span className="workspace-task-files-card__path">
              {directory.length > 0 && <span>{directory}</span>}{name}
            </span>
            <span className="workspace-task-files-card__diff">
              <span>+{Math.max(0, file.additions)}</span> <span>−{Math.max(0, file.deletions)}</span>
            </span>
          </div>
        );
      })}
      {hiddenCount > 0 && (
        <button
          type="button"
          className="workspace-task-files-card__more"
          aria-expanded={expanded}
          onClick={() => setExpanded(open => !open)}
        >
          {expanded ? 'Show fewer files' : `Show ${hiddenCount} more files`}
          <ChevronDownIcon open={expanded} />
        </button>
      )}
    </div>
  );
}

function FileIcon({ edited }: { edited: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6" />
      <path d={edited ? 'M9 14h6' : 'M9 15h6'} />
      {edited && <path d="M12 11v6" />}
    </svg>
  );
}

function UndoIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M9 14 4 9l5-5" />
      <path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" />
    </svg>
  );
}

function ChevronDownIcon({ open }: { open: boolean }) {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden
      style={{ transform: open ? 'rotate(180deg)' : undefined }}>
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
