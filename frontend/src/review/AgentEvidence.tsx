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
import { MarkdownProse } from '../threads/MarkdownProse';
import type {
  AgentReviewData, FindingEvidenceRow, FindingRow, FindingVerificationRow, ObservationRow,
} from './agentReviewTypes';
import { confidenceCeiling } from './agentReviewTypes';

export type AgentFindingPresentation = {
  finding: FindingRow;
  verification?: FindingVerificationRow;
  evidence: Array<{ evidence: FindingEvidenceRow; observation?: ObservationRow }>;
};

export function presentFinding(data: AgentReviewData, findingId: string): AgentFindingPresentation | undefined {
  const finding = data.findings.find(row => row.id === findingId);
  if (finding === undefined) return undefined;
  return {
    finding,
    verification: data.verifications.find(row => row.finding_id === findingId),
    evidence: data.evidence.filter(row => row.finding_id === findingId).map(evidence => ({
      evidence,
      observation: data.observations.find(row => row.id === evidence.observation_id),
    })),
  };
}

function criterionLabel(kind: FindingRow['criterion_kind']): string {
  if (kind === 'hard-invariant') return 'INVARIANT';
  if (kind === 'engineering-principle') return 'PRINCIPLE';
  return 'CONVENTION';
}

function severityLabel(severity: FindingRow['severity']): string {
  if (severity >= 5) return 'CRITICAL';
  if (severity >= 4) return 'MAJOR';
  if (severity >= 2) return 'MINOR';
  return 'NIT';
}

function strongestSupportingEvidence(view: AgentFindingPresentation): FindingEvidenceRow['strength_class'] {
  return view.evidence.filter(row => row.evidence.relation === 'SUPPORTS')
    .reduce<FindingEvidenceRow['strength_class']>(
      (best, row) => row.evidence.strength_class > best ? row.evidence.strength_class : best, 'E0');
}

function verificationLabel(view: AgentFindingPresentation): string {
  const { finding, verification } = view;
  if (verification?.status === 'rejected') return 'rejected — dropped';
  if (verification?.status === 'unknown') return 'unknown — asks author';
  if (verification?.status === 'partially') return 'partially verified';
  const strongest = strongestSupportingEvidence(view);
  return `verified ✓ · ceiling ${confidenceCeiling(strongest, finding.verification_status, finding.criterion_kind).toFixed(2)}`;
}

function displayCeiling(view: AgentFindingPresentation): number {
  const strongest = strongestSupportingEvidence(view);
  return confidenceCeiling(strongest, view.finding.verification_status, view.finding.criterion_kind);
}

export function AgentFindingContent({ view, body, pending = false }: {
  view: AgentFindingPresentation;
  body: string;
  pending?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const supports = view.evidence.filter(row => row.evidence.relation === 'SUPPORTS');
  const refutes = view.evidence.filter(row => row.evidence.relation === 'REFUTES');
  const groups = [
    { label: 'SUPPORTS', rows: supports, tone: 'supports' },
    { label: 'REFUTES', rows: refutes, tone: 'refutes' },
  ].filter(group => group.rows.length > 0);
  return (
    <div className="agent-finding-content">
      <div className="agent-finding-chips">
        <span className={`agent-finding-chip kind kind--${view.finding.criterion_kind}`}>{criterionLabel(view.finding.criterion_kind)}</span>
        <span className={`agent-finding-chip severity severity--${view.finding.severity}`}>{severityLabel(view.finding.severity)}</span>
        <span className="agent-finding-chip confidence">{view.finding.confidence_class} · ≤{displayCeiling(view).toFixed(2)}</span>
        {pending && <span className="agent-finding-chip pending">Pending</span>}
      </div>
      <MarkdownProse text={body} />
      <div className={`agent-verifier agent-verifier--${view.finding.verification_status}`}>
        <span>{verificationLabel(view)}</span>
        <button type="button" onClick={() => setOpen(value => !value)} aria-expanded={open}>
          evidence {open ? '▾' : '▸'}
        </button>
      </div>
      {open && (
        <div className="agent-evidence">
          {groups.map(group => (
            <div className={`agent-evidence__group agent-evidence__group--${group.tone}`} key={group.label}>
              <div className="agent-evidence__label">{group.label} · {group.rows.length}</div>
              {group.rows.map(({ evidence, observation }) => (
                <div className="agent-evidence__row" key={`${evidence.observation_id}-${evidence.relation}`}>
                  <span className="agent-evidence__glyph">{evidence.relation === 'SUPPORTS' ? '⌕' : '⊘'}</span>
                  <span className="agent-evidence__claim">{evidence.proposition}</span>
                  <span className="agent-evidence__strength">{evidence.strength_class}</span>
                  <code>{observation === undefined
                    ? evidence.observation_id
                    : `${observation.path ?? observation.source_type}:${observation.start_line ?? '—'}@${observation.commit_sha.slice(0, 7)}`}</code>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
