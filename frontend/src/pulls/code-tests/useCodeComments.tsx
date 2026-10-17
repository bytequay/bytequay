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
import { findingInResults } from '../agent-review/model';
import { useRef, useState, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import { useReviewDraft } from '../ReviewDrafts';
import type { InlineReviewCommentDto, PullRequestDetailDto, UserProfileDto } from '../../types';
import type { AgentReviewDto, ReviewFindingDto } from '../agent-review/contracts';
import ReviewFindingCard, { type FindingActions } from '../agent-review/ReviewFindingCard';
import { useFindingActions } from '../agent-review/useFindingActions';
import {
  PendingReviewCard,
  PLACEHOLDER_REVIEW,
  ReviewLineComposer,
  submitInlineReviewComment,
  updatePendingReviewComment,
  type DiffAgentComment,
  type ReviewThreads,
} from '../PullChanges';
import { remoteErrorMessage, ReviewThread } from '../PullOverview';
import { inlineReviewComment, NO_RANGE_SELECTION, rangeHeading, rangeSelectionReducer } from '../lineRangeSelection';
import LocalReviewThreadCard, { localReviewMatchesRevision } from '../local-review/LocalReviewThreadCard';
import type { LocalReviewThreads } from '../local-review/types';
import { findingOnLines, githubCommentBlocker } from './codeModel';

export type LineCard = { key: string; line: number };
export type CommentGesture =
  | { type: 'start'; line: number; extend: boolean }
  | { type: 'drag'; line: number }
  | { type: 'finish' }
  | { type: 'cancel' };

/** What the pull-request screen shares with Tears of time so comments land in the same review as on Changes. */
export type CodeCommentsOptions = {
  repo: string;
  number: number;
  workspaceId?: string;
  canReview: boolean;
  viewer: UserProfileDto | null;
  pending: InlineReviewCommentDto[];
  onPendingChange: (comments: InlineReviewCommentDto[]) => void;
  threads: ReviewThreads;
  onDetailChange: (detail: PullRequestDetailDto) => void;
  localReview: LocalReviewThreads;
  /** Present only while the Agent review is open. */
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  /** Shows a finding in Summary, as its card's link does on Changes. */
  onOpenFindingInReview?: (findingId: string) => void;
};

/** The Agent review whose findings the file shows, with how a finding action reports the updated review. */
export type CodeFindings = {
  review: AgentReviewDto;
  onReviewChange: (review: AgentReviewDto) => void;
};

export type CommentContext = {
  path: string;
  /** The text on screen, draft or saved. */
  text: string;
  prPatch: string | null;
  draft: boolean;
  uncommitted: boolean;
  /** The displayed file's committed revision, independent of the last agent review. */
  sourceHead: string | null;
  /** The pull request head, where GitHub places threads and publishes comments. */
  observedHead: string | null;
  /** Why the agent cannot take a question now (a run or recovery holds the working copy), or null. */
  askBlocked: string | null;
};

type FindingCardProps = {
  review: AgentReviewDto;
  finding: ReviewFindingDto;
  viewer: UserProfileDto | null;
  actions: FindingActions;
  busy: boolean;
  error: string | null;
};

function FindingCard({ review, finding, viewer, actions, busy, error }: FindingCardProps) {
  return (
    <>
      <ReviewFindingCard review={review} finding={finding} viewerLogin={viewer?.login ?? ''} viewerAvatarUrl={viewer?.avatarUrl ?? null}
        projection="changes" actions={actions} busy={busy} />
      {error !== null && <div className="agr-error" role="alert">{error}</div>}
    </>
  );
}

/**
 * Review comments in the whole-file view, sharing Changes' composer, pending list and discussion cards. A GitHub
 * comment anchors only to the current pull request's diff lines; Send to agent takes any line.
 */
export function useCodeComments(options: CodeCommentsOptions | undefined, context: CommentContext | null, findings?: CodeFindings, findingOverride?: ReturnType<typeof useFindingActions>) {
  const [range, setRange] = useReviewDraft('codeComment:range', NO_RANGE_SELECTION);
  const dispatchRange = (action: Parameters<typeof rangeSelectionReducer>[1]) => setRange(current => rangeSelectionReducer(current, action));
  const [rangePath, setRangePath] = useReviewDraft<string | null>('codeComment:path', null);
  const [body, setBody] = useReviewDraft('codeComment:body', '');
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [posting, setPosting] = useState(false);
  const [sending, setSending] = useState(false);
  const [pendingEdit, setPendingEdit] = useReviewDraft<{ comment: InlineReviewCommentDto; body: string } | null>('codeComment:pendingEdit', null);
  const editing = (item: InlineReviewCommentDto) => pendingEdit !== null && ['path', 'side', 'line', 'startLine', 'commitId', 'body'].every(key => pendingEdit.comment[key as keyof InlineReviewCommentDto] === item[key as keyof InlineReviewCommentDto]);
  // Made on the first Post now and kept as long as the box's text, so a retry is never a second comment.
  const [commentKey, setCommentKey] = useReviewDraft<string | null>('codeComment:key', null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  // One action state for every finding card, as on Changes, so two cards cannot race the same review draft version.
  const defaultFindingActions = useFindingActions({
    review: findings?.review ?? PLACEHOLDER_REVIEW,
    onReviewChange: findings?.onReviewChange ?? (() => {}),
    onNavigate: finding => options?.onOpenFindingInReview?.(finding.id),
  });

  const findingActions = findingOverride ?? defaultFindingActions;

  if (options === undefined || context === null) return { onComment: undefined, commentRange: null, cards: [] as LineCard[], renderCard: undefined, unplaced: null };
  const { path, text } = context;
  const lines = text.split('\n');
  const clean = !context.draft && !context.uncommitted && context.sourceHead !== null;
  const atHead = context.sourceHead !== null && context.sourceHead === context.observedHead;
  const active = rangePath === path ? range.selection : null;
  const selecting = range.anchor !== null;

  const close = () => {
    dispatchRange({ type: 'clear' });
    setBody('');
    setPreview(false);
    setError(null);
    setCommentKey(null);
  };

  const onComment = options.canReview || options.onAskAgent !== undefined
    ? (gesture: CommentGesture) => {
      if (gesture.type === 'finish') dispatchRange({ type: 'finish' });
      else if (gesture.type === 'cancel') dispatchRange({ type: 'clear' });
      else if (gesture.type === 'drag') dispatchRange({ type: 'drag', oldLine: null, newLine: gesture.line, row: gesture.line });
      else if (!posting && !sending) {
        setError(null);
        setRangePath(path);
        // A range only extends within the file it was started in.
        dispatchRange({ type: 'start', side: 'RIGHT', line: gesture.line, extend: gesture.extend && rangePath === path,
          oldLine: null, newLine: gesture.line, row: gesture.line });
      }
    }
    : undefined;

  const comment = (commitId: string | null) => active === null ? null : inlineReviewComment(path, body, active, commitId);

  const blocker = active === null ? null : githubCommentBlocker({
    prPatch: context.prPatch,
    start: active.start,
    end: active.end,
    draft: context.draft,
    uncommitted: context.uncommitted,
    atPrHead: atHead,
  });
  const askReason = context.askBlocked ?? (context.draft ? 'Save or revert your edits to send them to the agent' : null);

  const pendingHere = options.pending.filter(item => item.replyToCommentId == null && item.path === path && item.side === 'RIGHT');
  const placedPending = clean ? pendingHere.filter(item => item.commitId === context.sourceHead) : [];
  const threadsHere = options.threads.activities.filter(activity => activity.eventType === 'review_thread' && activity.payload.path === path);
  const placedThreads = clean && atHead
    ? threadsHere.filter(activity => !activity.payload.outdated && activity.payload.side === 'RIGHT' && activity.payload.line !== null)
    : [];
  const localHere = options.localReview.threads.filter(thread => thread.path === path);
  const placedLocal = clean
    ? localHere.filter(thread => localReviewMatchesRevision(thread, undefined, context.sourceHead ?? undefined)
      && lines.slice((thread.startLine ?? thread.line) - 1, thread.line).join('\n') === thread.code)
    : [];

  // A mapped line still needs semantic reassessment before an outdated finding returns to file views.
  const findingsHere = findings?.review.findings.filter(finding => findingInResults(findings.review, finding) && finding.path === path && finding.state !== 'WITHDRAWN' && !finding.outdated) ?? [];
  const placedFindings = findingsHere.filter(finding => findingOnLines(finding, clean, context.sourceHead, lines));
  const findingCard = (finding: ReviewFindingDto) => findings === undefined ? null : (
    <FindingCard review={findings.review} finding={finding} viewer={options.viewer} busy={findingActions.busy} error={findingActions.error}
      actions={options.onOpenFindingInReview === undefined ? { ...findingActions.actions, onNavigate: undefined } : findingActions.actions} />
  );

  const cards: LineCard[] = [
    ...placedFindings.map(finding => ({ key: `finding:${finding.id}`, line: finding.endLine })),
    ...placedThreads.map(activity => ({ key: `thread:${activity.githubId}`, line: activity.payload.line ?? 0 })),
    ...placedLocal.map(thread => ({ key: `local:${thread.id}`, line: thread.line })),
    ...placedPending.map((item, index) => ({ key: `pending:${index}`, line: item.line })),
    ...(active === null || selecting ? [] : [{ key: `composer:${active.start}-${active.end}`, line: active.end }]),
  ];

  const renderCard = (key: string): ReactNode => {
    if (key.startsWith('finding:')) {
      const finding = placedFindings.find(item => `finding:${item.id}` === key);
      return finding === undefined ? null : findingCard(finding);
    }
    if (key.startsWith('thread:')) {
      const activity = placedThreads.find(item => `thread:${item.githubId}` === key);
      return activity === undefined ? null : <ReviewThread pendingReview={{ comments: options.pending, onChange: options.onPendingChange }} {...options.threads} activity={activity} viewerLogin={options.viewer?.login} showCode={false} />;
    }
    if (key.startsWith('local:')) {
      const thread = placedLocal.find(item => `local:${item.id}` === key);
      return thread === undefined ? null : <LocalReviewThreadCard {...options.localReview} thread={thread} showCode={false} />;
    }
    if (key.startsWith('pending:')) {
      const item = placedPending[Number(key.slice('pending:'.length))];
      if (item === undefined) return null;
      return (
        <PendingReviewCard comment={item} viewer={options.viewer} repository={options.repo}
          suggestion={{ startLine: item.startLine ?? item.line, lines: lines.slice((item.startLine ?? item.line) - 1, item.line) }}
          editBody={editing(item) ? pendingEdit.body : null}
          onEdit={value => setPendingEdit(value === null ? null : { comment: item, body: value })}
          onSave={() => {
            if (!editing(item) || !pendingEdit.body.trim()) return;
            options.onPendingChange(updatePendingReviewComment(options.pending, item, pendingEdit.body));
            setPendingEdit(null);
          }}
          onDiscard={() => {
            options.onPendingChange(updatePendingReviewComment(options.pending, item, null));
            if (editing(item)) setPendingEdit(null);
          }} />
      );
    }
    if (active === null) return null;
    return (
      <ReviewLineComposer
        heading={rangeHeading(active)}
        viewer={options.viewer}
        review={{ repo: options.repo, workspaceId: options.workspaceId }}
        canReview={options.canReview}
        reviewDisabledReason={blocker}
        body={body}
        onBodyChange={setBody}
        preview={preview}
        onPreviewChange={setPreview}
        error={error}
        onError={setError}
        bodyRef={bodyRef}
        suggestion={blocker === null ? { startLine: active.start, lines: lines.slice(active.start - 1, active.end) } : undefined}
        submitting={posting || sending}
        posting={posting}
        askAgent={options.onAskAgent === undefined ? undefined : {
          sending,
          disabledReason: askReason,
          onSend: () => {
            // Local edits have no committed anchor, so they are never placed on the wrong lines.
            const value = comment(clean ? context.sourceHead : null);
            if (value === null || options.onAskAgent === undefined || askReason !== null) return;
            setSending(true);
            setError(null);
            void options.onAskAgent({ comment: value, baseSha: null, code: lines.slice(active.start - 1, active.end).join('\n') })
              .then(close)
              .catch(failure => setError(remoteErrorMessage(failure)))
              .finally(() => setSending(false));
          },
        }}
        onCancel={close}
        onAddPending={() => {
          const value = comment(context.sourceHead);
          if (value === null || blocker !== null) return;
          options.onPendingChange([...options.pending, value]);
          close();
        }}
        onPostNow={() => {
          const value = comment(context.sourceHead);
          if (value === null || blocker !== null) return;
          setPosting(true);
          setError(null);
          const key = commentKey ?? crypto.randomUUID();
          setCommentKey(key);
          void submitInlineReviewComment(window.bridge, options.repo, options.number, value, key, options.onDetailChange)
            .then(close)
            .catch(failure => setError(remoteErrorMessage(failure)))
            .finally(() => setPosting(false));
        }} />
    );
  };

  const unplacedThreads = threadsHere.filter(activity => !placedThreads.includes(activity));
  const unplacedLocal = localHere.filter(thread => !placedLocal.includes(thread));
  const unplacedPending = pendingHere.filter(item => !placedPending.includes(item));
  const unplacedFindings = findingsHere.filter(finding => !placedFindings.includes(finding));
  const unplacedCount = unplacedThreads.length + unplacedLocal.length + unplacedPending.length + unplacedFindings.length;
  const unplaced = unplacedCount === 0 ? null : (
    <details className="ct-unplaced">
      <summary>
        <ChevronRight className="ct-unplaced__chevron" size={12} strokeWidth={2} aria-hidden="true" />
        {unplacedCount} {unplacedCount === 1 ? 'comment is' : 'comments are'} not placed on these lines
      </summary>
      {unplacedFindings.map(finding => <div key={finding.id} className="ct-unplaced__card">{findingCard(finding)}</div>)}
      {unplacedPending.map((item, index) => (
        <div key={`pending:${index}`} className="ct-unplaced__card">
          <PendingReviewCard comment={item} viewer={options.viewer} repository={options.repo} editBody={null} onEdit={() => {}} onSave={() => {}}
            onDiscard={() => options.onPendingChange(updatePendingReviewComment(options.pending, item, null))} />
        </div>
      ))}
      {unplacedThreads.map(activity => (
        <div key={activity.githubId} className="ct-unplaced__card">
          <ReviewThread pendingReview={{ comments: options.pending, onChange: options.onPendingChange }} {...options.threads} activity={activity} viewerLogin={options.viewer?.login} />
        </div>
      ))}
      {unplacedLocal.map(thread => (
        <div key={thread.id} className="ct-unplaced__card">
          <LocalReviewThreadCard {...options.localReview} thread={thread} outdated={thread.headSha !== context.sourceHead} />
        </div>
      ))}
    </details>
  );

  return { onComment, commentRange: active, cards, renderCard, unplaced };
}
