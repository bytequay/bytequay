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
import { NodeCard } from './Spine';

/**
 * Layer-3 gate base: a decision node — a human gate promoted onto the spine,
 * never folded. `ask` is the amber `?` (Brain asks you); `approve` is the
 * orange `🔒` (an approval-gated action). Both wrap an arbitrary card body so
 * the brain feed, the trunk feed, and the root node share ONE gate shell.
 */
export function DecisionNode({ tone, children, id, flash }: {
  tone: 'ask' | 'approve';
  children: ReactNode;
  id?: string;
  flash?: boolean;
}) {
  return (
    <NodeCard mark={tone === 'ask' ? '?' : '🔒'} color={tone === 'ask' ? 'amber' : 'orange'} id={id} flash={flash}>
      <div className={`sp-gate sp-gate--${tone}`}>{children}</div>
    </NodeCard>
  );
}

/** The outcome of an approval decision. */
export type ApprovalDecision = 'approve' | 'deny' | 'always';

/**
 * Layer-3 gate: an approval-gated tool call. Shows the exact command in a
 * terminal block, why it's gated, and Approve / Deny / Always-allow. Resolves
 * in place to a green (or red) record. "Always allow" tells the host to add
 * the command to the auto-approve allowlist (see toolAllowlist) so the next
 * one folds silently into the activity strip. Callback-driven — the host
 * wires `onDecision` to `decideTaskPermission`.
 */
export function ApprovalNode({ tool, command, why, allowLabel, onDecision }: {
  tool?: string;
  command: string;
  why?: string;
  /** Token shown on the "Always allow X" button, e.g. "mvn". */
  allowLabel?: string;
  onDecision: (decision: ApprovalDecision) => void;
}) {
  const [resolved, setResolved] = useState<{ decision: ApprovalDecision } | null>(null);
  const decide = (decision: ApprovalDecision) => {
    setResolved({ decision });
    onDecision(decision);
  };
  if (resolved !== null) {
    const denied = resolved.decision === 'deny';
    return (
      <DecisionNode tone="approve">
        <div className={`sp-appr sp-appr--done${denied ? ' sp-appr--denied' : ''}`}>
          <span className="sp-appr__rc">
            {denied ? '⊘ Denied' : resolved.decision === 'always' ? `✓ Approved · ${allowLabel ?? 'tool'} allowlisted` : '✓ Approved'}
          </span>
          <span className="sp-appr__rcmd">{command}</span>
        </div>
      </DecisionNode>
    );
  }
  return (
    <DecisionNode tone="approve">
      <div className="sp-appr">
        <div className="sp-appr__head">
          <span className="sp-appr__lbl">⚑ Approve to run</span>
          {tool !== undefined && <span className="sp-appr__tool">{tool}</span>}
        </div>
        <div className="sp-appr__cmd"><span className="sp-appr__pfx">$ </span>{command}</div>
        {why !== undefined && <div className="sp-appr__why">{why}</div>}
        <div className="sp-appr__actions">
          <button type="button" className="sp-ab sp-ab--ok" onClick={() => decide('approve')}>Approve &amp; run</button>
          <button type="button" className="sp-ab sp-ab--deny" onClick={() => decide('deny')}>Deny</button>
          {allowLabel !== undefined && (
            <button type="button" className="sp-ab sp-ab--always" onClick={() => decide('always')}>Always allow {allowLabel}</button>
          )}
        </div>
      </div>
    </DecisionNode>
  );
}

/**
 * Layer-3 gate: Brain asking you a decision (`ask_user_question`). The amber
 * `?` spine node wrapping the question card body (passed as children — reuses
 * the existing question card, no second card). When `resolved` is given the
 * node shows the immutable answer chip instead.
 */
export function AskQuestionNode({ children, resolvedLabel, id, flash }: {
  children?: ReactNode;
  /** When set, the gate is answered — show the teal answer chip. */
  resolvedLabel?: string;
  id?: string;
  flash?: boolean;
}) {
  return (
    <DecisionNode tone="ask" id={id} flash={flash}>
      {resolvedLabel !== undefined
        ? <div className="sp-answered"><span className="sp-answered__chip">✓ {resolvedLabel}</span><span className="sp-answered__by">— you</span></div>
        : children}
    </DecisionNode>
  );
}
