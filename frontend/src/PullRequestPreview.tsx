import { useEffect, useRef, useState, type ReactNode } from 'react';
import { marked } from 'marked';
import type { ActivityItemDto, CheckRunDto, PullRequestDetailDto, PullRequestDto, ReviewThreadDto } from './types';
import { getCached, putCache } from './detailCache';
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
  optimisticallyBumpReaction,
  optimisticallyToggleResolved,
} from './pr/optimisticUpdates';
import { ReactionChips } from './pr/Reactions';
import { CiChecksRow, CiSummary } from './pr/Ci';
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
                    dangerouslySetInnerHTML={{ __html: marked.parse(aiState.text, { async: false }) as string }}
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

type MergeBarProps = {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  mergeState: 'idle' | 'running' | 'error';
  mergeError: string | null;
  onMerge: () => void;
};

/** Approval-count + "Rebase and merge" bar that sits above the comment
 *  box on the PR detail page. The button is intentionally always
 *  visible-but-greyed when the PR isn't ready — hover the disabled
 *  button to read why. Mirrors the merge-bar GitHub puts on its
 *  Conversation page. Clicking the button opens a confirm dialog —
 *  Yes fires the merge, No closes the dialog with no side effects. */
function MergeBar({ pr, detail, mergeState, mergeError, onMerge }: MergeBarProps) {
  // Failing-check list is folded by default — the red "CI failing"
  // pill in the middle of the bar is the affordance to expand it.
  const [failuresOpen, setFailuresOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const failingChecks = detail.checkRuns.filter(c => isCheckFailing(c.conclusion));
  const ciPassing = detail.ciStatus === 'PASSING' || detail.ciStatus === 'NONE';
  const ciPending = detail.ciStatus === 'PENDING';
  const closed = pr.state === 'closed';
  const enabled = !closed && ciPassing && detail.viewerCanWrite && mergeState !== 'running';
  // CI-failing is intentionally NOT in the disabled-reason text anymore —
  // the red "CI failing" pill in the middle of the bar carries that
  // signal, and expanding it shows per-check details. Keeping both would
  // double up on the same information.
  let disabledReason: string | null = null;
  if (closed) {
    disabledReason = 'This PR is closed.';
  } else if (!detail.viewerCanWrite) {
    disabledReason = 'You don’t have write access to this repository.';
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
  return (
    <div className={`merge-bar${enabled ? ' merge-bar--ready' : ' merge-bar--blocked'}`}>
      <div className="merge-bar__row">
        <button
          type="button"
          className="merge-bar__btn"
          onClick={() => setConfirmOpen(true)}
          disabled={!enabled}
          title={enabled
            ? 'Rebase and merge this PR on GitHub'
            : (disabledReason ?? 'Not ready to merge')}
        >
          {mergeState === 'running' ? 'Merging…' : 'Rebase and merge'}
        </button>
        {failingChecks.length > 0 ? (
          <button
            type="button"
            className={`merge-bar__ci-fail${failuresOpen ? ' merge-bar__ci-fail--open' : ''}`}
            onClick={() => setFailuresOpen(v => !v)}
            aria-expanded={failuresOpen}
            title={failuresOpen
              ? 'Hide failing-check details'
              : `Show why ${failingChecks.length} check${failingChecks.length === 1 ? '' : 's'} failed`}
          >
            <span aria-hidden="true">✗</span>
            <span>CI failing{failingChecks.length > 1 ? ` (${failingChecks.length})` : ''}</span>
            <span className="merge-bar__ci-fail-chevron" aria-hidden="true">{failuresOpen ? '▾' : '▸'}</span>
          </button>
        ) : detail.ciStatus === 'PASSING' ? (
          <span
            className="merge-bar__ci-pass"
            title={`All ${detail.checkRuns.length} check${detail.checkRuns.length === 1 ? '' : 's'} passing`}
          >
            <span aria-hidden="true">✓</span>
            <span>CI passed</span>
          </span>
        ) : null}
        <div className="merge-bar__summary">
          <div className="merge-bar__approvals">
            {approverLogins.length === 0 ? (
              <span className="merge-bar__approvals-empty">No approvals yet</span>
            ) : (
              <span className="merge-bar__approver-strip" title={`Approved by ${approverLogins.join(', ')}`}>
                {approverLogins.map((login) => (
                  <a
                    key={login}
                    href={`https://github.com/${login}`}
                    target="_blank"
                    rel="noreferrer"
                    className="merge-bar__approver"
                    title={`${login} approved`}
                  >
                    <Avatar login={login} size={20} />
                  </a>
                ))}
                <span className="merge-bar__approvals-label">
                  approved
                </span>
              </span>
            )}
            {changesSummary && (
              <span className="merge-bar__changes">· {changesSummary}</span>
            )}
          </div>
          {disabledReason && <span className="merge-bar__reason">{disabledReason}</span>}
          {mergeError && <span className="merge-bar__error" title={mergeError}>{mergeError}</span>}
        </div>
      </div>
      {failuresOpen && failingChecks.length > 0 && (
        <ul className="merge-bar__failures">
          {failingChecks.map((c, i) => (
            <FailingCheckCard key={`${c.name ?? 'unnamed'}-${i}`} check={c} repo={pr.repo} />
          ))}
        </ul>
      )}
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
                Rebase and merge <b>{pr.repo}#{pr.number}</b> on GitHub?
              </p>
              <p className="merge-confirm__sub">{pr.title}</p>
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
                  onMerge();
                  setConfirmOpen(false);
                }}
                disabled={mergeState === 'running'}
              >
                {mergeState === 'running' ? 'Merging…' : 'Yes, merge'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
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
  onMerge?: (prId: number, repo: string, number: number) => Promise<unknown>;
  // Optional "← Back" affordance shown in the page header. When provided
  // (e.g. from the team detail page) the button returns the user to the
  // referring screen. Inbox usage doesn't pass this — the sidebar list
  // already serves as navigation, so a redundant Back button would clutter.
  onBack?: () => void;
  // Label for the back button — defaults to "Back" when omitted. Callers
  // can pass "Team" / "Repo" / etc. so the breadcrumb names the origin.
  backLabel?: string;
};

/** Polling interval for the focus-driven CI snapshot refresh. ~12s is a
 *  reasonable middle ground: short enough to feel reactive when checks
 *  flip mid-review, long enough that we're not slamming GitHub for a
 *  user who just left the window open. */
const CI_POLL_INTERVAL_MS = 12_000;


type ActionState = 'idle' | 'confirming' | 'running' | 'done' | 'error';


function PullRequestPreview({ pr, onOpenReview, onInspectDiffs, onMarkHandled, onMerge, onBack, backLabel }: Props) {
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [handledState, setHandledState] = useState<ActionState>('idle');
  const [handledError, setHandledError] = useState<string | null>(null);
  const [sideWidth, setSideWidth] = useState<number>(loadSideWidth);
  const gridRef = useRef<HTMLDivElement>(null);
  const commentBoxRef = useRef<PrCommentBoxHandle>(null);

  // Merge bar state. `confirming` is the two-click safety net (one click
  // arms, second click actually merges) so a stray pointer doesn't ship
  // an unintended PR. `error` carries GitHub's reason if the call fails.
  const [mergeState, setMergeState] = useState<'idle' | 'running' | 'error'>('idle');
  const [mergeError, setMergeError] = useState<string | null>(null);
  // Manual-refresh / focus-poll spinner state for the CI summary.
  const [ciRefreshing, setCiRefreshing] = useState(false);

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
      // Re-fetch detail so the GraphQL flag is the canonical value
      // (and so any in-flight reaction tally also lands).
      const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
      putCache(pr.id, fresh);
      setDetail(fresh);
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
      const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
      putCache(pr.id, fresh);
      setDetail(fresh);
    } catch (e) {
      setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content, -1));
      console.warn('addIssueCommentReaction failed', e);
    }
  };

  /** Add an emoji reaction to a review-thread message. Optimistically
   *  bumps the local count so the chip appears immediately, then
   *  refreshes the PR detail in the background to pick up GitHub's
   *  authoritative tally. */
  const handleReact = async (commentGithubId: number, content: ReactionContent): Promise<void> => {
    setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content));
    try {
      await window.bridge.addReviewCommentReaction(pr.repo, commentGithubId, content);
      const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
      putCache(pr.id, fresh);
      setDetail(fresh);
    } catch (e) {
      // Rollback the optimistic bump on failure.
      setDetail(prev => optimisticallyBumpReaction(prev, commentGithubId, content, -1));
      console.warn('addReviewCommentReaction failed', e);
    }
  };

  useEffect(() => {
    setHandledState('idle');
    setHandledError(null);
    setError(null);
    setDetail(null); // clear immediately so old PR's detail never shows during load

    const cached = getCached(pr.id);
    if (cached) {
      setDetail(cached.data);
      setLoading(false);
      if (cached.stale) {
        setRefreshing(true);
        window.bridge
          .fetchPullRequestDetail(pr.repo, pr.number)
          .then((d) => { putCache(pr.id, d); setDetail(d); })
          .catch(() => { /* silently keep stale data */ })
          .finally(() => setRefreshing(false));
      }
      return;
    }

    setLoading(true);
    window.bridge
      .fetchPullRequestDetail(pr.repo, pr.number)
      .then((d) => {
        putCache(pr.id, d);
        setDetail(d);
        setLoading(false);
      })
      .catch((e: unknown) => {
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      });
  }, [pr.id]);

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

  const handleMerge = async () => {
    if (!onMerge || mergeState === 'running') return;
    setMergeState('running');
    setMergeError(null);
    try {
      await onMerge(pr.id, pr.repo, pr.number);
      // Drop the cached detail and refetch so the merged status, timeline
      // event, and disabled merge button all update in one pass.
      const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
      putCache(pr.id, fresh);
      setDetail(fresh);
      setMergeState('idle');
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

  const handleOpenEmbeddedReview = () => {
    // Opening counts as viewing, even if the user exits without acting.
    void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
    onOpenReview?.();
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

        {loading && <div className="preview__loading">Loading details…</div>}
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
                  onSaved={(newBody) => setDetail({ ...detail, body: newBody })}
                />
              </section>

              <div className="preview__main-pinned">
                {onMerge && !pr.mergedAt && (
                  <MergeBar
                    pr={pr}
                    detail={detail}
                    mergeState={mergeState}
                    mergeError={mergeError}
                    onMerge={() => { void handleMerge(); }}
                  />
                )}
                <PrCommentBox
                  pr={pr}
                  onClosed={() => { void onMarkHandled?.(pr.id).catch(() => { /* best-effort */ }); }}
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
                                  <div
                                    className="activity-item__body"
                                    dangerouslySetInnerHTML={{ __html: marked.parse(item.body ?? '', { async: false }) as string }}
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
                            onReply={async (body) => {
                              await window.bridge.replyToReviewThread(pr.repo, pr.number, thread.rootGithubId, body);
                              const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
                              putCache(pr.id, fresh);
                              setDetail(fresh);
                            }}
                            onReact={handleReact}
                            onSetResolved={handleSetThreadResolved}
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
        {onBack && (
          <button
            type="button"
            className="prc-back-link"
            onClick={onBack}
            title={`Back to ${backLabel ?? 'previous page'}`}
          >
            ← {backLabel ?? 'Back'}
          </button>
        )}
        <button
          type="button"
          className="prc-back"
          onClick={() => onMarkHandled?.(pr.id).catch(() => { /* best-effort */ })}
          title="Mark this PR handled"
        >
          ✓ Mark handled
        </button>
        <h1 className="prc-title">{pr.title}</h1>
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
          <a
            href={pr.htmlUrl}
            target="_blank"
            rel="noreferrer"
            className="prc-meta-link"
          >
            #{pr.number}
          </a>
          {/* Status pill: Merged (purple) > Closed (red) > Draft
              (purple-grey, only when not merged/closed) > Open (green).
              Reads pr.mergedAt / pr.state from the v26 list-DTO fields
              so the pill flips as soon as the next sync lands; falls
              back to "Open" when those fields aren't populated yet. */}
          {(() => {
            if (pr.mergedAt) {
              return <span className="prc-status-pill prc-status-pill--merged">Merged</span>;
            }
            if (pr.state === 'closed') {
              return <span className="prc-status-pill prc-status-pill--closed">Closed</span>;
            }
            if (detail?.draft ?? pr.draft) {
              return <span className="prc-status-pill prc-status-pill--draft">Draft</span>;
            }
            return <span className="prc-status-pill">Open</span>;
          })()}
          <span className="prc-meta-sep">·</span>
          {pr.author && (
            <>
              <span>by <b>{pr.author}</b></span>
              <span className="prc-meta-sep">·</span>
            </>
          )}
          <span className="prc-meta-time">
            {pr.createdAt ? `opened ${formatRelativeTime(pr.createdAt)} · ` : ''}updated {formatRelativeTime(pr.updatedAt)}
          </span>
        </div>
        <div className="prc-actions">
          {StyleToggle}
          {handledState !== 'done' && (
            <button
              type="button"
              className="button button--secondary"
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
          <button
            type="button"
            className="button button--remote"
            onClick={handleOpenEmbeddedReview}
            title="Open the embedded github.com window for this PR."
          >
            Open on Remote
          </button>
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

      {loading && <div className="prc-loading">Loading details…</div>}
      {refreshing && <div className="prc-refreshing">Refreshing…</div>}
      {error && <div className="prc-error">{error}</div>}

      {detail && (
        <div className="prc-body" ref={gridRef}>
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
                onSaved={(newBody) => setDetail({ ...detail, body: newBody })}
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
            </div>

            {onMerge && !pr.mergedAt && (
              <MergeBar
                pr={pr}
                detail={detail}
                mergeState={mergeState}
                mergeError={mergeError}
                onMerge={() => { void handleMerge(); }}
              />
            )}
            <PrCommentBox
              ref={commentBoxRef}
              pr={pr}
              onClosed={() => { void onMarkHandled?.(pr.id).catch(() => { /* best-effort */ }); }}
            />
          </main>

          {/* ── Right meta sidebar ───────────────────────────────── */}
          <aside className="prc-sidebar">
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

            <section className="prc-meta-section">
              <div className="prc-meta-label">Reviewers</div>
              <ReviewerEditor
                pr={pr}
                reviewerVerdicts={reviewerVerdicts}
                onRefresh={async () => {
                  const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
                  putCache(pr.id, fresh);
                  setDetail(fresh);
                }}
              />
            </section>

            <section className="prc-meta-section">
              <div className="prc-meta-label">Status</div>
              {detail.ciStatus && detail.ciStatus !== 'NONE' && (
                <CiChecksRow ciStatus={detail.ciStatus} checkRuns={detail.checkRuns} />
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
              {(detail.ciStatus === 'NONE' || !detail.ciStatus) && detail.approvalCount === 0 && detail.changesRequestedCount === 0 && (
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
      return (
        <ReviewActivityRow
          key={`a-${key}`}
          actor={item.actor}
          verb={verb}
          state={item.state ?? null}
          timestamp={item.timestamp}
          isAuthor={pr.author === item.actor}
          authorAssociation={item.authorAssociation}
          marker={eventMarker(item.eventType)}
          hasContent={hasBody || hasThreads}
        >
          {hasBody && (
            <div
              className="prc-comment-body"
              dangerouslySetInnerHTML={{ __html: marked.parse(item.body ?? '', { async: false }) as string }}
            />
          )}
          {hasThreads && (
            <div className="prc-comment-threads">
              {attachedThreads!.map(thread => (
                <ReviewThreadCard
                  key={thread.rootGithubId}
                  thread={thread}
                  prAuthor={pr.author}
                  onReply={async (body) => {
                    await window.bridge.replyToReviewThread(pr.repo, pr.number, thread.rootGithubId, body);
                    const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
                    putCache(pr.id, fresh);
                    setDetail(fresh);
                  }}
                  onReact={handleReact}
                  onSetResolved={handleSetThreadResolved}
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
          <span className="prc-event-marker" aria-hidden>{eventMarker(item.eventType)}</span>
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
            <span className="prc-event-marker" aria-hidden>{eventMarker(item.eventType)}</span>
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
          <span className="prc-event-marker" aria-hidden>{eventMarker(item.eventType)}</span>
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
            <div
              className="prc-comment-body"
              dangerouslySetInnerHTML={{ __html: marked.parse(item.body ?? '', { async: false }) as string }}
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
                  onReply={async (body) => {
                    await window.bridge.replyToReviewThread(pr.repo, pr.number, thread.rootGithubId, body);
                    const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
                    putCache(pr.id, fresh);
                    setDetail(fresh);
                  }}
                  onReact={handleReact}
                  onSetResolved={handleSetThreadResolved}
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
          <span className="prc-event-marker" aria-hidden>{eventMarker(e.eventType)}</span>
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
        <span className="prc-event-marker" aria-hidden>{eventMarker(e.eventType)}</span>
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
        onReply={async (body) => {
          await window.bridge.replyToReviewThread(pr.repo, pr.number, thread.rootGithubId, body);
          // Refresh the detail so the new reply shows up in the thread.
          const fresh = await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
          putCache(pr.id, fresh);
          setDetail(fresh);
        }}
        onReact={handleReact}
        onSetResolved={handleSetThreadResolved}
      />
    );
  }
}

export default PullRequestPreview;
