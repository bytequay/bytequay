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
import type { ActivityItemDto, CheckRunDto, MergeConflictPathsDto, PullRequestDetailDto, PullRequestDto, ReviewMessageDto, ReviewThreadDto, ThreadDto, UserProfileDto } from './types';
import { getCached as getCachedValue } from './dataCache';
import { EditableMarkdownBody } from './pr/EditableMarkdownBody';
import Avatar from './Avatar';
import ResizeHandle from './ResizeHandle';
import {
  activityVerb,
  authorAssociationLabel,
  conclusionLabel,
  eventMarker,
  formatRelativeTime,
  isBotActor,
  isCheckFailing,
  labelChipStyle,
  relativeDayLabel,
  truncatePath,
  type ReactionContent,
} from './pr/utils';
import {
  optimisticallyAppendReply,
  optimisticallyBumpReaction,
  optimisticallyToggleResolved,
  optimisticallyUpdateCommentBody,
} from './pr/optimisticUpdates';
import { ReactionChips } from './pr/Reactions';
import { CiChecksRow, CiSummary } from './pr/Ci';
import LogoLoading from './LogoLoading';
import { DescriptionCard } from './pr/DescriptionCard';
import { PrCommentBox, type PrCommentBoxHandle } from './pr/PrCommentBox';
import { ReviewActivityRow } from './pr/ReviewActivityRow';
import { ReviewerEditor } from './pr/ReviewerEditor';
import { ReviewThreadCard } from './pr/ReviewThreadCard';
import { groupTimelineEntries, type RawTimelineEntry, type TimelineEntry } from './pr/timelineGrouping';

const SIDE_WIDTH_KEY = 'settings:preview-conversation-width';
const SIDE_WIDTH_MIN = 180;
const SIDE_WIDTH_MAX = 520;
const SIDE_WIDTH_DEFAULT = 260;
const SIDEBAR_COLLAPSED_KEY = 'settings:pr-detail-sidebar-collapsed';

/** Renders a PR title string with backtick-wrapped segments turned into
 *  inline `<code>` spans. We don't run the title through a full markdown
 *  pass — github.com only honours inline code in titles, and pulling in
 *  marked here would also enable headings / lists / images, none of
 *  which make sense in a single-line title. Unbalanced trailing
 *  backticks fall through as literal text so a malformed title still
 *  renders. */
function renderTitleWithInlineCode(title: string): ReactNode[] {
  const parts = title.split('`');
  const out: ReactNode[] = [];
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1 && i < parts.length - 1) {
      out.push(<code key={i} className="prc-title__code">{parts[i]}</code>);
    } else if (i % 2 === 1) {
      // Trailing unmatched backtick — keep both the ` and the text after
      // it so users see exactly what was typed instead of a silent drop.
      out.push(<span key={i}>{'`' + parts[i]}</span>);
    } else {
      out.push(parts[i]);
    }
  }
  return out;
}

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
  /** Force-refresh the CI snapshot (status + per-check + viewerCanWrite)
   *  from GitHub. Wired through from the parent's refreshCi so the
   *  refresh button on the CI pill bypasses the focus poll's cadence. */
  onRefreshCi: () => void | Promise<void>;
  ciRefreshing: boolean;
};

/** Approval-count + "Rebase and merge" bar that sits above the comment
 *  box on the PR detail page. The button is intentionally always
 *  visible-but-greyed when the PR isn't ready — hover the disabled
 *  button to read why. Mirrors the merge-bar GitHub puts on its
 *  Conversation page. Clicking the button opens a confirm dialog —
 *  Yes fires the merge, No closes the dialog with no side effects. */
function MergeBar({ pr, detail, mergeState, mergeError, mergeQueuedMessage, onMerge, onRefreshCi, ciRefreshing }: MergeBarProps) {
  // Failing-check list is folded by default — the red "CI failing"
  // pill in the middle of the bar is the affordance to expand it.
  const [failuresOpen, setFailuresOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
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
  const enabled = !closed && ciPassing && detail.viewerCanWrite && mergeState !== 'running' && !hasConflict;
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
  // Heuristic merge-queue detection: every client-side signal says
  // this PR is ready to merge (CI green, ≥1 approval, no requested
  // changes), but GitHub still reports mergeable_state="blocked".
  // On repos without a merge queue, "blocked" almost always means a
  // required reviewer hasn't approved yet — so we gate on approvals
  // being present. False positives are possible (codeowners /
  // protected-files rules), but the case we want to catch right now
  // is Trino-style "queue is the required path". The button label
  // and strategy-picker visibility follow this heuristic; the *actual*
  // merge-vs-enqueue dispatch happens in the backend via a GraphQL
  // probe, so a wrong heuristic just shows the wrong button text — the
  // merge still routes correctly.
  const requiresMergeQueue = !closed
      && pr.mergeableState === 'blocked'
      && ciPassing
      && detail.changesRequestedCount === 0
      && approverLogins.length > 0;
  // ── Status-row content (mirrors github.com's merge card) ──────────────
  const totalChecks = detail.checkRuns.length;
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
        ? `${totalChecks} check${totalChecks === 1 ? '' : 's'} running`
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

  return (
    <div className={`merge-card${enabled ? ' merge-card--ready' : ' merge-card--blocked'}`}>
      <div className="merge-card__icon" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
          <path d="M5 3.25a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm0 9.5a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm8.25-6.25a.75.75 0 1 1 0-1.5.75.75 0 0 1 0 1.5Z" />
          <path fillRule="evenodd" d="M4.25 2.5a.75.75 0 0 0-.75.75v9.5a.75.75 0 0 0 1.5 0V8.122c.71.387 1.55.628 2.5.628 1.973 0 3.69-.69 4.84-1.677a.75.75 0 1 0-.98-1.14C10.43 6.71 9.083 7.25 7.5 7.25c-.992 0-1.85-.215-2.5-.553V3.25a.75.75 0 0 0-.75-.75Z" />
        </svg>
      </div>
      <div className="merge-card__rows">
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
                ? (requiresMergeQueue
                  ? 'Add this PR to the merge queue'
                  : `${MERGE_STRATEGY_LABEL[strategy]} this PR on GitHub`)
                : (disabledReason ?? 'Not ready to merge')}
            >
              {mergeState === 'running'
                ? (requiresMergeQueue ? 'Enqueuing…' : 'Merging…')
                : (requiresMergeQueue ? 'Add to merge queue' : MERGE_STRATEGY_LABEL[strategy])}
            </button>
            {!requiresMergeQueue && (
              <button
                type="button"
                className="merge-card__btn merge-card__btn--split-caret"
                onClick={() => setStrategyMenuOpen(v => !v)}
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
                <div className="merge-card__strategy-menu" role="menu">
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
                {MERGE_STRATEGY_LABEL[strategy]} <b>{pr.repo}#{pr.number}</b> on GitHub?
              </p>
              <p className="merge-confirm__sub">{pr.title}</p>
              <p className="merge-confirm__sub">{MERGE_STRATEGY_HINT[strategy]}</p>
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
                  // handleMerge surfaces errors via mergeError below.
                  onMerge(strategy);
                  setConfirmOpen(false);
                }}
                disabled={mergeState === 'running'}
              >
                {mergeState === 'running'
                  ? (requiresMergeQueue ? 'Enqueuing…' : 'Merging…')
                  : (requiresMergeQueue ? 'Yes, add to queue' : 'Yes, merge')}
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
};

/** Polling interval for the focus-driven CI snapshot refresh. ~12s is a
 *  reasonable middle ground: short enough to feel reactive when checks
 *  flip mid-review, long enough that we're not slamming GitHub for a
 *  user who just left the window open. */
const CI_POLL_INTERVAL_MS = 12_000;


type ActionState = 'idle' | 'confirming' | 'running' | 'done' | 'error';


function PullRequestPreview({ pr, onOpenReview, onInspectDiffs, onMarkHandled, onMerge, onBack, backLabel, onOpenLocalBranch, onOpenThread }: Props) {
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

  const [handledState, setHandledState] = useState<ActionState>('idle');
  const [handledError, setHandledError] = useState<string | null>(null);
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
  const [linkedTasks, setLinkedTasks] = useState<ThreadDto[]>([]);
  useEffect(() => {
    let cancelled = false;
    const repoLower = (repoContext?.repo ?? '').toLowerCase();
    if (repoLower === '') return;
    (async () => {
      try {
        const all = await window.bridge.listTasks();
        if (cancelled) return;
        const matched = all.filter(t => {
          if (t.linkedPrNumber !== pr.number) return false;
          // Repo match via path-segment scan (worktrees live at
          // `<repo>/.worktrees/<branch>`, so basename-only matching
          // misses them).
          const segs = (t.workingDir ?? '').split('/').filter(Boolean).map(s => s.toLowerCase());
          return segs.includes(repoLower);
        });
        setLinkedTasks(matched);
      }
      catch { /* non-fatal — no chip shown */ }
    })();
    return () => { cancelled = true; };
  }, [pr.number, repoContext?.repo]);

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
            {/* Linked-thread chips — visible only when an active thread
                ties itself to this PR via `linkedPrNumber`. Click
                jumps into the thread detail page so the user can
                travel back into the thread domain without manually
                searching. */}
            {linkedTasks.map(t => (
              <button
                key={t.id}
                type="button"
                onClick={() => onOpenThread?.(t.id)}
                className="pr-badge pr-badge--linked-thread"
                title={`Open thread: ${t.title}`}
                disabled={!onOpenThread}
              >
                ⌘ thread: {truncateThreadTitle(t.title)}
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
              <span>· updated {formatRelativeTime(pr.updatedAt)}</span>
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
                    onRefreshCi={refreshCi}
                    ciRefreshing={ciRefreshing}
                  />
                )}
                <PrCommentBox
                  pr={pr}
                  onClosed={() => { void handleClosed(); }}
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
              const visibleActivity = detail.recentActivity.filter(i => !isBotActor(i.actor));
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
                                    <span className="activity-item__time">{formatRelativeTime(item.timestamp)}</span>
                                  )}
                                </div>
                                {hasBody && (
                                  <EditableMarkdownBody
                                    body={item.body!}
                                    canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
                                    onSave={(b) => handleEditIssueComment(item.githubId!, b)}
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
                            onReply={(body) => handleReply(thread.rootGithubId, body)}
                            onReact={handleReact}
                            onSetResolved={handleSetThreadResolved}
                            currentUserLogin={currentUserLogin}
                            onEditMessage={handleEditReviewComment}
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
    let comments = 0, reviews = 0, commits = 0;
    for (const a of detail.recentActivity) {
      if (a.eventType === 'commented') comments++;
      else if (a.eventType === 'reviewed') reviews++;
      else if (a.eventType === 'committed') commits++;
    }
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
        {/* Row 1: large title with the #N link folded in at the end —
            matches docs/mockups/v2/detail/pr-header.png. The ✎ pencil
            in the mockup is intentionally omitted: there is no
            updatePrTitle bridge / backend endpoint yet, and a
            non-functional control would be worse than no control.
            Wire title editing in a follow-up commit alongside the
            backend handler. */}
        <div className="prc-header__title-row">
          <h1 className="prc-title">
            {renderTitleWithInlineCode(pr.title)}
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
                // Approximate count from the timeline's `committed`
                // events. Won't be exact on PRs whose timeline has
                // been truncated, but accurate enough for the header
                // copy ("1 commit" vs "12 commits"). When the count
                // can't be derived, fall back to the article-less
                // form so the sentence still parses.
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
            {pr.createdAt ? `opened ${formatRelativeTime(pr.createdAt)} · ` : ''}updated {formatRelativeTime(pr.updatedAt)}
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
                  onRefreshCi={refreshCi}
                  ciRefreshing={ciRefreshing}
                />
              )}
            </div>

            <PrCommentBox
              ref={commentBoxRef}
              pr={pr}
              onClosed={() => { void handleClosed(); }}
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
              </div>
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
    const stateBadge = item.eventType === 'reviewed' && item.state
      ? item.state.replace(/_/g, ' ').toLowerCase()
      : null;
    // Reviewed events live on the timeline rail as compact rows (like
    // committed/force-pushed) — see ReviewActivityRow. Comments still
    // render as full speech-bubble cards.
    if (item.eventType === 'reviewed') {
      const verb = item.state === 'APPROVED'
        ? 'approved'
        : item.state === 'CHANGES_REQUESTED'
          ? 'requested changes'
          : 'left a review';
      // No body + no inline threads = compact one-line row matching
      // docs/mockups/v2/codereview/approved.png. Avatar, colored check/×
      // marker, "<actor> approved these changes <time>". Reviews with
      // content keep the expandable card shape so the body is reachable.
      if (!hasBody && !hasThreads) {
        const variant = item.state === 'APPROVED'
          ? 'approved'
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
          <div key={`a-${key}`} className={`prc-approved-row prc-approved-row--${variant}`}>
            <Avatar login={item.actor} size={32} className="prc-approved-row__avatar" />
            <span className={`prc-approved-row__check prc-approved-row__check--${variant}`} aria-hidden>
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
                <> {' '}<span className="prc-approved-row__time">{formatRelativeTime(item.timestamp)}</span></>
              )}
            </span>
          </div>
        );
      }
      // State-specific marker glyph + color variant so a "requested
      // changes" review reads as a red ✕ badge rather than the same
      // neutral eye every other review event uses. Mirrors github.com
      // where each review verdict has its own obvious icon on the
      // timeline.
      const reviewMarker = item.state === 'APPROVED'
        ? '✓'
        : item.state === 'CHANGES_REQUESTED'
          ? '✕'
          : '💬';
      const reviewMarkerVariant = item.state === 'APPROVED'
        ? 'approved'
        : item.state === 'CHANGES_REQUESTED'
          ? 'changes-requested'
          : 'commented';
      return (
        <ReviewActivityRow
          key={`a-${key}`}
          actor={item.actor}
          verb={verb}
          state={item.state ?? null}
          timestamp={item.timestamp}
          isAuthor={pr.author === item.actor}
          authorAssociation={item.authorAssociation}
          marker={reviewMarker}
          markerVariant={reviewMarkerVariant}
          hasContent={hasBody || hasThreads}
        >
          {hasBody && (
            <EditableMarkdownBody
              body={item.body!}
              canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
              onSave={(b) => handleEditIssueComment(item.githubId!, b)}
              repoContext={repoContext}
            />
          )}
          {hasThreads && (
            <div className="prc-comment-threads">
              {attachedThreads!.map(thread => (
                <ReviewThreadCard
                  key={thread.rootGithubId}
                  thread={thread}
                  prAuthor={pr.author}
                  onReply={(body) => handleReply(thread.rootGithubId, body)}
                  onReact={handleReact}
                  onSetResolved={handleSetThreadResolved}
                  currentUserLogin={currentUserLogin}
                  onEditMessage={handleEditReviewComment}
                  repoContext={repoContext}
                />
              ))}
            </div>
          )}
        </ReviewActivityRow>
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
          {item.timestamp && <span className="prc-event-time">{formatRelativeTime(item.timestamp)}</span>}
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
            {item.timestamp && <span className="prc-event-time">{formatRelativeTime(item.timestamp)}</span>}
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
            <b>{item.actor}</b> {activityVerb(item.eventType)}
            {sha && <> · {commitLink(pr.repo, sha)}</>}
          </span>
          {item.timestamp && <span className="prc-event-time">{formatRelativeTime(item.timestamp)}</span>}
        </div>
      );
    }
    // Comment / review with body — full card. Avatar sits OUTSIDE the
    // bubble (left), the bordered body is the "speech bubble" the avatar
    // is saying.
    return (
      <article key={`a-${key}`} className="prc-comment-card">
        <Avatar login={item.actor} size={40} className="prc-comment-avatar" />
        <div className="prc-comment-card-body">
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
              <span className="prc-comment-time">{formatRelativeTime(item.timestamp)}</span>
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
            {hasBody && (
              <button
                type="button"
                className="prc-comment-quote"
                onClick={() => commentBoxRef.current?.insertQuote(item.body ?? '')}
                title="Quote this comment in your reply"
              >
                ↩ Quote reply
              </button>
            )}
          </header>
          {hasBody && (
            <EditableMarkdownBody
              body={item.body!}
              canEdit={!!(currentUserLogin && currentUserLogin === item.actor && item.githubId != null)}
              onSave={(b) => handleEditIssueComment(item.githubId!, b)}
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
                  onReply={(body) => handleReply(thread.rootGithubId, body)}
                  onReact={handleReact}
                  onSetResolved={handleSetThreadResolved}
                  currentUserLogin={currentUserLogin}
                  onEditMessage={handleEditReviewComment}
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
            <span className="prc-event-time">{formatRelativeTime(e.lastItem.timestamp)}</span>
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
          <span className="prc-event-time">{formatRelativeTime(e.lastItem.timestamp)}</span>
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
        onReply={(body) => handleReply(thread.rootGithubId, body)}
        onReact={handleReact}
        onSetResolved={handleSetThreadResolved}
        currentUserLogin={currentUserLogin}
        onEditMessage={handleEditReviewComment}
        repoContext={repoContext}
      />
    );
  }
}

export default PullRequestPreview;
