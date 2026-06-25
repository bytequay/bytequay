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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ResizeHandle from '../ResizeHandle';
import MarkdownComposer from '../MarkdownComposer';
import { CommitsColumn } from '../diff/CommitsColumn';
import { DiffChatColumn } from './DiffChatColumn';
import { ContinuousDiff, FileDiffBody } from '../diff/DiffFileList';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { contiguousRange } from '../diff/commitRange';
import { unionCommitFiles } from '../diff/unionCommitFiles';
import { statusBadge } from '../diffStatusBadge';
import type { DiffFileDto, NotificationDto, ReviewCommentDto, ThreadCommitDto } from '../types';
import { useThreadTasks } from './useThreadTasks';

/** The (file, line) the inline composer is open on, or null when closed.
 *  Local review comments always anchor to the new-side (RIGHT) line. */
type ComposerSlot = { file: string; line: number } | null;

/** One persisted local review comment rendered under its diff row, with a
 *  Resolve / Reopen toggle. Mirrors the inline-finding card visual. */
function ReviewCommentCard({ comment, onChanged }: {
  comment: ReviewCommentDto;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    try {
      if (comment.resolved) await window.bridge.reopenReviewComment(comment.id);
      else await window.bridge.resolveReviewComment(comment.id);
      onChanged();
    }
    catch (e) { console.error('toggle review comment failed', e); }
    finally { setBusy(false); }
  };
  return (
    <div className={`diff-row diff-row--inline-finding${comment.resolved ? ' diff-row--inline-finding--dismissed' : ' diff-row--inline-finding--human'}`}>
      <div className="inline-finding">
        <span className="inline-finding__sev inline-finding__sev--human">✎</span>
        <div className="inline-finding__body">
          <div className="inline-finding__head">
            <span className="inline-finding__source">
              {comment.resolved ? '✓ Resolved review comment' : '⏱ Review comment'}
            </span>
            <span className="inline-finding__loc">{comment.file}:{comment.line}</span>
            <button
              type="button"
              className="inline-finding__edit-btn"
              onClick={() => void toggle()}
              disabled={busy}
              title={comment.resolved ? 'Reopen this comment' : 'Mark this comment resolved'}
            >
              {busy ? '…' : comment.resolved ? '↺ Reopen' : '✓ Resolve'}
            </button>
          </div>
          <div className="inline-finding__text">{comment.body}</div>
        </div>
      </div>
    </div>
  );
}

/** Editable PR title + body panel shown above the diff in review mode.
 *  The text lives in the parent (so Approve can read the live value);
 *  this panel debounces persistence via setShipDescription and flushes
 *  on blur. */
function ShipDescriptionPanel({
  notificationId, title, body, onTitleChange, onBodyChange,
}: {
  notificationId: string;
  title: string;
  body: string;
  onTitleChange: (next: string) => void;
  onBodyChange: (next: string) => void;
}) {
  const [saving, setSaving] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latest = useRef({ title, body });
  latest.current = { title, body };

  const flush = useCallback(async () => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null; }
    setSaving(true);
    try {
      await window.bridge.setShipDescription(notificationId, latest.current.title, latest.current.body);
    }
    catch (e) { console.error('persist ship description failed', e); }
    finally { setSaving(false); }
  }, [notificationId]);

  const schedule = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => { void flush(); }, 800);
  }, [flush]);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  return (
    <div className="ship-description">
      <div className="ship-description__head">
        <span className="ship-description__label">Pull request description</span>
        {saving && <span className="ship-description__saving">Saving…</span>}
      </div>
      <input
        className="ship-description__title"
        value={title}
        onChange={(e) => { onTitleChange(e.target.value); schedule(); }}
        onBlur={() => void flush()}
        placeholder="Pull request title"
        aria-label="Pull request title"
      />
      <div onBlur={() => void flush()}>
        <MarkdownComposer
          value={body}
          onChange={(next) => { onBodyChange(next); schedule(); }}
          placeholder="Describe the change — markdown supported."
          rows={6}
        />
      </div>
    </div>
  );
}

const COMMITS_WIDTH_KEY = 'bytequay.taskCode.commitsWidth';
const FILES_WIDTH_KEY = 'bytequay.taskCode.filesWidth';
const COMMITS_DEFAULT = 230;
const FILES_DEFAULT = 280;
const WIDTH_MIN = 160;
const WIDTH_MAX = 560;

function loadWidth(key: string, fallback: number): number {
  try {
    const n = parseInt(window.localStorage.getItem(key) ?? '', 10);
    return Number.isFinite(n) && n >= WIDTH_MIN && n <= WIDTH_MAX ? n : fallback;
  }
  catch { return fallback; }
}

/**
 * Standalone "Code" page for a task — the diff/files viewer reached from
 * the brain view and the stage detail's "View code diff". It renders the
 * task's diff with the **same components** as the PR review's
 * {@code DiffViewerScreen}: {@link CommitsColumn}, {@link DiffFileTreePane},
 * {@link ResizeHandle}, and the continuous {@link ContinuousDiff} /
 * {@link FileDiffBody} body — so PR-diff rendering changes propagate here.
 * Read-only (no review draft to comment to).
 *
 * Default view is the task's cumulative diff (every commit, base..HEAD);
 * the commits column scopes to one commit or a contiguous range (union),
 * exactly like the PR page.
 */
export default function TaskCodePage({
  threadId, taskId, onBack, stageId,
}: {
  threadId: string;
  taskId: string;
  onBack: () => void;
  /** The stage the diff was opened from — drives the left conversation
   *  column (the dev-stage transcript + an inline steer composer). Absent
   *  when opened outside a stage. */
  stageId?: string;
}) {
  const { tasks } = useThreadTasks(threadId);
  const task = useMemo(() => tasks?.find(t => t.id === taskId) ?? null, [tasks, taskId]);
  const title = task === null
    ? 'Loading…'
    : task.name ?? task.branchName ?? `Task ${task.seq}`;

  const [commits, setCommits] = useState<ThreadCommitDto[] | null>(null);
  // Empty set ⇒ cumulative (all commits); otherwise a contiguous range.
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [rangeAnchor, setRangeAnchor] = useState<string | null>(null);
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [mode, setMode] = useState<FilesPaneMode>('tree');
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  // The middle column folds the old commits + changed-files panels into one
  // tabbed column; the left column is now the conversation.
  const [midTab, setMidTab] = useState<'files' | 'commits'>('files');
  const [convWidth, setConvWidth] = useState(() => loadWidth(COMMITS_WIDTH_KEY, COMMITS_DEFAULT));
  const [filesWidth, setFilesWidth] = useState(() => loadWidth(FILES_WIDTH_KEY, FILES_DEFAULT));
  const bodyRef = useRef<HTMLDivElement>(null);

  // ── Review mode ───────────────────────────────────────────────────────
  // Active only when the task's agent has parked a `ship_task` proposal
  // awaiting approval (an AWAITING_REVIEW notification). The user reviews
  // the diff + drafted PR description, leaves inline comments, submits
  // them to the agent, then approves to ship.
  const [proposal, setProposal] = useState<NotificationDto | null>(null);
  const [reviewComments, setReviewComments] = useState<ReviewCommentDto[]>([]);
  const [composer, setComposer] = useState<ComposerSlot>(null);
  const [composerBody, setComposerBody] = useState('');
  const [composerPending, setComposerPending] = useState(false);
  const [actionNote, setActionNote] = useState<string | null>(null);
  const [actionBusy, setActionBusy] = useState(false);

  // Poll for a pending ship_task proposal, like PendingApprovalToast.
  const refreshProposal = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listNotificationsForThread === undefined) return;
    try {
      const list = await bridge.listNotificationsForThread(threadId);
      const next = list.find((n) => {
        if (n.kind !== 'AWAITING_REVIEW' || n.taskId !== taskId) return false;
        if (n.status !== 'UNREAD' && n.status !== 'RESOLVING') return false;
        try { return JSON.parse(n.payloadJson)?.action === 'ship_task'; }
        catch { return false; }
      }) ?? null;
      setProposal(next);
    }
    catch { /* non-fatal — page stays read-only */ }
  }, [threadId, taskId]);

  useEffect(() => {
    void refreshProposal();
    const t = setInterval(() => { void refreshProposal(); }, 6000);
    return () => clearInterval(t);
  }, [refreshProposal]);

  const reviewMode = proposal !== null;

  // Editable PR title/body. Seeded from the parked payload when a proposal
  // is first detected (keyed by notification id so a new proposal reseeds,
  // but polling refreshes of the same proposal don't clobber edits).
  const [prTitle, setPrTitle] = useState('');
  const [prBody, setPrBody] = useState('');
  const seededFor = useRef<string | null>(null);
  useEffect(() => {
    if (proposal === null) { seededFor.current = null; return; }
    if (seededFor.current === proposal.id) return;
    try {
      const p = JSON.parse(proposal.payloadJson) as { prTitle?: unknown; prBody?: unknown };
      setPrTitle(typeof p.prTitle === 'string' ? p.prTitle : '');
      setPrBody(typeof p.prBody === 'string' ? p.prBody : '');
    }
    catch { setPrTitle(''); setPrBody(''); }
    seededFor.current = proposal.id;
  }, [proposal]);

  // Local review comments, fetched once review mode is active.
  const refreshComments = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listReviewComments === undefined) return;
    try { setReviewComments(await bridge.listReviewComments(taskId)); }
    catch { /* non-fatal */ }
  }, [taskId]);

  useEffect(() => {
    if (!reviewMode) { setReviewComments([]); return; }
    void refreshComments();
  }, [reviewMode, refreshComments]);

  // Index comments by `${file}:${line}` (new-side line, anchors RIGHT).
  const commentsByAnchor = useMemo(() => {
    const map = new Map<string, ReviewCommentDto[]>();
    for (const c of reviewComments) {
      const key = `${c.file}:${c.line}`;
      const list = map.get(key) ?? [];
      list.push(c);
      map.set(key, list);
    }
    return map;
  }, [reviewComments]);

  const openComposer = useCallback((file: string, line: number) => {
    setComposer({ file, line });
    setComposerBody('');
  }, []);
  const closeComposer = useCallback(() => {
    setComposer(null);
    setComposerBody('');
  }, []);

  const saveComment = useCallback(async () => {
    if (composer === null) return;
    const trimmed = composerBody.trim();
    if (trimmed.length === 0) return;
    setComposerPending(true);
    try {
      await window.bridge.addReviewComment(taskId, composer.file, composer.line, trimmed);
      await refreshComments();
      closeComposer();
    }
    catch (e) { console.error('add review comment failed', e); }
    finally { setComposerPending(false); }
  }, [composer, composerBody, taskId, refreshComments, closeComposer]);

  const openCount = useMemo(() => reviewComments.filter(c => !c.resolved).length, [reviewComments]);
  const hasUnresolved = openCount > 0;

  const submitReview = useCallback(async () => {
    if (actionBusy) return;
    setActionBusy(true);
    setActionNote(null);
    try {
      const { submitted } = await window.bridge.submitReview(taskId);
      setActionNote(submitted === 0
        ? 'No unresolved comments to submit.'
        : `Submitted ${submitted} comment${submitted === 1 ? '' : 's'} to the agent.`);
      await refreshComments();
    }
    catch (e) { setActionNote(e instanceof Error ? e.message : String(e)); }
    finally { setActionBusy(false); }
  }, [actionBusy, taskId, refreshComments]);

  const approveShip = useCallback(async () => {
    if (actionBusy || proposal === null || hasUnresolved) return;
    setActionBusy(true);
    setActionNote(null);
    try {
      // Persist the (possibly edited) description first — the panel
      // debounces, so flush the live copy through before the deferred
      // action runs.
      await window.bridge.setShipDescription(proposal.id, prTitle, prBody);
      await window.bridge.approveNotification(proposal.id, null, 'ship_task');
      setProposal(null);
      onBack();
    }
    catch (e) {
      setActionNote(e instanceof Error ? e.message : String(e));
      setActionBusy(false);
    }
  }, [actionBusy, proposal, hasUnresolved, prTitle, prBody, onBack]);

  // Commit list for the left column.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommits(threadId);
        if (!cancelled) setCommits(list);
      }
      catch { if (!cancelled) setCommits([]); }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Diff for the active scope. Empty selection ⇒ cumulative; one commit ⇒
  // that commit; a range ⇒ the union of the selected commits' diffs.
  const selKey = useMemo(() => [...selected].sort().join(','), [selected]);
  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    void (async () => {
      try {
        let list: DiffFileDto[];
        if (selected.size === 0) {
          list = await window.bridge.getTaskCumulativeDiff(threadId);
        }
        else {
          // Fetch each selected commit (in commit order) and union by path.
          const orderedSel = (commits ?? []).map(c => c.sha).filter(sha => selected.has(sha));
          const perCommit = await Promise.all(
            orderedSel.map(sha => window.bridge.getTaskCommitDiffFiles(threadId, sha)));
          list = unionCommitFiles(perCommit, f => f.filename);
        }
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(prev => (prev && list.some(f => f.filename === prev) ? prev : list[0]?.filename ?? null));
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
    // selKey captures the selection contents; commits feed the union order.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId, selKey, commits]);

  const orderedShas = useMemo(() => (commits ?? []).map(c => c.sha), [commits]);

  const onSelectCommit = useCallback((sha: string, extend: boolean) => {
    if (extend && rangeAnchor !== null && selected.size > 0) {
      setSelected(contiguousRange(orderedShas, rangeAnchor, sha));
    }
    else {
      setSelected(new Set([sha]));
      setRangeAnchor(sha);
    }
  }, [rangeAnchor, selected, orderedShas]);

  const onSelectAll = useCallback(() => {
    setSelected(new Set());
    setRangeAnchor(null);
  }, []);

  const toggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  const summary = useMemo(() => (files ?? []).reduce(
    (acc, f) => ({ additions: acc.additions + f.additions, deletions: acc.deletions + f.deletions }),
    { additions: 0, deletions: 0 }), [files]);

  const handleConvResize = useCallback((clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, clientX - rect.left));
    setConvWidth(next);
    try { window.localStorage.setItem(COMMITS_WIDTH_KEY, String(next)); } catch { /* private mode */ }
  }, []);

  const handleFilesResize = useCallback((clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, clientX - rect.left - convWidth - 5));
    setFilesWidth(next);
    try { window.localStorage.setItem(FILES_WIDTH_KEY, String(next)); } catch { /* private mode */ }
  }, [convWidth]);

  return (
    // .diff-viewer is position:absolute/inset:0 — give it a positioned,
    // full-height host since .app-content isn't a positioning context.
    <div style={{ position: 'relative', height: '100%', minHeight: 0 }}>
      <div className="diff-viewer">
        <div className="diff-viewer__toolbar">
          <button className="button button--secondary" onClick={onBack} type="button">
            ← Back
          </button>
          <div className="diff-viewer__title">
            {task?.branchName != null && (
              <span className="diff-viewer__repo">⎇ {task.branchName}</span>
            )}
            <span className="diff-viewer__pr-title">{title}</span>
          </div>
          {reviewMode && (
            <div className="diff-viewer__review-actions">
              {actionNote !== null && (
                <span className="diff-viewer__review-note">{actionNote}</span>
              )}
              <span className="diff-viewer__review-count">
                {openCount} open comment{openCount === 1 ? '' : 's'}
              </span>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void submitReview()}
                disabled={actionBusy}
                title="Send unresolved review comments to the agent"
              >
                Submit review
              </button>
              <button
                type="button"
                className="button button--submit"
                onClick={() => void approveShip()}
                disabled={actionBusy || hasUnresolved}
                title={hasUnresolved
                  ? 'resolve the open review comments first'
                  : 'Approve and ship this task'}
              >
                Approve &amp; ship
              </button>
            </div>
          )}
        </div>

        <div
          className="diff-viewer__body"
          ref={bodyRef}
          style={{ gridTemplateColumns: `${convWidth}px 5px ${filesWidth}px 5px minmax(0, 1fr)` }}
        >
          {/* Conversation column — the originating stage's transcript (with an
              inline steer), or a PR-agent chat scaffold when stageless. */}
          <DiffChatColumn stageId={stageId} />
          <ResizeHandle onResize={handleConvResize} ariaLabel="Resize conversation panel" />

          {/* Middle column: Changed files + Commits folded into two tabs. */}
          <aside className="diff-viewer__files">
            <div className="diff-viewer__files-header">
              <div className="diff-viewer__col-tabs" role="tablist" aria-label="Files or commits">
                <button
                  type="button"
                  role="tab"
                  className={`diff-viewer__col-tab${midTab === 'files' ? ' diff-viewer__col-tab--active' : ''}`}
                  onClick={() => setMidTab('files')}
                  aria-selected={midTab === 'files'}
                >
                  Changed files
                  {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
                </button>
                <button
                  type="button"
                  role="tab"
                  className={`diff-viewer__col-tab${midTab === 'commits' ? ' diff-viewer__col-tab--active' : ''}`}
                  onClick={() => setMidTab('commits')}
                  aria-selected={midTab === 'commits'}
                >
                  Commits
                  {commits !== null && <span className="diff-viewer__files-count">{commits.length}</span>}
                </button>
              </div>
              {midTab === 'files' && (
                <div className="diff-viewer__mode-toggle" role="tablist" aria-label="File list layout">
                  <button
                    type="button"
                    role="tab"
                    className={`diff-viewer__mode-btn${mode === 'tree' ? ' diff-viewer__mode-btn--active' : ''}`}
                    onClick={() => setMode('tree')}
                    aria-selected={mode === 'tree'}
                  >
                    Tree
                  </button>
                  <button
                    type="button"
                    role="tab"
                    className={`diff-viewer__mode-btn${mode === 'flat' ? ' diff-viewer__mode-btn--active' : ''}`}
                    onClick={() => setMode('flat')}
                    aria-selected={mode === 'flat'}
                  >
                    Flat
                  </button>
                </div>
              )}
            </div>
            {midTab === 'files' ? (
              <DiffFileTreePane<DiffFileDto>
                files={files}
                error={error}
                mode={mode}
                pathOf={(f) => f.filename}
                statusBadgeOf={(f) => statusBadge(f.status)}
                selectedPath={selectedPath}
                onSelectPath={setSelectedPath}
                collapsedDirs={collapsedDirs}
                onToggleDir={toggleDir}
              />
            ) : (
              <CommitsColumn
                commits={(commits ?? []).map(c => ({
                  sha: c.sha, subject: c.subject, author: c.authorName, authoredAt: c.authoredAt,
                }))}
                selected={selected}
                onSelectCommit={onSelectCommit}
                onSelectAll={onSelectAll}
                summary={summary}
                loading={files === null}
                collapsed={false}
                onToggleCollapsed={() => { /* embedded as a tab; no collapse */ }}
                embedded
              />
            )}
          </aside>
          <ResizeHandle onResize={handleFilesResize} ariaLabel="Resize changed-files panel" />

          {/* Continuous multi-file diff — the same renderer as the PR page. */}
          <main className="diff-viewer__pane">
            {reviewMode && proposal !== null && (
              <ShipDescriptionPanel
                notificationId={proposal.id}
                title={prTitle}
                body={prBody}
                onTitleChange={setPrTitle}
                onBodyChange={setPrBody}
              />
            )}
            {files !== null && files.length > 0 ? (
              <ContinuousDiff
                files={files}
                selectedPath={selectedPath}
                onActiveFileChange={setSelectedPath}
                renderFileBody={(file) => (reviewMode ? (
                  <FileDiffBody
                    file={file}
                    rowDecoration={(anchorSide, anchorLine) => {
                      // Only new-side rows are commentable; local review
                      // comments anchor RIGHT/newLine like AI findings.
                      if (anchorSide !== 'RIGHT') return null;
                      const hasComment = commentsByAnchor.has(`${file.filename}:${anchorLine}`);
                      return {
                        addCommentAffordance: true,
                        onClick: () => openComposer(file.filename, anchorLine),
                        role: 'button',
                        tabIndex: 0,
                        title: 'Click to leave a review comment on this line',
                        className: (hasComment ? ' diff-row--has-finding' : '') + ' diff-row--commentable',
                      };
                    }}
                    renderAfterRow={(anchorSide, anchorLine) => {
                      if (anchorSide !== 'RIGHT') return null;
                      const here = commentsByAnchor.get(`${file.filename}:${anchorLine}`);
                      const composerHere = composer !== null
                        && composer.file === file.filename
                        && composer.line === anchorLine;
                      if ((here === undefined || here.length === 0) && !composerHere) return null;
                      return (
                        <>
                          {here?.map(c => (
                            <ReviewCommentCard key={c.id} comment={c} onChanged={() => void refreshComments()} />
                          ))}
                          {composerHere && (
                            <div className="diff-inline-composer">
                              <div className="diff-inline-composer__header">
                                Adding a review comment on line {anchorLine}
                              </div>
                              <MarkdownComposer
                                value={composerBody}
                                onChange={setComposerBody}
                                placeholder="Leave a review comment — markdown supported."
                                rows={3}
                                disabled={composerPending}
                                autoFocus
                                textareaClassName="diff-inline-composer__input"
                              />
                              <div className="diff-inline-composer__actions">
                                <button
                                  type="button"
                                  className="button button--primary"
                                  onClick={() => void saveComment()}
                                  disabled={composerPending || composerBody.trim().length === 0}
                                >
                                  {composerPending ? 'Saving…' : 'Save'}
                                </button>
                                <button
                                  type="button"
                                  className="pr-comment-box__cancel"
                                  onClick={closeComposer}
                                  disabled={composerPending}
                                >
                                  Cancel
                                </button>
                              </div>
                            </div>
                          )}
                        </>
                      );
                    }}
                  />
                ) : (
                  <FileDiffBody file={file} />
                ))}
              />
            ) : error !== null ? (
              <div className="diff-viewer__empty">{error}</div>
            ) : files === null ? (
              <div className="diff-viewer__loading">Loading diff…</div>
            ) : (
              <div className="diff-viewer__empty">No changes in this task yet.</div>
            )}
          </main>
        </div>
      </div>
    </div>
  );
}
