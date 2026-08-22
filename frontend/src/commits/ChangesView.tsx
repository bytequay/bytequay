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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DiffViewer } from '../pulls/PullChanges';
import type { DiffFileDto, GitWorktreeStatusDto } from '../types';

/** GitHub-Desktop-style working-tree view; workspace repo only. */
export default function ChangesView({ workspaceId, onCommitted }: {
  workspaceId: string;
  onCommitted: () => void;
}) {
  const [status, setStatus] = useState<GitWorktreeStatusDto | null>(null);
  const [summary, setSummary] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.gitStatus(workspaceId);
      setStatus(next);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [workspaceId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const commit = async () => {
    if (!summary.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await window.bridge.gitCommit(workspaceId, summary.trim(), description.trim());
      setSummary('');
      setDescription('');
      await refresh();
      onCommitted();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const files = useMemo(() => status?.files ?? [], [status]);
  const viewerFiles = useMemo<DiffFileDto[]>(() => files.map(file => ({
    filename: file.path,
    status: file.status,
    additions: file.additions,
    deletions: file.deletions,
    patch: file.patch ?? null,
  })), [files]);
  const totals = files.reduce(
    (sum, file) => ({ additions: sum.additions + file.additions, deletions: sum.deletions + file.deletions }),
    { additions: 0, deletions: 0 },
  );

  return (
    <div className="cmt-changes">
      <div className="cmt-changes-workbar">
        <div className="cmt-changes-summary">
          {files.length} changed files · +{totals.additions} −{totals.deletions} · uncommitted
        </div>
        <div className="cmt-commit-box">
          {error && <div className="cmt-error" role="alert">{error}</div>}
          <input
            value={summary}
            placeholder="Summary (required)"
            onChange={event => setSummary(event.target.value)}
          />
          <textarea
            rows={3}
            value={description}
            placeholder="Description"
            onChange={event => setDescription(event.target.value)}
          />
          <button
            type="button"
            className="cmt-btn-green cmt-commit-btn"
            disabled={busy || !summary.trim() || files.length === 0}
            onClick={() => { void commit(); }}
          >
            Commit to {status?.branch ?? '…'}
          </button>
        </div>
      </div>
      {status !== null && files.length === 0
        ? <div className="cmt-loading">The working tree is clean.</div>
        : <DiffViewer files={viewerFiles} loading={status === null} />}
    </div>
  );
}
