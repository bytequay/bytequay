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
import type { LocalPRStatus } from '../../types/localPr';
import { MergeBranchIcon } from '../../ui/TaskBrainDesignIcons';

/** Solid GitHub-parity state pill (U13a): Open green, Merged purple, Local
 *  amber with a lock (task-origin, still pre-push), Closed gray. One
 *  component, `status` in — color out; no caller re-derives this mapping. */
export function StatePill({ status }: { status: LocalPRStatus }) {
  if (status === 'merged') {
    return <span className="pr-state-pill merged"><MergeBranchIcon size={12} strokeWidth={2.2} />Merged</span>;
  }
  if (status === 'closed') {
    return <span className="pr-state-pill closed">Closed</span>;
  }
  if (status === 'local-drafted' || status === 'local-open') {
    return <span className="pr-state-pill local">🔒 Local · {status === 'local-open' ? 'Open' : 'Drafting'}</span>;
  }
  // remote-drafted / remote-open — GitHub still calls a draft PR "open".
  return <span className="pr-state-pill open">⎇ Open{status === 'remote-drafted' ? ' · Draft' : ''}</span>;
}
