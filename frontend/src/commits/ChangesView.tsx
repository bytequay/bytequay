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
import type { GitWorktreeStatusDto } from '../types';
import { DiffView, FileList } from './DiffPane';

/** GitHub-Desktop-style working-tree view; workspace repo only. */
export default function ChangesView({ repository, onCommitted }: {
  repository: string;
  onCommitted: () => void;
}) {
  const [status, setStatus] = useState<GitWorktreeStatusDto | null>(null);
  const [selected, setSelected] = useState(0);
  const [summary, setSummary] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.gitStatus(repository);
      setStatus(next);
      setSelected(current => Math.min(current, Math.max(0, next.files.length - 1)));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [repository]);

  useEffect(() => { void refresh(); }, [refresh]);

  const commit = async () => {
    if (!summary.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await window.bridge.gitCommit(repository, summary.trim(), description.trim());
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

  const files = status?.files ?? [];
  const totals = files.reduce(
    (sum, file) => ({ additions: sum.additions + file.additions, deletions: sum.deletions + file.deletions }),
    { additions: 0, deletions: 0 },
  );

  return (
    <div className="cmt-changes">
      <div className="cmt-changes-left">
        <div className="cmt-changes-summary">
          {files.length} changed files · +{totals.additions} −{totals.deletions} · uncommitted
        </div>
        <div className="cmt-changes-files">
          <FileList files={files} selected={selected} onSelect={setSelected} />
          {status !== null && files.length === 0 && (
            <div className="cmt-loading">The working tree is clean.</div>
          )}
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
      <div className="cmt-changes-right">
        <DiffView file={files[Math.min(selected, files.length - 1)] ?? null} />
      </div>
    </div>
  );
}
