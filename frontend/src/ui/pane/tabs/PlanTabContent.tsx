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
import type { ReactNode } from 'react';

/** Where the plan came from — the initial pass or a revision. */
export type PlanSource = { revised?: boolean; label: string; revPill?: string };
/** One developing step in the plan. */
export type PlanStep = { text: ReactNode; file?: string };
/** A plan signal chip (risk / complexity / push budget) — legacy fallback. */
export type PlanSignal = { kind: 'risk-low' | 'cmplx' | 'push'; label: string };
/** Overall confidence the plan succeeds as written. */
export type PlanConfidence = 'low' | 'medium' | 'high';

/**
 * The Plan tab — the recorded plan distilled to what the user decides on:
 * the goal, the developing steps, and a single confidence badge, plus the
 * approve / request-changes actions. Presentational: the host supplies the
 * data and callbacks. When `approved`, the actions collapse to a locked
 * note. `signals` is kept as a fallback for plans recorded before the brain
 * emitted a confidence level.
 */
export function PlanTabContent({
  source, goal, steps, confidence, signals, approved = false, onApprove, onRequestChanges,
}: {
  source?: PlanSource;
  goal: ReactNode;
  steps?: PlanStep[];
  confidence?: PlanConfidence;
  signals?: PlanSignal[];
  approved?: boolean;
  onApprove?: () => void;
  onRequestChanges?: () => void;
}) {
  // Latch on first approve so a slow approve→lock round-trip can't be
  // double-clicked into a second approval.
  const [approving, setApproving] = useState(false);
  return (
    <>
      {source !== undefined && (
        <div className="plan-source">
          <span className={source.revised === true ? 'src rev' : 'src'}>
            {source.revised === true ? 'Revised plan' : 'Plan'}
          </span>
          <span>{source.label}</span>
          {source.revPill !== undefined && <span className="rev-pill">{source.revPill}</span>}
        </div>
      )}

      <div className="plan-sec">
        <span className="lbl">Goal</span>
        <div className="summary plan-goal">{goal}</div>
      </div>

      {steps !== undefined && steps.length > 0 && (
        <div className="plan-sec">
          <span className="lbl">Developing steps</span>
          <div className="plan-steps">
            {steps.map((s, i) => (
              <div className="plan-step" key={i}>
                <span className="ord">{i + 1}</span>
                <span className="stx">
                  {s.text}
                  {s.file !== undefined && <span className="f">{s.file}</span>}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {confidence !== undefined ? (
        <div className="plan-sec">
          <span className="lbl">Confidence</span>
          <div className="plan-confidence">
            <span className={`conf conf--${confidence}`}>{confidence}</span>
          </div>
        </div>
      ) : signals !== undefined && signals.length > 0 && (
        <div className="plan-sec">
          <span className="lbl">Confidence</span>
          <div className="plan-sigs">
            {signals.map((sig, i) => <span className={`sig ${sig.kind}`} key={i}>{sig.label}</span>)}
          </div>
        </div>
      )}

      {approved
        ? <div className="plan-source"><span className="src">Approved &amp; locked</span></div>
        : (onApprove !== undefined || onRequestChanges !== undefined) && (
          <div className="plan-actions">
            {onApprove !== undefined && (
              <button
                type="button"
                className="plan-btn primary"
                disabled={approving}
                onClick={() => { setApproving(true); onApprove(); }}
              >
                {approving ? 'Approving…' : 'Approve plan'}
              </button>
            )}
            {onRequestChanges !== undefined && (
              <button type="button" className="plan-btn ghost" onClick={onRequestChanges}>Request changes</button>
            )}
          </div>
        )}
    </>
  );
}
