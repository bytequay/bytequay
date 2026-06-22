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
import { createPortal } from 'react-dom';
import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { PlanCardDto } from '../../types/brainView';
import { highlightToHtml } from '../../highlight';

/** Inline GFM markdown — matches the brain feed's renderer so plan prose
 *  (lists, code spans, emphasis) renders the same everywhere. */
function Md({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>;
}

/** Syntax-highlighted markdown for the roomy zoom view: code spans/blocks run
 *  through highlight.js so keywords, strings, and calls get token colors (the
 *  global highlight.css theme). Used only in the zoom — the narrow rail card
 *  keeps the plain {@link Md}. */
const HI_COMPONENTS: Components = {
  code({ className, children }) {
    const text = String(children ?? '').replace(/\n$/, '');
    const lang = /language-(\w+)/.exec(className ?? '')?.[1];
    const block = className != null || text.includes('\n');
    return (
      <code
        className={`hljs plan-code${block ? ' plan-code--block' : ' plan-code--inline'}`}
        dangerouslySetInnerHTML={{ __html: highlightToHtml(text, lang) }}
      />
    );
  },
};

function MdHi({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]} components={HI_COMPONENTS}>{children}</ReactMarkdown>;
}

/** Plan steps arrive with their ordinal baked into the text ("1. Do X"), and
 *  we render them inside an <ol> that numbers them too — strip the leading
 *  "N." / "N)" so the number isn't shown twice. */
function stripLeadingOrdinal(action: string): string {
  return action.replace(/^\s*\d+[.)]\s+/, '');
}

type Props = {
  plan: PlanCardDto;
  /** Approve the plan and start development (awaiting state only). */
  onApprove: () => void;
  /** Focus the brain composer so the user can request changes. */
  onRequestChanges: () => void;
  /** Mark a follow-up note addressed / dismissed (locked state). */
  onResolveFollowup: (eventId: string, status: 'addressed' | 'dismissed') => void;
  /** Disables the action buttons while a request is in flight. */
  busy?: boolean;
};

const STATE_LABEL: Record<PlanCardDto['state'], string> = {
  draft: 'PLAN DRAFT',
  awaiting: 'AWAITING APPROVAL',
  locked: 'APPROVED & LOCKED',
};

const STATE_GLYPH: Record<PlanCardDto['state'], string> = {
  draft: '✎',
  awaiting: '⏳',
  locked: '🔒',
};

/**
 * The structured plan card on the right rail. One component, three tints
 * driven by {@code plan.state}: purple while the brain drafts it, amber when
 * it's finalized and awaiting the user, green-locked once approved. Reuses
 * the {@code .approval-card} primitives (hd / kv rows / signal pills / btn).
 */
export function PlanCard({ plan, onApprove, onRequestChanges, onResolveFollowup, busy = false }: Props) {
  const openFollowups = plan.followups.filter(f => f.status === 'open');
  const [zoomOpen, setZoomOpen] = useState(false);
  return (
    <div className={`plan-card plan-card--${plan.state}`} aria-label="Plan">
      <div className="hd">
        <span className="ic" aria-hidden>{STATE_GLYPH[plan.state]}</span>
        {STATE_LABEL[plan.state]}
        {plan.revisionCount > 1 && (
          <span className="rev" title={`${plan.revisionCount} revisions`}>· rev {plan.revisionCount}</span>
        )}
        <button
          type="button"
          className={`plan-zoom-btn${plan.revisionCount > 1 ? '' : ' plan-zoom-btn--start'}`}
          aria-label="View full plan"
          title="View full plan"
          onClick={() => setZoomOpen(true)}
        >
          ⤢
        </button>
      </div>

      {plan.error != null && plan.error !== '' && (
        <div className="plan-error" role="alert">
          ⚠ Planning didn't complete: {plan.error}
        </div>
      )}

      <div className="kv">
        <div className="kv-row">
          <div className="kv-lbl">Understanding</div>
          <div className="kv-val md"><Md>{plan.understandingSummary || '…'}</Md></div>
        </div>
        <div className="kv-row">
          <div className="kv-lbl">Intent</div>
          <div className="kv-val md"><Md>{plan.intentSummary || '…'}</Md></div>
        </div>
      </div>

      {/* The full step list shows once the plan is up for review or locked. */}
      {plan.state !== 'draft' && plan.steps.length > 0 && (
        <ol className="plan-steps">
          {plan.steps.map(s => <li key={s.ordinal}><Md>{stripLeadingOrdinal(s.action)}</Md></li>)}
        </ol>
      )}

      {zoomOpen && (
        <PlanZoomModal
          plan={plan}
          busy={busy}
          onApprove={onApprove}
          onRequestChanges={onRequestChanges}
          onClose={() => setZoomOpen(false)}
        />
      )}

      <div className="plan-signals">
        <span className={`pill risk-${plan.signals.riskLevel}`}>risk: {plan.signals.riskLevel}</span>
        <span className="pill">{plan.signals.estimatedComplexity}</span>
        <span className="pill">{plan.signals.componentsCount} files</span>
      </div>

      {plan.state === 'awaiting' && (
        <div className="plan-actions">
          <button type="button" className="btn" disabled={busy} onClick={onApprove}>
            ✓ Approve &amp; start development
          </button>
          <button type="button" className="btn-secondary" disabled={busy} onClick={onRequestChanges}>
            Request changes
          </button>
        </div>
      )}

      {plan.state === 'locked' && (
        <>
          <div className="plan-locked-note">
            Approved plans are immutable. To change direction, open a new PlanStage.
          </div>
          {openFollowups.length > 0 && (
            <div className="plan-followups">
              <div className="kv-lbl">Follow-up notes</div>
              {openFollowups.map(f => (
                <div key={f.eventId} className="followup-row">
                  <div className="followup-note">{f.note}</div>
                  <div className="followup-meta">
                    {f.sourceAgent} agent · {f.createdAt}
                  </div>
                  <div className="followup-actions">
                    <button type="button" disabled={busy}
                      onClick={() => onResolveFollowup(f.eventId, 'addressed')}>Mark addressed</button>
                    <button type="button" disabled={busy}
                      onClick={() => onResolveFollowup(f.eventId, 'dismissed')}>Dismiss</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/**
 * A centered, roomy read of the full plan — the rail card is narrow, so
 * this overlays the window middle and renders every section (understanding,
 * intent + numbered steps, strategies, signals) in markdown. Closes on
 * Escape, backdrop click, or the × button.
 */
function PlanZoomModal({ plan, onClose, onApprove, onRequestChanges, busy = false }: {
  plan: PlanCardDto;
  onClose: () => void;
  onApprove: () => void;
  onRequestChanges: () => void;
  busy?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Portal to document.body so the fixed overlay centers on the whole app
  // window — rendered in place it'd be trapped by the right rail's
  // transformed / overflow-clipped ancestor and only cover that column.
  return createPortal(
    <div className="plan-zoom-backdrop" role="presentation" onClick={onClose}>
      <div
        className="plan-zoom"
        role="dialog"
        aria-modal="true"
        aria-label="Full plan"
        onClick={e => e.stopPropagation()}
      >
        <div className="plan-zoom__hd">
          <span className="plan-zoom__title">
            {STATE_GLYPH[plan.state]} {STATE_LABEL[plan.state]}
            {plan.revisionCount > 1 && <span className="rev"> · rev {plan.revisionCount}</span>}
          </span>
          <button type="button" className="plan-zoom__close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="plan-zoom__body">
          {plan.error != null && plan.error !== '' && (
            <div className="plan-error" role="alert">
              ⚠ Planning didn't complete: {plan.error}
            </div>
          )}
          <section className="plan-zoom__sec plan-zoom__sec--understanding">
            <h4>Understanding</h4>
            <div className="md"><MdHi>{plan.understandingSummary || '_None recorded._'}</MdHi></div>
          </section>
          <section className="plan-zoom__sec plan-zoom__sec--intent">
            <h4>Intent</h4>
            <div className="md"><MdHi>{plan.intentSummary || '_None recorded._'}</MdHi></div>
            {plan.steps.length > 0 && (
              <ol className="plan-steps">
                {plan.steps.map(s => <li key={s.ordinal}><MdHi>{stripLeadingOrdinal(s.action)}</MdHi></li>)}
              </ol>
            )}
          </section>
          {(plan.validationStrategy || plan.pushStrategy) && (
            <section className="plan-zoom__sec plan-zoom__sec--strategy">
              <h4>Strategy</h4>
              {plan.validationStrategy && (
                <div className="md"><MdHi>{`**Validation:** ${plan.validationStrategy}`}</MdHi></div>
              )}
              <div className="md">
                <MdHi>{`**Push:** ${plan.pushStrategy === 'autonomous' ? 'autonomous' : 'await approval'}`}</MdHi>
              </div>
            </section>
          )}
          <section className="plan-zoom__signals">
            <span className={`pill risk-${plan.signals.riskLevel}`}>risk: {plan.signals.riskLevel}</span>
            <span className="pill">{plan.signals.estimatedComplexity}</span>
            <span className="pill">{plan.signals.componentsCount} files</span>
          </section>
        </div>
        {plan.state === 'awaiting' && (
          <div className="plan-zoom__footer">
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={() => { onApprove(); onClose(); }}
            >
              ✓ Approve &amp; start development
            </button>
            <button
              type="button"
              className="btn-secondary"
              disabled={busy}
              onClick={() => { onRequestChanges(); onClose(); }}
            >
              Request changes
            </button>
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}
