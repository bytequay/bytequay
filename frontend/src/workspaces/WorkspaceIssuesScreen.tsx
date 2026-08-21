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
import MarkdownBody from '../pulls/MarkdownBody';
import { relativeTime } from '../relativeTime';
import type { RepositoryIssueSnapshotDto } from '../types';
import '../css/issues.css';

export default function WorkspaceIssuesScreen({ workspaceId, repository }: {
  workspaceId: string;
  repository: string;
}) {
  const [snapshot, setSnapshot] = useState<RepositoryIssueSnapshotDto | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const apply = useCallback((value: RepositoryIssueSnapshotDto) => {
    setSnapshot(value);
    setSelectedId(current => value.issues.some(issue => issue.id === current)
      ? current
      : value.issues[0]?.id ?? null);
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      apply(await window.bridge.syncWorkspaceIssues(workspaceId));
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setLoading(false);
    }
  }, [apply, workspaceId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    window.bridge.fetchWorkspaceIssues(workspaceId)
      .then(value => { if (!cancelled) apply(value); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    void window.bridge.syncWorkspaceIssues(workspaceId)
      .then(value => { if (!cancelled) apply(value); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { cancelled = true; };
  }, [apply, workspaceId]);

  const selected = snapshot?.issues.find(issue => issue.id === selectedId) ?? null;

  return (
    <div className="issues-page">
      <section className="issues-list">
        <header className="issues-toolbar">
          <span className="pulls-scope" title={`Showing ${repository}`}>{repository}</span>
          <strong>Open issues</strong>
          <button type="button" onClick={() => { void refresh(); }} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </header>
        {snapshot?.syncedAt && (
          <div className="issues-freshness">Updated {relativeTime(snapshot.syncedAt)}</div>
        )}
        {error && <div className="issues-error" role="alert">Could not refresh {repository}. {error}</div>}
        {!loading && snapshot?.issues.length === 0 && (
          <div className="hp-empty">No open issues for {repository}.</div>
        )}
        <div className="issues-rows">
          {snapshot?.issues.map(issue => (
            <button
              key={issue.id}
              type="button"
              className={`issues-row${issue.id === selectedId ? ' is-selected' : ''}`}
              onClick={() => setSelectedId(issue.id)}
            >
              <span className="issues-row-title">{issue.title}</span>
              <span className="issues-row-meta">#{issue.number} opened by {issue.author} · {issue.commentCount} comments</span>
              {issue.labels.length > 0 && (
                <span className="issues-labels">{issue.labels.map(label => <i key={label}>{label}</i>)}</span>
              )}
            </button>
          ))}
        </div>
      </section>
      <section className="issue-detail">
        {selected ? (
          <>
            <header className="issue-detail-head">
              <div>
                <h1>{selected.title}</h1>
                <span>{repository}#{selected.number} · {selected.author} · updated {relativeTime(selected.updatedAt)}</span>
              </div>
              <button type="button" onClick={() => { void window.bridge.openExternal(selected.htmlUrl); }}>
                Open on GitHub
              </button>
            </header>
            {selected.assignees.length > 0 && (
              <div className="issue-assignees">Assigned to {selected.assignees.join(', ')}</div>
            )}
            <div className="issue-body">
              <MarkdownBody body={selected.body || 'No description provided.'} />
            </div>
          </>
        ) : (
          <div className="hp-empty">Select an issue to view its details.</div>
        )}
      </section>
    </div>
  );
}
