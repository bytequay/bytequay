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
import type { PullRequestDto } from '../types';
import { PullRequestStateIcon } from './PullRequestStateIcon';

export function PullRequestChip({ pullRequest, pressed, onClick }: {
  pullRequest: Pick<PullRequestDto, 'number' | 'title' | 'state' | 'draft'>;
  pressed: boolean;
  onClick: () => void;
}) {
  return <button type="button" className="agt-pr-chip" aria-pressed={pressed}
    title={`#${pullRequest.number} ${pullRequest.title}`} onClick={onClick}>
    <PullRequestStateIcon pullRequest={pullRequest} size={16} />
    PR <span className="agt-pr-chip__number">#{pullRequest.number}</span>
  </button>;
}
