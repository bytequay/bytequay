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
import type { PlanCardDto } from '../../types/brainView';

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
  return (
    <div className={`plan-card plan-card--${plan.state}`} aria-label="Plan">
      <div className="hd">
        <span className="ic" aria-hidden>{STATE_GLYPH[plan.state]}</span>
        {STATE_LABEL[plan.state]}
        {plan.revisionCount > 1 && (
          <span className="rev" title={`${plan.revisionCount} revisions`}>· rev {plan.revisionCount}</span>
        )}
      </div>

      <div className="kv">
        <div className="kv-row">
          <div className="kv-lbl">Understanding</div>
          <div className="kv-val">{plan.understandingSummary || '…'}</div>
        </div>
        <div className="kv-row">
          <div className="kv-lbl">Intent</div>
          <div className="kv-val">{plan.intentSummary || '…'}</div>
        </div>
      </div>

      {/* The full step list shows once the plan is up for review or locked. */}
      {plan.state !== 'draft' && plan.steps.length > 0 && (
        <ol className="plan-steps">
          {plan.steps.map(s => <li key={s.ordinal}>{s.action}</li>)}
        </ol>
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
