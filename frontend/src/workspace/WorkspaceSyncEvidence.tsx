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
import { CheckIcon, ChevronIcon, ShieldIcon } from './WorkspaceSyncIcons';
import { clockLabel } from './syncRunModel';
import type {
  SyncBoundaryDto, SyncCompileProofDto, SyncFixupDto,
} from './workspaceApi';

/**
 * Which fixup repaired which pick.
 *
 * A sync branch is a reviewable series — each upstream commit with the fork's
 * repair attached to it — so the reader's question is never "was there a
 * repair" but "whose". Every row names the commit it belongs behind.
 */
export function SyncFixupAttribution({ fixups }: { fixups: SyncFixupDto[] }) {
  const [open, setOpen] = useState(false);
  if (fixups.length === 0) return null;
  const ci = fixups.filter(fixup => fixup.origin === 'CI_REPAIR').length;
  return (
    <>
      <button type="button" className="sf-group" aria-expanded={open}
        onClick={() => setOpen(current => !current)}>
        <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
        <span className="sf-pill">
          <span className="sf-pill__glyph is-done" aria-hidden>
            <CheckIcon size={12} />
          </span>
          Attributed repairs
        </span>
        <span className="sf-group__meta">
          {fixups.length} fixup{fixups.length === 1 ? '' : 's'}
          {' · '}{fixups.length - ci} while picking · {ci} from CI
        </span>
      </button>
      {open && (
        <div className="sf-nested">
          {fixups.map(fixup => (
            <div className="sf-fixup" key={fixup.upstreamSha}>
              <span className="sf-pick__ordinal">PICK {fixup.pickIndex + 1}</span>
              <code title={fixup.commitSha}>{short(fixup.commitSha)}</code>
              <span className="sf-fixup__target" title={fixup.targetSubject}>
                fixup! {fixup.targetSubject === ''
                  ? short(fixup.upstreamSha) : fixup.targetSubject}
              </span>
              <span className={`sf-fixup__origin is-${
                fixup.origin === 'CI_REPAIR' ? 'ci' : 'pick'}`}>
                {fixup.origin === 'CI_REPAIR' ? 'CI repair' : 'conflict repair'}
              </span>
              {/* A second repair amends the first rather than adding a
                  commit, so a pick still carries exactly one fixup. */}
              {fixup.amendCount > 0 && (
                <span className="sf-fixup__amends">
                  amended ×{fixup.amendCount}
                </span>
              )}
              <span className="sf-fixup__paths"
                title={fixup.changedPaths.join('\n')}>
                {fixup.changedPaths.length} file
                {fixup.changedPaths.length === 1 ? '' : 's'}
              </span>
              <time>{clockLabel(fixup.at)}</time>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

/**
 * The excused per-commit compile red, and the evidence that excused it.
 *
 * A target commit whose repair lives in the fixup after it is red in isolation
 * by construction. The only thing that may excuse that is this proof — a
 * rebase whose boundary builds the program generated, never a reading of the
 * remote log. So the card shows the builds themselves: an excused red with no
 * visible evidence is indistinguishable from a bug.
 */
export function SyncExcusedCheck({ proof }: { proof: SyncCompileProofDto }) {
  const [open, setOpen] = useState(false);
  const failed = proof.boundaries.filter(
    boundary => boundary.exitState === 'FAILED');
  return (
    <section className={`sf-excused${failed.length > 0 ? ' is-bad' : ''}`}>
      <header>
        <span className="sf-excused__glyph" aria-hidden><ShieldIcon size={12} /></span>
        <strong>
          {failed.length > 0
            ? 'Boundary compile failed — nothing is excused'
            : `Per-commit compile red excused on ${
              proof.excusedTargets.length} target${
              proof.excusedTargets.length === 1 ? '' : 's'}`}
        </strong>
        <time>{clockLabel(proof.provedAt)}</time>
      </header>
      <p>
        {proof.compileSelectors.length === 0
          // No selector, no exception: the flow degrades to plain finalized
          // red rather than excusing a check it cannot identify.
          ? 'No per-commit compile check was identified, so no red is excused.'
          : `${proof.compileSelectors.join(', ')} on ${short(proof.headSha)}`}
        {proof.compileSourceRef !== null && (
          <span className="sf-excused__source">
            read from {proof.compileSourceRef}
          </span>
        )}
      </p>
      {proof.excusedTargets.length > 0 && (
        <ul className="sf-excused__targets">
          {proof.excusedTargets.map((target, index) => (
            // Two commits can carry the same subject, so the position is the
            // only stable key here.
            <li key={index}>{target}</li>
          ))}
        </ul>
      )}
      <button type="button" className="sf-disclose" aria-expanded={open}
        onClick={() => setOpen(current => !current)}>
        <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
        <span>Boundary compile proof</span>
        <span className="sf-disclose__count">
          {proof.boundaries.length} build
          {proof.boundaries.length === 1 ? '' : 's'}
          {failed.length > 0 ? ` · ${failed.length} failed` : ' · all passed'}
        </span>
      </button>
      {open && (
        <div className="sf-nested">
          {proof.boundaries.map(boundary => (
            <div className="sf-boundary" key={boundary.commitSha}>
              <span className={`sf-boundary__mark is-${
                boundary.exitState.toLowerCase()}`} aria-hidden>
                {boundary.exitState === 'PASSED' ? <CheckIcon size={9} /> : '×'}
              </span>
              <code title={boundary.commitSha}>{short(boundary.commitSha)}</code>
              <span className="sf-boundary__kind">
                {boundaryLabel(boundary.kind)}
              </span>
              <span className="sf-boundary__evidence"
                title={boundary.evidenceRef}>{boundary.evidenceRef}</span>
            </div>
          ))}
          {proof.boundaries.length === 0 && (
            <p className="sf-excused__none">
              The proof recorded no boundary build.
            </p>
          )}
        </div>
      )}
    </section>
  );
}

/**
 * A bare target followed by its fixup is deliberately not a boundary, which is
 * what makes the exception provable rather than assumed — so the list names
 * what each build stood on.
 */
function boundaryLabel(kind: SyncBoundaryDto['kind']): string {
  switch (kind) {
    case 'TARGET_WITH_FIXUP': return 'target with its fixup';
    case 'FIXUP': return 'after the fixup';
    default: return 'target with no fixup';
  }
}

function short(sha: string): string {
  return sha.length <= 7 ? sha : sha.slice(0, 7);
}
