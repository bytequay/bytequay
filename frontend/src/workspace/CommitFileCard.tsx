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
import { FileDiffBody } from '../diff/DiffFileList';
import type { LocalCommitFileDto } from '../types';
import { selectionColour, shortPath } from './CommitEditorUi';
import { message } from './WorkspaceRepoUi';

/**
 * One collapsible changed file. The patch source is injected so the same
 * card serves a commit selection and the uncommitted working tree, and
 * the diff itself is the app's shared renderer either way.
 */
export default function CommitFileCard({
  file,
  markers = [],
  open,
  onToggle,
  fetchPatch,
}: {
  file: LocalCommitFileDto;
  /** Selection indexes that touched this path, for the colour dots. */
  markers?: number[];
  open: boolean;
  onToggle: () => void;
  fetchPatch: (path: string) => Promise<string | null>;
}) {
  const [patch, setPatch] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || patch !== null) return undefined;
    let cancelled = false;
    void fetchPatch(file.path)
      .then(result => { if (!cancelled) setPatch(result); })
      .catch(reason => { if (!cancelled) setError(message(reason)); });
    return () => { cancelled = true; };
  }, [open, patch, file.path, fetchPatch]);

  return (
    <section className="wu-ce-file">
      <button type="button" className="wu-ce-file__head" onClick={onToggle} aria-expanded={open}>
        <span className={`wu-ce-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="m9 18 6-6-6-6" />
          </svg>
        </span>
        <code title={file.path}>{shortPath(file.path)}</code>
        <span className="wu-row-spacer" />
        <span className="wu-ce-stat"><b>+{file.additions}</b> <em>−{file.deletions}</em></span>
        {markers.length > 0 && (
          <span className="wu-ce-markers"
            title={`touched by ${markers.length} of the selected commits`}>
            {markers.map(index => (
              <i key={index} style={{ background: selectionColour(index) }} />
            ))}
          </span>
        )}
      </button>
      {open && (
        error !== null
          ? <p className="wu-ce-note">{error}</p>
          : patch === null
            ? <p className="wu-ce-note">Loading diff…</p>
            : (
              <div className="diff-file-body">
                <FileDiffBody file={{
                  filename: file.path,
                  status: file.status,
                  additions: file.additions,
                  deletions: file.deletions,
                  patch,
                }} />
              </div>
            )
      )}
    </section>
  );
}
