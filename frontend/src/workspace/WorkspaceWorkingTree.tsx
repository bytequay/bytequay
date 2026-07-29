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
import { useCallback, useEffect, useState } from 'react';
import type { LocalCommitFileDto } from '../types';
import CommitFileCard from './CommitFileCard';
import { workspaceApi } from './workspaceApi';
import { BodyMessage, message } from './WorkspaceRepoUi';

/**
 * Everything changed but not committed — staged, unstaged and untracked
 * together, the way `git status` reports them. Read-only: this surface
 * shows what a rewrite would refuse to run over, it doesn't stage or
 * discard anything.
 */
export default function WorkspaceWorkingTree({
  workspaceId,
  query,
  onCountChange,
}: {
  workspaceId: string;
  /** Same filter box as the commits tab; matches on path here. */
  query: string;
  onCountChange: (count: number) => void;
}) {
  const [files, setFiles] = useState<LocalCommitFileDto[]>([]);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void workspaceApi.workingTreeFiles(workspaceId)
      .then(next => {
        if (cancelled) return;
        const rows = Array.isArray(next) ? next : [];
        setFiles(rows);
        onCountChange(rows.length);
        setError(null);
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspaceId, onCountChange]);

  const fetchPatch = useCallback(
    (path: string) => workspaceApi.workingTreeDiff(workspaceId, path)
      .then(result => result.patch),
    [workspaceId]);

  const needle = query.trim().toLowerCase();
  const shown = needle.length === 0
    ? files
    : files.filter(file => file.path.toLowerCase().includes(needle));
  const adds = shown.reduce((n, f) => n + Math.max(f.additions, 0), 0);
  const dels = shown.reduce((n, f) => n + Math.max(f.deletions, 0), 0);
  const anyOpen = shown.some(file => open[file.path] === true);

  if (loading) return <BodyMessage>Loading working tree…</BodyMessage>;

  return (
    <div className="wu-ce-worktree">
      {error !== null && <div className="wu-inline-error">{error}</div>}
      {files.length === 0 ? (
        <div className="wu-ce-detail--empty">
          <div>
            <strong>Nothing uncommitted</strong>
            <span>The working tree is clean, so history here is safe to rewrite.</span>
          </div>
        </div>
      ) : (
        <>
          <div className="wu-ce-files__head">
            <strong>{shown.length} uncommitted {shown.length === 1 ? 'file' : 'files'}</strong>
            <span className="wu-ce-stat"><b>+{adds}</b> <em>−{dels}</em></span>
            <span className="wu-row-spacer" />
            <button type="button" onClick={() => {
              const next: Record<string, boolean> = {};
              for (const file of shown) next[file.path] = !anyOpen;
              setOpen(next);
            }}>{anyOpen ? 'Collapse all' : 'Expand all'}</button>
          </div>
          <div className="wu-ce-files">
            {shown.map(file => (
              <CommitFileCard
                key={file.path}
                file={file}
                open={open[file.path] === true}
                onToggle={() => setOpen(prev => ({ ...prev, [file.path]: prev[file.path] !== true }))}
                fetchPatch={fetchPatch}
              />
            ))}
            {shown.length === 0 && <p className="wu-ce-note">No file matches this filter.</p>}
          </div>
        </>
      )}
    </div>
  );
}
