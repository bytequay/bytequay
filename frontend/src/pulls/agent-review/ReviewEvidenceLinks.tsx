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
import type { ReviewFindingDto, ReviewRunDto } from './contracts';

export type EvidenceCitation = { target: string; label: string };
export type EvidenceCitations = Map<string, EvidenceCitation[]>;
const outcomes = { SUPPORTED: 'supported', REJECTED: 'rejected', NEEDS_EVIDENCE: 'needs evidence', UNRESOLVED: 'unresolved' } as const;
export const evidenceTarget = (runId: string, reference: string) => `review-evidence-${runId}-${reference}`;
export const validationTarget = (runId: string, step: number, candidate: number) => `review-validation-${runId}-${step}-${candidate}`;

/** Only saved findings and validation conclusions count as citations; a brief reference is not a verdict. */
export function reviewCitations(run: ReviewRunDto, findings: ReviewFindingDto[]): EvidenceCitations {
  const result: EvidenceCitations = new Map();
  const add = (reference: string, citation: EvidenceCitation) => {
    const values = result.get(reference) ?? [];
    if (!values.some(value => value.target === citation.target)) values.push(citation);
    result.set(reference, values);
  };
  for (const finding of findings.filter(item => item.runId === run.id)) {
    for (const evidence of finding.evidence) add(evidence.ref, { target: `agent-review-finding-${finding.id}`, label: `${finding.label}: ${finding.title}` });
  }
  const passes = run.steps.length === 0 ? [{ number: 0, candidates: run.investigation?.validations ?? [] }]
    : run.steps.flatMap(step => step.validation === null ? [] : [{ number: step.number, candidates: step.validation.candidates }]);
  for (const pass of passes) for (const candidate of pass.candidates) for (const reference of candidate.evidenceRefs) {
    add(reference, { target: validationTarget(run.id, pass.number, candidate.candidate),
      label: `${pass.number === 0 ? 'Validation' : `Pass ${pass.number}`} · hypothesis ${candidate.candidate} · ${outcomes[candidate.outcome]}` });
  }
  return result;
}

export function ReviewEvidenceLink({ target, children }: { target: string; children: ReactNode }) {
  return <a className="agr-context__link" href={`#${encodeURIComponent(target)}`} onClick={event => {
    event.preventDefault();
    const element = document.getElementById(target);
    if (element === null) return;
    if (element instanceof HTMLDetailsElement) element.open = true;
    // Finding cards contain their own disclosure; validation targets live inside several folded passes.
    element.querySelectorAll('details').forEach(details => { details.open = true; });
    for (let parent = element.parentElement; parent !== null; parent = parent.parentElement) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
    }
    element.tabIndex = -1;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }}>{children}</a>;
}

export function EvidenceCitationLinks({ reference, citations }: { reference: string; citations: EvidenceCitations }) {
  const targets = citations.get(reference) ?? [];
  return targets.length === 0 ? <p>No saved finding or validation conclusion cites this source.</p>
    : <div className="agr-context__citations"><strong>Cited by</strong><ul>{targets.map(target => <li key={target.target}>
      <ReviewEvidenceLink target={target.target}>{target.label}</ReviewEvidenceLink>
    </li>)}</ul></div>;
}
