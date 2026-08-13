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
import { ShieldIcon } from './WorkspaceSyncIcons';
import type { SyncPublishGateDto } from './workspaceApi';

/**
 * The publish boundary, as a decision.
 *
 * Everything before this is local and revocable; everything after it is
 * public. The run parks here and waits, which is not a convenience — it is the
 * gate. Authorizing sends back the exact revision and digests shown, so a run
 * that moved underneath the reader is refused rather than published.
 */
export default function WorkspaceSyncPublishCard({
  gate, branch, busy, onAuthorize,
}: {
  gate: SyncPublishGateDto;
  branch: string;
  busy: boolean;
  onAuthorize: () => void;
}) {
  return (
    <section className="sf-publish">
      <header>
        <span className="sf-publish__glyph" aria-hidden><ShieldIcon size={12} /></span>
        <strong>Ready to publish — nothing is pushed yet</strong>
      </header>
      <p>
        Pushes <code>{gate.branchRef ?? branch}</code>
        {gate.proposedHead !== null && (
          <> at <code title={gate.proposedHead}>{short(gate.proposedHead)}</code></>
        )}
        {gate.targetBaseRef !== null && (
          <> and opens a draft pull request against <code>{gate.targetBaseRef}</code></>
        )}.
      </p>
      <div className="sf-publish__actions">
        <button type="button" className="sf-publish__go" disabled={busy}
          onClick={onAuthorize}>
          {busy ? 'Authorizing…' : 'Authorize the first push'}
        </button>
        <span>revision {gate.revision} · this exact series and no other</span>
      </div>
    </section>
  );
}

function short(sha: string): string {
  return sha.length <= 7 ? sha : sha.slice(0, 7);
}
