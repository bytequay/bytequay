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
import type { LocalPRComment } from '../types/localPr';
import { isLocalStatus } from '../types/localPr';
import { useLocalPr } from './brain/useLocalPr';
import { DiffInlineComments } from '../diff/DiffInlineComments';
import { useThreadTasks } from './useThreadTasks';
import { MarkReadyPanel, type MarkReadyPrRef } from './MarkReadyPanel';
import { ConfirmDialog } from '../workspace/ConfirmDialog';
import { isPendingProposal } from '../notificationDisplay';

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
          initialTab="preview"
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
const WIDTH_MAX = 900;

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
  threadId, taskId, onBack, stageId, embedded = false,
}: {
  threadId: string;
  taskId: string;
  /** Absent when {@code embedded} — the host page's own back nav applies. */
  onBack?: () => void;
  /** The stage the diff was opened from — drives the left conversation
   *  column (the dev-stage transcript + an inline steer composer). Absent
   *  when opened outside a stage. */
  stageId?: string;
  /** True when mounted inline inside another page's tab pane (the brain /
   *  stage-detail "Changes" tab) rather than as its own standalone route —
   *  hides the toolbar (Back button, title, ship actions) since the host
   *  page already renders its own top bar for those. */
  embedded?: boolean;
}) {
  const { tasks, refresh: refreshTasks } = useThreadTasks(threadId);
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
  // Code / Pull request tabs inside the diff panel — Code (the diff) is the
  // default; Pull request shows the drafted PR description. They sit in the
  // right-hand pane so the conversation + changed-files columns stay visible.
  const [paneTab, setPaneTab] = useState<'code' | 'pr'>('code');
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
  // After a successful approve, a confirmation dialog: 'shipped' (first push +
  // PR opened, CI/comments to follow), 'pushed' (a mid-cycle fix pushed to the
  // already-open PR, CI re-runs), 'merged' (the PR was merged), or 'ready'
  // (a draft PR flipped ready for review + reviewers requested).
  const [shipNotice, setShipNotice] = useState<null | 'shipped' | 'pushed' | 'merged' | 'ready'>(null);

  // Poll for a pending ship_task proposal, like PendingApprovalToast.
  const refreshProposal = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listNotificationsForThread === undefined) return;
    try {
      const list = await bridge.listNotificationsForThread(threadId);
      const next = list.find((n) => isPendingProposal(n, taskId)) ?? null;
      setProposal(next);
    }
    catch { /* non-fatal — page stays read-only */ }
  }, [threadId, taskId]);

  useEffect(() => {
    void refreshProposal();
    const t = setInterval(() => { void refreshProposal(); }, 6000);
    return () => clearInterval(t);
  }, [refreshProposal]);

  // The proposal's action drives approval (it must echo back as
  // expectedAction) and which payload keys carry the PR description.
  const proposalAction = useMemo(() => {
    if (proposal === null) return null;
    try { return (JSON.parse(proposal.payloadJson)?.action ?? null) as string | null; }
    catch { return null; }
  }, [proposal]);
  // The `mark_ready` gate (a shipped draft whose CI went green) is not a diff
  // review — the PR already exists. It gets its own pane (reviewers + Mark
  // ready), so keep the diff read-only and the ship toolbar hidden for it.
  const markReadyMode = proposal !== null && proposalAction === 'mark_ready';
  const reviewMode = proposal !== null && !markReadyMode;

  // The task's local PR. While it's in its local phase, this page allows
  // inline local review comments on any line — commenting must not depend
  // on a parked publish gate: the local-review moment (task IN_REVIEW,
  // dev stage closed, PR local-open) has no proposal parked.
  const { bundle: localPrBundle, refresh: refreshLocalPr } = useLocalPr(taskId);
  const localPhasePr = localPrBundle?.pr != null && isLocalStatus(localPrBundle.pr.status)
    ? localPrBundle.pr : null;
  const localCommentMode = !reviewMode && !markReadyMode && localPhasePr !== null;
  const localByAnchor = useMemo(() => {
    const map = new Map<string, LocalPRComment[]>();
    for (const c of localPrBundle?.comments ?? []) {
      if (c.scope !== 'file-line' || c.filePath === null || c.lineNumber === null) continue;
      const key = `${c.filePath}:${c.lineNumber}`;
      const list = map.get(key) ?? [];
      list.push(c);
      map.set(key, list);
    }
    return map;
  }, [localPrBundle]);
  const addLocalComment = useCallback((filePath: string, lineNumber: number, body: string) => {
    if (localPhasePr === null) return;
    void window.bridge.addLocalPrComment(localPhasePr.id, { scope: 'file-line', filePath, lineNumber, body })
      .then(() => refreshLocalPr())
      .catch(() => { /* poll reconciles */ });
  }, [localPhasePr, refreshLocalPr]);
  const resolveLocalComment = useCallback((commentId: string) => {
    void window.bridge.resolveLocalPrComment(commentId)
      .then(() => refreshLocalPr())
      .catch(() => { /* poll reconciles */ });
  }, [refreshLocalPr]);
  const dismissLocalComment = useCallback((commentId: string) => {
    void window.bridge.dismissLocalPrComment(commentId)
      .then(() => refreshLocalPr())
      .catch(() => { /* poll reconciles */ });
  }, [refreshLocalPr]);
  // Only the PR-opening gates carry a title/body to review + edit. A bare
  // `push` gate pushes the branch; the agent opens the PR (with its
  // description) as the next gate — so show that instead of an empty editor.
  const hasPrDescription = proposalAction === 'ship_task' || proposalAction === 'open_pr';

  // The PR the mark-ready gate targets (owner/repo/number from the payload).
  const markReadyPr = useMemo<MarkReadyPrRef | null>(() => {
    if (!markReadyMode || proposal === null) return null;
    try {
      const ref = JSON.parse(proposal.payloadJson)?.pr as {
        owner?: unknown; repo?: unknown; number?: unknown;
      };
      if (typeof ref?.owner === 'string' && typeof ref?.repo === 'string'
          && typeof ref?.number === 'number') {
        return { owner: ref.owner, repo: ref.repo, number: ref.number };
      }
    }
    catch { /* fall through to null */ }
    return null;
  }, [markReadyMode, proposal]);

  // The mark-ready gate lives on the pull-request pane — surface it the
  // moment the gate appears so navigating in lands directly on it.
  useEffect(() => { if (markReadyMode) setPaneTab('pr'); }, [markReadyMode]);

  // Editable PR title/body. Seeded from the parked payload when a proposal
  // is first detected (keyed by notification id so a new proposal reseeds,
  // but polling refreshes of the same proposal don't clobber edits). A
  // ship_task park carries prTitle/prBody; an open_pr park carries title/body.
  const [prTitle, setPrTitle] = useState('');
  const [prBody, setPrBody] = useState('');
  const seededFor = useRef<string | null>(null);
  useEffect(() => {
    if (proposal === null) { seededFor.current = null; return; }
    if (seededFor.current === proposal.id) return;
    try {
      const p = JSON.parse(proposal.payloadJson) as {
        prTitle?: unknown; prBody?: unknown; title?: unknown; body?: unknown;
      };
      const t = typeof p.prTitle === 'string' ? p.prTitle : (typeof p.title === 'string' ? p.title : '');
      const b = typeof p.prBody === 'string' ? p.prBody : (typeof p.body === 'string' ? p.body : '');
      setPrTitle(t);
      setPrBody(b);
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
      // expectedAction must echo the proposal's real action — the server
      // 409s on a mismatch. ship_task stores its edited title+body via
      // setShipDescription (approve takes no body); open_pr (and the other
      // body-only gates) take the edited body straight through approve.
      const action = proposalAction ?? 'ship_task';
      let result;
      if (action === 'ship_task') {
        await window.bridge.setShipDescription(proposal.id, prTitle, prBody);
        result = await window.bridge.approveNotification(proposal.id, null, action);
      }
      else {
        result = await window.bridge.approveNotification(proposal.id, prBody, action);
      }
      // The publish only succeeded if the server resolved it 'approved'. An
      // 'interrupted' / 'failed' result means nothing (or an unknown amount)
      // reached the remote — surface the reason and stay put rather than
      // navigating away as if it shipped.
      if (result.resolution !== 'approved') {
        setActionNote(result.message);
        setActionBusy(false);
        await refreshProposal();
        return;
      }
      // Success — confirm what happens next instead of silently leaving. The
      // gate is resolved, so the proposal clears (review mode ends); refetch
      // the task so its status (IN_REVIEW/COMPLETED) is current by the time
      // the ship-notice dialog closes — otherwise the toolbar just vanishes
      // with no Shipped/Finalized pill to replace it, even on "Stay here".
      setProposal(null);
      setActionBusy(false);
      void refreshTasks();
      // A bare `push` updates an already-open PR (the mid-cycle CI-fix /
      // address-comments loop); `ship_task` / `open_pr` are the first push
      // that opens the draft PR.
      setShipNotice(
        action === 'merge_pr' ? 'merged'
          : action === 'push' ? 'pushed'
            : 'shipped');
    }
    catch (e) {
      setActionNote(e instanceof Error ? e.message : String(e));
      setActionBusy(false);
    }
  }, [actionBusy, proposal, proposalAction, hasUnresolved, prTitle, prBody, onBack, refreshProposal, refreshTasks]);

  // Commit list for the left column.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommits(threadId, taskId);
        if (!cancelled) setCommits(list);
      }
      catch { if (!cancelled) setCommits([]); }
    })();
    return () => { cancelled = true; };
  }, [threadId, taskId]);

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
          list = await window.bridge.getTaskCumulativeDiff(threadId, taskId);
        }
        else {
          // Fetch each selected commit (in commit order) and union by path.
          const orderedSel = (commits ?? []).map(c => c.sha).filter(sha => selected.has(sha));
          const perCommit = await Promise.all(
            orderedSel.map(sha => window.bridge.getTaskCommitDiffFiles(threadId, sha, taskId)));
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
  }, [threadId, taskId, selKey, commits]);

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
    // Embedded drops the conversation column entirely (see the render below),
    // so the files column starts flush at the body's left edge — no conv
    // column + its resize handle to subtract first.
    const leadingOffset = embedded ? 0 : convWidth + 5;
    const next = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, clientX - rect.left - leadingOffset));
    setFilesWidth(next);
    try { window.localStorage.setItem(FILES_WIDTH_KEY, String(next)); } catch { /* private mode */ }
  }, [convWidth, embedded]);

  return (
    // .diff-viewer is position:absolute/inset:0 — give it a positioned,
    // full-height host since .app-content isn't a positioning context.
    <div style={{ position: 'relative', height: '100%', minHeight: 0 }}>
      <div className="diff-viewer">
        {/* The whole toolbar — Back/title, and the open-comments/ship actions
            — is the host page's job when embedded: StageDetailPage /
            TaskBrainPage already render their own title bar and Submit
            review button, so this would just duplicate it. */}
        {!embedded && (
          <div className="diff-viewer__toolbar">
            <button className="button button--secondary" onClick={onBack} type="button">
              ← Back
            </button>
            <div className="diff-viewer__title">
              {task?.branchName != null && (
                <span className="diff-viewer__repo">⎇ {task.branchName}</span>
              )}
              <span className="diff-viewer__pr-title">{title}</span>
              {(task?.status === 'IN_REVIEW' || task?.status === 'COMPLETED') && (
                <span className="diff-viewer__shipped" title="This task has been shipped">
                  <span aria-hidden>✓</span>
                  {task.status === 'COMPLETED' ? 'Finalized' : 'Shipped'}
                </span>
              )}
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
                    : proposalAction === 'merge_pr'
                      ? 'Approve and merge this pull request'
                      : 'Approve and ship this task'}
                >
                  {proposalAction === 'merge_pr' ? 'Approve & merge' : 'Approve & ship'}
                </button>
              </div>
            )}
          </div>
        )}

        <div
          className="diff-viewer__body"
          ref={bodyRef}
          style={embedded
            ? { gridTemplateColumns: `${filesWidth}px 5px minmax(0, 1fr)` }
            : { gridTemplateColumns: `${convWidth}px 5px ${filesWidth}px 5px minmax(0, 1fr)` }}
        >
          {/* Conversation column — the originating stage's transcript (with an
              inline steer), or a PR-agent chat scaffold when stageless. Dropped
              when embedded: the host page (brain / stage-detail) already shows
              its own conversation alongside this tab, so this would just be a
              second, redundant copy of the same transcript. */}
          {!embedded && (
            <>
              <DiffChatColumn stageId={stageId} taskId={taskId} threadId={threadId} />
              <ResizeHandle onResize={handleConvResize} ariaLabel="Resize conversation panel" />
            </>
          )}

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

          {/* Right pane — Code (the diff) or Pull request (the description),
              switched by tabs that live inside the pane so the conversation
              + changed-files columns stay put. */}
          <main className="diff-viewer__pane">
            <div className="diff-viewer__pane-tabs" role="tablist" aria-label="Code or pull request">
              <button
                type="button"
                role="tab"
                aria-selected={paneTab === 'code'}
                className={`diff-viewer__pane-tab${paneTab === 'code' ? ' diff-viewer__pane-tab--active' : ''}`}
                onClick={() => setPaneTab('code')}
              >
                Code
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={paneTab === 'pr'}
                className={`diff-viewer__pane-tab${paneTab === 'pr' ? ' diff-viewer__pane-tab--active' : ''}`}
                onClick={() => setPaneTab('pr')}
              >
                Pull request
              </button>
            </div>
            {paneTab === 'pr' ? (
              <div className="diff-viewer__pr-pane">
                {markReadyMode && markReadyPr !== null && proposal !== null ? (
                  <MarkReadyPanel
                    notificationId={proposal.id}
                    pr={markReadyPr}
                    onMarked={() => { setProposal(null); setShipNotice('ready'); }}
                  />
                ) : proposal !== null && hasPrDescription ? (
                  <ShipDescriptionPanel
                    notificationId={proposal.id}
                    title={prTitle}
                    body={prBody}
                    onTitleChange={setPrTitle}
                    onBodyChange={setPrBody}
                  />
                ) : proposal !== null ? (
                  <div className="diff-viewer__empty">
                    No pull request to describe yet. Approving pushes this branch;
                    the agent then opens the pull request — with its title and
                    description — as the next step, which you’ll review here.
                  </div>
                ) : (
                  <div className="diff-viewer__empty">
                    No pull request yet — ship the task to open one.
                  </div>
                )}
              </div>
            ) : files !== null && files.length > 0 ? (
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
                ) : localCommentMode ? (
                  <FileDiffBody
                    file={file}
                    rowDecoration={(anchorSide, anchorLine) => {
                      // Local comments anchor to new-side lines, like the
                      // stage pane's diff and the gate-review flow above.
                      if (anchorSide !== 'RIGHT') return null;
                      const hasComment = localByAnchor.has(`${file.filename}:${anchorLine}`);
                      const composerHere = composer !== null
                        && composer.file === file.filename
                        && composer.line === anchorLine;
                      return {
                        addCommentAffordance: true,
                        // Clicking the line again discards its open composer.
                        onClick: () => (composerHere ? closeComposer() : openComposer(file.filename, anchorLine)),
                        role: 'button',
                        tabIndex: 0,
                        title: 'Click to leave a local review comment on this line',
                        className: (hasComment ? ' diff-row--has-finding' : '') + ' diff-row--commentable',
                      };
                    }}
                    renderAfterRow={(anchorSide, anchorLine) => {
                      if (anchorSide !== 'RIGHT') return null;
                      const here = localByAnchor.get(`${file.filename}:${anchorLine}`) ?? [];
                      const composerHere = composer !== null
                        && composer.file === file.filename
                        && composer.line === anchorLine;
                      if (here.length === 0 && !composerHere) return null;
                      return (
                        <DiffInlineComments
                          comments={here}
                          allowLocalComments
                          onAdd={composerHere
                            ? body => { addLocalComment(file.filename, anchorLine, body); closeComposer(); }
                            : undefined}
                          onResolve={resolveLocalComment}
                          onDismiss={dismissLocalComment}
                          onCancel={composerHere ? closeComposer : undefined}
                        />
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
      {shipNotice !== null && (
        <ConfirmDialog
          title={shipNotice === 'merged'
            ? 'Pull request merged'
            : shipNotice === 'ready'
              ? 'Marked ready for review'
              : shipNotice === 'pushed'
                ? 'Fix pushed'
                : 'Changes approved'}
          body={shipNotice === 'merged'
            ? 'The pull request was merged and the task is complete.'
            : shipNotice === 'ready'
              ? 'The pull request is out of draft and ready for review. Any reviewers you '
                + 'requested have been notified, and incoming review comments are addressed '
                + 'automatically from here.'
              : shipNotice === 'pushed'
                ? 'Your changes are pushed to the open pull request. CI re-runs automatically — '
                  + 'come back to merge once it’s green.'
                : 'The branch is pushed and a draft pull request is open. CI fixes and review '
                  + 'comments are handled automatically from here — come back to merge once it’s ready.'}
          confirmLabel="Back to thread"
          cancelLabel="Stay here"
          onConfirm={() => { setShipNotice(null); onBack?.(); }}
          onCancel={() => setShipNotice(null)}
        />
      )}
    </div>
  );
}
