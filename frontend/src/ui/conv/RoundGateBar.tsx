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
import type { ReviewRoundDto } from '../../types/brainView';

/**
 * The round's posting gate (plan-rail-runs.md R12-R13) — commits + drafted
 * replies ready, nothing reaches GitHub until the user approves. Renders
 * nothing outside {@code awaiting_gate} (per-round render owns the check via
 * `round.status === 'awaiting_gate'`, mirrored here so a stray render is a
 * no-op rather than a dangling gate).
 */
export function RoundGateBar({ round, busy = false, onApprove }: {
  round: ReviewRoundDto;
  busy?: boolean;
  onApprove: () => void;
}) {
  if (round.status !== 'awaiting_gate') return null;
  const { fixed, replied } = round.stats;
  return (
    <div className="round-gate-bar">
      <span className="rgb-glyph" aria-hidden>◆</span>
      <span className="rgb-text">
        {fixed} fix commit{fixed === 1 ? '' : 's'} + {replied} drafted repl{replied === 1 ? 'y' : 'ies'} ready
        · review before anything posts to GitHub
      </span>
      <button type="button" className="rgb-approve" onClick={onApprove} disabled={busy}>
        {busy ? 'Posting…' : 'Approve & post'}
      </button>
    </div>
  );
}
