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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { renderMarkdown } from './markdown';
import type { ActivityItemDto, CheckRunDto, MergeConflictPathsDto, PullRequestDetailDto, PullRequestDto, ReviewMessageDto, ReviewThreadDto, TaskRefDto, UserProfileDto } from './types';
import { getCached as getCachedValue } from './dataCache';
import { EditableMarkdownBody } from './pr/EditableMarkdownBody';
import { EditableTitle } from './components/EditableTitle';
import Avatar from './Avatar';
import ResizeHandle from './ResizeHandle';
import {
  activityVerb,
  approvalCountsTowardMerge,
  authorAssociationLabel,
  conclusionLabel,
  displayActor,
  eventMarker,
  isBotActor,
  isCheckFailing,
  isMergeQueueEvent,
  labelChipStyle,
  relativeDayLabel,
  truncatePath,
  type ReactionContent,
} from './pr/utils';
import {
  mergeFetchedComments,
  optimisticallyAppendReply,
  optimisticallyBumpReaction,
  optimisticallyRemoveComment,
  optimisticallyToggleResolved,
  optimisticallyUpdateCommentBody,
} from './pr/optimisticUpdates';
import { CommentActionsMenu, issueCommentLink } from './pr/CommentActionsMenu';
import { ReactionChips } from './pr/Reactions';
import { CiChecksRow, CiSummary } from './pr/Ci';
import LogoLoading from './LogoLoading';
import { DescriptionCard } from './pr/DescriptionCard';
import { PrCommentBox, type PrCommentBoxHandle } from './pr/PrCommentBox';
import { describePrChange } from './pr/prFreshness';
import { ReviewerEditor } from './pr/ReviewerEditor';
import { ReviewThreadCard } from './pr/ReviewThreadCard';
import { RelativeTime } from './pr/RelativeTime';
import { groupTimelineEntries, type RawTimelineEntry, type TimelineEntry } from './pr/timelineGrouping';

const SIDE_WIDTH_KEY = 'settings:preview-conversation-width';
const SIDE_WIDTH_MIN = 180;
const SIDE_WIDTH_MAX = 520;
const SIDE_WIDTH_DEFAULT = 260;
const SIDEBAR_COLLAPSED_KEY = 'settings:pr-detail-sidebar-collapsed';

/** Threshold past which the failure summary auto-collapses to a teaser
 *  with a "Show more" button. Long check outputs (think a CI log dump
 *  with 200 lines of stack trace) shouldn't blow out the merge bar by
 *  default; reviewers can click through if they want the full text. */
const FAILURE_SUMMARY_COLLAPSED_CHARS = 600;

/** Patterns the merge bar highlights inside an Actions log so failing
 *  lines pop visually — exactly the markers reviewers grep for first
 *  ([ERROR], [FAILURE], etc.). Case-insensitive. The pattern is split
 *  across alternations rather than one mega-regex so the visible match
 *  stays compact (we wrap the matched substring, not whole lines). */
const LOG_ERROR_PATTERN = /(\[ERROR\]|\[FAILURE\]|\[FATAL\]|\[FAIL(?:ED)?\]|Caused by:|Exception(?: in thread)?[: ]|Error:|FAILED|Stack trace:|Traceback)/gi;

/** Splits a log blob into alternating plain / highlighted pieces so the
 *  failure markers can be wrapped in a styled span. Returns the original
 *  text as a single plain piece when no marker is present. */
function highlightLogText(text: string): ReactNode[] {
  if (!text) return [text];
  const out: ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  // Reset state on the global regex — it's a module-level constant.
  LOG_ERROR_PATTERN.lastIndex = 0;
  while ((match = LOG_ERROR_PATTERN.exec(text)) !== null) {
    if (match.index > lastIndex) {
      out.push(text.slice(lastIndex, match.index));
    }
    out.push(<mark key={`m-${match.index}`} className="merge-bar__failure-pre__hit">{match[0]}</mark>);
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < text.length) {
    out.push(text.slice(lastIndex));
  }
  return out;
}

function FailingCheckCard({ check, repo }: { check: CheckRunDto; repo: string }) {
  const [bodyOpen, setBodyOpen] = useState(false);
  const name = check.name && check.name.trim().length > 0 ? check.name : '(unnamed check)';
  const title = check.outputTitle && check.outputTitle.trim().length > 0 ? check.outputTitle.trim() : null;
  const summary = check.outputSummary && check.outputSummary.trim().length > 0 ? check.outputSummary.trim() : null;
  const hasSummary = title !== null || summary !== null;
  const canFetchLog = check.githubId != null;
  // Body is openable whenever there's anything to show — either an
  // inline summary OR a fetchable Actions log.
  const hasErrorBody = hasSummary || canFetchLog;
  const longSummary = summary !== null && summary.length > FAILURE_SUMMARY_COLLAPSED_CHARS;
  const [summaryExpanded, setSummaryExpanded] = useState(false);
  const visibleSummary = summary === null
    ? null
    : !longSummary || summaryExpanded
      ? summary
      : summary.slice(0, FAILURE_SUMMARY_COLLAPSED_CHARS) + '…';

  // Full-log state. Lazy-fetched when the user clicks "Show full log";
  // null means "not fetched yet". Empty string after a successful fetch
  // means "GitHub returned no log" (external CI / expired). Error
  // string carries any IPC failure message.
  const [logState, setLogState] = useState<{ status: 'idle' | 'loading' | 'loaded' | 'error'; text: string; error: string | null }>(
    { status: 'idle', text: '', error: null },
  );
  const [logVisible, setLogVisible] = useState(false);
  const loadLog = async () => {
    if (check.githubId == null) return;
    setLogVisible(true);
    if (logState.status === 'loading' || logState.status === 'loaded') return;
    setLogState({ status: 'loading', text: '', error: null });
    try {
      const r = await window.bridge.fetchCheckLog(repo, check.githubId);
      setLogState({ status: 'loaded', text: r.log ?? '', error: null });
    }
    catch (e) {
      setLogState({ status: 'error', text: '', error: e instanceof Error ? e.message : String(e) });
    }
  };

  // Ask-AI state. The button is enabled when we have either a fetched
  // log OR an inline outputSummary — anything is better than no context.
  const [aiState, setAiState] = useState<{ status: 'idle' | 'loading' | 'loaded' | 'error'; text: string; error: string | null }>(
    { status: 'idle', text: '', error: null },
  );
  const askAi = async () => {
    // Prefer the freshly-fetched full log; fall back to the inline
    // summary if the user clicked Ask AI before opening the log.
    const context = logState.status === 'loaded' && logState.text !== ''
      ? logState.text
      : (summary ?? title ?? '');
    if (!context) return;
    setAiState({ status: 'loading', text: '', error: null });
    try {
      const suggestion = await window.bridge.diagnoseCheckFailure(name, context);
      setAiState({ status: 'loaded', text: suggestion, error: null });
    }
    catch (e) {
      setAiState({ status: 'error', text: '', error: e instanceof Error ? e.message : String(e) });
    }
  };
  const canAsk = logState.status === 'loaded' && logState.text !== '' || hasSummary;
  return (
    <li className="merge-bar__failure-card">
      <button
        type="button"
        className="merge-bar__failure-head"
        onClick={() => hasErrorBody && setBodyOpen(v => !v)}
        disabled={!hasErrorBody}
        aria-expanded={hasErrorBody ? bodyOpen : undefined}
      >
        <span className="merge-bar__failure-icon" aria-hidden="true">✗</span>
        <div className="merge-bar__failure-text">
          <span className="merge-bar__failure-name" title={name}>{name}</span>
          <span className="merge-bar__failure-reason">
            {title ?? conclusionLabel(check.conclusion)}
          </span>
        </div>
        {check.htmlUrl && (
          <a
            className="merge-bar__failure-link"
            href={check.htmlUrl}
            target="_blank"
            rel="noreferrer"
            title={`Open ${name} on GitHub`}
            // Don't bubble the click into the toggle — clicking the link
            // opens GitHub, not the body collapse.
            onClick={(e) => e.stopPropagation()}
          >
            View ↗
          </a>
        )}
        {hasErrorBody && (
          <span className="merge-bar__failure-chevron" aria-hidden="true">{bodyOpen ? '▾' : '▸'}</span>
        )}
      </button>
      {bodyOpen && hasErrorBody && (
        <div className="merge-bar__failure-body">
          {hasSummary && (
            <>
              {/* Render in a <pre> so stack traces and indented test
                  output keep their formatting. */}
              <pre className="merge-bar__failure-pre">{visibleSummary ?? title}</pre>
              {longSummary && (
                <button
                  type="button"
                  className="merge-bar__failure-more"
                  onClick={() => setSummaryExpanded(v => !v)}
                >
                  {summaryExpanded ? 'Show less' : `Show more (${summary!.length} chars)`}
                </button>
              )}
            </>
          )}
          {canFetchLog && (
            <div className="merge-bar__failure-log">
              <div className="merge-bar__failure-log-bar">
                <button
                  type="button"
                  className="merge-bar__failure-more"
                  onClick={() => {
                    if (logVisible) {
                      setLogVisible(false);
                    }
                    else {
                      void loadLog();
                    }
                  }}
                  disabled={logState.status === 'loading'}
                >
                  {logVisible
                    ? 'Hide full log'
                    : logState.status === 'loading'
                      ? 'Loading log…'
                      : 'Show full log'}
                </button>
                <button
                  type="button"
                  className="merge-bar__failure-ai-btn"
                  onClick={() => { void askAi(); }}
                  disabled={!canAsk || aiState.status === 'loading'}
                  title={canAsk
                    ? 'Send the failure log to the active AI provider for a root-cause + fix suggestion'
                    : 'Open the log first so the AI has context to work with'}
                >
                  {aiState.status === 'loading' ? 'Asking AI…' : '✨ Ask AI to fix'}
                </button>
              </div>
              {logVisible && logState.status === 'loading' && (
                <div className="merge-bar__failure-log-status">Fetching log from GitHub…</div>
              )}
              {logVisible && logState.status === 'error' && (
                <div className="merge-bar__failure-log-status merge-bar__failure-log-status--error">
                  Couldn’t load log: {logState.error}
                </div>
              )}
              {logVisible && logState.status === 'loaded' && logState.text === '' && (
                <div className="merge-bar__failure-log-status">
                  No log available — check probably ran on an external CI, or the log expired.
                </div>
              )}
              {logVisible && logState.status === 'loaded' && logState.text !== '' && (
                <pre className="merge-bar__failure-pre merge-bar__failure-pre--log">
                  {highlightLogText(logState.text)}
                </pre>
              )}
              {aiState.status === 'error' && (
                <div className="merge-bar__failure-log-status merge-bar__failure-log-status--error">
                  AI request failed: {aiState.error}
                </div>
              )}
              {aiState.status === 'loaded' && aiState.text !== '' && (
                <div className="merge-bar__failure-ai">
                  <div className="merge-bar__failure-ai-head">
                    <span aria-hidden="true">✨</span>
                    <span>AI suggestion</span>
                  </div>
                  <div
                    className="merge-bar__failure-ai-body"
                    dangerouslySetInnerHTML={{ __html: renderMarkdown(aiState.text) }}
                  />
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </li>
  );
}

/** GitHub's three merge strategies. Backend mirrors the same identifiers
 *  in PullRequestService#strategyCommand. */
type MergeStrategy = 'rebase' | 'squash' | 'merge';

const MERGE_STRATEGY_LABEL: Record<MergeStrategy, string> = {
  rebase: 'Rebase and merge',
  squash: 'Squash and merge',
  merge: 'Create a merge commit',
};

const MERGE_STRATEGY_HINT: Record<MergeStrategy, string> = {
  rebase: "Replays the PR's commits onto the base branch one by one. Linear history; fails when the branch already has merge commits.",
  squash: 'Combines every commit in the PR into a single commit on the base branch.',
  merge: 'Creates a merge commit that joins the PR branch into the base.',
};

const MERGE_STRATEGY_KEY = 'settings:merge-strategy';

function loadMergeStrategy(): MergeStrategy {
  const stored = localStorage.getItem(MERGE_STRATEGY_KEY);
  return stored === 'squash' || stored === 'merge' ? stored : 'rebase';
}

/** Small circular status glyph used on each merge-card row — mirrors
 *  github.com's green check / red x / yellow spinner at the start of
 *  every blocker row. The 'pending' state renders as an animated
 *  arc-spinner (not a static glyph) so a running CI reads as actively
 *  running, not parked. */
function StatusGlyph({ state }: { state: 'approved' | 'pending' | 'changes' }) {
  if (state === 'pending') {
    return (
      <span
        className="merge-card__glyph merge-card__glyph--pending"
        aria-hidden="true"
        role="img"
        aria-label="Running"
      />
    );
  }
  const glyph = state === 'approved' ? '✓' : '✕';
  return (
    <span className={`merge-card__glyph merge-card__glyph--${state}`} aria-hidden="true">
      {glyph}
    </span>
  );
}

type MergeBarProps = {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  mergeState: 'idle' | 'running' | 'error' | 'queued';
  mergeError: string | null;
  /** Message returned by the backend when the merge was accepted into
   *  a merge queue ({@code mergeState === 'queued'}). Drives the success
   *  banner so the user sees "Added to merge queue (position 3, awaiting
   *  checks)" instead of a generic "merged" affordance. */
  mergeQueuedMessage: string | null;
  onMerge: (strategy: MergeStrategy) => void;
  /** Click handler for the "Merge when ready" path — enables GitHub's
   *  auto-merge so the PR merges automatically once required checks
   *  pass. Optional so this prop can be omitted by callers that haven't
   *  wired it; in that case the button falls back to its disabled
   *  "wait for CI" treatment. */
  onEnableAutoMerge?: (strategy: MergeStrategy) => void;
  /** Removes the PR from its repo's merge queue. Triggered by the
   *  "Remove from queue" button on the queued-card variant — only
   *  shown when GitHub reports the PR sits in a queue. */
  onDequeue?: () => void;
  /** Force-refresh the CI snapshot (status + per-check + viewerCanWrite)
   *  from GitHub. Wired through from the parent's refreshCi so the
   *  refresh button on the CI pill bypasses the focus poll's cadence. */
  onRefreshCi: () => void | Promise<void>;
  ciRefreshing: boolean;
  /** Whether the PR has an active task worktree we can push an empty
   *  commit from (enables the empty-commit CI fallback). */
  canEmptyCommit: boolean;
};

/** Humanises GitHub's merge-queue entry state into the small chip on
 *  the queued card. Only the states we can phrase confidently get a
 *  chip; anything else (or null) returns null so the card stays clean
 *  rather than echoing a raw enum. */
function queueStateLabel(state: string | null): string | null {
  switch (state) {
    case 'QUEUED': return 'In line';
    case 'AWAITING_CHECKS':
    case 'PENDING': return 'Checking';
    case 'MERGEABLE': return 'Ready';
    case 'UNMERGEABLE': return 'Blocked';
    case 'LOCKED': return 'Locked';
    default: return null;
  }
}

/** Approval-count + "Rebase and merge" bar that sits above the comment
 *  box on the PR detail page. The button is intentionally always
 *  visible-but-greyed when the PR isn't ready — hover the disabled
 *  button to read why. Mirrors the merge-bar GitHub puts on its
 *  Conversation page. Clicking the button opens a confirm dialog —
 *  Yes fires the merge, No closes the dialog with no side effects. */
function MergeBar({ pr, detail, mergeState, mergeError, mergeQueuedMessage, onMerge, onEnableAutoMerge, onDequeue, onRefreshCi, ciRefreshing, canEmptyCommit }: MergeBarProps) {
  // Failing-check list is folded by default — the red "CI failing"
  // pill in the middle of the bar is the affordance to expand it.
  const [failuresOpen, setFailuresOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  // Re-run the failed CI jobs on GitHub — the one-click flaky-failure
  // fix. Self-contained: hits the bridge directly and refreshes CI so the
  // re-running jobs flip to in-progress. 'empty' = nothing on the head had
  // failed (so there was nothing to re-run).
  const [rerunState, setRerunState] = useState<'idle' | 'running' | 'queued' | 'empty' | 'error'>('idle');
  const handleRerunChecks = async () => {
    if (rerunState === 'running') {
      return;
    }
    setRerunState('running');
    try {
      const { rerunCount } = await window.bridge.rerunChecks(pr.repo, pr.number);
      setRerunState(rerunCount > 0 ? 'queued' : 'empty');
      if (rerunCount > 0) {
        // GitHub re-runs asynchronously — an immediate fetch races the
        // propagation and catches the stale "failed" attempts. Refresh
        // after a beat (and again) so the checks read as in-progress.
        window.setTimeout(() => { void onRefreshCi(); }, 2500);
        window.setTimeout(() => { void onRefreshCi(); }, 8000);
      }
      window.setTimeout(() => setRerunState('idle'), 4000);
    }
    catch {
      setRerunState('error');
      window.setTimeout(() => setRerunState('idle'), 4000);
    }
  };
  // Fallback for push-driven CI: push an empty commit to the PR's branch
  // via its task worktree. 'unavailable' = no pushable worktree (the
  // backend's reason).
  const [emptyCommitState, setEmptyCommitState] =
    useState<'idle' | 'running' | 'pushed' | 'unavailable' | 'error'>('idle');
  const handleEmptyCommit = async () => {
    if (emptyCommitState === 'running') {
      return;
    }
    setEmptyCommitState('running');
    try {
      const { triggered } = await window.bridge.triggerCi(pr.repo, pr.number);
      setEmptyCommitState(triggered ? 'pushed' : 'unavailable');
      if (triggered) {
        // The push moves the head SHA and CI re-registers on it — slower
        // than a job re-run, so refresh later (and again) to catch the
        // new run instead of the prior head's stale checks.
        window.setTimeout(() => { void onRefreshCi(); }, 5000);
        window.setTimeout(() => { void onRefreshCi(); }, 15000);
      }
      window.setTimeout(() => setEmptyCommitState('idle'), 4000);
    }
    catch {
      setEmptyCommitState('error');
      window.setTimeout(() => setEmptyCommitState('idle'), 4000);
    }
  };
  // Selected merge strategy. Persisted in localStorage so the user's
  // last choice (e.g. "Squash and merge" for repos with non-linear
  // feature branches) is the default next time. The split-button
  // dropdown lets them pick a different one per merge.
  const [strategy, setStrategyRaw] = useState<MergeStrategy>(loadMergeStrategy);
  const setStrategy = (next: MergeStrategy) => {
    setStrategyRaw(next);
    localStorage.setItem(MERGE_STRATEGY_KEY, next);
  };
  const [strategyMenuOpen, setStrategyMenuOpen] = useState(false);
  const strategyCaretRef = useRef<HTMLButtonElement>(null);
  const [strategyMenuPos, setStrategyMenuPos] = useState<{ top: number; left: number } | null>(null);
  const failingChecks = detail.checkRuns.filter(c => isCheckFailing(c.conclusion));
  const ciPassing = detail.ciStatus === 'PASSING' || detail.ciStatus === 'NONE';
  const ciPending = detail.ciStatus === 'PENDING';
  const closed = pr.state === 'closed';
  // GitHub's mergeable_state="dirty" is the canonical "head and base
  // conflict at the file level" signal. Other false-values ('blocked',
  // 'behind') aren't file conflicts — surfacing those here would
  // wrongly imply the author needs to resolve text.
  const hasConflict = (detail.mergeableState ?? pr.mergeableState) === 'dirty';
  // Conflict-path probe — runs `git merge-tree --name-only` in the
  // local clone so we can show "Conflict (N files)" + an expandable
  // file list. When unavailable (no local clone, fetch failed) the
  // pill stays as a plain link to github.com's editor.
  const [conflictPaths, setConflictPaths] = useState<MergeConflictPathsDto | null>(null);
  const [conflictExpanded, setConflictExpanded] = useState(false);
  useEffect(() => {
    if (!hasConflict) {
      setConflictPaths(null);
      return;
    }
    const baseRef = detail.baseRef ?? null;
    const [owner, repoName] = pr.repo.split('/');
    if (!baseRef || !owner || !repoName) {
      return;
    }
    let cancelled = false;
    void window.bridge.fetchPrConflictPaths(owner, repoName, pr.number, baseRef)
      .then((res) => { if (!cancelled) setConflictPaths(res); })
      .catch(() => {
        // Network / IPC error — keep the pill in its
        // unavailable state. The github.com link still works.
        if (!cancelled) setConflictPaths({ available: false, reason: 'fetch_error', paths: [] });
      });
    return () => { cancelled = true; };
  }, [hasConflict, detail.baseRef, pr.repo, pr.number]);
  // `mergeMode` is computed below, after `requiresMergeQueue` lands,
  // because the queue heuristic needs `approverLogins`. `enabled`
  // ultimately follows from it, but the disabled-reason text only
  // depends on the conditions visible here, so we set the reason now.
  // CI-failing is intentionally NOT in the disabled-reason text anymore —
  // the red "CI failing" pill in the middle of the bar carries that
  // signal, and expanding it shows per-check details. Keeping both would
  // double up on the same information.
  let disabledReason: string | null = null;
  if (closed) {
    disabledReason = 'This PR is closed.';
  } else if (!detail.viewerCanWrite) {
    disabledReason = 'You don’t have write access to this repository.';
  } else if (hasConflict) {
    // Take precedence over the CI-pending message — the author can't
    // do anything useful until conflicts are resolved.
    disabledReason = 'This PR has file conflicts — resolve them on github.com first.';
  } else if (ciPending && failingChecks.length === 0) {
    disabledReason = 'CI is still running — wait for it to finish.';
  }
  // Latest verdict per reviewer wins (matches GitHub) — same logic as
  // reviewerVerdicts in the parent. We need just the approvers here so
  // the avatar strip mirrors what the user expects under "approvals".
  const approverLogins = (() => {
    const verdicts = new Map<string, string>();
    const reviewed = detail.recentActivity.filter(a => a.eventType === 'reviewed' && a.state && a.actor);
    for (let i = reviewed.length - 1; i >= 0; i--) {
      verdicts.set(reviewed[i].actor, reviewed[i].state ?? '');
    }
    const out: string[] = [];
    verdicts.forEach((state, login) => { if (state === 'APPROVED') out.push(login); });
    return out;
  })();
  const changesSummary = detail.changesRequestedCount > 0
    ? `${detail.changesRequestedCount} change${detail.changesRequestedCount === 1 ? '' : 's'} requested`
    : null;
  // Authoritative merge-queue detection: the backend reports whether the
  // PR's base branch actually has a merge queue configured (GraphQL
  // `pullRequest.mergeQueue`). When it does, a PR can't be merged
  // directly — it goes through the queue — so github.com shows
  // "Merge when ready" (enable auto-merge; the PR joins the queue once
  // requirements are met), even when CI is green and the PR is approved.
  // We mirror that exactly: a queue repo routes to the 'when-ready' mode
  // below. This replaces the old client-side heuristic (mergeable_state=
  // "blocked" + CI green + approvals) which guessed wrong whenever
  // something other than review blocked the merge. File conflicts still
  // win as 'disabled' (checked above).
  const requiresMergeQueue = !closed && detail.mergeQueueEnabled;

  // Primary modes the button can land in, computed once so label, click
  // handler, confirm-modal copy, and disabled-reason all pivot off one
  // variable rather than re-checking the same conditions four times.
  //   - merge        CI is green and no merge queue; clicking merges now.
  //   - when-ready   Clicking enables GitHub's auto-merge so the PR merges
  //                  automatically once requirements are met — github.com's
  //                  "Merge when ready" button. Used both for a merge-queue
  //                  repo (always, since direct merge isn't possible) and
  //                  for a non-queue PR whose CI is still pending / blocked.
  //                  Needs the onEnableAutoMerge prop wired through.
  //   - queue        Fallback for a queue repo when onEnableAutoMerge isn't
  //                  wired: clicking enqueues directly. Label "Add to merge
  //                  queue".
  //   - disabled     Something is blocking (closed, no write access,
  //                  conflicts, mid-merge); button greys out.
  const mergeMode: 'merge' | 'queue' | 'when-ready' | 'disabled' = (() => {
    if (closed) return 'disabled';
    if (!detail.viewerCanWrite) return 'disabled';
    if (mergeState === 'running') return 'disabled';
    if (hasConflict) return 'disabled';
    // Queue repo → "Merge when ready" (enable auto-merge → joins queue).
    // Falls back to a direct enqueue only when auto-merge isn't wired.
    if (requiresMergeQueue) return onEnableAutoMerge ? 'when-ready' : 'queue';
    if (ciPassing) return 'merge';
    if ((ciPending || pr.mergeableState === 'blocked') && onEnableAutoMerge) {
      return 'when-ready';
    }
    return 'disabled';
  })();
  const enabled = mergeMode !== 'disabled';
  // The pending-CI disabled-reason was set on the assumption that
  // ciPending was a blocker — in when-ready mode it isn't, so clear it.
  // (disabledReason only matters when the button is greyed out.)
  if (mergeMode === 'when-ready') {
    disabledReason = null;
  }
  // ── Status-row content (mirrors github.com's merge card) ──────────────
  const totalChecks = detail.checkRuns.length;
  // A check with no conclusion yet is still running; the rest (minus the
  // failing ones) have passed. Counting running checks as "passing" is
  // what made an in-progress PR read "110 checks running".
  const runningChecks = detail.checkRuns.filter(c => c.conclusion === null).length;
  const successfulChecks = Math.max(0, totalChecks - failingChecks.length - runningChecks);
  const passingChecks = totalChecks - failingChecks.length;
  // Review-row copy: combines approvals, requested changes, and pending
  // requests into a single "what's the human signal here" line.
  const pendingRequestCount = (detail.recentActivity ?? [])
    .filter(a => a.eventType === 'review_requested').length
    - (detail.recentActivity ?? []).filter(a => a.eventType === 'reviewed').length;
  const pendingReviews = Math.max(0, pendingRequestCount);
  const reviewState = detail.changesRequestedCount > 0
    ? 'changes' as const
    : approverLogins.length > 0
      ? 'approved' as const
      : 'pending' as const;
  const reviewTitle = reviewState === 'approved'
    ? `Approved by ${approverLogins.length} reviewer${approverLogins.length === 1 ? '' : 's'}`
    : reviewState === 'changes'
      ? `${detail.changesRequestedCount} change${detail.changesRequestedCount === 1 ? '' : 's'} requested`
      : 'Review requested';
  const reviewDesc = reviewState === 'approved'
    ? `Approved by ${approverLogins.join(', ')}.`
    : reviewState === 'changes'
      ? 'Address the requested changes, then re-request review.'
      : 'A review has been requested on this pull request.';

  const ciState = failingChecks.length > 0
    ? 'fail' as const
    : detail.ciStatus === 'PASSING'
      ? 'pass' as const
      : ciPending
        ? 'pending' as const
        : 'none' as const;
  const ciTitle = ciState === 'fail'
    ? 'Some checks were not successful'
    : ciState === 'pass'
      ? 'All checks have passed'
      : ciState === 'pending'
        ? 'Checks in progress'
        : 'No checks reported';
  const ciDesc = ciState === 'fail'
    ? `${failingChecks.length} failing, ${passingChecks} successful check${passingChecks === 1 ? '' : 's'}`
    : ciState === 'pass'
      ? `${totalChecks} successful check${totalChecks === 1 ? '' : 's'}`
      : ciState === 'pending'
        ? (successfulChecks > 0
            ? `${successfulChecks} passed, ${runningChecks} running`
            : `${runningChecks} check${runningChecks === 1 ? '' : 's'} running`)
        : 'No CI configured for this branch.';

  const conflictRow = hasConflict
    ? {
      state: 'fail' as const,
      title: `Conflict with ${detail.baseRef ?? 'base branch'}`,
      desc: 'Resolve the conflicts on github.com before merging.',
    }
    : {
      state: 'pass' as const,
      title: 'No conflicts with base branch',
      desc: 'Merging can be performed automatically.',
    };

  // PR is in the merge queue — replace the normal merge-card with the
  // queued treatment (mirrors github.com's amber-bordered "Queued to
  // merge…" card). Only pivots on detail.mergeQueueState; the
  // mergeState==='queued' transient is also set by the auto-merge
  // enablement path (handleEnableAutoMerge) and that case should keep
  // the regular merge-card with a success notice instead.
  const isQueued = detail.mergeQueueState !== null;
  if (isQueued) {
    const stateChip = queueStateLabel(detail.mergeQueueState);
    return (
      <div className="merge-card merge-card--queued">
        <div className="merge-card__icon" aria-hidden="true">
          <svg viewBox="0 0 16 16" width="17" height="17" fill="currentColor">
            {/* queue glyph — three stacked rows, the top one highlighted
                as "this PR, waiting in line" behind the others */}
            <rect x="2" y="2.4" width="12" height="2.6" rx="1.3" />
            <rect x="2" y="6.7" width="12" height="2.6" rx="1.3" opacity="0.55" />
            <rect x="2" y="11" width="12" height="2.6" rx="1.3" opacity="0.3" />
          </svg>
        </div>
        <div className="merge-card__rows merge-card__queued-rows">
          <div className="merge-card__queued-content">
            <div className="merge-card__queued-titlerow">
              <span className="merge-card__queued-pulse" aria-hidden="true" />
              <span className="merge-card__queued-title">Queued to merge</span>
              {stateChip && <span className="merge-card__queued-chip">{stateChip}</span>}
            </div>
            <div className="merge-card__queued-desc">
              GitHub will merge this PR through the{' '}
              <a href={pr.htmlUrl} target="_blank" rel="noreferrer">merge queue</a>
              {' '}once the items ahead clear.
            </div>
            {mergeError && (
              <div className="merge-card__queued-error" role="alert">{mergeError}</div>
            )}
          </div>
          {onDequeue && (
            <button
              type="button"
              className="merge-card__queued-action"
              onClick={onDequeue}
              disabled={mergeState === 'running'}
              title="Remove this PR from the merge queue"
            >
              {mergeState === 'running' ? 'Removing…' : 'Remove from queue'}
            </button>
          )}
        </div>
      </div>
    );
  }

  // The github-merge-queue bot only ejects a PR when the queue's required
  // checks fail. When that's the latest queue event and the PR hasn't been
  // re-queued, the normal card would misleadingly show the PR's own
  // (passing) checks — so call the ejection out explicitly.
  const lastQueueEvent = (detail.recentActivity ?? []).find(a => isMergeQueueEvent(a.eventType));
  const ejectedFromQueue = lastQueueEvent?.eventType === 'removed_from_merge_queue'
    && isBotActor(lastQueueEvent.actor);

  return (
    <div className={`merge-card${enabled ? ' merge-card--ready' : ' merge-card--blocked'}`}>
      <div className="merge-card__icon" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
          <path d="M5 3.25a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm0 9.5a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm8.25-6.25a.75.75 0 1 1 0-1.5.75.75 0 0 1 0 1.5Z" />
          <path fillRule="evenodd" d="M4.25 2.5a.75.75 0 0 0-.75.75v9.5a.75.75 0 0 0 1.5 0V8.122c.71.387 1.55.628 2.5.628 1.973 0 3.69-.69 4.84-1.677a.75.75 0 1 0-.98-1.14C10.43 6.71 9.083 7.25 7.5 7.25c-.992 0-1.85-.215-2.5-.553V3.25a.75.75 0 0 0-.75-.75Z" />
        </svg>
      </div>
      <div className="merge-card__rows">
        {ejectedFromQueue && (
          <div className="merge-card__eject" role="alert">
            <span className="merge-card__eject-icon" aria-hidden="true">⚠</span>
            <div className="merge-card__row-text">
              <div className="merge-card__row-title">Removed from the merge queue</div>
              <div className="merge-card__row-desc">
                The merge queue ejected this PR because its required status
                checks failed. Fix the failure, then add it back to the queue.
              </div>
            </div>
            <a className="merge-card__eject-link" href={pr.htmlUrl} target="_blank" rel="noreferrer">
              View on GitHub
            </a>
          </div>
        )}
        {/* Row 1 — Review status */}
        <div className="merge-card__row">
          <StatusGlyph state={reviewState} />
          <div className="merge-card__row-text">
            <div className="merge-card__row-title">{reviewTitle}</div>
            <div className="merge-card__row-desc">{reviewDesc}</div>
          </div>
          {approverLogins.length > 0 && (
            <span className="merge-card__approvers" title={`Approved by ${approverLogins.join(', ')}`}>
              {approverLogins.slice(0, 4).map((login) => (
                <a
                  key={login}
                  href={`https://github.com/${login}`}
                  target="_blank"
                  rel="noreferrer"
                  className="merge-card__approver"
                  title={`${login} approved`}
                >
                  <Avatar login={login} size={20} />
                </a>
              ))}
            </span>
          )}
        </div>
        {/* Row 1b — pending reviews sub-row */}
        {pendingReviews > 0 && (
          <div className="merge-card__row merge-card__row--sub">
            <span className="merge-card__sub-icon" aria-hidden="true">👤</span>
            <span>{pendingReviews} pending review{pendingReviews === 1 ? '' : 's'}</span>
          </div>
        )}

        {/* Row 2 — CI checks */}
        <button
          type="button"
          className={`merge-card__row merge-card__row--clickable${failuresOpen ? ' merge-card__row--open' : ''}`}
          onClick={() => failingChecks.length > 0 && setFailuresOpen(v => !v)}
          aria-expanded={failingChecks.length > 0 ? failuresOpen : undefined}
          disabled={failingChecks.length === 0}
        >
          <StatusGlyph state={ciState === 'fail' ? 'changes' : ciState === 'pending' ? 'pending' : 'approved'} />
          <div className="merge-card__row-text">
            <div className="merge-card__row-title">{ciTitle}</div>
            <div className="merge-card__row-desc">{ciDesc}</div>
          </div>
          <span className="merge-card__row-actions">
            {failingChecks.length > 0 && (
              <button
                type="button"
                className="merge-card__rerun"
                onClick={(e) => { e.stopPropagation(); void handleRerunChecks(); }}
                disabled={rerunState === 'running'}
                title="Re-run the failed CI jobs on GitHub — fixes a flaky failure without a new commit"
              >
                {rerunState === 'running' ? 'Re-running…'
                  : rerunState === 'queued' ? '✓ Re-running'
                    : rerunState === 'empty' ? 'Nothing to re-run'
                      : rerunState === 'error' ? 'Re-run failed'
                        : '↻ Re-run failed checks'}
              </button>
            )}
            {failingChecks.length > 0 && canEmptyCommit && (
              <button
                type="button"
                className="merge-card__rerun"
                onClick={(e) => { e.stopPropagation(); void handleEmptyCommit(); }}
                disabled={emptyCommitState === 'running'}
                title="Push an empty commit to this PR's branch to re-trigger CI (for repos whose CI only runs on push)"
              >
                {emptyCommitState === 'running' ? 'Pushing…'
                  : emptyCommitState === 'pushed' ? '✓ Pushed'
                    : emptyCommitState === 'unavailable' ? 'No local branch'
                      : emptyCommitState === 'error' ? 'Push failed'
                        : 'Empty commit'}
              </button>
            )}
            <button
              type="button"
              className="merge-card__refresh"
              onClick={(e) => { e.stopPropagation(); void onRefreshCi(); }}
              disabled={ciRefreshing}
              title="Force-refresh CI status from GitHub"
              aria-label="Refresh CI status"
            >
              <span className={ciRefreshing ? 'merge-card__refresh-icon merge-card__refresh-icon--spin' : 'merge-card__refresh-icon'} aria-hidden="true">↻</span>
            </button>
            {failingChecks.length > 0 && (
              <span className="merge-card__row-chevron" aria-hidden="true">{failuresOpen ? '▴' : '▾'}</span>
            )}
          </span>
        </button>

        {/* Failing-check details slot between CI row and Conflict row so
            the expansion reads as "drill-down on the row above". */}
        {failuresOpen && failingChecks.length > 0 && (
          <ul className="merge-bar__failures merge-card__failures">
            {failingChecks.map((c, i) => (
              <FailingCheckCard key={`${c.name ?? 'unnamed'}-${i}`} check={c} repo={pr.repo} />
            ))}
          </ul>
        )}

        {/* Row 3 — Conflict status */}
        <div
          className={`merge-card__row${conflictExpanded ? ' merge-card__row--open' : ''}`}
        >
          <StatusGlyph state={conflictRow.state === 'fail' ? 'changes' : 'approved'} />
          <div className="merge-card__row-text">
            <div className="merge-card__row-title">{conflictRow.title}</div>
            <div className="merge-card__row-desc">{conflictRow.desc}</div>
          </div>
          {hasConflict && (
            <a
              className="merge-card__row-action-btn"
              href={`${pr.htmlUrl}/conflicts`}
              target="_blank"
              rel="noreferrer"
            >
              Resolve on GitHub
            </a>
          )}
          {hasConflict && conflictPaths?.available && conflictPaths.paths.length > 0 && (
            <button
              type="button"
              className="merge-card__row-chevron-btn"
              onClick={() => setConflictExpanded(v => !v)}
              aria-expanded={conflictExpanded}
              title={conflictExpanded ? 'Hide conflicting files' : 'Show conflicting files'}
            >
              <span aria-hidden="true">{conflictExpanded ? '▴' : '▾'}</span>
            </button>
          )}
        </div>

        {/* Footer — merge button + command-line link */}
        <div className="merge-card__footer">
          <div className="merge-card__split">
            <button
              type="button"
              className="merge-card__btn merge-card__btn--split-main"
              onClick={() => setConfirmOpen(true)}
              disabled={!enabled}
              title={enabled
                ? (mergeMode === 'queue'
                  ? 'Add this PR to the merge queue'
                  : mergeMode === 'when-ready'
                    ? (detail.mergeQueueEnabled
                      ? 'Enable auto-merge — GitHub adds this PR to the merge queue when all requirements are met'
                      : 'Enable auto-merge — GitHub merges this PR once required checks pass')
                    : `${MERGE_STRATEGY_LABEL[strategy]} this PR on GitHub`)
                : (disabledReason ?? 'Not ready to merge')}
            >
              {mergeState === 'running'
                ? (mergeMode === 'queue' ? 'Enqueuing…'
                  : mergeMode === 'when-ready' ? 'Enabling…'
                  : 'Merging…')
                : (mergeMode === 'queue' ? 'Add to merge queue'
                  : mergeMode === 'when-ready' ? 'Merge when ready'
                  : MERGE_STRATEGY_LABEL[strategy])}
            </button>
            {mergeMode !== 'queue' && (
              <button
                ref={strategyCaretRef}
                type="button"
                className="merge-card__btn merge-card__btn--split-caret"
                onClick={() => {
                  if (!strategyMenuOpen && strategyCaretRef.current) {
                    const r = strategyCaretRef.current.getBoundingClientRect();
                    setStrategyMenuPos({ top: r.bottom + 4, left: r.left });
                  }
                  setStrategyMenuOpen(v => !v);
                }}
                disabled={!enabled}
                aria-haspopup="menu"
                aria-expanded={strategyMenuOpen}
                title="Pick a different merge strategy"
              >
                <span aria-hidden="true">▾</span>
              </button>
            )}
            {strategyMenuOpen && (
              <>
                <button
                  type="button"
                  className="merge-card__strategy-backdrop"
                  aria-hidden="true"
                  tabIndex={-1}
                  onClick={() => setStrategyMenuOpen(false)}
                />
                <div
                  className="merge-card__strategy-menu"
                  role="menu"
                  style={strategyMenuPos
                    ? { top: strategyMenuPos.top, left: strategyMenuPos.left }
                    : undefined}
                >
                  {(['rebase', 'squash', 'merge'] as MergeStrategy[]).map(opt => (
                    <button
                      key={opt}
                      type="button"
                      role="menuitemradio"
                      aria-checked={strategy === opt}
                      className={`merge-card__strategy-option${strategy === opt ? ' merge-card__strategy-option--active' : ''}`}
                      onClick={() => { setStrategy(opt); setStrategyMenuOpen(false); }}
                    >
                      <span className="merge-card__strategy-option-label">{MERGE_STRATEGY_LABEL[opt]}</span>
                      <span className="merge-card__strategy-option-hint">{MERGE_STRATEGY_HINT[opt]}</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
          <span className="merge-card__cmdline">
            You can also merge this with the command line.{' '}
            <a href={`${pr.htmlUrl}#cmdline-instructions`} target="_blank" rel="noreferrer">
              View command line instructions.
            </a>
          </span>
          {disabledReason && !hasConflict && (
            <span className="merge-card__reason" title={disabledReason}>{disabledReason}</span>
          )}
          {mergeError && (
            <span className="merge-card__error" title={mergeError}>{mergeError}</span>
          )}
          {mergeState === 'queued' && mergeQueuedMessage && (
            <span className="merge-card__queue-notice" title={mergeQueuedMessage}>
              ✓ {mergeQueuedMessage}
            </span>
          )}
        </div>

        {/* Conflict-files expansion — slotted just under the Conflict
            row (the failing-checks list now lives between rows 2 and 3
            instead of at the bottom). */}
        {conflictExpanded && conflictPaths?.available && conflictPaths.paths.length > 0 && (
          <div className="merge-bar__conflict-files">
            <div className="merge-bar__conflict-files-head">
              {conflictPaths.paths.length} file{conflictPaths.paths.length === 1 ? '' : 's'} in conflict with{' '}
              <code>{detail.baseRef}</code>
              <a
                className="merge-bar__conflict-files-link"
                href={`${pr.htmlUrl}/conflicts`}
                target="_blank"
                rel="noreferrer"
              >
                Resolve on GitHub →
              </a>
            </div>
            <ul className="merge-bar__conflict-files-list">
              {conflictPaths.paths.map((p) => (
                <li key={p}>
                  <code>{p}</code>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      {confirmOpen && (
        <div
          className="modal-overlay"
          role="presentation"
          onClick={(e) => {
            // Click on the backdrop (not bubbled from inside the modal) ⇒ cancel.
            if (e.target === e.currentTarget && mergeState !== 'running') {
              setConfirmOpen(false);
            }
          }}
        >
          <div className="modal merge-confirm" role="dialog" aria-modal="true" aria-labelledby="merge-confirm-title">
            <div className="modal__header">
              <h3 className="modal__title" id="merge-confirm-title">Confirm merge?</h3>
            </div>
            <div className="merge-confirm__body">
              <p>
                {mergeMode === 'when-ready'
                  ? <>Enable auto-merge on <b>{pr.repo}#{pr.number}</b>?</>
                  : <>{MERGE_STRATEGY_LABEL[strategy]} <b>{pr.repo}#{pr.number}</b> on GitHub?</>}
              </p>
              <p className="merge-confirm__sub">{pr.title}</p>
              <p className="merge-confirm__sub">
                {mergeMode === 'when-ready'
                  ? (detail.mergeQueueEnabled
                    ? 'This repository uses a merge queue. GitHub will add this PR to the queue and merge it once it reaches the front with all requirements met.'
                    : `GitHub will ${MERGE_STRATEGY_LABEL[strategy].toLowerCase()} this PR automatically once required checks pass.`)
                  : MERGE_STRATEGY_HINT[strategy]}
              </p>
            </div>
            <div className="merge-confirm__actions">
              <button
                type="button"
                className="merge-bar__cancel"
                onClick={() => setConfirmOpen(false)}
                disabled={mergeState === 'running'}
              >
                No
              </button>
              <button
                type="button"
                className="merge-bar__btn"
                onClick={() => {
                  // Fire the merge then close the dialog. The parent's
                  // handleMerge / handleEnableAutoMerge surfaces errors
                  // via mergeError below.
                  if (mergeMode === 'when-ready' && onEnableAutoMerge) {
                    onEnableAutoMerge(strategy);
                  }
                  else {
                    onMerge(strategy);
                  }
                  setConfirmOpen(false);
                }}
                disabled={mergeState === 'running'}
              >
                {mergeState === 'running'
                  ? (mergeMode === 'queue' ? 'Enqueuing…'
                    : mergeMode === 'when-ready' ? 'Enabling…'
                    : 'Merging…')
                  : (mergeMode === 'queue' ? 'Yes, add to queue'
                    : mergeMode === 'when-ready' ? 'Yes, enable auto-merge'
                    : 'Yes, merge')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Compact a thread title to ~32 chars for chip display so a long
 *  title doesn't push the rest of the PR meta row off-screen. */
function truncateThreadTitle(title: string): string {
  const trimmed = title.trim();
  if (trimmed.length <= 32) return trimmed;
  return trimmed.slice(0, 31) + '…';
}

function loadSideWidth(): number {
  const raw = localStorage.getItem(SIDE_WIDTH_KEY);
  const n = raw ? parseInt(raw, 10) : NaN;
  if (!Number.isFinite(n)) return SIDE_WIDTH_DEFAULT;
  return Math.max(SIDE_WIDTH_MIN, Math.min(SIDE_WIDTH_MAX, n));
}

type Props = {
  pr: PullRequestDto;
  onOpenReview?: () => void;
  /** Open the diff viewer. When `initialCommitSha` is set, the diff
   *  viewer should land on that commit's diff (single-commit view)
   *  instead of the cumulative PR diff — used by clickable timeline
   *  SHAs like "pushed a commit · 3ee6644". */
  onInspectDiffs?: (initialCommitSha?: string | null) => void;
  // Called when the user clicks "Mark as handled". The parent is expected to
  // patch its PR list optimistically and call markPrHandled on the bridge —
  // otherwise the list wouldn't refresh and the PR would still show as unhandled.
  onMarkHandled?: (prId: number) => Promise<void>;
  // Called when the user clicks "Rebase and merge". When omitted the merge
  // bar above the comment box is hidden — pages that don't want merge from
  // the preview (e.g. archived PRs) just skip the prop. Return value is
  // ignored; the preview re-fetches detail itself once the call resolves.
  onMerge?: (prId: number, repo: string, number: number, strategy?: MergeStrategy) =>
    Promise<{ merged: boolean; message: string; queued: boolean } | undefined | void>;
  // Optional "← Back" affordance shown in the page header. When provided
  // (e.g. from the team detail page) the button returns the user to the
  // referring screen. Inbox usage doesn't pass this — the sidebar list
  // already serves as navigation, so a redundant Back button would clutter.
  onBack?: () => void;
  // Label for the back button — defaults to "Back" when omitted. Callers
  // can pass "Team" / "Repo" / etc. so the breadcrumb names the origin.
  backLabel?: string;
  /** Reverse nav from this PR to its head branch's local-repo
   *  Commits tab. The head-ref chip surfaces a button that calls
   *  this when the head is in the same repo (cross-fork PRs hide
   *  it — the branch isn't in the local clone). */
  onOpenLocalBranch?: (owner: string, repo: string, branch: string) => void;
  /** Jump from this PR back into the thread domain — used when one
   *  or more threads have `linkedPrNumber === this.PR`. The header
   *  surfaces a small thread chip for each match. */
  onOpenThread?: (threadId: string) => void;
  /** Open a new AI review-panel thread on this PR. The button manages
   *  its own loading state because the kickoff is an LLM call (~10s);
   *  the parent only owns navigation — receives the new thread id and
   *  routes to the review page. Omit to hide the button. */
  onStartReview?: (threadId: string) => void;
};

/** Polling interval for the focus-driven CI snapshot refresh. ~12s is a
 *  reasonable middle ground: short enough to feel reactive when checks
 *  flip mid-review, long enough that we're not slamming GitHub for a
 *  user who just left the window open. */
const CI_POLL_INTERVAL_MS = 12_000;

/** Cadence of the lightweight comments-delta poll. Tighter than the 10s
 *  full-detail tick so a reviewer's new conversation comment lands within
 *  a few seconds, but it only hits the issue-comments endpoint so the
 *  extra requests are cheap. */
const COMMENT_POLL_INTERVAL_MS = 7_000;

/** Epoch-ms high-water mark to hand the comments-delta poll: the newest
 *  conversation-comment timestamp we already show, so GitHub returns only
 *  comments at-or-after it (the boundary one is deduped on merge). Falls
 *  back to the newest activity timestamp when no comment is on screen yet
 *  so we don't re-pull the whole comment history each tick; 0 only when
 *  the timeline is empty. */
function latestCommentEpochMs(detail: PullRequestDetailDto): number {
  let latestComment = 0;
  let latestAny = 0;
  for (const item of detail.recentActivity) {
    if (!item.timestamp) continue;
    const t = Date.parse(item.timestamp);
    if (Number.isNaN(t)) continue;
    if (t > latestAny) latestAny = t;
    if (item.eventType === 'commented' && t > latestComment) latestComment = t;
  }
  return latestComment > 0 ? latestComment : latestAny;
}


type ActionState = 'idle' | 'confirming' | 'running' | 'done' | 'error';


function PullRequestPreview({
  pr, onOpenReview, onInspectDiffs, onMarkHandled, onMerge, onBack, backLabel,
  onOpenLocalBranch, onOpenThread, onStartReview,
}: Props) {
  // {owner, repo} for renderMarkdown — pr.repo is GitHub's "owner/repo"
  // form. Used so `#N` references in the PR body / comments become
  // clickable in-app via App.tsx's global click delegate.
  const repoContext = (() => {
    const [o, r] = pr.repo.split('/');
    return o && r ? { owner: o, repo: r } : undefined;
  })();
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Optimistic title after an inline rename — the title lives on `pr` (a
  // prop), so we hold the new value locally until the next detail sync
  // brings it through. Reset when navigating to a different PR.
  const [titleOverride, setTitleOverride] = useState<string | null>(null);
  useEffect(() => { setTitleOverride(null); }, [pr.number, pr.repo]);

  const [handledState, setHandledState] = useState<ActionState>('idle');
  const [handledError, setHandledError] = useState<string | null>(null);
  // The "Start review-panel" button is async (kickoff calls the LLM);
  // local state surfaces loading + error inline so the parent doesn't
  // have to plumb a global loader for one PR-preview button.
  const [reviewStarting, setReviewStarting] = useState(false);
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [sideWidth, setSideWidth] = useState<number>(loadSideWidth);
  // Collapse the right meta sidebar to a thin rail with just an
  // expand affordance. Lets the timeline column claim the full width
  // when the user wants to focus on the conversation. Persisted in
  // localStorage so the choice survives navigation + restart.
  const [sidebarCollapsed, setSidebarCollapsedRaw] = useState<boolean>(() => {
    try { return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'; }
    catch { return false; }
  });
  const setSidebarCollapsed = (next: boolean) => {
    setSidebarCollapsedRaw(next);
    try { localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0'); }
    catch { /* ignore */ }
  };
  const gridRef = useRef<HTMLDivElement>(null);
  const commentBoxRef = useRef<PrCommentBoxHandle>(null);

  // Merge bar state. `confirming` is the two-click safety net (one click
  // arms, second click actually merges) so a stray pointer doesn't ship
  // an unintended PR. `error` carries GitHub's reason if the call fails.
  // `queued` is the merge-queue success path — onMerge resolves with
  // {queued: true} when the target branch is queue-protected and the
  // PR was accepted into the queue rather than merged immediately.
  const [mergeState, setMergeState] = useState<'idle' | 'running' | 'error' | 'queued'>('idle');
  const [mergeError, setMergeError] = useState<string | null>(null);
  const [mergeQueuedMessage, setMergeQueuedMessage] = useState<string | null>(null);
  // Manual-refresh / focus-poll spinner state for the CI summary.
  const [ciRefreshing, setCiRefreshing] = useState(false);

  // Draft toggle state — surfaces a "Mark as ready" / "Convert to draft"
  // button in the right sidebar. Authors and maintainers commonly flip
  // this without leaving the detail page.
  const [draftToggleState, setDraftToggleState] = useState<'idle' | 'running' | 'error'>('idle');
  const [draftToggleError, setDraftToggleError] = useState<string | null>(null);

  // Reverse PR → thread lookup. Lets the header surface a small chip
  // for every thread whose `linkedPrNumber` matches this PR's number
  // AND whose working directory is rooted in the same repo. Lets
  // the user jump from the PR domain straight back into a thread that
  // owns this PR.
  // Tasks that produced this PR — the active one plus the completed
  // audit log. Sourced from /prs/linked-tasks (authoritative) rather than
  // scanning live threads by `activeTask.linkedPrNumber`, which missed a
  // PR whose task already COMPLETED (its thread has no active task) — so
  // a finished task's PR showed no link back at all.
  const [linkedTasks, setLinkedTasks] = useState<TaskRefDto[]>([]);
  // An active linked task means there's a worktree we can push an empty
  // commit from, so the "trigger CI via empty commit" fallback is enabled.
  const [canEmptyCommit, setCanEmptyCommit] = useState(false);
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const links = await window.bridge.getPrLinks(pr.repo, pr.number);
        if (cancelled) return;
        const refs = links.linkedActiveTask
          ? [links.linkedActiveTask, ...links.linkedCompletedTasks]
          : links.linkedCompletedTasks;
        setLinkedTasks(refs);
        setCanEmptyCommit(links.linkedActiveTask !== null);
      }
      catch { /* non-fatal — no chip shown */ }
    })();
    return () => { cancelled = true; };
  }, [pr.number, pr.repo]);

  const refreshDetailFromGitHub = async (): Promise<PullRequestDetailDto> => {
    // No maxAgeSeconds → always-probe semantics. Used by the manual
    // ↻ button and by post-mutation paths (description save, etc.).
    const fresh = await window.bridge.refreshPullRequestDetail(pr.repo, pr.number);
    setDetail(fresh);
    return fresh;
  };

  const handleDescriptionSaved = (newBody: string) => {
    setDetail(prev => prev ? { ...prev, body: newBody } : prev);
    void refreshDetailFromGitHub().catch(() => { /* keep optimistic body */ });
  };

  /** Manual refresh — drops the backend's cached snapshot for this PR
   *  and re-fetches live from GitHub, then replaces the in-memory
   *  detailCache entry and current view state. Wired to the ↻ button
   *  in the header so the user can pull in github.com edits without
   *  waiting for the next periodic sync. */
  const handleRefreshDetail = async () => {
    if (refreshing) return;
    setRefreshing(true);
    setError(null);
    try {
      await refreshDetailFromGitHub();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setRefreshing(false);
    }
  };

  const handleToggleDraft = async () => {
    if (!detail || draftToggleState === 'running') return;
    const nextDraft = !detail.draft;
    setDraftToggleState('running');
    setDraftToggleError(null);
    try {
      await window.bridge.setPrDraft(pr.repo, pr.number, nextDraft);
      // Optimistically reflect the new draft state immediately, then
      // re-fetch so the timeline picks up GitHub's synthetic event.
      setDetail(prev => prev ? { ...prev, draft: nextDraft } : prev);
      await refreshDetailFromGitHub();
      setDraftToggleState('idle');
    }
    catch (e) {
      setDraftToggleError(e instanceof Error ? e.message : String(e));
      setDraftToggleState('error');
      // Revert if the backend rejected the call.
      setDetail(prev => prev ? { ...prev, draft: !nextDraft } : prev);
    }
  };

  /** Refetch just the CI slice from /prs/ci. Used by the focus-driven
   *  poll, by the visibility-change handler, and by the manual refresh
   *  button rendered in the CI summary. Errors are swallowed — a failed
   *  poll just leaves the previous snapshot in place. */
  const refreshCi = async () => {
    if (ciRefreshing) return;
    setCiRefreshing(true);
    try {
      const snap = await window.bridge.fetchPrCi(pr.repo, pr.number);
      setDetail(prev => prev ? { ...prev, ciStatus: snap.ciStatus, checkRuns: snap.checkRuns, viewerCanWrite: snap.viewerCanWrite } : prev);
    }
    catch {
      // Best-effort — the existing snapshot stays on screen.
    }
    finally {
      setCiRefreshing(false);
    }
  };

  /** Refetch the full PR detail (conversation, review threads, CI) and
   *  swap it in now. Called right after the user posts a comment / reply
   *  so this focus page reflects the change immediately instead of
   *  waiting for the next 10s poll tick. Best-effort: a failed refetch
   *  keeps the last good detail on screen and the poll catches up. */
  const refreshDetail = async () => {
    try {
      const fresh = await window.bridge.refreshPullRequestDetail(pr.repo, pr.number, 20);
      setDetail(fresh);
    }
    catch {
      // Best-effort — the next poll tick will reconcile.
    }
  };

  /** Revalidate-before-submit guard handed to the comment composer. Forces
   *  a fresh probe of GitHub (maxAge 0), swaps the latest detail onto the
   *  page, and — if the PR moved since the snapshot the user was looking
   *  at — returns a description of what changed so the composer holds the
   *  post. Returns null (proceed) when nothing change-sensitive moved, or
   *  when we couldn't reach GitHub (best-effort: never block on our own
   *  failure to verify). */
  const guardFreshBeforePost = async (): Promise<string | null> => {
    const shown = detail;
    if (!shown) return null;
    let fresh: PullRequestDetailDto;
    try {
      fresh = await window.bridge.refreshPullRequestDetail(pr.repo, pr.number, 0);
    }
    catch {
      return null;
    }
    setDetail(fresh);
    return describePrChange(shown, fresh);
  };

  const handleSideResize = (clientX: number) => {
    const rect = gridRef.current?.getBoundingClientRect();
    if (!rect) return;
    // ResizeHandle sits between main and side; dragging left widens the side.
    const next = Math.max(SIDE_WIDTH_MIN, Math.min(SIDE_WIDTH_MAX, rect.right - clientX));
    setSideWidth(next);
    localStorage.setItem(SIDE_WIDTH_KEY, String(next));
  };

  /** Toggle the resolved state of a review thread. Optimistically
   *  flips the local flag so the pill + UI text update immediately;
   *  rolls back on backend failure. */
  const handleSetThreadResolved = async (rootGithubId: number, resolved: boolean): Promise<void> => {
    setDetail(prev => optimisticallyToggleResolved(prev, rootGithubId, resolved));
    try {
      await window.bridge.setReviewThreadResolved(pr.repo, pr.id, rootGithubId, resolved);
    } catch (e) {
      setDetail(prev => optimisticallyToggleResolved(prev, rootGithubId, !resolved));
      console.warn('setReviewThreadResolved failed', e);
    }
  };

  /** Add an emoji reaction to a top-level issue/PR comment. Same
   *  optimistic + rollback shape as handleReact (review-thread
   *  variant), targeting a different DTO and bridge path. */
  const handleIssueReact = async (commentGithubId: number, content: ReactionContent): Promise<void> => {
    setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content));
    try {
      await window.bridge.addIssueCommentReaction(pr.repo, commentGithubId, content);
    } catch (e) {
      setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content, -1));
      console.warn('addIssueCommentReaction failed', e);
    }
  };

  /** Add an emoji reaction to a review-thread message. Optimistically
   *  bumps the local count so the chip appears immediately. The next
   *  natural detail refresh picks up GitHub's authoritative tally. */
  const handleReact = async (commentGithubId: number, content: ReactionContent): Promise<void> => {
    setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content));
    try {
      await window.bridge.addReviewCommentReaction(pr.repo, commentGithubId, content);
    } catch (e) {
      // Rollback the optimistic bump on failure.
      setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content, -1));
      console.warn('addReviewCommentReaction failed', e);
    }
  };

  // Detail load + 10s polling tick. No client-side cache: every mount
  // calls refreshPullRequestDetail with maxAgeSeconds=20, which lets
  // the backend short-circuit to its L2 (SQLite) snapshot when our
  // last ETag probe is younger than 20s — and ETag-probe-then-304
  // when it isn't. So in the steady state we probe GitHub at most
  // once per 20s while the page is open, regardless of how many
  // tabs / sessions are viewing the same PR.
  //
  // Nothing fires when the detail page isn't mounted; this useEffect
  // tears the interval down on pr.id change or unmount.
  useEffect(() => {
    setHandledState('idle');
    setHandledError(null);
    setError(null);
    setDetail(null); // clear immediately so old PR's detail never shows during load
    setLoading(true);

    let cancelled = false;
    const tick = (initial: boolean) => window.bridge
      .refreshPullRequestDetail(pr.repo, pr.number, 20)
      .then((d) => {
        if (cancelled) return;
        setDetail(d);
        if (initial) setLoading(false);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // First load can surface the error; subsequent ticks are
        // best-effort — silently keep the last good detail on screen.
        if (initial) {
          setError(e instanceof Error ? e.message : String(e));
          setLoading(false);
        }
      });

    void tick(true);
    const interval = setInterval(() => { void tick(false); }, 10_000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [pr.id, pr.repo, pr.number]);

  // Lightweight comments-delta poll. The 10s tick above is the source of
  // truth for everything (reviews, files, threads, events); this faster
  // poll hits only the issue-comments endpoint so a reviewer's new
  // conversation comment shows up within a few seconds instead of waiting
  // for the next heavy refetch. New comments merge into recentActivity,
  // deduped by id, so the two polls never double up. We read the live
  // detail through a ref so a new comment landing doesn't tear down and
  // rebuild the interval.
  const detailRef = useRef<PullRequestDetailDto | null>(null);
  detailRef.current = detail;
  useEffect(() => {
    let cancelled = false;
    let interval: ReturnType<typeof setInterval> | null = null;
    const poll = async () => {
      const current = detailRef.current;
      if (!current) return; // wait for the first full load
      try {
        const fresh = await window.bridge.fetchNewComments(
          pr.repo, pr.number, latestCommentEpochMs(current));
        if (cancelled || fresh.length === 0) return;
        setDetail(prev => mergeFetchedComments(prev, fresh));
      } catch {
        // Best-effort — the 10s full poll reconciles anything missed.
      }
    };
    // Only poll while the window AND document are visible/focused, so a
    // backgrounded PR page stops spending GitHub rate-limit on comment
    // checks. Fire once on focus regain so a returning user sees fresh
    // comments immediately.
    const isVisible = () => document.visibilityState === 'visible' && document.hasFocus();
    const start = () => {
      if (interval != null) return;
      void poll();
      interval = setInterval(() => { void poll(); }, COMMENT_POLL_INTERVAL_MS);
    };
    const stop = () => {
      if (interval != null) {
        clearInterval(interval);
        interval = null;
      }
    };
    const onVisibility = () => { isVisible() ? start() : stop(); };
    if (isVisible()) start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onVisibility);
    window.addEventListener('blur', onVisibility);
    return () => {
      cancelled = true;
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onVisibility);
      window.removeEventListener('blur', onVisibility);
    };
  }, [pr.id, pr.repo, pr.number]);

  // Authoritative PR commit count — reuse the same commit list the diff
  // viewer and the branch/commits view load (GitHub /pulls/{n}/commits),
  // rather than tallying `committed` timeline events, which over-counts
  // when the head branch carries history outside the base..head range.
  // null while loading / on failure → the header drops the number.
  const [commitCount, setCommitCount] = useState<number | null>(null);
  useEffect(() => {
    let cancelled = false;
    setCommitCount(null);
    void window.bridge.fetchPrCommits(pr.repo, pr.number)
      .then((list) => { if (!cancelled) setCommitCount(list.length); })
      .catch(() => { /* leave null — header shows "wants to merge into" */ });
    return () => { cancelled = true; };
  }, [pr.repo, pr.number]);

  // Focus-driven CI poll. Active only when the OS window AND the document
  // are visible — leaves the poll dormant when the user tabs away so we
  // don't burn rate-limit on a hidden window. Resets whenever the open PR
  // changes so a switched-to PR fires a refresh on its first visible tick.
  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | null = null;
    const isVisible = () => document.visibilityState === 'visible' && document.hasFocus();
    const start = () => {
      if (interval != null) return;
      // Fire one immediately on focus/visibility regain, then every CI_POLL_INTERVAL_MS.
      void refreshCi();
      interval = setInterval(() => { void refreshCi(); }, CI_POLL_INTERVAL_MS);
    };
    const stop = () => {
      if (interval != null) {
        clearInterval(interval);
        interval = null;
      }
    };
    const onVisibility = () => { isVisible() ? start() : stop(); };
    if (isVisible()) start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onVisibility);
    window.addEventListener('blur', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onVisibility);
      window.removeEventListener('blur', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pr.id, pr.repo, pr.number]);

  const handleMerge = async (strategy: MergeStrategy) => {
    if (!onMerge || mergeState === 'running') return;
    setMergeState('running');
    setMergeError(null);
    setMergeQueuedMessage(null);
    try {
      const result = await onMerge(pr.id, pr.repo, pr.number, strategy);
      // Drop the cached detail and refetch so the merged status, timeline
      // event, and disabled merge button all update in one pass.
      await refreshDetailFromGitHub();
      if (result && typeof result === 'object' && 'queued' in result && result.queued) {
        setMergeQueuedMessage(typeof result.message === 'string' ? result.message : 'Added to merge queue');
        setMergeState('queued');
      }
      else {
        setMergeState('idle');
      }
    }
    catch (e) {
      setMergeError(e instanceof Error ? e.message : String(e));
      setMergeState('error');
    }
  };

  /** Mirror of {@link handleMerge} for the "Remove from queue" path.
   *  Reuses mergeState/mergeError so the queued card greys out and
   *  surfaces the same banner as the normal merge button when the
   *  GraphQL call fails. Detail refresh clears mergeQueueState so the
   *  next render falls back to the regular merge bar. */
  const handleDequeue = async () => {
    if (mergeState === 'running') return;
    setMergeState('running');
    setMergeError(null);
    try {
      await window.bridge.dequeuePr(pr.id, pr.repo, pr.number);
      await refreshDetailFromGitHub();
      setMergeQueuedMessage(null);
      setMergeState('idle');
    }
    catch (e) {
      setMergeError(e instanceof Error ? e.message : String(e));
      setMergeState('error');
    }
  };

  /** Mirror of {@link handleMerge} for the "Merge when ready" path.
   *  Goes straight through the bridge (no parent prop) since the
   *  enable mutation isn't routed through any optimistic list-level
   *  state — the PR row doesn't move to Handled until GitHub actually
   *  merges it, which happens after checks pass. We refresh the detail
   *  so the next render reflects whatever side-effects the backend
   *  records (cache invalidation primarily). */
  const handleEnableAutoMerge = async (strategy: MergeStrategy) => {
    if (mergeState === 'running') return;
    setMergeState('running');
    setMergeError(null);
    setMergeQueuedMessage(null);
    try {
      await window.bridge.enableAutoMerge(pr.id, pr.repo, pr.number, strategy);
      await refreshDetailFromGitHub();
      setMergeQueuedMessage('Auto-merge enabled — will merge once checks pass.');
      setMergeState('queued');
    }
    catch (e) {
      setMergeError(e instanceof Error ? e.message : String(e));
      setMergeState('error');
    }
  };

  const handleMarkHandled = async () => {
    setHandledState('running');
    setHandledError(null);
    try {
      // Let the parent patch its list optimistically so this PR moves from
      // Inbox to Handled right away. Fallback to a direct bridge call if no
      // parent handler was wired up — at least the backend state is updated.
      if (onMarkHandled) {
        await onMarkHandled(pr.id);
      } else {
        await window.bridge.markPrHandled(pr.id, 'MANUAL');
      }
      setHandledState('done');
    } catch (e) {
      setHandledError(e instanceof Error ? e.message : String(e));
      setHandledState('error');
    }
  };

  const handleClosed = async () => {
    // The PATCH that closed the PR has already returned, but our local
    // `detail.state` is still "OPEN" — refetch so the header pill flips
    // to CLOSED and the timeline picks up the new event. Then mark the
    // PR handled like before so the inbox row moves to Handled.
    try {
      await refreshDetailFromGitHub();
    }
    catch {
      // best-effort — the PR is closed remotely either way
    }
    void onMarkHandled?.(pr.id).catch(() => { /* best-effort */ });
  };

  const handleOpenEmbeddedReview = () => {
    // Opening counts as viewing, even if the user exits without acting.
    void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
    onOpenReview?.();
  };

  // Cached login of the authenticated user — populated by the home
  // page's profile fetch. Used to gate the per-comment ✎ Edit
  // affordance: only the comment author sees it. Null when the home
  // page hasn't been visited yet (rare in practice — login is the
  // entry point); in that case Edit just stays hidden.
  const currentUserLogin = (getCachedValue<UserProfileDto>('home:profile') ?? null)?.login ?? null;

  /** Submit an edit to a top-level issue / PR comment, then patch
   *  local state so the new body renders immediately without a full
   *  detail refetch. Throws on failure so EditableMarkdownBody can
   *  surface the error inline (and stay in edit mode). */
  const handleEditIssueComment = async (commentId: number, body: string): Promise<void> => {
    await window.bridge.editIssueComment(pr.repo, commentId, body);
    setDetail(prev => optimisticallyUpdateCommentBody(prev, commentId, body));
  };

  /** Same as handleEditIssueComment but for per-line review comments. */
  const handleEditReviewComment = async (commentId: number, body: string): Promise<void> => {
    await window.bridge.editReviewComment(pr.repo, commentId, body);
    setDetail(prev => optimisticallyUpdateCommentBody(prev, commentId, body));
  };

  /** Deletes a top-level issue comment on GitHub, then drops it from
   *  local state. The confirm gate lives in CommentActionsMenu, so by
   *  the time this runs the user has already confirmed. */
  const handleDeleteIssueComment = async (commentId: number): Promise<void> => {
    await window.bridge.deleteIssueComment(pr.repo, commentId);
    setDetail(prev => optimisticallyRemoveComment(prev, commentId));
  };

  /** Same as handleDeleteIssueComment but for per-line review comments. */
  const handleDeleteReviewComment = async (commentId: number): Promise<void> => {
    await window.bridge.deleteReviewComment(pr.repo, commentId);
    setDetail(prev => optimisticallyRemoveComment(prev, commentId));
  };

  /** Whether the current user may delete a comment authored by
   *  {@code author}: their own comments always, anyone's when they hold
   *  write access on the repo (mirrors what github.com lets maintainers
   *  do). Gated to real positive GitHub ids so optimistic placeholders
   *  (negative ids) never expose a delete that can't resolve. */
  const canDeleteComment = (author: string | null, githubId: number | null | undefined): boolean =>
    githubId != null && githubId > 0
    && ((currentUserLogin != null && currentUserLogin === author) || detail?.viewerCanWrite === true);

  /** Whether the current user may edit a comment. Unlike delete, GitHub
   *  only lets you edit your *own* comments — write access doesn't
   *  extend to editing someone else's. */
  const canEditComment = (author: string | null, githubId: number | null | undefined): boolean =>
    githubId != null && githubId > 0 && currentUserLogin != null && currentUserLogin === author;

  // Which top-level comment is currently in edit mode (keyed by GitHub
  // id), so the "⋯ → Edit" menu item can open the body's editor without
  // a per-row ref. Only one comment edits at a time. Inline review-
  // thread messages own their own equivalent state in ReviewThreadCard.
  const [editingCommentId, setEditingCommentId] = useState<number | null>(null);

  /** Posts a reply to the given review thread, then patches the new
   *  message into local state right away. Throws if the post itself
   *  fails so the composer can surface the error. */
  const handleReply = async (rootGithubId: number, body: string) => {
    await window.bridge.replyToReviewThread(pr.repo, pr.number, rootGithubId, body);
    const profile = getCachedValue<UserProfileDto>('home:profile') ?? null;
    const optimistic: ReviewMessageDto = {
      // Negative id keeps it distinct from anything GitHub would assign
      // and avoids reaction-bump etc. matching the placeholder.
      githubId: -Date.now(),
      author: profile?.login ?? null,
      body,
      createdAt: new Date().toISOString(),
      reactions: null,
      reviewId: null,
      authorAssociation: null,
    };
    setDetail(prev => optimisticallyAppendReply(prev, rootGithubId, optimistic));
  };

  const repoOwner = pr.repo.includes('/') ? pr.repo.split('/')[0] : pr.repo;

  // Logins the composer offers as @mention autocomplete candidates:
  // everyone already on this PR — author, (requested) reviewers, and
  // anyone who has commented or replied. Deduped, bots dropped, sorted.
  const mentionCandidates = Array.from(new Set([
    pr.author,
    ...(detail?.requestedReviewers ?? []),
    ...(detail?.recentActivity ?? []).flatMap(a => [a.actor, a.requestedReviewer]),
    ...(detail?.reviewThreads ?? []).flatMap(t => t.messages.map(m => m.author)),
  ].filter((x): x is string => typeof x === 'string' && x.length > 0 && !x.includes('[bot]'))))
    .sort((a, b) => a.localeCompare(b));

  // Two layouts available — "classic" is the new mockup-faithful conversation
  // page (Phase 2); "clean" is the original layout we had before. The user
  // can switch any time via the toggle in the page header. Persisted globally.
  const [layoutStyle, setLayoutStyleRaw] = useState<'classic' | 'clean'>(
    () => (localStorage.getItem('settings:pr-detail-style') === 'clean' ? 'clean' : 'classic'),
  );
  const setLayoutStyle = (next: 'classic' | 'clean') => {
    setLayoutStyleRaw(next);
    localStorage.setItem('settings:pr-detail-style', next);
  };

  const RefreshButton = (
    <button
      type="button"
      className="prc-refresh-btn"
      onClick={() => void handleRefreshDetail()}
      disabled={refreshing}
      title="Refresh — re-fetch this PR's comments / timeline from GitHub."
      aria-label="Refresh PR detail"
    >
      <span className={`prc-refresh-btn__icon${refreshing ? ' prc-refresh-btn__icon--spinning' : ''}`} aria-hidden="true">↻</span>
      <span className="prc-refresh-btn__label">{refreshing ? 'Refreshing…' : 'Refresh'}</span>
    </button>
  );

  const StyleToggle = (
    <div className="prc-style-toggle" role="tablist" aria-label="Detail layout">
      <button
        type="button"
        role="tab"
        aria-selected={layoutStyle === 'classic'}
        className={`prc-style-toggle__btn${layoutStyle === 'classic' ? ' prc-style-toggle__btn--active' : ''}`}
        onClick={() => setLayoutStyle('classic')}
        title="Conversation-style detail page (default)"
      >
        Classic
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={layoutStyle === 'clean'}
        className={`prc-style-toggle__btn${layoutStyle === 'clean' ? ' prc-style-toggle__btn--active' : ''}`}
        onClick={() => setLayoutStyle('clean')}
        title="Compact original layout"
      >
        Clean
      </button>
    </div>
  );

  if (layoutStyle === 'clean') {
    return (
      <div className="preview">
        <div className="preview__header">
          {onBack && (
            <button
              type="button"
              className="preview__back"
              onClick={onBack}
              title={`Back to ${backLabel ?? 'previous page'}`}
            >
              ← {backLabel ?? 'Back'}
            </button>
          )}
          <div className="preview__title-row">
            <h1 className="preview__title">{pr.title}</h1>
            {RefreshButton}
            {StyleToggle}
          </div>
          <div className="preview__meta">
            <Avatar login={repoOwner} size={16} className="avatar--repo" />
            <span className="preview__repo">{pr.repo}</span>
            <span className="preview__num">#{pr.number}</span>
            {detail?.draft ?? pr.draft ? (
              <span className="pr-badge pr-badge--draft">draft</span>
            ) : null}
            {(detail?.labels ?? pr.labels).map((l) => {
              const color = pr.labelColors?.[l];
              const style = labelChipStyle(color);
              return (
                <span key={l} className="pr-badge pr-badge--label" style={style}>{l}</span>
              );
            })}
            {/* Linked-task chips — the dev task(s) that produced this PR,
                active or completed. Click jumps into the owning thread so
                the user can travel back into the task domain. */}
            {linkedTasks.map(t => (
              <button
                key={t.id}
                type="button"
                onClick={() => onOpenThread?.(t.threadId)}
                className="pr-badge pr-badge--linked-thread"
                title={`Open task: ${t.title}`}
                disabled={!onOpenThread}
              >
                ↗ task: {truncateThreadTitle(t.title)}
              </button>
            ))}
          </div>
          {pr.author && (
            <div className="preview__sub-meta">
              <Avatar login={pr.author} size={18} />
              <a
                className="preview__author-link"
                href={`https://github.com/${pr.author}`}
                target="_blank"
                rel="noreferrer"
              >
                {pr.author}
              </a>
              <span>· updated <RelativeTime timestamp={pr.updatedAt} /></span>
            </div>
          )}
        </div>

        {loading && (
          <div className="preview__loading">
            <LogoLoading size={56} label="Loading details" />
          </div>
        )}
        {refreshing && <div className="preview__refreshing">Refreshing…</div>}
        {error && <div className="preview__error">{error}</div>}

        {detail && (
          <div className="preview__grid" ref={gridRef}>
            <div className="preview__main">
              {pr.requestedReviewers.length > 0 && (
                <section className="preview__section preview__section--reviewers">
                  <h4 className="preview__section-title">Reviewers</h4>
                  <div className="reviewers-list">
                    {pr.requestedReviewers.map((login) => (
                      <a
                        key={login}
                        className="reviewer-chip"
                        href={`https://github.com/${login}`}
                        target="_blank"
                        rel="noreferrer"
                        title={`${login} · review pending`}
                      >
                        <Avatar login={login} size={20} />
                        <span className="reviewer-chip__name">{login}</span>
                      </a>
                    ))}
                    {detail.approvalCount > 0 && (
                      <span className="reviewer-summary reviewer-summary--approved">
                        ✓ {detail.approvalCount} approved
                      </span>
                    )}
                    {detail.changesRequestedCount > 0 && (
                      <span className="reviewer-summary reviewer-summary--changes">
                        ✗ {detail.changesRequestedCount} changes requested
                      </span>
                    )}
                  </div>
                </section>
              )}

              {/* Wrap in the flex-growing description section so the
                  bubble can fill the main column and the body scrolls
                  internally — clean mode's main column has overflow:hidden
                  and relies on its children to handle their own scroll. */}
              <section className="preview__section preview__section--description">
                <DescriptionCard
                  pr={pr}
                  body={detail.body ?? ''}
                  linkedIssues={detail.linkedIssues ?? []}
                  onSaved={handleDescriptionSaved}
                />
              </section>

              <div className="preview__main-pinned">
                {onMerge && !pr.mergedAt && (
                  <MergeBar
                    pr={pr}
                    detail={detail}
                    mergeState={mergeState}
                    mergeError={mergeError}
                    mergeQueuedMessage={mergeQueuedMessage}
                    onMerge={(strategy) => { void handleMerge(strategy); }}
                    onEnableAutoMerge={(strategy) => { void handleEnableAutoMerge(strategy); }}
                    onDequeue={() => { void handleDequeue(); }}
                    onRefreshCi={refreshCi}
                    ciRefreshing={ciRefreshing}
                    canEmptyCommit={canEmptyCommit}
                  />
                )}
                <PrCommentBox
                  pr={pr}
                  mentionCandidates={mentionCandidates}
                  beforeSubmit={guardFreshBeforePost}
                  onClosed={() => { void handleClosed(); }}
                  onCommented={() => { void refreshDetail(); }}
                />
                <CiSummary
                  ciStatus={detail.ciStatus ?? 'NONE'}
                  checkRuns={detail.checkRuns}
                  onRefresh={refreshCi}
                  refreshing={ciRefreshing}
                />
              </div>
            </div>

            {(() => {
              // Hide bot noise (labels, github-actions chatter) EXCEPT
              // merge-queue events: the queue's add/remove is posted by the
              // github-merge-queue bot, and "removed … due to failed status
              // checks" is exactly what the user needs to see.
              const visibleActivity = detail.recentActivity.filter(
                i => !isBotActor(i.actor) || isMergeQueueEvent(i.eventType));
              const threads = detail.reviewThreads ?? [];
              if (visibleActivity.length === 0 && threads.length === 0) return null;
              return (
                <>
                  <ResizeHandle
                    onResize={handleSideResize}
                    ariaLabel="Resize conversation panel"
                    className="preview__side-handle"
                  />
                  <aside className="preview__side" style={{ flexBasis: sideWidth }}>
                    <div className="preview__side-header">
                      <span className="preview__side-title">Conversation</span>
                      <span className="preview__side-count">{visibleActivity.length + threads.length}</span>
                    </div>
                    {visibleActivity.length > 0 && (
                      <ul className="activity-list">
                        {visibleActivity.map((item, i) => {
                          const hasBody = item.body && item.body.trim().length > 0;
                          const stateBadge = item.eventType === 'reviewed' && item.state
                            ? item.state.replace(/_/g, ' ').toLowerCase()
                            : null;
                          return (
                            <li key={i} className={`activity-item${hasBody ? ' activity-item--with-body' : ''}`}>
                              <Avatar login={item.actor} size={24} className="activity-item__avatar" />
                              <div className="activity-item__bubble">
                                <div className="activity-item__header">
                                  <a
                                    className="activity-actor"
                                    href={`https://github.com/${item.actor}`}
                                    target="_blank"
                                    rel="noreferrer"
                                  >
                                    {item.actor}
                                  </a>
                                  <span className="activity-item__verb">{activityVerb(item.eventType)}</span>
                                  {stateBadge && (
                                    <span className={`activity-item__state activity-item__state--${item.state?.toLowerCase()}`}>
                                      {stateBadge}
                                    </span>
                                  )}
                                  {item.timestamp && (
                                    <RelativeTime className="activity-item__time" timestamp={item.timestamp} />
                                  )}
                                  {item.githubId != null && (
                                    <CommentActionsMenu
                                      linkHref={issueCommentLink(pr.htmlUrl, item.githubId)}
                                      onEdit={hasBody && canEditComment(item.actor, item.githubId)
                                        ? () => setEditingCommentId(item.githubId!)
                                        : undefined}
                                      onDelete={canDeleteComment(item.actor, item.githubId)
                                        ? () => handleDeleteIssueComment(item.githubId!)
                                        : undefined}
                                    />
                                  )}
                                </div>
                                {hasBody && (
                                  <EditableMarkdownBody
                                    body={item.body!}
                                    canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
                                    onSave={(b) => handleEditIssueComment(item.githubId!, b)}
                                    editing={editingCommentId === item.githubId}
                                    onEditingChange={(v) => setEditingCommentId(v ? item.githubId! : null)}
                                    className="activity-item__body"
                                    repoContext={repoContext}
                                  />
                                )}
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    )}
                    {threads.length > 0 && (
                      <div className="review-threads">
                        <div className="review-threads__divider">Code review threads</div>
                        {threads.map(thread => (
                          <ReviewThreadCard
                            key={thread.rootGithubId}
                            thread={thread}
                            prAuthor={pr.author}
                            prHtmlUrl={pr.htmlUrl}
                            onReply={(body) => handleReply(thread.rootGithubId, body)}
                            onReact={handleReact}
                            onSetResolved={handleSetThreadResolved}
                            currentUserLogin={currentUserLogin}
                            onEditMessage={handleEditReviewComment}
                            onDeleteMessage={handleDeleteReviewComment}
                            canDeleteMessage={canDeleteComment}
                            repoContext={repoContext}
                          />
                        ))}
                      </div>
                    )}
                  </aside>
                </>
              );
            })()}
          </div>
        )}

        <div className="preview__actions">
          {onInspectDiffs && (
            <button
              className="button button--review button--review--lg"
              type="button"
              onClick={() => {
                void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
                onInspectDiffs();
              }}
              title="Open the native diff viewer — file list, unified diff, and the AI review sidebar all in one screen."
            >
              Review →
            </button>
          )}
          {handledState !== 'done' && (
            <button
              className="button button--secondary"
              type="button"
              disabled={handledState === 'running'}
              onClick={handleMarkHandled}
              title="Mark this PR as handled in your local queue. Doesn't touch GitHub."
            >
              {handledState === 'running' ? 'Marking…' : 'Mark as handled'}
            </button>
          )}
          {handledState === 'done' && (
            <span className="action-badge action-badge--success">✓ Handled</span>
          )}
          {handledState === 'error' && handledError && (
            <span className="action-badge action-badge--error">{handledError}</span>
          )}
          <span className="preview__actions-spacer" aria-hidden="true" />
          <button
            className="button button--remote"
            type="button"
            onClick={handleOpenEmbeddedReview}
            title="Open the full GitHub review UI in an embedded window — stays logged in after first sign-in."
          >
            Open on Remote
          </button>
        </div>
      </div>
    );
  }

  // Per-reviewer verdict derived from recent 'reviewed' events. The latest
  // event per actor wins (matches GitHub's "current verdict" behaviour;
  // a later DISMISSED reverts to "no stance" but we treat it as Pending).
  const reviewerVerdicts = (() => {
    const map = new Map<string, string>();
    if (!detail) return map;
    const reviewed = detail.recentActivity.filter(a => a.eventType === 'reviewed' && a.state);
    // recentActivity is newest-first; iterate reversed so the latest wins.
    for (let i = reviewed.length - 1; i >= 0; i--) {
      const r = reviewed[i];
      map.set(r.actor, r.state ?? '');
    }
    return map;
  })();

  // Activity counts for the right sidebar's "Activity" stats card.
  const stats = (() => {
    if (!detail) return { comments: 0, reviews: 0, commits: 0, daysOpen: null as number | null };
    let comments = 0, reviews = 0;
    for (const a of detail.recentActivity) {
      if (a.eventType === 'commented') comments++;
      else if (a.eventType === 'reviewed') reviews++;
    }
    // Commit count comes from the PR's commit list (fetched above),
    // matching github.com's "wants to merge N commits". 0 while loading
    // → the header reads "wants to merge into".
    const commits = commitCount ?? 0;
    const daysOpen = pr.createdAt
      ? Math.max(0, Math.floor((Date.now() - new Date(pr.createdAt).getTime()) / 86_400_000))
      : null;
    return { comments, reviews, commits, daysOpen };
  })();

  // Build a chronological (oldest-first) timeline of comments + threads +
  // structural events with date dividers between calendar days. The
  // description card is pinned to the very top regardless of timestamps.
  const timelineEntries = (() => {
    if (!detail) return [] as TimelineEntry[];
    const raw: RawTimelineEntry[] = [];
    for (const a of detail.recentActivity) {
      if (isBotActor(a.actor)) continue;
      // Defensive: an event with no actor would render as a blank row,
      // which is what the user reported in review-comment-2.png. Drop
      // them; this is rare and only happens on malformed timeline data.
      if (!a.actor || !a.actor.trim()) continue;
      const ts = a.timestamp ? new Date(a.timestamp).getTime() : 0;
      raw.push({ kind: 'activity', ts, item: a });
    }

    // Match review-thread cards back to the `reviewed` event they were
    // submitted with. We have the GitHub review id on both sides:
    //   - reviewed event:    item.reviewId
    //   - review-thread root: messages[0].reviewId (and reply messages
    //     can carry their own reviewId pointing at a different review)
    // Exact id match avoids the actor+timestamp heuristic.
    //
    // Matched threads render INSIDE the review card and are skipped from
    // the standalone-thread stream so the user sees them once, in context.
    // Fallback heuristic (actor + 60s window) covers events synced before
    // the reviewId column was populated by the migration.
    const allThreads = detail.reviewThreads ?? [];
    const consumedThreads = new Set<number>();
    for (const r of raw) {
      if (r.kind !== 'activity') continue;
      if (r.item.eventType !== 'reviewed') continue;
      const matched: ReviewThreadDto[] = [];
      const reviewId = r.item.reviewId;
      for (const t of allThreads) {
        if (consumedThreads.has(t.rootGithubId)) continue;
        const root = t.messages[0];
        if (!root) continue;
        let isMatch = false;
        if (reviewId != null && root.reviewId === reviewId) {
          isMatch = true;
        }
        else if (reviewId == null) {
          // Fallback for legacy rows where reviewId hasn't been backfilled.
          if (root.author !== r.item.actor) continue;
          const tts = root.createdAt ? new Date(root.createdAt).getTime() : 0;
          if (!tts || Math.abs(tts - r.ts) > 60_000) continue;
          isMatch = true;
        }
        if (isMatch) {
          matched.push(t);
          consumedThreads.add(t.rootGithubId);
        }
      }
      if (matched.length > 0) {
        // GitHub shows a review's inline comments oldest-first (≈ diff
        // order, as the reviewer worked top-to-bottom). detail.reviewThreads
        // can arrive newest-first, and these matched threads bypass the
        // global raw.sort below — so sort them here by the root comment's
        // GitHub id, which increases monotonically with creation, to
        // restore GitHub's order instead of rendering them reversed.
        matched.sort((a, b) => a.rootGithubId - b.rootGithubId);
        (r as Extract<RawTimelineEntry, { kind: 'activity' }>).attachedThreads = matched;
      }
    }

    for (const t of allThreads) {
      if (consumedThreads.has(t.rootGithubId)) continue;
      const root = t.messages[0];
      // Bot-filter at the thread level too. coderabbitai/sourcery/etc.
      // emit one root comment per "finding" — without this filter a PR
      // with a noisy bot reviewer renders as a wall of bot cards on the
      // conversation timeline. We still render bot threads inline in the
      // diff viewer (they're useful in code context); the conversation
      // panel just shouldn't lead with them.
      if (root && isBotActor(root.author)) continue;
      const ts = root?.createdAt ? new Date(root.createdAt).getTime() : 0;
      raw.push({ kind: 'thread', ts, thread: t });
    }
    raw.sort((a, b) => a.ts - b.ts);

    // GitHub's REST timeline doesn't expose before/after SHAs for
    // head_ref_force_pushed events (the starfox preview that used to
    // include them was retired). Derive a "new head" SHA per force-push
    // by looking forward in the timeline: the next committed event with
    // an afterSha, before any later force-push, is the head we want.
    const lastForcePushSha = new Map<number, string | null>();
    {
      let nextHead: string | null = null;
      for (let k = raw.length - 1; k >= 0; k--) {
        const r = raw[k];
        if (r.kind !== 'activity') continue;
        if (r.item.eventType === 'committed' && r.item.afterSha) {
          nextHead = r.item.afterSha;
        }
        if (r.item.eventType === 'head_ref_force_pushed') {
          lastForcePushSha.set(r.ts, nextHead);
          // The next force-push (earlier in the array) gets a fresh
          // search window starting from its own position.
          nextHead = null;
        }
      }
    }
    // Hydrate force-push items so the renderer doesn't need to know how
    // we sourced the SHA. Mutating the local Raw entry is fine — the
    // ActivityItemDto is fresh per fetch.
    for (const r of raw) {
      if (r.kind !== 'activity') continue;
      if (r.item.eventType !== 'head_ref_force_pushed') continue;
      const derived = lastForcePushSha.get(r.ts);
      if (derived && !r.item.afterSha) {
        r.item = { ...r.item, afterSha: derived };
      }
    }

    // Run-length / burst collapse — see ./pr/timelineGrouping.ts.
    // Same-actor commits or force-pushes on the same day fold into a
    // single summary line; same-actor review_requested events within
    // a 60s burst fold into a "requested @a, @b and @c for review"
    // line. Everything else passes through.
    return groupTimelineEntries(raw);
  })();

  return (
    <div className="prc-page">
      <header className="prc-header">
        {/* Row 1: large title, inline-editable (click to rename — saves
            via the PATCH /prs/title endpoint), with the #N link folded in
            at the end. Matches docs/mockups/v2/detail/pr-header.png. */}
        <div className="prc-header__title-row">
          <h1 className="prc-title">
            <EditableTitle
              title={titleOverride ?? pr.title}
              titleStyleOverride={{ fontSize: 'inherit', fontWeight: 'inherit', overflow: 'visible', textOverflow: 'clip', whiteSpace: 'normal' }}
              inputStyleOverride={{ fontSize: 'inherit', fontWeight: 'inherit' }}
              onRename={async next => {
                const updated = await window.bridge.updatePrTitle(pr.repo, pr.number, next);
                setTitleOverride(updated.title);
              }}
            />
            {/* No target="_blank": main.ts's will-navigate handler
                routes plain clicks into the in-app browser overlay,
                whereas window-open clicks (target="_blank") spawn a
                detached native popup with no path back to the app. */}
            <a
              className="prc-title__number"
              href={pr.htmlUrl}
              title="Open this PR on github.com"
            >
              #{pr.number}
            </a>
          </h1>
        </div>
        {/* Row 2: github.com-style branch sentence with the state pill
            leading and the source/target branches as monospace chips.
            Per pr-header.png — "[Open] author wants to merge N commits
            into base from head ⎘". Hidden until detail-sync populates
            the refs since the sentence reads broken without them. */}
        {detail?.headRef && detail?.baseRef && (
          <div className="prc-branch-line">
            {(() => {
              if (pr.mergedAt) {
                return <span className="prc-status-pill prc-status-pill--merged">Merged</span>;
              }
              if (pr.state === 'closed') {
                return <span className="prc-status-pill prc-status-pill--closed">Closed</span>;
              }
              // Queued: GraphQL says the PR has a mergeQueueEntry. Sits
              // above Draft / Open because a queued PR is in-flight —
              // CI passing, approvals in, GitHub is actively trying to
              // merge it. Same affordance github.com shows.
              if (detail.mergeQueueState) {
                return <span className="prc-status-pill prc-status-pill--queued">Queued</span>;
              }
              if (detail.draft ?? pr.draft) {
                return <span className="prc-status-pill prc-status-pill--draft">Draft</span>;
              }
              return <span className="prc-status-pill">Open</span>;
            })()}
            {pr.author && <a className="prc-branch-line__author" href={`https://github.com/${pr.author}`} target="_blank" rel="noreferrer">{pr.author}</a>}
            <span className="prc-branch-line__verb">
              {(() => {
                // PR commit count (stats.commits is sourced from the
                // PR's commit list). When it's 0 — the count isn't
                // available yet — fall back to the article-less form so
                // the sentence still parses.
                const n = stats.commits;
                if (n <= 0) return 'wants to merge into';
                return `wants to merge ${n} commit${n === 1 ? '' : 's'} into`;
              })()}
            </span>
            <code className="prc-branches__ref" title={`${detail.baseRepo ?? ''}:${detail.baseRef}`}>
              {detail.baseRepo ? `${detail.baseRepo.split('/')[0]}:${detail.baseRef}` : detail.baseRef}
            </code>
            <span className="prc-branch-line__verb">from</span>
            {onOpenLocalBranch && detail.headRef ? (
              // The chip itself is the click target — opens this
              // branch's commits in the local-repo Commits tab. The
              // local-repo page handles the "branch not in local
              // clone yet" case with a fetch-and-retry prompt, so we
              // don't gate this on detail.headRepo === pr.repo.
              <button
                type="button"
                className="prc-branches__ref prc-branches__ref--clickable"
                title={`Open ${detail.headRepo ?? ''}:${detail.headRef} in the local-repo Commits tab`}
                onClick={() => {
                  const [o, r] = pr.repo.split('/');
                  if (o && r) onOpenLocalBranch(o, r, detail.headRef!);
                }}
              >
                {detail.headRepo ? `${detail.headRepo.split('/')[0]}:${detail.headRef}` : detail.headRef}
              </button>
            ) : (
              <code className="prc-branches__ref" title={`${detail.headRepo ?? ''}:${detail.headRef}`}>
                {detail.headRepo ? `${detail.headRepo.split('/')[0]}:${detail.headRef}` : detail.headRef}
              </code>
            )}
            <button
              type="button"
              className="prc-branch-line__copy"
              onClick={() => { void navigator.clipboard.writeText(detail.headRef ?? ''); }}
              title="Copy branch name"
              aria-label="Copy branch name"
            >
              ⎘
            </button>
          </div>
        )}
        {/* Row 3: muted secondary line — repo + relative timestamps.
            Status pill, #number, and author all moved into the upper
            rows above, so this strip drops them to avoid duplication. */}
        <div className="prc-meta-line">
          <Avatar login={repoOwner} size={14} className="avatar--repo" />
          <a
            href={`https://github.com/${pr.repo}`}
            target="_blank"
            rel="noreferrer"
            className="prc-meta-link"
          >
            {pr.repo}
          </a>
          <span className="prc-meta-sep">·</span>
          <span className="prc-meta-time">
            {pr.createdAt && <>opened <RelativeTime timestamp={pr.createdAt} /> · </>}updated{' '}
            <RelativeTime timestamp={pr.updatedAt} />
          </span>
          {/* GitHub-mark icon button stands in for the old "Open on
              Remote" text button — it lives next to the timestamps so
              the actions row only has to carry the primary "Review"
              action plus the small secondary controls. Triggers the
              same embedded-tab handler. */}
          <button
            type="button"
            className="prc-meta-github"
            onClick={handleOpenEmbeddedReview}
            title="Open the embedded github.com window for this PR"
            aria-label="Open this PR in the embedded github.com window"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.65 7.65 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
            </svg>
          </button>
          {/* Linked-task chip — the dev task that produced this PR (active
              or completed). Lets the user jump from the PR back to the task
              that developed it. */}
          {linkedTasks.map(t => (
            <button
              key={t.id}
              type="button"
              className="prc-meta-task"
              onClick={() => onOpenThread?.(t.threadId)}
              disabled={!onOpenThread}
              title={`Open the task that developed this PR: ${t.title}`}
            >
              ↗ task: {truncateThreadTitle(t.title)}
            </button>
          ))}
        </div>
        <div className="prc-actions">
          {RefreshButton}
          {StyleToggle}
          {handledState !== 'done' && (
            <button
              type="button"
              className="button button--secondary button--sm"
              disabled={handledState === 'running'}
              onClick={handleMarkHandled}
              title="Mark as handled in your local queue. Doesn't touch GitHub."
            >
              {handledState === 'running' ? 'Marking…' : '✓ Mark handled'}
            </button>
          )}
          {handledState === 'done' && (
            <span className="action-badge action-badge--success">✓ Handled</span>
          )}
          {onInspectDiffs && (
            <button
              type="button"
              className="button button--review button--review--lg"
              onClick={() => {
                void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
                onInspectDiffs();
              }}
              title="Open the native diff viewer + AI review sidebar."
            >
              Review →
            </button>
          )}
        </div>
      </header>

      {loading && (
        <div className="prc-loading">
          <LogoLoading size={56} label="Loading details" />
        </div>
      )}
      {refreshing && <div className="prc-refreshing">Refreshing…</div>}
      {error && <div className="prc-error">{error}</div>}

      {detail && (
        <div className={`prc-body${sidebarCollapsed ? ' prc-body--sidebar-collapsed' : ''}`} ref={gridRef}>
          {/* ── Conversation column ──────────────────────────────── */}
          <main className="prc-timeline">
            {/* Wrapper so the rail is exactly the height of the timeline
                entries, and not the height of the scroll viewport. The
                comment box sits OUTSIDE this wrapper so the rail stops at
                the last event instead of continuing through the composer
                and into any empty space below. */}
            <div className="prc-timeline__entries">
              <DescriptionCard
                pr={pr}
                body={detail.body ?? ''}
                linkedIssues={detail.linkedIssues ?? []}
                onSaved={handleDescriptionSaved}
              />

              {timelineEntries.map((e, i) => {
                if (e.kind === 'date-divider') {
                  return (
                    <div key={`d-${i}`} className="prc-date-divider">
                      <span>{e.label}</span>
                    </div>
                  );
                }
                if (e.kind === 'activity') {
                  return renderActivity(e.item, i, e.attachedThreads);
                }
                if (e.kind === 'event-group') {
                  return renderEventGroup(e, i);
                }
                return renderThread(e.thread, i);
              })}
              {/* Merge card lives inside .prc-timeline__entries so the
                  rail extends down to it and the 30px sibling gap
                  applies above. */}
              {onMerge && !pr.mergedAt && (
                <MergeBar
                  pr={pr}
                  detail={detail}
                  mergeState={mergeState}
                  mergeError={mergeError}
                  mergeQueuedMessage={mergeQueuedMessage}
                  onMerge={(strategy) => { void handleMerge(strategy); }}
                  onEnableAutoMerge={(strategy) => { void handleEnableAutoMerge(strategy); }}
                  onDequeue={() => { void handleDequeue(); }}
                  onRefreshCi={refreshCi}
                  ciRefreshing={ciRefreshing}
                    canEmptyCommit={canEmptyCommit}
                />
              )}
            </div>

            <PrCommentBox
              ref={commentBoxRef}
              pr={pr}
              mentionCandidates={mentionCandidates}
              beforeSubmit={guardFreshBeforePost}
              onClosed={() => { void handleClosed(); }}
              onCommented={() => { void refreshDetail(); }}
            />
          </main>

          {/* ── Right meta sidebar ───────────────────────────────── */}
          <aside className={`prc-sidebar${sidebarCollapsed ? ' prc-sidebar--collapsed' : ''}`}>
            {/* Collapse/expand toggle — always rendered so the rail
                form still carries an affordance to re-open. Single-
                chevron glyphs (‹/›) match the visual weight of the
                kanban PR card's circular handle chip (✓ / ↺ / ⌛);
                the button styling mirrors .kpr-card__handle. Arrow
                points the direction the sidebar is about to move:
                › collapses (rolls toward the right edge), ‹ expands
                (rolls back leftward into the conversation). */}
            <button
              type="button"
              className="prc-sidebar__toggle"
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
              title={sidebarCollapsed ? 'Expand the meta sidebar' : 'Collapse the meta sidebar to the right edge'}
              aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              aria-expanded={!sidebarCollapsed}
            >
              <span aria-hidden="true">{sidebarCollapsed ? '‹' : '›'}</span>
            </button>
            {!sidebarCollapsed && (<>
            {onInspectDiffs && (
              <section className="prc-meta-section prc-yrb">
                <div className="prc-yrb__title">Your review</div>
                <div className="prc-yrb__text">
                  Click below to open the diff + AI review sidebar.
                </div>
                <button
                  type="button"
                  className="prc-yrb__cta"
                  onClick={() => {
                    void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
                    onInspectDiffs();
                  }}
                >
                  Start review →
                </button>
              </section>
            )}

            {/* Draft / ready-for-review toggle. Both directions are useful:
                authors flip an in-progress PR to ready, and reviewers /
                maintainers can knock a regressed PR back to draft. The
                button is greyed-while-running with the error inlined. */}
            {!pr.mergedAt && pr.state !== 'closed' && (
              <section className="prc-meta-section">
                <div className="prc-meta-label">Draft</div>
                <div className="prc-status-row">
                  <span
                    className="prc-status-dot"
                    style={{ background: detail.draft ? '#6b7280' : '#2da44e' }}
                  />
                  <span>
                    <b>{detail.draft ? 'Draft' : 'Ready for review'}</b>
                  </span>
                </div>
                <button
                  type="button"
                  className={`prc-draft-toggle-btn${detail.draft ? ' prc-draft-toggle-btn--ready' : ' prc-draft-toggle-btn--draft'}`}
                  onClick={() => { void handleToggleDraft(); }}
                  disabled={draftToggleState === 'running'}
                  title={detail.draft
                    ? 'Mark this PR as ready for review on GitHub'
                    : 'Convert this PR back to a draft on GitHub'}
                >
                  {draftToggleState === 'running'
                    ? '…'
                    : detail.draft
                      ? 'Mark as ready'
                      : 'Convert to draft'}
                </button>
                {draftToggleState === 'error' && draftToggleError && (
                  <div className="prc-draft-toggle-error" title={draftToggleError}>
                    {draftToggleError.length > 80 ? draftToggleError.slice(0, 77) + '…' : draftToggleError}
                  </div>
                )}
              </section>
            )}

            <section className="prc-meta-section">
              <div className="prc-meta-label">Reviewers</div>
              <ReviewerEditor
                pr={pr}
                reviewerVerdicts={reviewerVerdicts}
                pendingReviewers={detail?.requestedReviewers}
                onRefresh={async () => {
                  await refreshDetailFromGitHub();
                }}
              />
            </section>

            <section className="prc-meta-section">
              <div className="prc-meta-label">Status</div>
              {detail.ciStatus && detail.ciStatus !== 'NONE' && (
                <CiChecksRow ciStatus={detail.ciStatus} checkRuns={detail.checkRuns} />
              )}
              {(detail.mergeableState ?? pr.mergeableState) === 'dirty' && (
                // Sibling row to the CI status line. Same shape, red
                // dot — the eye scans them as a list. The full advice
                // (+ link to github.com's conflict editor) lives in
                // the top-of-page banner so this row stays compact.
                <div className="prc-status-row prc-status-row--conflict">
                  <span className="prc-status-dot" style={{ background: '#dc2626' }} />
                  <span><b>Merge conflict</b> — resolve before merging</span>
                </div>
              )}
              {detail.approvalCount > 0 && (
                <div className="prc-status-row">
                  <span className="prc-status-dot" style={{ background: '#16a34a' }} />
                  <span><b>{detail.approvalCount} approval{detail.approvalCount === 1 ? '' : 's'}</b></span>
                </div>
              )}
              {detail.changesRequestedCount > 0 && (
                <div className="prc-status-row">
                  <span className="prc-status-dot" style={{ background: '#ef4444' }} />
                  <span><b>{detail.changesRequestedCount} changes requested</b></span>
                </div>
              )}
              {(detail.ciStatus === 'NONE' || !detail.ciStatus)
                && detail.approvalCount === 0
                && detail.changesRequestedCount === 0
                && (detail.mergeableState ?? pr.mergeableState) !== 'dirty' && (
                <div className="prc-meta-empty">No checks or reviews yet.</div>
              )}
            </section>

            <section className="prc-meta-section">
              <div className="prc-meta-label">
                Files changed
                {onInspectDiffs && (
                  // Wrap the call: onInspectDiffs takes an optional sha
                  // string. Passing it bare as onClick forwarded the
                  // React MouseEvent into that slot, which later crashed
                  // formatShortSha(.slice).
                  <button type="button" className="prc-meta-link-btn" onClick={() => onInspectDiffs?.()}>View diff →</button>
                )}
                {onStartReview && (
                  <button
                    type="button"
                    className="prc-meta-link-btn"
                    disabled={reviewStarting}
                    onClick={async () => {
                      if (reviewStarting) return;
                      setReviewStarting(true);
                      setReviewError(null);
                      try {
                        const result = await window.bridge.startReview(pr.repo, pr.number);
                        onStartReview(result.pass.threadId);
                      }
                      catch (e) {
                        setReviewError(e instanceof Error ? e.message : String(e));
                      }
                      finally {
                        setReviewStarting(false);
                      }
                    }}
                  >
                    {reviewStarting ? 'Starting…' : 'AI panel review →'}
                  </button>
                )}
              </div>
              {reviewError !== null && (
                <div className="prc-meta-error" role="alert">{reviewError}</div>
              )}
              <div className="prc-stat-line">
                <span>{detail.files.length} file{detail.files.length === 1 ? '' : 's'}</span>
                <span className="prc-stat-num">
                  <span className="prc-stat-add">+{detail.additions}</span>
                  {' '}
                  <span className="prc-stat-rem">−{detail.deletions}</span>
                </span>
              </div>
              {detail.files.slice(0, 4).map(f => (
                <div key={f.filename} className="prc-file-row" title={f.filename}>
                  <span className="prc-file-name">{truncatePath(f.filename)}</span>
                  <span className="prc-stat-num">
                    <span className="prc-stat-add">+{f.additions}</span>
                    <span className="prc-stat-rem">−{f.deletions}</span>
                  </span>
                </div>
              ))}
              {detail.files.length > 4 && (
                <div className="prc-file-row prc-file-row--more">+ {detail.files.length - 4} more</div>
              )}
            </section>

            {pr.labels.length > 0 && (
              <section className="prc-meta-section">
                <div className="prc-meta-label">Labels</div>
                <div className="prc-label-row">
                  {pr.labels.map(l => (
                    <span key={l} className="prc-label-pill" style={labelChipStyle(pr.labelColors?.[l])}>{l}</span>
                  ))}
                </div>
              </section>
            )}

            {detail.linkedIssues && detail.linkedIssues.length > 0 && (
              <section className="prc-meta-section">
                <div className="prc-meta-label">Linked issues</div>
                {detail.linkedIssues.map(li => (
                  <a
                    key={li.number}
                    className="prc-linked-issue"
                    href={li.htmlUrl}
                    target="_blank"
                    rel="noreferrer"
                    title={`${li.state} · #${li.number} ${li.title}`}
                  >
                    <span className={`prc-linked-issue__state prc-linked-issue__state--${li.state.toLowerCase()}`}>
                      {li.state === 'closed' ? '✓' : '○'}
                    </span>
                    <span className="prc-linked-issue__num">#{li.number}</span>
                    <span className="prc-linked-issue__title">{li.title}</span>
                  </a>
                ))}
              </section>
            )}

            <section className="prc-meta-section">
              <div className="prc-meta-label">Activity</div>
              <div className="prc-stat-line"><span>Comments</span><span className="prc-stat-num">{stats.comments}</span></div>
              <div className="prc-stat-line"><span>Reviews</span><span className="prc-stat-num">{stats.reviews}</span></div>
              <div className="prc-stat-line"><span>Commits</span><span className="prc-stat-num">{stats.commits}</span></div>
              {stats.daysOpen != null && (
                <div className="prc-stat-line"><span>Days open</span><span className="prc-stat-num">{stats.daysOpen}</span></div>
              )}
            </section>
            </>)}
          </aside>
        </div>
      )}
    </div>
  );

  // ── Inline render helpers (closure over detail / pr / onInspectDiffs) ──
  /** Render a 7-char commit SHA chip styled like a link. Click jumps to
   *  the in-app diff viewer with that commit pre-selected (single-commit
   *  view); only when no `onInspectDiffs` handler is wired (e.g. when
   *  the preview is mounted somewhere that doesn't host the diff viewer)
   *  does it fall back to opening github.com in a new tab. */
  function commitLink(repo: string, sha: string) {
    const short = sha.slice(0, 7);
    const githubHref = `https://github.com/${repo}/commit/${sha}`;
    return (
      <a
        className="prc-event-sha"
        href={githubHref}
        target={onInspectDiffs ? undefined : '_blank'}
        rel={onInspectDiffs ? undefined : 'noreferrer'}
        onClick={(e) => {
          if (!onInspectDiffs) return;
          // Don't hijack a modified click — Cmd/Ctrl/middle-click should
          // still open github.com in a new tab so the user can pop the
          // commit out without losing their place in the timeline.
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) return;
          e.preventDefault();
          onInspectDiffs(sha);
        }}
        title={`View commit ${short} in the diff viewer`}
      >
        <code>{short}</code>
      </a>
    );
  }

  function renderActivity(item: ActivityItemDto, key: number | string, attachedThreads?: ReviewThreadDto[]) {
    const hasBody = !!item.body && item.body.trim().length > 0;
    const hasThreads = !!attachedThreads && attachedThreads.length > 0;
    // Tint the signed-in user's own comments so they're easy to pick out
    // of the thread, the way github.com shades "your" comments.
    const isMine = !!currentUserLogin && currentUserLogin === item.actor;
    const stateBadge = item.eventType === 'reviewed' && item.state
      ? item.state.replace(/_/g, ' ').toLowerCase()
      : null;
    // Reviewed events render as a compact verdict row on the rail; a
    // review body (if any) and its inline threads sit in nested blocks
    // beneath it. Plain issue comments still render as speech-bubble cards.
    if (item.eventType === 'reviewed') {
      // GitHub-style review event: a compact verdict line on the rail
      // ("<actor> approved these changes · <time>"), then — only when the
      // review carried a body — a separate bordered "left a comment" card
      // indented beneath it, and finally the inline threads. github.com
      // keeps the verdict line and the comment dialog as distinct blocks
      // rather than merging them into one card.
      // An approval from a reviewer without write access (a drive-by
      // CONTRIBUTOR / outside user) is shown by GitHub but doesn't count
      // toward the merge requirement — render it muted (gray) instead of
      // the authoritative green so it doesn't read as a blocking approval.
      const approvalDoesNotCount = item.state === 'APPROVED'
        && !approvalCountsTowardMerge(item.authorAssociation);
      const variant = item.state === 'APPROVED'
        ? (approvalDoesNotCount ? 'approved-muted' : 'approved')
        : item.state === 'CHANGES_REQUESTED'
          ? 'changes-requested'
          : 'commented';
      const phrase = item.state === 'APPROVED'
        ? 'approved these changes'
        : item.state === 'CHANGES_REQUESTED'
          ? 'requested changes'
          : 'left a review';
      const glyph = item.state === 'APPROVED' ? '✓' : item.state === 'CHANGES_REQUESTED' ? '✕' : '◐';
      return (
        <div key={`a-${key}`} className="prc-review-event">
          <div className={`prc-approved-row prc-approved-row--${variant}`}>
            <Avatar login={item.actor} size={40} className="prc-approved-row__avatar" />
            <span
              className={`prc-approved-row__check prc-approved-row__check--${variant}`}
              aria-hidden
              title={approvalDoesNotCount
                ? 'Approved by a reviewer without write access — does not count toward merge'
                : undefined}
            >
              {glyph}
            </span>
            <span className="prc-approved-row__text">
              <a
                href={`https://github.com/${item.actor}`}
                target="_blank"
                rel="noreferrer"
                className="prc-approved-row__author"
              >
                {item.actor}
              </a>
              {' '}{phrase}
              {item.timestamp && (
                <> {' '}<RelativeTime className="prc-approved-row__time" timestamp={item.timestamp} /></>
              )}
            </span>
          </div>
          {hasBody && (
            <article className="prc-comment-card prc-review-comment-card">
              <div className={`prc-comment-card-body${isMine ? ' prc-comment-card-body--mine' : ''}`}>
                <header className="prc-comment-head">
                  <a
                    href={`https://github.com/${item.actor}`}
                    target="_blank"
                    rel="noreferrer"
                    className="prc-comment-author"
                  >
                    {item.actor}
                  </a>
                  <span className="prc-comment-verb">left a comment</span>
                  {pr.author === item.actor
                    ? <span className="prc-comment-role">AUTHOR</span>
                    : authorAssociationLabel(item.authorAssociation) && (
                      <span className="prc-comment-role prc-comment-role--association">
                        {authorAssociationLabel(item.authorAssociation)}
                      </span>
                    )}
                </header>
                <EditableMarkdownBody
                  body={item.body!}
                  canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
                  onSave={(b) => handleEditIssueComment(item.githubId!, b)}
                  repoContext={repoContext}
                />
              </div>
            </article>
          )}
          {hasThreads && (
            <div className="prc-review-event__detail">
              <div className="prc-comment-threads">
                {attachedThreads!.map(thread => (
                  <ReviewThreadCard
                    key={thread.rootGithubId}
                    thread={thread}
                    prAuthor={pr.author}
                    prHtmlUrl={pr.htmlUrl}
                    onReply={(body) => handleReply(thread.rootGithubId, body)}
                    onReact={handleReact}
                    onSetResolved={handleSetThreadResolved}
                    currentUserLogin={currentUserLogin}
                    onEditMessage={handleEditReviewComment}
                    onDeleteMessage={handleDeleteReviewComment}
                    canDeleteMessage={canDeleteComment}
                    repoContext={repoContext}
                  />
                ))}
              </div>
            </div>
          )}
        </div>
      );
    }
    const isStructural = !hasBody && item.eventType !== 'reviewed';
    if (item.eventType === 'head_ref_force_pushed') {
      return (
        <div key={`a-${key}`} className="prc-event-row">
          <span className={`prc-event-marker prc-event-marker--${item.eventType}`} aria-hidden>{eventMarker(item.eventType)}</span>
          <Avatar login={item.actor} size={20} className="prc-event-avatar" />
          <span>
            <b>{item.actor}</b> force-pushed
            {item.beforeSha && item.afterSha
              ? <> · {commitLink(pr.repo, item.beforeSha)} → {commitLink(pr.repo, item.afterSha)}</>
              : item.afterSha
                ? <> · {commitLink(pr.repo, item.afterSha)}</>
                : null}
          </span>
          {item.timestamp && <RelativeTime className="prc-event-time" timestamp={item.timestamp} />}
        </div>
      );
    }
    if (isStructural) {
      // review_requested needs special wording: actor is the inviter, the
      // requestedReviewer field is the invitee. Everything else uses the
      // generic activityVerb mapping.
      if (item.eventType === 'review_requested') {
        return (
          <div key={`a-${key}`} className="prc-event-row">
            <span className={`prc-event-marker prc-event-marker--${item.eventType}`} aria-hidden>{eventMarker(item.eventType)}</span>
            <Avatar login={item.actor} size={20} className="prc-event-avatar" />
            <span>
              <b>{item.actor}</b> requested
              {item.requestedReviewer
                ? <> <a className="prc-event-mention" href={`https://github.com/${item.requestedReviewer}`} target="_blank" rel="noreferrer">@{item.requestedReviewer}</a></>
                : <> a reviewer</>}
              {' '}to review
            </span>
            {item.timestamp && <RelativeTime className="prc-event-time" timestamp={item.timestamp} />}
          </div>
        );
      }
      // For solo "committed" events the SHA is in afterSha (extracted
      // from GitHub's top-level `sha` field on those payloads).
      const sha = item.eventType === 'committed' ? item.afterSha : null;
      return (
        <div key={`a-${key}`} className="prc-event-row">
          <span className={`prc-event-marker prc-event-marker--${item.eventType}`} aria-hidden>{eventMarker(item.eventType)}</span>
          <Avatar login={item.actor} size={20} className="prc-event-avatar" />
          <span>
            <b>{displayActor(item.actor)}</b>
            {isBotActor(item.actor) && <span className="prc-bot-tag">bot</span>}
            {' '}{activityVerb(item.eventType)}
            {/* The github-merge-queue bot only ejects a PR when the queue's
                required checks fail — surface that reason like GitHub does. */}
            {item.eventType === 'removed_from_merge_queue' && isBotActor(item.actor)
              && ' due to failed status checks'}
            {sha && <> · {commitLink(pr.repo, sha)}</>}
          </span>
          {item.timestamp && <RelativeTime className="prc-event-time" timestamp={item.timestamp} />}
        </div>
      );
    }
    // Comment / review with body — full card. Avatar sits OUTSIDE the
    // bubble (left), the bordered body is the "speech bubble" the avatar
    // is saying.
    return (
      <article key={`a-${key}`} className="prc-comment-card">
        <Avatar login={item.actor} size={40} className="prc-comment-avatar" />
        <div className={`prc-comment-card-body${isMine ? ' prc-comment-card-body--mine' : ''}`}>
          <header className="prc-comment-head">
            <a
              href={`https://github.com/${item.actor}`}
              target="_blank"
              rel="noreferrer"
              className="prc-comment-author"
            >
              {item.actor}
            </a>
            <span className="prc-comment-verb">commented</span>
            {item.timestamp && (
              <RelativeTime className="prc-comment-time" timestamp={item.timestamp} />
            )}
            {pr.author === item.actor
              ? <span className="prc-comment-role">AUTHOR</span>
              : authorAssociationLabel(item.authorAssociation) && (
                <span className="prc-comment-role prc-comment-role--association">
                  {authorAssociationLabel(item.authorAssociation)}
                </span>
              )}
            {stateBadge && (
              <span className={`prc-verdict-pill prc-verdict-pill--${item.state?.toLowerCase()}`}>
                {stateBadge}
              </span>
            )}
            {item.githubId != null && (
              <CommentActionsMenu
                linkHref={issueCommentLink(pr.htmlUrl, item.githubId)}
                onQuote={hasBody ? () => commentBoxRef.current?.insertQuote(item.body ?? '') : undefined}
                onEdit={hasBody && canEditComment(item.actor, item.githubId)
                  ? () => setEditingCommentId(item.githubId!)
                  : undefined}
                onDelete={canDeleteComment(item.actor, item.githubId)
                  ? () => handleDeleteIssueComment(item.githubId!)
                  : undefined}
              />
            )}
          </header>
          {hasBody && (
            <EditableMarkdownBody
              body={item.body!}
              canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
              onSave={(b) => handleEditIssueComment(item.githubId!, b)}
              editing={editingCommentId === item.githubId}
              onEditingChange={(v) => setEditingCommentId(v ? item.githubId! : null)}
              repoContext={repoContext}
            />
          )}
          {/* Reactions row + emoji-add button. Only renders when we
              have a github comment id (issue-comment timeline events
              do; structural events without bodies skip the comment-
              card branch entirely). */}
          {item.githubId != null && (
            <ReactionChips
              reactions={item.reactions}
              onAddReaction={(content) => { void handleIssueReact(item.githubId!, content); }}
            />
          )}
          {!hasBody && attachedThreads && attachedThreads.length > 0 && (
            <div className="prc-comment-empty-note">
              Submitted {attachedThreads.length} inline comment{attachedThreads.length === 1 ? '' : 's'}:
            </div>
          )}
          {attachedThreads && attachedThreads.length > 0 && (
            <div className="prc-comment-threads">
              {attachedThreads.map(thread => (
                <ReviewThreadCard
                  key={thread.rootGithubId}
                  thread={thread}
                  prAuthor={pr.author}
                  prHtmlUrl={pr.htmlUrl}
                  onReply={(body) => handleReply(thread.rootGithubId, body)}
                  onReact={handleReact}
                  onSetResolved={handleSetThreadResolved}
                  currentUserLogin={currentUserLogin}
                  onEditMessage={handleEditReviewComment}
                  onDeleteMessage={handleDeleteReviewComment}
                  canDeleteMessage={canDeleteComment}
                  repoContext={repoContext}
                />
              ))}
            </div>
          )}
        </div>
      </article>
    );
  }

  function renderEventGroup(
    e: {
      kind: 'event-group';
      actor: string;
      eventType: string;
      count: number;
      lastItem: ActivityItemDto;
      reviewers?: string[];
    },
    key: number | string,
  ) {
    // review_requested burst: list every requested reviewer inline as
    // "x requested a, b, c and d for review" instead of N stacked rows.
    if (e.eventType === 'review_requested' && e.reviewers && e.reviewers.length > 1) {
      const links = e.reviewers.map((login, idx) => {
        const sep =
          idx === 0 ? '' :
          idx === e.reviewers!.length - 1 ? ' and ' :
          ', ';
        return (
          <span key={login}>
            {sep}
            <a className="prc-event-mention" href={`https://github.com/${login}`} target="_blank" rel="noreferrer">@{login}</a>
          </span>
        );
      });
      return (
        <div key={`g-${key}`} className="prc-event-row">
          <span className={`prc-event-marker prc-event-marker--${e.eventType}`} aria-hidden>{eventMarker(e.eventType)}</span>
          <Avatar login={e.actor} size={20} className="prc-event-avatar" />
          <span>
            <b>{e.actor}</b> requested {links} for review
          </span>
          {e.lastItem.timestamp && (
            <RelativeTime className="prc-event-time" timestamp={e.lastItem.timestamp} />
          )}
        </div>
      );
    }
    // Group label per event type. SHA shown is the last event in the run —
    // for committed events that's `afterSha`; for force-pushes it's the
    // newest `afterSha` (the new head).
    const sha = e.lastItem.afterSha ?? null;
    let label: string;
    if (e.eventType === 'committed') {
      label = `pushed ${e.count} commits`;
    }
    else if (e.eventType === 'head_ref_force_pushed') {
      label = `force-pushed ${e.count} times`;
    }
    else {
      label = `${activityVerb(e.eventType)} ×${e.count}`;
    }
    const shaLabel = e.eventType === 'head_ref_force_pushed' ? 'last head' : 'last commit';
    return (
      <div key={`g-${key}`} className="prc-event-row">
        <span className={`prc-event-marker prc-event-marker--${e.eventType}`} aria-hidden>{eventMarker(e.eventType)}</span>
        <Avatar login={e.actor} size={20} className="prc-event-avatar" />
        <span>
          <b>{e.actor}</b> {label}
          {sha && <> · {shaLabel} {commitLink(pr.repo, sha)}</>}
        </span>
        {e.lastItem.timestamp && (
          <RelativeTime className="prc-event-time" timestamp={e.lastItem.timestamp} />
        )}
      </div>
    );
  }

  function renderThread(thread: ReviewThreadDto, key: number | string) {
    return (
      <ReviewThreadCard
        key={`t-${key}`}
        thread={thread}
        prAuthor={pr.author}
        prHtmlUrl={pr.htmlUrl}
        onReply={(body) => handleReply(thread.rootGithubId, body)}
        onReact={handleReact}
        onSetResolved={handleSetThreadResolved}
        currentUserLogin={currentUserLogin}
        onEditMessage={handleEditReviewComment}
        onDeleteMessage={handleDeleteReviewComment}
        canDeleteMessage={canDeleteComment}
        repoContext={repoContext}
      />
    );
  }
}

export default PullRequestPreview;
