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
import { useEffect, useMemo, useState } from 'react';
import { relativeTime } from '../relativeTime';
import type { GitCommitDto, GitDiffFileDto } from '../types';
import Avatar from '../Avatar';
import DiffPane from './DiffPane';
import { groupByDay, selectedSpan, shortSha, spanFromShas, squashMessage } from './model';

export default function HistoryView({
  workspaceId,
  branch,
  logRef,
  isUpstream,
  canRewrite,
  commits,
  loading,
  checked,
  onChangeChecked,
  onHistoryRewritten,
}: {
  workspaceId: string;
  branch: string;
  /** The ref the commits were listed from (branch, or remote/branch). */
  logRef: string;
  isUpstream: boolean;
  /** Message edits and squashes need the viewed branch checked out. */
  canRewrite: boolean;
  commits: GitCommitDto[];
  loading: boolean;
  checked: ReadonlySet<string>;
  onChangeChecked: (next: Set<string>) => void;
  onHistoryRewritten: () => void;
}) {
  const [viewedSha, setViewedSha] = useState<string | null>(null);
  const [rangeFrom, setRangeFrom] = useState('');
  const [rangeTo, setRangeTo] = useState('');
  const [rangeError, setRangeError] = useState(false);
  const [diffFiles, setDiffFiles] = useState<GitDiffFileDto[]>([]);
  const [diffIndex, setDiffIndex] = useState(0);
  const [editTitle, setEditTitle] = useState<string | null>(null);
  const [editBody, setEditBody] = useState<string | null>(null);
  const [squashDraft, setSquashDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const span = useMemo(() => selectedSpan(commits, checked), [commits, checked]);
  const spanCommits = useMemo(
    () => (span ? commits.slice(span.lo, span.hi + 1) : []),
    [commits, span],
  );
  const single = !isUpstream && spanCommits.length === 1 ? spanCommits[0] : null;
  const multi = !isUpstream && spanCommits.length > 1;
  const dayGroups = useMemo(() => groupByDay(commits), [commits]);

  const viewed = commits.find(commit => commit.sha === viewedSha) ?? commits[0] ?? null;

  // The right pane always shows something concrete: the squash preview for a
  // multi-selection, otherwise the viewed commit.
  const diffBase = multi && span ? `${commits[span.hi].sha}^` : viewed ? `${viewed.sha}^` : null;
  const diffHead = multi && span ? commits[span.lo].sha : viewed?.sha ?? null;

  useEffect(() => {
    if (!diffBase || !diffHead) {
      setDiffFiles([]);
      return;
    }
    let cancelled = false;
    window.bridge.gitDiff(workspaceId, diffBase, diffHead)
      .then(files => {
        if (cancelled) return;
        setDiffFiles(files);
        setDiffIndex(0);
      })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { cancelled = true; };
  }, [workspaceId, diffBase, diffHead]);

  const resetDrafts = () => {
    setEditTitle(null);
    setEditBody(null);
    setSquashDraft(null);
    setNotice(null);
    setError(null);
  };

  const toggle = (sha: string) => {
    const next = new Set(checked);
    if (next.has(sha)) next.delete(sha);
    else next.add(sha);
    onChangeChecked(next);
    resetDrafts();
  };

  const applyRange = () => {
    const result = spanFromShas(commits, rangeFrom, rangeTo);
    if ('error' in result) {
      setRangeError(true);
      return;
    }
    setRangeError(false);
    onChangeChecked(new Set(result.anchors));
    resetDrafts();
  };

  const clearSelection = () => {
    onChangeChecked(new Set());
    resetDrafts();
  };

  const updateMessage = async () => {
    if (!single) return;
    setBusy(true);
    setError(null);
    try {
      const title = (editTitle ?? single.title).trim();
      const body = (editBody ?? single.body).trim();
      await window.bridge.gitReword(
        workspaceId,
        branch,
        single.sha,
        body === '' ? title : `${title}\n\n${body}`,
      );
      setNotice('Updated locally — amends this workspace commit only');
      onHistoryRewritten();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const squash = async () => {
    if (!multi || !span) return;
    setBusy(true);
    setError(null);
    try {
      await window.bridge.gitSquash(
        workspaceId,
        branch,
        commits[span.hi].sha,
        commits[span.lo].sha,
        (squashDraft ?? squashMessage(spanCommits)).trim(),
      );
      setNotice('Squashed locally — original SHAs kept in the body');
      onChangeChecked(new Set());
      onHistoryRewritten();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="cmt-history">
      <div className="cmt-history-left">
        <div className="cmt-range-bar">
          <div className="cmt-range-row">
            <span className="cmt-range-label">RANGE</span>
            <input
              value={rangeFrom}
              placeholder="from SHA"
              onChange={event => { setRangeFrom(event.target.value); setRangeError(false); }}
            />
            <span className="cmt-range-sep">…</span>
            <input
              value={rangeTo}
              placeholder="to SHA"
              onChange={event => { setRangeTo(event.target.value); setRangeError(false); }}
            />
            <button type="button" onClick={applyRange}>Select range</button>
            {rangeError && <span className="cmt-range-error">SHA not on this branch</span>}
          </div>
          {isUpstream && (
            <div className="cmt-hint is-upstream">
              <strong>Upstream · read-only</strong> — check a start and end commit (or type SHAs),
              then cherry-pick the range into {branch}.
            </div>
          )}
          {!isUpstream && spanCommits.length === 0 && (
            <div className="cmt-hint">
              Click a commit to see its diff on the right · check one to edit its message ·
              check two to squash a range.
            </div>
          )}
          {!isUpstream && spanCommits.length > 0 && !canRewrite && (
            <div className="cmt-hint">
              Check out {branch} to edit messages or squash; {logRef} is only being viewed.
            </div>
          )}
        </div>
        <div className="cmt-commit-list">
          {loading && <div className="cmt-loading">Loading commits…</div>}
          {!loading && commits.length === 0 && <div className="cmt-loading">No commits on this ref.</div>}
          {dayGroups.map(group => (
            <div key={group.label}>
              <div className="cmt-day-head">{group.label}</div>
              {group.commits.map(({ commit, index }) => {
                const inRange = span !== null && index >= span.lo && index <= span.hi;
                const isViewed = viewed?.sha === commit.sha;
                return (
                  <div key={commit.sha}>
                    <div
                      className={`cmt-commit-row${inRange ? ' is-selected' : ''}${isViewed && !multi ? ' is-viewed' : ''}`}
                      onClick={() => { setViewedSha(commit.sha); setDiffIndex(0); }}
                    >
                      <input
                        type="checkbox"
                        checked={inRange}
                        aria-label={`Select ${shortSha(commit.sha)}`}
                        onClick={event => event.stopPropagation()}
                        onChange={() => toggle(commit.sha)}
                      />
                      <div className="cmt-commit-main">
                        <div className="cmt-commit-title-row">
                          <span className="cmt-commit-title">{commit.title}</span>
                          {commit.pullRequestNumber != null && (
                            <span className="cmt-chip is-pr">#{commit.pullRequestNumber}</span>
                          )}
                        </div>
                        <div className="cmt-commit-meta">
                          <Avatar login={commit.author} size={14} />
                          <span>
                            <strong>{commit.author}</strong>
                            {' committed '}
                            {relativeTime(commit.authoredAt)}
                          </span>
                        </div>
                      </div>
                      <span className="cmt-commit-sha">{shortSha(commit.sha)}</span>
                    </div>

                    {single !== null && single.sha === commit.sha && canRewrite && (
                      <div className="cmt-inline-editor">
                        <div className="cmt-editor-label">COMMIT MESSAGE</div>
                        <input
                          className="cmt-editor-title"
                          value={editTitle ?? single.title}
                          onChange={event => { setEditTitle(event.target.value); setNotice(null); }}
                        />
                        <textarea
                          rows={4}
                          value={editBody ?? single.body}
                          onChange={event => { setEditBody(event.target.value); setNotice(null); }}
                        />
                        <div className="cmt-editor-actions">
                          <button
                            type="button"
                            className="cmt-btn-green"
                            disabled={busy}
                            onClick={() => { void updateMessage(); }}
                          >
                            Update message
                          </button>
                          <button type="button" disabled={busy} onClick={resetDrafts}>Discard</button>
                          {notice && <span className="cmt-editor-notice">✓ {notice}</span>}
                        </div>
                      </div>
                    )}

                    {multi && span !== null && span.hi === index && canRewrite && (
                      <div className="cmt-inline-editor">
                        <div className="cmt-squash-head">
                          <span>Squash {spanCommits.length} commits</span>
                          <span className="cmt-squash-range">
                            {shortSha(commits[span.hi].sha)}…{shortSha(commits[span.lo].sha)}
                          </span>
                        </div>
                        <div className="cmt-editor-label">COMBINED MESSAGE</div>
                        <textarea
                          rows={6}
                          value={squashDraft ?? squashMessage(spanCommits)}
                          onChange={event => { setSquashDraft(event.target.value); setNotice(null); }}
                        />
                        <div className="cmt-editor-actions">
                          <button
                            type="button"
                            className="cmt-btn-green"
                            disabled={busy}
                            onClick={() => { void squash(); }}
                          >
                            Squash {spanCommits.length} commits
                          </button>
                          <button type="button" disabled={busy} onClick={clearSelection}>Clear selection</button>
                          {notice && <span className="cmt-editor-notice">✓ {notice}</span>}
                        </div>
                        <div className="cmt-hint">The combined range diff is shown on the right.</div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>

      <div className="cmt-history-right">
        {error && <div className="cmt-error" role="alert">{error}</div>}
        <div className="cmt-view-head">
          <div className="cmt-view-head-main">
            <div className="cmt-view-title">
              {multi ? `Range changes — ${spanCommits.length} commits` : viewed?.title ?? ''}
            </div>
            <div className="cmt-view-meta">
              {multi && span
                ? `${shortSha(commits[span.hi].sha)}…${shortSha(commits[span.lo].sha)} · squash preview`
                : viewed
                  ? `${shortSha(viewed.sha)} · ${viewed.author} committed ${relativeTime(viewed.authoredAt)}`
                  : ''}
            </div>
          </div>
          <span className="cmt-view-summary">
            {diffFiles.length} changed files ·{' '}
            +{diffFiles.reduce((sum, file) => sum + file.additions, 0)}{' '}
            −{diffFiles.reduce((sum, file) => sum + file.deletions, 0)}
          </span>
        </div>
        <DiffPane files={diffFiles} selected={diffIndex} onSelect={setDiffIndex} />
      </div>
    </div>
  );
}
