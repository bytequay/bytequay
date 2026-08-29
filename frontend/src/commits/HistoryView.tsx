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
import { useEffect, useMemo, useRef, useState, type UIEvent as ReactUIEvent } from 'react';
import { relativeTime } from '../relativeTime';
import type { DiffFileDto, GitCommitDto, GitDiffFileDto } from '../types';
import Avatar from '../Avatar';
import { DiffViewer } from '../pulls/PullChanges';
import { useResizablePane } from '../useResizablePane';
import {
  groupByDay,
  limitDayGroups,
  matchesCommitQuery,
  selectedSpan,
  shortSha,
  squashMessage,
} from './model';

const COMMIT_RENDER_BATCH = 50;
const COMMIT_LOAD_THRESHOLD = 240;

export function nextCommitRenderLimit(
  currentLimit: number,
  visibleCount: number,
  totalCount: number,
  remainingScroll: number,
): number {
  if (visibleCount >= totalCount || remainingScroll >= COMMIT_LOAD_THRESHOLD || currentLimit > visibleCount) {
    return currentLimit;
  }
  return Math.min(totalCount, Math.max(currentLimit, visibleCount) + COMMIT_RENDER_BATCH);
}

function CommitByline({ commit }: { commit: GitCommitDto }) {
  const sameActor = commit.author === commit.committer;
  return (
    <>
      <span
        aria-hidden="true"
        className={`cmt-commit-avatars${sameActor ? '' : ' is-pair'}`}
      >
        <Avatar login={commit.author} avatarUrl={commit.authorAvatarUrl} size={20} allowGitHubFallback={false} />
        {!sameActor && (
          <Avatar
            login={commit.committer}
            avatarUrl={commit.committerAvatarUrl}
            size={20}
            allowGitHubFallback={false}
          />
        )}
      </span>
      <span className="cmt-commit-byline">
        <strong>{commit.author}</strong>
        {sameActor ? ' committed ' : <>{' authored and '}<strong>{commit.committer}</strong>{' committed '}</>}
        <time dateTime={commit.committedAt}>
          {relativeTime(commit.committedAt, { style: 'long' })}
        </time>
      </span>
    </>
  );
}

export default function HistoryView({
  workspaceId,
  branch,
  logRef,
  isUpstream,
  canRewrite,
  commits,
  loading,
  checked,
  filter = '',
  onChangeChecked,
  onHistoryRewritten,
  onOpenPullRequest,
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
  /** Toolbar search query; hides non-matching rows, selection is untouched. */
  filter?: string;
  onChangeChecked: (next: Set<string>) => void;
  onHistoryRewritten: () => void;
  onOpenPullRequest: (number: number) => void;
}) {
  const [viewedSha, setViewedSha] = useState<string | null>(null);
  const [diffFiles, setDiffFiles] = useState<GitDiffFileDto[]>([]);
  const [diffLoading, setDiffLoading] = useState(false);
  const [editTitle, setEditTitle] = useState<string | null>(null);
  const [editBody, setEditBody] = useState<string | null>(null);
  const [squashDraft, setSquashDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const commitPane = useResizablePane(400, 340, 800);
  const [visibleCommitLimit, setVisibleCommitLimit] = useState(COMMIT_RENDER_BATCH);
  const commitList = useRef<HTMLDivElement>(null);

  const span = useMemo(() => selectedSpan(commits, checked), [commits, checked]);
  const spanCommits = useMemo(
    () => (span ? commits.slice(span.lo, span.hi + 1) : []),
    [commits, span],
  );
  const single = !isUpstream && spanCommits.length === 1 ? spanCommits[0] : null;
  const multi = !isUpstream && spanCommits.length > 1;
  const viewedIndex = Math.max(0, commits.findIndex(commit => commit.sha === viewedSha));
  const viewed = commits[viewedIndex] ?? null;
  const dayGroups = useMemo(() => groupByDay(commits), [commits]);
  const query = filter.trim();
  const matchingGroups = useMemo(() => {
    if (query === '') return dayGroups;
    return dayGroups
      .map(group => ({
        ...group,
        commits: group.commits.filter(({ commit }) => matchesCommitQuery(commit, query)),
      }))
      .filter(group => group.commits.length > 0);
  }, [dayGroups, query]);
  const matchingCommitCount = useMemo(
    () => matchingGroups.reduce((total, group) => total + group.commits.length, 0),
    [matchingGroups],
  );
  const requiredIndex = Math.max(
    viewed !== null && matchesCommitQuery(viewed, query) ? viewedIndex : -1,
    span === null ? -1 : span.hi,
  );
  const requiredMatchingCommitCount = useMemo(
    () => matchingGroups.reduce(
      (total, group) => total + group.commits.filter(entry => entry.index <= requiredIndex).length,
      0,
    ),
    [matchingGroups, requiredIndex],
  );
  const requiredCommitLimit = Math.max(visibleCommitLimit, requiredMatchingCommitCount);
  const visibleGroups = useMemo(
    () => limitDayGroups(matchingGroups, requiredCommitLimit),
    [matchingGroups, requiredCommitLimit],
  );
  const visibleCommitCount = useMemo(
    () => visibleGroups.reduce((total, group) => total + group.commits.length, 0),
    [visibleGroups],
  );
  const viewerFiles = useMemo<DiffFileDto[]>(() => diffFiles.map(file => ({
    filename: file.path,
    status: file.status,
    additions: file.additions,
    deletions: file.deletions,
    patch: file.patch ?? null,
  })), [diffFiles]);

  useEffect(() => {
    setVisibleCommitLimit(COMMIT_RENDER_BATCH);
    if (commitList.current) commitList.current.scrollTop = 0;
  }, [filter, isUpstream, logRef, workspaceId]);

  const scrollCommits = (event: ReactUIEvent<HTMLDivElement>) => {
    const list = event.currentTarget;
    const remainingScroll = list.scrollHeight - list.scrollTop - list.clientHeight;
    setVisibleCommitLimit(current => nextCommitRenderLimit(
      current,
      visibleCommitCount,
      matchingCommitCount,
      remainingScroll,
    ));
  };

  // The right pane always shows something concrete: the squash preview for a
  // multi-selection, otherwise the viewed commit.
  const diffBase = multi && span ? `${commits[span.hi].sha}^` : viewed ? `${viewed.sha}^` : null;
  const diffHead = multi && span ? commits[span.lo].sha : viewed?.sha ?? null;

  useEffect(() => {
    if (!diffBase || !diffHead) {
      setDiffFiles([]);
      setDiffLoading(false);
      return;
    }
    let cancelled = false;
    setDiffLoading(true);
    window.bridge.gitDiff(workspaceId, diffBase, diffHead)
      .then(files => {
        if (cancelled) return;
        setDiffFiles(files);
        setError(null);
      })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => {
        if (!cancelled) setDiffLoading(false);
      });
    return () => { cancelled = true; };
    // `commits` retries a diff that failed transiently (e.g. while a sync
    // held the repository) once the next log snapshot arrives.
  }, [workspaceId, diffBase, diffHead, commits]);

  const resetDrafts = () => {
    setEditTitle(null);
    setEditBody(null);
    setSquashDraft(null);
    setNotice(null);
    setError(null);
  };

  // The toolbar's range picker changes `checked` from outside; drop drafts so
  // an edit typed for one commit never applies to a newly selected one.
  useEffect(() => {
    setEditTitle(null);
    setEditBody(null);
    setSquashDraft(null);
  }, [checked]);

  const toggle = (sha: string) => {
    const next = new Set(checked);
    if (next.has(sha)) next.delete(sha);
    else next.add(sha);
    onChangeChecked(next);
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
      <div
        className="cmt-history-left"
        style={{ width: commitPane.width, flexBasis: commitPane.width }}
      >
        {(isUpstream || (spanCommits.length > 0 && !canRewrite)) && (
          <div className="cmt-range-bar">
            {isUpstream && (
              <div className="cmt-hint is-upstream">
                <strong>Upstream · read-only</strong> — check a start and end commit (or type SHAs),
                then cherry-pick the range into {branch}.
              </div>
            )}
            {!isUpstream && spanCommits.length > 0 && !canRewrite && (
              <div className="cmt-hint">
                Check out {branch} to edit messages or squash; {logRef} is only being viewed.
              </div>
            )}
          </div>
        )}
        <div ref={commitList} className="cmt-commit-list" onScroll={scrollCommits}>
          {loading && <div className="cmt-loading">Loading commits…</div>}
          {!loading && commits.length === 0 && <div className="cmt-loading">No commits on this ref.</div>}
          {!loading && commits.length > 0 && matchingGroups.length === 0 && (
            <div className="cmt-loading">No commits match “{query}”.</div>
          )}
          {visibleGroups.map(group => (
            <div className="cmt-day-card" key={group.commits[0].commit.sha}>
              <div className="cmt-day-head">
                <span className="cmt-day-icon">⑂</span>
                <span>{group.label}</span>
                <span className="cmt-day-count">
                  {group.totalCommits} {group.totalCommits === 1 ? 'commit' : 'commits'}
                </span>
              </div>
              {group.commits.map(({ commit, index }) => {
                const inRange = span !== null && index >= span.lo && index <= span.hi;
                const isViewed = viewed?.sha === commit.sha;
                const pullRequestNumber = commit.pullRequestNumber ?? null;
                const pullRequestTitle = commit.pullRequestTitle ?? null;
                return (
                  <div key={commit.sha}>
                    <div
                      className={`cmt-commit-row${inRange ? ' is-selected' : ''}${isViewed && !multi ? ' is-viewed' : ''}`}
                      onClick={() => setViewedSha(commit.sha)}
                    >
                      <input
                        type="checkbox"
                        checked={inRange}
                        aria-label={`Select ${shortSha(commit.sha)}`}
                        onClick={event => event.stopPropagation()}
                        onChange={() => toggle(commit.sha)}
                      />
                      <div className="cmt-commit-main">
                        <div className="cmt-commit-title">{commit.title}</div>
                        <div className="cmt-commit-meta">
                          <CommitByline commit={commit} />
                        </div>
                        {pullRequestNumber !== null && (
                          <button
                            type="button"
                            className="cmt-commit-pr"
                            title={pullRequestTitle
                              ? `Open PR #${pullRequestNumber}: ${pullRequestTitle}`
                              : `Open PR #${pullRequestNumber}`}
                            onClick={event => {
                              event.stopPropagation();
                              onOpenPullRequest(pullRequestNumber);
                            }}
                          >
                            <span className="cmt-chip is-pr">⑃ #{pullRequestNumber}</span>
                            <span className="cmt-commit-pr-title">
                              {pullRequestTitle ?? 'View pull request'}
                            </span>
                          </button>
                        )}
                      </div>
                      <button
                        type="button"
                        className="cmt-commit-sha"
                        title="Copy full commit SHA"
                        aria-label={`Copy full commit SHA ${commit.sha}`}
                        onClick={event => {
                          event.stopPropagation();
                          void navigator.clipboard.writeText(commit.sha);
                        }}
                      >
                        {shortSha(commit.sha)}
                      </button>
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
          {visibleCommitCount < matchingCommitCount && (
            <div className="cmt-commit-more">
              Showing {visibleCommitCount} of {matchingCommitCount} commits · scroll to load more
            </div>
          )}
        </div>
      </div>

      <div
        className={`cmt-history-resize${commitPane.resizing ? ' is-resizing' : ''}`}
        role="separator"
        aria-label="Resize commit history"
        aria-orientation="vertical"
        title="Drag to resize"
        onMouseDown={commitPane.startResize}
      />

      <div className="cmt-history-right">
        {error && <div className="cmt-error" role="alert">{error}</div>}
        <div className="cmt-view-head">
          <div className="cmt-view-head-main">
            <div className="cmt-view-title">
              {multi ? `Range changes — ${spanCommits.length} commits` : viewed?.title ?? ''}
            </div>
            <div className="cmt-view-meta">
              {multi && span
                ? <span>{shortSha(commits[span.hi].sha)}…{shortSha(commits[span.lo].sha)} · squash preview</span>
                : viewed
                  ? (
                    <>
                      <span>{shortSha(viewed.sha)} ·</span>
                      <span className="cmt-view-author">
                        <CommitByline commit={viewed} />
                      </span>
                    </>
                  )
                  : null}
            </div>
          </div>
        </div>
        <DiffViewer
          files={viewerFiles}
          loading={diffLoading}
          loadFileContent={diffBase && diffHead
            ? path => window.bridge.gitDiffFileContent(workspaceId, diffBase, diffHead, path)
            : undefined}
        />
      </div>
    </div>
  );
}
