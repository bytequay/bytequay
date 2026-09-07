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
import { useState } from 'react';
import { Chevron } from '../../Chevron';
import MarkdownBody from '../MarkdownBody';
import { relativeTime } from '../../relativeTime';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import type { AgentReviewDto, ReviewFindingDto } from './contracts';
import { askLabel, findingPill, findingPublication, messagesFor, shortPath } from './model';

export type FindingActions = {
  onAsk: (finding: ReviewFindingDto, message: string) => Promise<void>;
  onResolve: (finding: ReviewFindingDto) => Promise<void>;
  onReopen: (finding: ReviewFindingDto) => Promise<void>;
  onAddToReview: (finding: ReviewFindingDto) => Promise<void>;
  onPostNow: (finding: ReviewFindingDto, body: string) => Promise<void>;
  onEditDraft: (finding: ReviewFindingDto, body: string) => Promise<void>;
  /** Opens the finding at its diff location, or returns to its card from Changes. */
  onNavigate: (finding: ReviewFindingDto) => void;
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
  const [open, setOpen] = useState(defaultOpen);
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [composer, setComposer] = useState<'ask' | 'post' | 'draft' | null>(null);
  const [text, setText] = useState('');
  const pill = findingPill(review, finding);
  const publication = findingPublication(review, finding);
  const messages = messagesFor(review, finding.id);
  const item = publication.kind === 'draft' || publication.kind === 'published' ? publication.item : null;
  const withdrawn = finding.state === 'WITHDRAWN';
  const evidenceLabel = finding.evidence.length === 1
    ? '1 reference'
    : `${finding.evidence.length} references`;

  const openComposer = (kind: 'ask' | 'post' | 'draft', initial: string) => {
    setComposer(kind);
    setText(initial);
  };
  const close = () => {
    setComposer(null);
    setText('');
  };
  const submit = async () => {
    const body = text.trim();
    if (body === '') return;
    if (composer === 'ask') await actions.onAsk(finding, body);
    if (composer === 'post') await actions.onPostNow(finding, body);
    if (composer === 'draft') await actions.onEditDraft(finding, body);
    close();
  };

  return (
    <article className={`agr-finding${withdrawn || finding.state === 'RESOLVED' || finding.outdated ? ' is-muted' : ''}${publication.kind === 'draft' ? ' is-draft' : ''}`}
      id={`agent-review-finding-${finding.id}`}
      data-finding-id={finding.id}>
      <button type="button" className="agr-finding__head" aria-expanded={open} onClick={() => setOpen(value => !value)}>
        <Chevron open={open} />
        <span className="agr-finding__path" title={finding.path}>{shortPath(finding.path)}</span>
        <span className="agr-finding__range">{finding.range}</span>
        <span className="agr-spacer" />
        <span className={`agr-pill is-${pill.tone}`}>{pill.text}</span>
      </button>
      {open && (
        <div className="agr-finding__body">
          <div className="agr-finding__meta">
            <span className="agr-agent-mark" aria-hidden="true">A</span>
            <strong>Agent</strong>
            <span>· {review.task.laneLabel} · {relativeTime(finding.updatedAt)}</span>
            {finding.severity !== null && (
              <span className={`agr-severity ${severityTone(finding.severity)}`}>{finding.severity}</span>
            )}
            {publication.kind === 'blocked' && publication.reason.startsWith('Publishing unlocks') && (
              <span className="agr-pill is-provisional">Provisional</span>
            )}
            <span className="agr-spacer" />
            <span className="agr-finding__id">{finding.label} · rev {finding.revision}</span>
          </div>
          <h3 className="agr-finding__title">{finding.title}</h3>
          <MarkdownBody body={finding.body} repository={review.repo} />

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

          {finding.state === 'RESOLVED' && (
            <div className="agr-notice is-resolved">
              <span aria-hidden="true">✓</span>
              <span>
                Resolved locally by you
                {finding.stateReason ? ` · “${finding.stateReason}”` : ''}
                {' · excluded from publication, draft text kept'}
              </span>
              <button type="button" className="agr-action" disabled={busy}
                onClick={() => { void actions.onReopen(finding); }}>Reopen</button>
            </div>
          )}
          {withdrawn && (
            <div className="agr-notice is-withdrawn">
              <span aria-hidden="true">⌫</span>
              <span>The agent withdrew this finding{finding.stateReason ? ` · “${finding.stateReason}”` : ''}. It can no longer be published.</span>
            </div>
          )}
          {finding.outdated && (
            <div className="agr-notice is-outdated">
              <span aria-hidden="true">⚠</span>
              <span>
                New commits moved these lines and no unambiguous mapping was found. The recorded snapshot below is the
                version reviewed; it is not publishable until Update review re-anchors it.
              </span>
            </div>
          )}
          {finding.outdated && finding.excerpt.length > 0 && (
            <pre className="agr-snapshot" aria-label="Recorded snapshot of the reviewed lines">
              {finding.excerpt.join('\n')}
            </pre>
          )}
          {item !== null && !item.published && (
            <div className="agr-draft">
              <div className="agr-draft__head">
                Outgoing comment · in your pending review
                <span className="agr-spacer" />
                <span className="agr-draft__note">Editing this never changes the finding</span>
                <button type="button" disabled={busy} onClick={() => openComposer('draft', item.body)}>Edit</button>
              </div>
              <div className="agr-draft__body"><MarkdownBody body={item.body} repository={review.repo} /></div>
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

          <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />

          {composer !== null && (
            <ReviewComposer
              repository={review.repo}
              value={text}
              autoFocus
              busy={busy}
              placeholder={composer === 'ask' ? 'Ask the agent about this finding…' : 'Comment text (edits here never change the finding)'}
              ariaLabel={composer === 'ask' ? 'Ask the agent about this finding' : 'Outgoing comment text'}
              primaryLabel={composer === 'ask' ? askLabel(review) : composer === 'post' ? 'Post now' : 'Save'}
              outgoing={composer === 'ask'
                ? null
                : composer === 'post'
                  ? `Posts to GitHub on ${shortPath(finding.path, 28)} ${finding.range} · the head is re-checked on send`
                  : 'Your outgoing text; the agent never sees these edits'}
              onChange={setText}
              onSubmit={() => { void submit(); }}
              onCancel={close} />
          )}

          {composer === null && !withdrawn && (
            <div className="agr-actions">
              <button type="button" className="agr-action" disabled={busy}
                onClick={() => openComposer('ask', '')}>Ask agent</button>
              {finding.state === 'OPEN' && (
                <button type="button" className="agr-action" disabled={busy}
                  onClick={() => { void actions.onResolve(finding); }}>Resolve locally</button>
              )}
              <button type="button" className="agr-action" onClick={() => actions.onNavigate(finding)}>
                {projection === 'review' ? 'View in Changes' : 'Open in Agent review'}
              </button>
              <span className="agr-spacer" />
              {publication.kind === 'publishable' && (
                <>
                  <span className="agr-actions__divider" aria-hidden="true" />
                  <button type="button" className="agr-action" disabled={busy}
                    onClick={() => { void actions.onAddToReview(finding); }}>Add to my review</button>
                  <button type="button" className="agr-action is-primary" disabled={busy}
                    onClick={() => openComposer('post', finding.body)}>Post comment now</button>
                </>
              )}
              {publication.kind === 'blocked' && <span className="agr-actions__blocked">{publication.reason}</span>}
              {publication.kind === 'draft' && <span className="agr-actions__blocked">In your pending review</span>}
            </div>
          )}
        </div>
      )}
    </article>
  );
}
