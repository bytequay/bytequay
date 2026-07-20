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
import { diffInlineCommentFromLocalPr, isPendingLocalComment } from '../diff/DiffInlineComments';
import { SubmitReviewDrawer, type ReviewVerdict } from '../pages/SubmitReviewDrawer';
import { usePR } from '../pr/usePR';
import { derivePRCapabilities } from '../pr/prCapabilities';
import type { AiReviewDraftDto } from '../types';
import type { LocalPRBundle } from '../types/localPr';
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
  if (actions.noWorkspace === true) {
    const quick = actions.quickReview;
    const quickLabel = quick?.state === 'running'
      ? 'Quick review • running'
      : quick?.state === 'done'
        ? 'Quick review ✓'
        : quick?.state === 'failed'
          ? 'Retry quick review'
          : 'Run quick review';
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
      <>
        <ReviewAction onClick={quickAction}>{quickLabel}</ReviewAction>
        <ReviewAction onClick={watchAction}>{watchLabel}</ReviewAction>
      </>
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
        <button className="pl-hov-btn" onClick={actions.onOpenInWorkspace} title="Open related workspace" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
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

export function PullDetailBody({ row, bundle, refresh, onComment, openChangesToken, ...actions }: PullDetailBodyProps) {
  const [subTab, setSubTab] = useState<'overview' | 'changes'>('overview');
  const [submitOpen, setSubmitOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reviewNotice, setReviewNotice] = useState<string | null>(null);
  const det = buildHeader(row, bundle);
  const isOverview = subTab === 'overview';
  useEffect(() => {
    if (openChangesToken !== undefined) setSubTab('changes');
  }, [openChangesToken]);
  const pending = (bundle?.comments ?? []).filter(isPendingLocalComment);
  const canPublish = bundle !== null && bundle !== undefined
    && derivePRCapabilities(bundle.pr, 'details').publishReview;
  const removePending = (id: string) => {
    void window.bridge.deleteLocalPrComment(id).then(refresh).catch(() => { /* poll reconciles */ });
  };
  const submitReview = async (body: string, verdict: ReviewVerdict) => {
    if (bundle === null || bundle === undefined) return;
    setSubmitting(true);
    setReviewNotice(null);
    try {
      await window.bridge.publishLocalPrReview(bundle.pr.id, {
        verdict, findingIds: [], comments: [], body: body.trim().length > 0 ? body : null,
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

  return (
    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ flexShrink: 0, borderBottom: '1px solid #e7e9ec', background: '#fff' }}>
        <div style={{ maxWidth: 880, margin: '0 auto', padding: '18px 36px 0' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
            <span style={{ fontSize: 21, fontWeight: 600, lineHeight: 1.3, letterSpacing: '-0.01em', color: '#17191c', minWidth: 0, flex: 1 }}>
              {det.title}{' '}
              <span style={{ fontWeight: 300, color: '#8b949e' }}>{det.numS}</span>
            </span>
            <span
              className="pl-hov-ic"
              title="Copy title"
              onClick={() => { void navigator.clipboard.writeText(`${det.title} #${row.num}`); }}
              style={{ width: 28, height: 28, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 7, color: '#8b949e', flexShrink: 0, marginTop: 2 }}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', marginTop: 12 }}>
            {det.isMerged
              ? <span style={{ ...statePillStyle, background: '#8250df' }}><PrMergedIcon size={13} strokeWidth={2.2} />Merged</span>
              : <span style={{ ...statePillStyle, background: '#1f883d' }}><PrOpenIcon size={13} strokeWidth={2.2} />Open</span>}
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
                {submitting ? 'Submitting…' : `Submit review • ${pending.length}`}
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
            <PullOverview
              row={row}
              bundle={bundle}
              isMerged={det.isMerged}
              onComment={onComment}
              onDescriptionSaved={refresh}
            />
          </div>
        </div>
      ) : (
        <PullChanges row={row} bundle={bundle} refresh={refresh} onComment={onComment} />
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
