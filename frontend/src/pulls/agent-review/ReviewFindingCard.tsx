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
import { useReviewDraft } from '../ReviewDrafts';
import { useState } from 'react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { Chevron } from '../../Chevron';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import MarkdownBody, { type SuggestionBase } from '../MarkdownBody';
import { CommentedCodeLine } from '../PullOverview';
import LocalCard, { LocalCardMessage, ReviewerAvatar } from '../local-review/LocalCard';
import { commentIndexTarget } from '../ReviewCommentIndex';
import { findingConversation } from '../local-review/conversation';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import { findingLines } from './contracts';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';
import { askLabel, draftCommentCommit, draftItemFor, findingPill, findingPublication, findingReviewText, messagesFor, queued, shortPath, shortSha } from './model';

export type FindingActions = {
  onRequestFix?: (finding: ReviewFindingDto, instruction: string) => Promise<void>;
  onAsk: (finding: ReviewFindingDto, message: string) => Promise<void>;
  /** Resolves the finding privately; with remember, the reason is also kept as the user's rule for the finding's file. */
  onResolve: (finding: ReviewFindingDto, reason: string, remember?: boolean) => Promise<void>;
  onReopen: (finding: ReviewFindingDto) => Promise<void>;
  onAddToReview: (finding: ReviewFindingDto) => Promise<void>;
  onPostNow: (finding: ReviewFindingDto, body: string) => Promise<void>;
  /** Saves against the draft version Edit opened on, so a change made meanwhile conflicts instead of being overwritten. */
  onEditDraft: (finding: ReviewFindingDto, body: string, expectedVersion: number) => Promise<void>;
  onRemoveFromReview: (finding: ReviewFindingDto) => Promise<void>;
  /** Opens the finding at its diff location, or returns to its card from Changes; absent when there is nowhere to go. */
  onNavigate?: (finding: ReviewFindingDto) => void;
};

function severityTone(severity: string | null): string {
  const value = (severity ?? '').toLowerCase();
  if (value === 'critical') return 'is-critical';
  if (value === 'high') return 'is-high';
  if (value === 'medium') return 'is-medium';
  return 'is-low';
}

/**
 * One finding, with its evidence, its private discussion, and the two kinds of action it offers.
 *
 * The same card renders in the Agent review tab and at the finding's diff location in Changes:
 * one finding ID, one discussion, two projections. The diff projection leaves out the code and path,
 * and the navigation label always offers the way back to the other one.
 */
export default function ReviewFindingCard({
  review,
  finding,
  viewerLogin,
  viewerAvatarUrl,
  projection,
  actions,
  busy = false,
  defaultOpen,
}: {
  review: AgentReviewDto;
  finding: ReviewFindingDto;
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  /** Which surface is drawing it, which is the one thing the two projections do differently. */
  projection: 'review' | 'changes';
  actions: FindingActions;
  busy?: boolean;
  defaultOpen?: boolean;
}) {
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [originalOpen, setOriginalOpen] = useState(false);
  const [composer, setComposer] = useReviewDraft<'ask' | 'resolve' | 'post' | 'draft' | 'fix' | null>(`finding:${finding.id}:composer`, null);
  const [fixSent, setFixSent] = useState(false);
  const [remember, setRemember] = useReviewDraft(`finding:${finding.id}:remember`, false);
  const [text, setText] = useReviewDraft(`finding:${finding.id}:text`, '');
  const [editVersion, setEditVersion] = useReviewDraft<number | null>(`finding:${finding.id}:version`, null);
  const [error, setError] = useState<string | null>(null);
  const pill = findingPill(review, finding);
  const accepted = pill.text === 'Accepted';
  const publication = findingPublication(review, finding);
  const awaitingConfirmation = publication.kind === 'sending' || publication.kind === 'uncertain';
  const messages = messagesFor(review, finding.id);
  const responding = review.state === 'OPEN' && (review.activeTurn?.state === 'ACCEPTED' || review.activeTurn?.state === 'RUNNING')
    && messages.some(message => message.author === 'USER' && message.turnId === review.activeTurn?.id && !queued(review, message));
  const waitingReason = responding ? 'Agent responding'
    : review.state === 'OPEN' && messages.some(message => message.author === 'USER' && queued(review, message)) ? 'Question queued' : null;
  const actionsDisabled = busy || waitingReason !== null;
  const item = draftItemFor(review.draft, finding.id);
  const itemCommit = item === null ? null : draftCommentCommit(item, review.findings);
  const reviewText = item === null && finding.path === null && review.draft?.body.includes(finding.body.trim()) ? review.draft.body : null;
  const outgoingBody = reviewText ?? (item !== null && !item.published ? item.body : null);
  // Null for a finding about the whole pull request, such as a commit message: it has no code, no suggestion and no line to post on.
  const lines = findingLines(finding);
  const suggestion: SuggestionBase | undefined = lines !== null && lines.side === 'RIGHT'
    ? { startLine: lines.startLine, lines: finding.excerpt }
    : undefined;
  const withdrawn = finding.state === 'WITHDRAWN';
  // The diff row above already shows the file and its lines, as for GitHub threads drawn there.
  const inDiff = projection === 'changes';
  // Deleted ranges can omit intervening context; do not invent their individual line numbers.
  const completeExcerpt = lines !== null && finding.excerpt.length === lines.endLine - lines.startLine + 1;
  const evidenceLabel = finding.evidence.length === 1
    ? '1 reference'
    : `${finding.evidence.length} references`;
  // On the Summary, an outgoing comment takes the finding's place; the finding stays one click away.
  const replaced = !inDiff && (outgoingBody !== null || composer === 'draft');
  const edited = outgoingBody !== null && reviewText === null && outgoingBody.trim() !== findingReviewText(finding);
  const idLabel = <span className="agr-finding__id">{finding.label} · rev {finding.revision}</span>;
  const provisionalPill = publication.kind === 'blocked' && publication.reason.startsWith('Publishing unlocks') && <span className="agr-pill is-provisional">Provisional</span>;

  const openComposer = (kind: Exclude<typeof composer, null>, initial: string) => {
    if (actionsDisabled) return;
    setError(null);
    setComposer(kind);
    setText(initial);
  };
  const close = () => {
    setError(null);
    setComposer(null);
    setText('');
    setRemember(false);
    setEditVersion(null);
  };
  const submit = async () => {
    const body = text.trim();
    if (actionsDisabled || (composer === 'post' && awaitingConfirmation)
      || (body === '' && !(composer === 'resolve' && !remember) && !(composer === 'draft' && reviewText !== null))) return;
    setError(null);
    try {
      if (composer === 'fix') { await actions.onRequestFix?.(finding, body); setFixSent(true); }
      if (composer === 'ask') await actions.onAsk(finding, body);
      if (composer === 'resolve') await actions.onResolve(finding, body, remember);
      if (composer === 'post') await actions.onPostNow(finding, body);
      if (composer === 'draft') await actions.onEditDraft(finding, body, editVersion ?? review.draft?.version ?? 0);
      close();
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not send your message.'); }
  };
  const remove = async () => {
    if (actionsDisabled || awaitingConfirmation) return;
    setError(null);
    try { await actions.onRemoveFromReview(finding); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not remove this comment.'); }
  };

  const editor = composer !== null && (
    <ReviewComposer
      key={composer}
      repository={review.repo}
      workspaceId={review.workspaceId}
      value={text}
      suggestion={composer === 'post' ? suggestion : undefined}
      autoFocus
      busy={busy}
      disabledReason={waitingReason ?? (composer === 'post' && awaitingConfirmation ? 'Wait for delivery confirmation before sending again.' : null)}
      allowEmpty={(composer === 'resolve' && !remember) || (composer === 'draft' && reviewText !== null)}
      extra={composer === 'resolve' && lines !== null && <label className="agr-remember" title="Saves your reason as a rule agents get for this file">
        <input type="checkbox" checked={remember} disabled={busy} onChange={event => setRemember(event.target.checked)} />
        Remember for <code>{shortPath(lines.path, 36)}</code></label>}
      placeholder={composer === 'fix' ? 'Tell the developer what to fix…' : composer === 'resolve' ? 'Why are you resolving this finding locally? (optional)'
        : composer === 'ask' ? 'Ask the reviewer about this finding…' : reviewText !== null ? 'Your review summary…' : 'Comment text (edits here never change the finding)'}
      ariaLabel={composer === 'fix' ? 'Instructions for the development agent' : composer === 'resolve' ? 'Resolution reason (optional)' : composer === 'ask' ? 'Ask the reviewer about this finding' : reviewText !== null ? 'Review summary text' : 'Outgoing comment text'}
      primaryLabel={composer === 'fix' ? 'Send to developer' : composer === 'resolve' ? 'Resolve locally' : composer === 'ask' ? askLabel(review) : composer === 'post' ? 'Post now' : 'Save'}
      outgoing={composer === 'ask' || composer === 'resolve' || composer === 'fix'
        ? null
        : composer === 'post' && lines !== null
          ? `Posts to GitHub on ${shortPath(lines.path, 28)} ${lines.range} at reviewed commit ${shortSha(finding.validatedHeadSha)}`
          : ''}
      onChange={setText}
      onSubmit={composer === 'ask' ? submit : () => { void submit(); }}
      onCancel={close} />
  );

  const indexTarget = commentIndexTarget('agent', `Agent review · ${finding.label} · ${finding.state.toLowerCase()} · ${finding.outdated ? 'Outdated · ' : ''}${finding.title}`, finding.body);
  const title = <h3 className="agr-finding__title">{finding.title}</h3>;
  const body = <MarkdownBody body={finding.body} repository={review.repo} suggestion={suggestion} />;
  const details = (
    <div className="agr-finding__details">
    {finding.evidence.length > 0 && (
      <div className="agr-evidence">
        <button type="button" className="agr-evidence__toggle" aria-expanded={evidenceOpen}
          onClick={() => setEvidenceOpen(value => !value)}>
          <Chevron open={evidenceOpen} />Evidence · {evidenceLabel}
        </button>
        {evidenceOpen && finding.evidence.map(evidence => (
          <div className="agr-evidence__item" key={evidence.ref}>
            <div className="agr-evidence__head">
              <span className="agr-evidence__source" title={evidence.source}>{evidence.source}</span>
              <span className="agr-spacer" />
              <span>{evidence.provenance}</span>
            </div>
            <pre className="agr-evidence__excerpt">{evidence.excerpt}</pre>
            <div className="agr-evidence__why">{evidence.explanation}</div>
          </div>
        ))}
      </div>
    )}
    {finding.memories.length > 0 && (
      <div className="agr-evidence">
        <button type="button" className="agr-evidence__toggle" aria-expanded={memoryOpen} onClick={() => setMemoryOpen(value => !value)}>
          <Chevron open={memoryOpen} />Memory used · {finding.memories.length}
        </button>
        {memoryOpen && finding.memories.map(memory => (
          <div className="agr-evidence__item" key={memory.id}>
            <div className="agr-evidence__head"><span className="agr-evidence__source">{memory.id}</span></div>
            <div className="agr-evidence__why">{memory.line}</div>
          </div>
        ))}
      </div>
    )}

    {finding.state === 'RESOLVED' && (
      <div className="agr-notice is-resolved">
        <span aria-hidden="true">✓</span>
        <span>
          {/* A development agent resolves what it fixed; its reply below says what changed. */}
          {finding.resolvedByTaskId != null ? 'Resolved by Developer' : 'Resolved locally by you'}
          {finding.stateReason ? ` · “${finding.stateReason}”` : ''}
          {outgoingBody !== null ? ' · your saved review is unchanged' : ''}
        </span>
        <button type="button" className="agr-action" disabled={actionsDisabled} title={waitingReason ?? undefined}
          onClick={() => { void actions.onReopen(finding); }}>Reopen</button>
      </div>
    )}
    {withdrawn && (
      <div className="agr-notice is-withdrawn">
        <span aria-hidden="true">⌫</span>
        <span>The agent withdrew this finding{finding.stateReason ? ` · “${finding.stateReason}”` : ''}.
          {outgoingBody !== null ? ' Your saved review is unchanged.' : ' It can no longer be added to your review.'}</span>
      </div>
    )}
    {finding.outdated && (
      <div className="agr-notice is-outdated">
        <span aria-hidden="true">⚠</span>
        <span>
          This finding describes an earlier reviewed commit. Its comment stays attached to that commit and may
          appear outdated on GitHub. Reassessment can check whether the finding still applies to the latest code.
        </span>
      </div>
    )}
    {composer === 'draft' ? <>
      {editor}
      {error && <span className="lrc__error" role="alert">{error}</span>}
    </> : outgoingBody !== null && (
      <div className="agr-draft">
        <div className="agr-draft__head">
          {inDiff ? <>
            {reviewText !== null ? 'Outgoing review summary' : publication.kind === 'uncertain' ? 'Outgoing comment · delivery unconfirmed'
              : publication.kind === 'sending' ? 'Outgoing comment · sending' : 'Outgoing comment · in your pending review'}
            {item !== null && itemCommit !== null && <span className="agr-draft__note">
              · {shortPath(item.path, 28)} · commit {shortSha(itemCommit)}
            </span>}
            <span className="agr-spacer" />
            <span className="agr-draft__note">Editing this never changes the finding</span>
          </> : <>
            {reviewText !== null ? 'Your review summary' : publication.kind === 'uncertain' ? 'Your comment · delivery unconfirmed'
              : publication.kind === 'sending' ? 'Your comment · sending' : 'Your comment'}
            {edited && <><span className="agr-draft__note">· edited</span>
              <button type="button" className="agr-link" aria-expanded={originalOpen} onClick={() => setOriginalOpen(value => !value)}>
                {originalOpen ? 'Hide original' : 'Show original'}</button></>}
            <span className="agr-spacer" />
            <span className="agr-draft__note">Agent · {relativeTime(finding.updatedAt)}</span>
          </>}
          <button type="button" disabled={actionsDisabled || awaitingConfirmation} title={waitingReason ?? undefined}
            onClick={() => { setEditVersion(review.draft?.version ?? 0); openComposer('draft', outgoingBody); }}>Edit</button>
          <button type="button" disabled={actionsDisabled || awaitingConfirmation} title={waitingReason ?? (reviewText !== null ? "Remove this finding's text from your review summary" : undefined)}
            onClick={() => { void remove(); }}>Remove</button>
        </div>
        <div className="agr-draft__body"><MarkdownBody body={outgoingBody} repository={review.repo} /></div>
        {!inDiff && edited && originalOpen && <div className="agr-draft__original">
          <span>Original finding</span>
          <MarkdownBody body={findingReviewText(finding)} repository={review.repo} />
        </div>}
      </div>
    )}
    {publication.kind === 'uncertain' && (
      <div className="agr-notice is-outdated" role="alert">
        <span aria-hidden="true">⚠</span>
        <span>GitHub did not confirm this comment. Check the <a href={`https://github.com/${review.repo}/pull/${review.number}`}>pull request on GitHub</a>.
          {' '}Then open <strong>Submit review</strong> and choose <strong>Confirmed posted</strong> if it is there, or <strong>Confirmed not posted</strong> to prepare it again.
          {' '}Your comment is saved here and will not be sent again automatically.</span>
      </div>
    )}
    {item !== null && publication.kind === 'published' && (
      <div className="agr-notice is-published">
        <span aria-hidden="true">✓</span>
        <span>
          Posted to GitHub as a review comment
          {item.publication?.url ? <> · <a href={item.publication.url}>{item.publication.url.split('#').pop()}</a></> : ''}
          . The thread below stays private.
        </span>
      </div>
    )}

    </div>
  );

  return (
    <article className={`agr-finding${inDiff ? '' : ' is-summary'}${withdrawn || finding.state === 'RESOLVED' || finding.outdated ? ' is-muted' : ''}${outgoingBody !== null ? ' is-draft' : ''}`}
      id={`agent-review-finding-${finding.id}`} data-finding-id={finding.id} data-review-closed={withdrawn || undefined}>
      <LocalCard resolved={finding.state === 'RESOLVED'} outdated={finding.outdated} open={defaultOpen}
        title={lines === null
          ? <span className="lrc__title">Whole change</span>
          : inDiff
            ? <span className="lrc__title">Comment on {lines.startLine === lines.endLine ? 'line' : 'lines'} <strong>{lines.range}</strong></span>
            : <code className="lrc__title" title={lines.path}>{shortPath(lines.path)} <span className="agr-finding__range">{lines.range}</span></code>}
        badges={<>{!inDiff && idLabel}
          {!accepted && pill.text !== 'Private' && finding.state !== 'RESOLVED' && <span className={`lrc__badge is-${pill.tone}`}>{pill.text}</span>}
          {finding.severity !== null && <span className={`agr-severity ${severityTone(finding.severity)}`}>{finding.severity}</span>}
          <CopyLinkButton label="conversation" value={findingConversation(review, finding)} />
          {accepted && <span className="lrc__badge is-ready" title="Posted to GitHub in your review">Accepted</span>}</>}
        code={!inDiff && lines !== null && finding.excerpt.length > 0 && <div className="lrc__code">
          {finding.excerpt.map((text, index) => <CommentedCodeLine key={lines.startLine + index}
            line={{ lineNumber: completeExcerpt ? lines.startLine + index : null, prefix: lines.side === 'LEFT' ? '-' : ' ', text }} />)}
        </div>}
        controls={composer !== 'draft' && <footer className={`lrc__foot${composer === null ? '' : ' is-composing'}`}>
          {responding && <span className="pr-local-review__progress" role="status"><span className="sr-only">Agent responding</span></span>}
          {editor}

          {composer === null && (
            <button type="button" className="lrc__ask" disabled={actionsDisabled || review.state === 'CLOSED'} title={waitingReason ?? undefined}
              onClick={() => openComposer('ask', '')}>Ask reviewer</button>
          )}
          {composer === null && !withdrawn && <>
            {actions.onRequestFix !== undefined && finding.state === 'OPEN' && <button type="button" className="lrc__action" disabled={actionsDisabled} title={waitingReason ?? undefined}
              onClick={() => openComposer('fix', 'Please investigate and address this finding.')}>Request fix</button>}
            {fixSent && <span className="pr-local-review__note" role="status">Sent to developer</span>}
            {actions.onNavigate !== undefined && lines !== null && (
              <button type="button" className="lrc__action" disabled={actionsDisabled} title={waitingReason ?? undefined} onClick={() => actions.onNavigate?.(finding)}>
                {projection === 'review' ? 'View in Changes' : 'Open in Summary'}
              </button>
            )}
            {!inDiff && <span className="agr-spacer" />}
            {finding.state === 'OPEN' && (
              <button type="button" className="lrc__action" disabled={actionsDisabled} title={waitingReason ?? undefined}
                onClick={() => openComposer('resolve', '')}>Resolve</button>
            )}
            {publication.kind === 'publishable' && (
              <>
                <span className="agr-actions__divider" aria-hidden="true" />
                <button type="button" className="lrc__action is-add" disabled={actionsDisabled}
                  title={waitingReason ?? (lines === null ? 'Adds this text to your review summary, which you can edit before submitting'
                    : 'Copies this comment into your pending GitHub review. Nothing is sent until you submit.')}
                  onClick={() => { void actions.onAddToReview(finding); }}>Add to my review</button>
                {lines !== null && <button type="button" className="lrc__action is-primary" disabled={actionsDisabled} title={waitingReason ?? undefined}
                  onClick={() => openComposer('post', findingReviewText(finding))}>{inDiff ? 'Post comment now' : 'Post now'}</button>}
              </>
            )}
            {publication.kind === 'blocked' && <span className="agr-actions__blocked">{publication.reason}</span>}
            {!inDiff && (publication.kind === 'draft' || publication.kind === 'inReviewText') ? <>
              <span className="agr-actions__divider" aria-hidden="true" />
              <button type="button" className="lrc__action is-added" disabled={actionsDisabled}
                title={waitingReason ?? (publication.kind === 'draft' ? 'In your pending review. Click to remove it.' : 'In your review summary. Click to remove it.')}
                onClick={() => { void remove(); }}>✓ In my review</button>
            </> : <>
              {publication.kind === 'draft' && <span className="agr-actions__blocked">In your pending review</span>}
              {publication.kind === 'inReviewText' && <span className="agr-actions__blocked">In your review summary</span>}
            </>}
          </>}
          {error && <span className="lrc__error" role="alert">{error}</span>}
        </footer>}>
        {inDiff ? <LocalCardMessage timestamp={finding.updatedAt} author="Agent review" avatar={<ReviewerAvatar />}
          attributes={indexTarget} menu={<>{provisionalPill}{idLabel}</>}>
          {title}{body}{details}
        </LocalCardMessage> : <div className="lrc-message agr-finding__message" {...indexTarget}>
          {!replaced && <div className="agr-finding__head">{title}{provisionalPill}
            <span className="agr-finding__by">Agent · <time>{relativeTime(finding.updatedAt)}</time></span></div>}
          {!replaced && body}
          {details}
        </div>}
        <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />
      </LocalCard>
    </article>
  );
}
