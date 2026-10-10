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
import { Chevron } from '../../Chevron';
import CopyLinkButton from '../../CopyLinkButton';
import MarkdownBody, { type SuggestionBase } from '../MarkdownBody';
import { CommentedCodeLine } from '../PullOverview';
import LocalCard, { LocalCardMessage, ReviewerAvatar } from '../local-review/LocalCard';
import { commentIndexTarget } from '../ReviewCommentIndex';
import { findingConversation } from '../local-review/conversation';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import { findingLines } from './contracts';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';
import { askLabel, draftCommentCommit, draftItemFor, findingPill, findingPublication, findingReviewText, messagesFor, shortPath, shortSha } from './model';

export type FindingActions = {
  onRequestFix?: (finding: ReviewFindingDto, instruction: string) => Promise<void>;
  onAsk: (finding: ReviewFindingDto, message: string) => Promise<void>;
  /** Resolves the finding privately; with remember, the reason is also kept as the user's rule for the finding's file. */
  onResolve: (finding: ReviewFindingDto, reason: string, remember?: boolean) => Promise<void>;
  onReopen: (finding: ReviewFindingDto) => Promise<void>;
  onAddToReview: (finding: ReviewFindingDto) => Promise<void>;
  onPostNow: (finding: ReviewFindingDto, body: string) => Promise<void>;
  onEditDraft: (finding: ReviewFindingDto, body: string) => Promise<void>;
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
 * one finding ID, one discussion, two projections. Only the navigation label differs, so the user
 * always has the way back to the other one.
 */
export default function ReviewFindingCard({
  review,
  finding,
  viewerLogin,
  viewerAvatarUrl,
  projection,
  actions,
  busy = false,
  defaultOpen = true,
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
  const [composer, setComposer] = useReviewDraft<'ask' | 'resolve' | 'post' | 'draft' | 'fix' | null>(`finding:${finding.id}:composer`, null);
  const [fixSent, setFixSent] = useState(false);
  const [remember, setRemember] = useReviewDraft(`finding:${finding.id}:remember`, false);
  const [text, setText] = useReviewDraft(`finding:${finding.id}:text`, '');
  const [error, setError] = useState<string | null>(null);
  const pill = findingPill(review, finding);
  const publication = findingPublication(review, finding);
  const messages = messagesFor(review, finding.id);
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
  // Deleted ranges can omit intervening context; do not invent their individual line numbers.
  const completeExcerpt = lines !== null && finding.excerpt.length === lines.endLine - lines.startLine + 1;
  const evidenceLabel = finding.evidence.length === 1
    ? '1 reference'
    : `${finding.evidence.length} references`;

  const openComposer = (kind: Exclude<typeof composer, null>, initial: string) => {
    setError(null);
    setComposer(kind);
    setText(initial);
  };
  const close = () => {
    setError(null);
    setComposer(null);
    setText('');
    setRemember(false);
  };
  const submit = async () => {
    const body = text.trim();
    if (busy || (body === '' && !(composer === 'resolve' && !remember) && !(composer === 'draft' && reviewText !== null))) return;
    setError(null);
    try {
      if (composer === 'fix') { await actions.onRequestFix?.(finding, body); setFixSent(true); }
      if (composer === 'ask') await actions.onAsk(finding, body);
      if (composer === 'resolve') await actions.onResolve(finding, body, remember);
      if (composer === 'post') await actions.onPostNow(finding, body);
      if (composer === 'draft') await actions.onEditDraft(finding, body);
      close();
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not send your message.'); }
  };
  const remove = async () => {
    setError(null);
    try { await actions.onRemoveFromReview(finding); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not remove this comment.'); }
  };

  return (
    <article className={`agr-finding${withdrawn || finding.state === 'RESOLVED' || finding.outdated ? ' is-muted' : ''}${outgoingBody !== null ? ' is-draft' : ''}`}
      id={`agent-review-finding-${finding.id}`} data-finding-id={finding.id}>
      <LocalCard resolved={finding.state === 'RESOLVED'} outdated={finding.outdated} open={defaultOpen}
        title={lines === null
          ? <span className="lrc__title">Whole change</span>
          : <code className="lrc__title" title={lines.path}>{shortPath(lines.path)} {lines.range}</code>}
        badges={<>{pill.text !== 'Private' && finding.state !== 'RESOLVED' && <span className={`lrc__badge is-${pill.tone}`}>{pill.text}</span>}
          {finding.severity !== null && <span className={`agr-severity ${severityTone(finding.severity)}`}>{finding.severity}</span>}
          <CopyLinkButton label="conversation" value={findingConversation(review, finding)} /></>}
        code={lines !== null && finding.excerpt.length > 0 && <div className="lrc__code">
          {finding.excerpt.map((text, index) => <CommentedCodeLine key={lines.startLine + index}
            line={{ lineNumber: completeExcerpt ? lines.startLine + index : null, prefix: lines.side === 'LEFT' ? '-' : ' ', text }} />)}
        </div>}
        controls={<footer className={`lrc__foot${composer === null ? '' : ' is-composing'}`}>
          {composer !== null && (
            <ReviewComposer
              key={composer}
              repository={review.repo}
              workspaceId={review.workspaceId}
              value={text}
              suggestion={composer === 'post' ? suggestion : undefined}
              autoFocus
              busy={busy}
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
                  : 'Your outgoing text; the agent never sees these edits'}
              onChange={setText}
              onSubmit={composer === 'ask' ? submit : () => { void submit(); }}
              onCancel={close} />
          )}

          {composer === null && (
            <button type="button" className="lrc__ask" disabled={busy || review.state === 'CLOSED'}
              onClick={() => openComposer('ask', '')}>Ask reviewer</button>
          )}
          {composer === null && !withdrawn && <>
            {actions.onRequestFix !== undefined && finding.state === 'OPEN' && <button type="button" className="lrc__action" disabled={busy}
              onClick={() => openComposer('fix', 'Please investigate and address this finding.')}>Request fix</button>}
            {fixSent && <span className="pr-local-review__note" role="status">Sent to developer</span>}
            {actions.onNavigate !== undefined && lines !== null && (
              <button type="button" className="lrc__action" onClick={() => actions.onNavigate?.(finding)}>
                {projection === 'review' ? 'View in Changes' : 'Open in Summary'}
              </button>
            )}
            {finding.state === 'OPEN' && (
              <button type="button" className="lrc__action" disabled={busy}
                onClick={() => openComposer('resolve', '')}>Resolve</button>
            )}
            {publication.kind === 'publishable' && (
              <>
                <span className="agr-actions__divider" aria-hidden="true" />
                <button type="button" className="lrc__action" disabled={busy}
                  title={lines === null ? 'Adds this text to your review summary, which you can edit before submitting'
                    : 'Copies this comment into your pending GitHub review. Nothing is sent until you submit.'}
                  onClick={() => { void actions.onAddToReview(finding); }}>Add to my review</button>
                {lines !== null && <button type="button" className="lrc__action is-primary" disabled={busy}
                  onClick={() => openComposer('post', findingReviewText(finding))}>Post comment now</button>}
              </>
            )}
            {publication.kind === 'blocked' && <span className="agr-actions__blocked">{publication.reason}</span>}
            {publication.kind === 'draft' && <span className="agr-actions__blocked">In your pending review</span>}
            {publication.kind === 'inReviewText' && <span className="agr-actions__blocked">In your review summary</span>}
          </>}
          {error && <span className="lrc__error" role="alert">{error}</span>}
        </footer>}>
        <LocalCardMessage timestamp={finding.updatedAt} author="Agent review" avatar={<ReviewerAvatar />}
          attributes={commentIndexTarget('agent', `Agent review · ${finding.label} · ${finding.state.toLowerCase()} · ${finding.outdated ? 'Outdated · ' : ''}${finding.title}`, finding.body)}
          tag={<span className="lrc-message__meta">{review.task.laneLabel}</span>}
          menu={<>{publication.kind === 'blocked' && publication.reason.startsWith('Publishing unlocks') && <span className="agr-pill is-provisional">Provisional</span>}
            <span className="agr-finding__id">{finding.label} · rev {finding.revision}</span></>}>
          <h3 className="agr-finding__title">{finding.title}</h3>
          <MarkdownBody body={finding.body} repository={review.repo} suggestion={suggestion} />
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
                Resolved locally by you
                {finding.stateReason ? ` · “${finding.stateReason}”` : ''}
                {outgoingBody !== null ? ' · your saved review is unchanged' : ''}
              </span>
              <button type="button" className="agr-action" disabled={busy}
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
          {outgoingBody !== null && (
            <div className="agr-draft">
              <div className="agr-draft__head">
                {reviewText !== null ? 'Outgoing review summary' : 'Outgoing comment · in your pending review'}
                {item !== null && itemCommit !== null && <span className="agr-draft__note">
                  · {shortPath(item.path, 28)} · commit {shortSha(itemCommit)}
                </span>}
                <span className="agr-spacer" />
                <span className="agr-draft__note">Editing this never changes the finding</span>
                <button type="button" disabled={busy} onClick={() => openComposer('draft', outgoingBody)}>Edit</button>
                <button type="button" disabled={busy} title={reviewText !== null ? "Remove this finding's text from your review summary" : undefined}
                  onClick={() => { void remove(); }}>Remove</button>
              </div>
              <div className="agr-draft__body"><MarkdownBody body={outgoingBody} repository={review.repo} /></div>
            </div>
          )}
          {item !== null && item.published && (
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
        </LocalCardMessage>
        <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />
      </LocalCard>
    </article>
  );
}
