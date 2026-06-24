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
import type { ReactNode } from 'react';

/** Where the plan came from — the initial pass or a revision. */
export type PlanSource = { revised?: boolean; label: string; revPill?: string };
/** One intent step in the plan. */
export type PlanStep = { text: ReactNode; file?: string };
/** A plan signal chip (risk / complexity / push budget). */
export type PlanSignal = { kind: 'risk-low' | 'cmplx' | 'push'; label: string };

/**
 * The Plan tab — renders the agent's recorded plan (understanding,
 * affected files, intent steps, signals) plus the approve / request
 * changes actions. Presentational: the host supplies the plan data and
 * action callbacks. When `approved`, the actions collapse to a locked
 * note.
 */
export function PlanTabContent({
  source, summary, affectedFiles, steps, signals, approved = false, onApprove, onRequestChanges,
}: {
  source?: PlanSource;
  summary: ReactNode;
  affectedFiles?: ReactNode[];
  steps?: PlanStep[];
  signals?: PlanSignal[];
  approved?: boolean;
  onApprove?: () => void;
  onRequestChanges?: () => void;
}) {
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
        <span className="lbl">Understanding</span>
        <div className="summary">{summary}</div>
      </div>

      {affectedFiles !== undefined && affectedFiles.length > 0 && (
        <div className="plan-sec">
          <span className="lbl">Affected files</span>
          <ul className="plan-bullets">
            {affectedFiles.map((f, i) => <li key={i}>{f}</li>)}
          </ul>
        </div>
      )}

      {steps !== undefined && steps.length > 0 && (
        <div className="plan-sec">
          <span className="lbl">Intent</span>
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

      {signals !== undefined && signals.length > 0 && (
        <div className="plan-sec">
          <span className="lbl">Signals</span>
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
              <button type="button" className="plan-btn primary" onClick={onApprove}>Approve plan</button>
            )}
            {onRequestChanges !== undefined && (
              <button type="button" className="plan-btn ghost" onClick={onRequestChanges}>Request changes</button>
            )}
          </div>
        )}
    </>
  );
}
