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
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { diffInlineCommentFromLocalPr, isPublishableReviewDraft } from '../diff/DiffInlineComments';
import { SubmitReviewDrawer, type ReviewVerdict } from '../pages/SubmitReviewDrawer';
import { usePR } from '../pr/usePR';
import { derivePRCapabilities } from '../pr/prCapabilities';
import type { AiReviewDraftDto, DiffFileDto, UserProfileDto } from '../types';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { getCached, setCached } from '../dataCache';
import { CommentBubbleIcon, PrMergedIcon, PrOpenIcon, RobotIcon } from './atoms';
import { buildHeader } from './detailModel';
import type { PullRow } from './model';
import PullChanges from './PullChanges';
import PullOverview from './PullOverview';
import '../css/pulls.css';

/**
 * The unified PR detail pane (header + Overview tab) from the redesign
 * prototype — the pane container, drag handle, and width state stay in
 * PullsScreen; this renders the content column. Mount with `key={row.id}`
 * so switching PRs resets the sub-tab and composer draft.
 */

const branchChipStyle = { fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 12, color: '#0969da', background: '#ddf4ff', borderRadius: 6, padding: '3px 10px' } as const;
const statePillStyle = { display: 'inline-flex', alignItems: 'center', gap: 6, color: '#fff', fontSize: 12.5, fontWeight: 600, borderRadius: 999, padding: '5px 13px' } as const;
const tabBtnStyle = { display: 'inline-flex', alignItems: 'center', gap: 7, padding: '6px 4px 10px', border: 0, background: 'transparent', fontSize: 13, cursor: 'pointer' } as const;

export type PullDetailActions = {
  /** Zooms this already-mounted detail pane without changing its PR or tab. */
  onToggleZoom?: () => void;
  /** Switches the top-right affordance from maximize to restore/close. */
  zoomed?: boolean;
  /** A changed token selects Overview without remounting the PR detail pane. */
  openOverviewToken?: number;
  /** A changed token selects Changes without remounting the PR detail pane. */
  openChangesToken?: number;
  /** Opens the agent-review column for an agent-assigned PR. */
  onWorkWithAgent?: () => void;
  /** Jumps to the repo's workspace PR surface. */
  onOpenInWorkspace?: () => void;
  /** Starts an agent review for a PR with no agent assigned yet. */
  onAssignAgent?: () => void;
  /** Runs the non-navigable, diff-only review available to unwatched repos. */
  onRunQuickReview?: () => void;
  /** Watches an external repo, then starts its workspace-bound full review. */
  onWatchRepoForFullReview?: () => void;
  /** State and persisted result for the non-navigable one-shot review. */
  quickReview?: {
    state: 'idle' | 'running' | 'done' | 'failed';
    result: AiReviewDraftDto | null;
    error: string | null;
  };
  /** State of cloning/syncing an unwatched repository for full review. */
  fullReviewPreparation?: {
    state: 'idle' | 'preparing' | 'failed';
    error: string | null;
  };
  /** True when the host resolved the repo and found no watched workspace. */
  noWorkspace?: boolean;
  /** Explicit user-owned remote close action. Omitted for local/terminal PRs. */
  onClosePullRequest?: () => Promise<void>;
};

export type PullDetailBodyProps = {
  /** Dashboard metadata used by the locked detail header and overview. */
  row: PullRow;
  /** Already-resolved PR data. Task pages pass their useLocalPr bundle here. */
  bundle: LocalPRBundle | null | undefined;
  /** Refreshes the supplied bundle after description/diff mutations. */
  refresh: () => void;
  /** User-gated PR-level comment mutation supplied by the host. */
  onComment?: (body: string) => Promise<void>;
  /** Task-owned PR panes supply their local cumulative diff before GitHub has
   *  assigned a PR number. Omit to load the remote PR diff normally. */
  changesFiles?: DiffFileDto[] | null;
  /** Optional task-worktree blob loader for expanding collapsed local diff context. */
  fetchChangesBlob?: (path: string) => Promise<{ lines: string[] }>;
  /** Gate/action content shown above the Changes toolbar. */
  changesBanner?: ReactNode;
  /** Notification-owned terminal action shown on the Overview tab. */
  overviewBanner?: ReactNode;
} & PullDetailActions;

function ReviewAction({ children, onClick, title }: {
  children: ReactNode;
  onClick?: () => void;
  title?: string;
}) {
  return (
    <button
      type="button"
      className="pl-review-action"
      onClick={onClick}
      disabled={onClick === undefined}
      title={title}
    >
      {children}
    </button>
  );
}

function AgentButtons({ det, actions }: { det: ReturnType<typeof buildHeader>; actions: PullDetailActions }) {
  const [reviewMenuOpen, setReviewMenuOpen] = useState(false);

  if (actions.noWorkspace === true) {
    const quick = actions.quickReview;
    const quickTitle = quick?.state === 'running'
      ? 'Quick review is running'
      : quick?.state === 'done'
        ? 'Quick review completed'
        : quick?.state === 'failed'
          ? 'Retry quick review'
          : undefined;
    const quickAction = quick?.state === 'running' || quick?.state === 'done'
      ? undefined
      : actions.onRunQuickReview;
    const preparation = actions.fullReviewPreparation;
    const watchLabel = preparation?.state === 'preparing'
      ? 'Preparing repo…'
      : preparation?.state === 'failed'
        ? 'Retry watching repo'
        : 'Watch repo · Full review';
    const watchAction = preparation?.state === 'preparing'
      ? undefined
      : actions.onWatchRepoForFullReview;
    return (
      <span style={{ position: 'relative', display: 'inline-flex', marginBottom: 4, flexShrink: 0 }}>
        <button
          type="button"
          className="pl-review-action"
          onClick={quickAction}
          disabled={quickAction === undefined}
          title={quickTitle}
          style={{ margin: 0, borderRadius: '8px 0 0 8px' }}
        >
          Run quick review
        </button>
        <button
          type="button"
          className="pl-review-action"
          aria-label="More review options"
          aria-haspopup="menu"
          aria-expanded={reviewMenuOpen}
          onClick={() => setReviewMenuOpen(open => !open)}
          style={{ margin: 0, marginLeft: -1, paddingInline: 7, borderRadius: '0 8px 8px 0' }}
        >
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
        </button>
        {reviewMenuOpen && (
          <span role="menu" style={{ position: 'absolute', zIndex: 20, top: 'calc(100% + 6px)', right: 0, minWidth: 190, padding: 4, border: '1px solid #d5dbe1', borderRadius: 8, background: '#fff', boxShadow: '0 8px 24px rgba(31,35,40,.16)' }}>
            <button
              type="button"
              role="menuitem"
              disabled={watchAction === undefined}
              onClick={() => { setReviewMenuOpen(false); watchAction?.(); }}
              style={{ width: '100%', padding: '7px 9px', border: 0, borderRadius: 6, background: 'transparent', color: '#17191c', font: 'inherit', fontSize: 12, textAlign: 'left', whiteSpace: 'nowrap', cursor: watchAction === undefined ? 'default' : 'pointer' }}
            >
              {watchLabel}
            </button>
          </span>
        )}
      </span>
    );
  }

  const label = det.agentState === 'running'
    ? 'Full review • running'
    : det.agentState === 'done'
      ? 'Full review • completed'
      : det.agentState === 'stale'
        ? 'Full review • update available'
        : 'Full review';
  const action = det.agentState === 'none' ? actions.onAssignAgent : actions.onWorkWithAgent;

  return (
    <>
      <ReviewAction onClick={action} title={label}>
        <RobotIcon size={14} />
        {label}
      </ReviewAction>
      {actions.onOpenInWorkspace !== undefined && (
        <button type="button" className="pl-hov-btn" onClick={actions.onOpenInWorkspace} aria-label="Open in workspace" title="Open in workspace" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.6" /><rect x="14" y="3" width="7" height="7" rx="1.6" /><rect x="3" y="14" width="7" height="7" rx="1.6" /><rect x="14" y="14" width="7" height="7" rx="1.6" /></svg>
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M7 17 17 7" /><path d="M9 7h8v8" /></svg>
        </button>
      )}
    </>
  );
}

function QuickReviewInline({
  quickReview,
  fullReviewPreparation,
}: Pick<PullDetailActions, 'quickReview' | 'fullReviewPreparation'>) {
  const quickError = quickReview?.state === 'failed' ? quickReview.error : null;
  const watchError = fullReviewPreparation?.state === 'failed' ? fullReviewPreparation.error : null;
  const result = quickReview?.state === 'done' ? quickReview.result : null;

  return (
    <>
      {(quickError !== null || watchError !== null) && (
        <div className="pl-review-inline-error" role="alert">
          {quickError ?? watchError}
        </div>
      )}
      {result !== null && (
        <section className="pl-quick-review" aria-label="Quick review result">
          <header className="pl-quick-review__header">
            <span>Quick review</span>
            <small>Diff only · no repository exploration</small>
          </header>
          {result.summary !== null && result.summary.trim() !== '' && (
            <p className="pl-quick-review__summary">{result.summary}</p>
          )}
          {result.comments.length === 0 ? (
            <p className="pl-quick-review__empty">No actionable findings in the supplied diff.</p>
          ) : (
            <div className="pl-quick-review__findings">
              {result.comments.map(finding => (
                <article key={finding.id} className="pl-quick-review__finding">
                  <div className="pl-quick-review__finding-meta">
                    <span>{finding.severity}</span>
                    <code>{finding.filePath}{finding.lineNumber > 0 ? `:${finding.lineNumber}` : ''}</code>
                  </div>
                  <p>{finding.editedBody ?? finding.body}</p>
                </article>
              ))}
            </div>
          )}
        </section>
      )}
    </>
  );
}

export function PullDetailBody({
  row, bundle, refresh, onComment, changesFiles, fetchChangesBlob, changesBanner, overviewBanner,
  openOverviewToken, openChangesToken, ...actions
}: PullDetailBodyProps) {
  const [subTab, setSubTab] = useState<'overview' | 'changes'>('overview');
  const [submitOpen, setSubmitOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reviewNotice, setReviewNotice] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  // The signed-in GitHub handle, so "You" comments render the real avatar
  // instead of a github.com/You.png placeholder.
  const [currentUser, setCurrentUser] = useState<UserProfileDto | null>(
    () => getCached<UserProfileDto>('home:profile') ?? null,
  );
  useEffect(() => {
    if (currentUser !== null || typeof window === 'undefined'
      || typeof window.bridge?.getUserProfile !== 'function') return;
    let cancelled = false;
    window.bridge.getUserProfile()
      .then(profile => { if (!cancelled) { setCached('home:profile', profile); setCurrentUser(profile); } })
      .catch(() => { /* avatar falls back to the display name */ });
    return () => { cancelled = true; };
  }, [currentUser]);
  const currentUserLogin = currentUser?.login ?? null;
  const det = buildHeader(row, bundle);
  const copyTitleLink = () => {
    const url = row.dto.htmlUrl;
    void navigator.clipboard.writeText(url ? `${det.title} [${det.numS}](${url})` : `${det.title} ${det.numS}`);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };
  const isOverview = subTab === 'overview';
  useEffect(() => {
    if (openChangesToken !== undefined) setSubTab('changes');
  }, [openChangesToken]);
  useEffect(() => {
    if (openOverviewToken !== undefined) setSubTab('overview');
  }, [openOverviewToken]);
  const pending = (bundle?.comments ?? []).filter(isPublishableReviewDraft);
  const detailCapabilities = bundle === null || bundle === undefined
    ? null
    : derivePRCapabilities(bundle.pr, 'details');
  const canPublish = detailCapabilities?.publishReview === true;
  const canActOnLocalThreads = detailCapabilities?.draftLocalComments === true;
  const removePending = (id: string) => {
    void window.bridge.deleteLocalPrComment(id).then(refresh).catch(() => { /* poll reconciles */ });
  };
  const submitReview = async (body: string, verdict: ReviewVerdict) => {
    if (bundle === null || bundle === undefined) return;
    setSubmitting(true);
    setReviewNotice(null);
    try {
      await window.bridge.publishLocalPrReview(bundle.pr.id, {
        verdict, findingIds: [], comments: pending.map(comment => comment.id),
        body: body.trim().length > 0 ? body : null,
      });
      setReviewNotice(verdict === 'APPROVE'
        ? 'Review approved on GitHub. The timeline may take a moment to update.'
        : verdict === 'REQUEST_CHANGES'
          ? 'Changes requested on GitHub. The timeline may take a moment to update.'
          : 'Review submitted to GitHub. The timeline may take a moment to update.');
      refresh();
    }
    finally { setSubmitting(false); }
  };
  const replyLocalComment = bundle === null || bundle === undefined || !canActOnLocalThreads ? undefined
    : async (root: LocalPRComment, body: string) => {
        await window.bridge.addLocalPrComment(bundle.pr.id, {
          scope: root.scope,
          filePath: root.filePath,
          lineNumber: root.lineNumber,
          side: root.side,
          startLine: root.startLine,
          startSide: root.startSide,
          body,
          parentCommentId: root.id,
        });
        if (root.findingId != null) {
          await window.bridge.answerAgentReviewFinding(root.findingId, body);
        }
        refresh();
      };
  const resolveLocalComment = bundle === null || bundle === undefined || !canActOnLocalThreads ? undefined
    : async (commentId: string) => {
        await window.bridge.resolveLocalPrComment(commentId);
        refresh();
      };
  const reopenLocalComment = bundle === null || bundle === undefined || !canActOnLocalThreads ? undefined
    : async (commentId: string) => {
        await window.bridge.reopenLocalPrComment(commentId);
        refresh();
      };
  const dismissLocalComment = bundle === null || bundle === undefined || !canActOnLocalThreads ? undefined
    : async (commentId: string) => {
        await window.bridge.dismissLocalPrComment(commentId);
        refresh();
      };
  const submitPendingReviewToDev = bundle?.pr.taskId == null
      || bundle.pr.origin !== 'task' || bundle.pr.status !== 'local-open' ? undefined
    : async (commentIds: string[]) => {
        await window.bridge.submitReview(bundle.pr.taskId!, { verdict: 'COMMENT', commentIds });
        refresh();
      };

  return (
    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ position: 'relative', flexShrink: 0, borderBottom: '1px solid #e7e9ec', background: '#fff' }}>
        {actions.onToggleZoom !== undefined && (
          <button
            type="button"
            className="pl-hov-ic"
            aria-label={actions.zoomed === true ? 'Close pull request details' : 'Maximize pull request details'}
            title={actions.zoomed === true ? 'Close pull request details' : 'Maximize pull request details'}
            onClick={actions.onToggleZoom}
            style={{ position: 'absolute', zIndex: 2, top: 12, right: 14, width: 28, height: 28, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', padding: 0, border: 0, borderRadius: 7, background: 'transparent', color: '#6e7781', cursor: 'pointer' }}
          >
            <PullDetailZoomIcon zoomed={actions.zoomed === true} />
          </button>
        )}
        <div style={{ maxWidth: 880, margin: '0 auto', padding: actions.onToggleZoom === undefined ? '18px 36px 0' : '18px 62px 0 36px' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
            <span style={{ fontSize: 21, fontWeight: 600, lineHeight: 1.3, letterSpacing: '-0.01em', color: '#17191c', minWidth: 0, flex: 1 }}>
              {det.title}{' '}
              <span style={{ fontWeight: 300, color: '#8b949e' }}>{det.numS}</span>
              <span
                className="pl-hov-ic"
                title="Copy title and link"
                onClick={copyTitleLink}
                style={{ width: 24, height: 24, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', verticalAlign: 'middle', cursor: 'pointer', borderRadius: 7, color: copied ? '#1a7f37' : '#8b949e', marginLeft: 4 }}
              >
                {copied
                  ? <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>
                  : <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>}
              </span>
              {copied && <span style={{ fontSize: 12, fontWeight: 500, color: '#1a7f37', marginLeft: 4, verticalAlign: 'middle' }}>Copied</span>}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', marginTop: 12 }}>
            <span style={{ ...statePillStyle, background: det.pill.bg }}>
              {det.pill.icon === 'merged' ? <PrMergedIcon size={13} strokeWidth={2.2} /> : <PrOpenIcon size={13} strokeWidth={2.2} />}
              {det.pill.label}
            </span>
            {det.base !== null && det.branch !== null && (
              <>
                <span style={branchChipStyle}>{det.base}</span>
                <span style={{ color: '#8b949e' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5" /><path d="m12 19-7-7 7-7" /></svg>
                </span>
                <span style={{ ...branchChipStyle, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 420 }}>{det.branch}</span>
              </>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 6 }}>
            <button onClick={() => setSubTab('overview')} style={{ ...tabBtnStyle, borderBottom: `2px solid ${isOverview ? '#c2632a' : 'transparent'}`, fontWeight: isOverview ? 600 : 500, color: isOverview ? '#17191c' : '#6e7781' }}>
              <CommentBubbleIcon size={14} />
              Overview
              <span style={{ fontSize: 10.5, fontWeight: 700, background: isOverview ? 'rgba(194,99,42,0.12)' : '#eceef0', color: isOverview ? '#c2632a' : '#59636e', borderRadius: 999, padding: '1px 7px' }}>{det.ovCount}</span>
            </button>
            <button onClick={() => setSubTab('changes')} style={{ ...tabBtnStyle, borderBottom: `2px solid ${!isOverview ? '#c2632a' : 'transparent'}`, fontWeight: !isOverview ? 600 : 500, color: !isOverview ? '#17191c' : '#6e7781' }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3" /><path d="M16 3h3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3" /><path d="M12 8v8" /><path d="M8 12h8" /></svg>
              Changes
              <span style={{ fontSize: 10.5, fontWeight: 700, background: '#dafbe1', color: '#1a7f37', borderRadius: 999, padding: '1px 7px' }}>{det.addP}</span>
              <span style={{ fontSize: 10.5, fontWeight: 700, background: '#ffebe9', color: '#cf222e', borderRadius: 999, padding: '1px 7px' }}>{det.delP}</span>
            </button>
            <span style={{ flex: 1 }} />
            <AgentButtons det={det} actions={actions} />
            {canPublish && (
              <button
                type="button"
                className="pl-hov-green"
                onClick={!submitting ? () => setSubmitOpen(true) : undefined}
                disabled={submitting}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '5px 13px', marginBottom: 4, border: '1px solid #1a7f37', background: '#1f883d', borderRadius: 7, fontSize: 12.5, fontWeight: 400, color: '#fff', cursor: submitting ? 'default' : 'pointer', flexShrink: 0 }}
              >
                {submitting ? 'Submitting…' : `Submit review${pending.length > 0 ? ` • ${pending.length}` : ''}`}
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
              </button>
            )}
          </div>
        </div>
      </div>

      {reviewNotice !== null && (
        <div role="status" aria-live="polite" style={reviewNoticeStyle}>
          <span aria-hidden="true">✓</span> {reviewNotice}
        </div>
      )}

      {isOverview ? (
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          <div style={{ maxWidth: 880, margin: '0 auto', padding: '20px 36px 60px' }}>
            <QuickReviewInline
              quickReview={actions.quickReview}
              fullReviewPreparation={actions.fullReviewPreparation}
            />
            {overviewBanner}
            <PullOverview
              row={row}
              bundle={bundle}
              isMerged={det.isMerged}
              onComment={onComment}
              onClosePullRequest={actions.onClosePullRequest}
              onDescriptionSaved={refresh}
              onLocalReply={replyLocalComment}
              onLocalResolve={resolveLocalComment}
              onLocalReopen={reopenLocalComment}
              onLocalDismiss={dismissLocalComment}
              currentUserLogin={currentUserLogin}
              onSubmitLocalReview={submitPendingReviewToDev}
            />
          </div>
        </div>
      ) : (
        <PullChanges
          row={row}
          bundle={bundle}
          refresh={refresh}
          onComment={onComment}
          filesOverride={changesFiles}
          fetchBlobOverride={fetchChangesBlob}
          banner={changesBanner}
        />
      )}
      <SubmitReviewDrawer
        open={submitOpen}
        submitting={submitting}
        pendingComments={pending.map(comment => diffInlineCommentFromLocalPr(comment))}
        onRemovePending={removePending}
        onClose={() => setSubmitOpen(false)}
        onSubmit={async (body, verdict) => {
          await submitReview(body, verdict);
          setSubmitOpen(false);
        }}
      />
    </div>
  );
}

function PullDetailZoomIcon({ zoomed }: { zoomed: boolean }) {
  return zoomed ? (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M9 3v4a2 2 0 0 1-2 2H3M15 3v4a2 2 0 0 0 2 2h4M9 21v-4a2 2 0 0 0-2-2H3M15 21v-4a2 2 0 0 1 2-2h4" />
    </svg>
  ) : (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3" />
    </svg>
  );
}

const reviewNoticeStyle = {
  margin: '12px 36px 0',
  padding: '9px 12px',
  border: '1px solid #aceebb',
  borderRadius: 8,
  background: '#dafbe1',
  color: '#116329',
  fontSize: 12.5,
  fontWeight: 500,
} as const;

export default function PullDetailPane({ row, ...actions }: { row: PullRow } & PullDetailActions) {
  const { bundle, refresh } = usePR(row.dto.id);

  // The same bridge decision PRView's hosts make (useExternalPrActions.
  // submitLocalComment): remote-capable PRs post straight to GitHub,
  // otherwise the comment is drafted locally.
  const onComment = bundle === null || bundle === undefined ? undefined : async (body: string) => {
    if (derivePRCapabilities(bundle.pr, 'details').postRemoteComment) {
      await window.bridge.postRemotePrComment(bundle.pr.id, body);
    }
    else {
      await window.bridge.addLocalPrComment(bundle.pr.id, { scope: 'pr', body });
    }
    refresh();
  };

  return (
    <PullDetailBody
      row={row}
      bundle={bundle}
      refresh={refresh}
      onComment={onComment}
      {...actions}
    />
  );
}
