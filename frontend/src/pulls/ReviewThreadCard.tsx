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
import { CircleCheck } from 'lucide-react';
import { relativeTime } from '../relativeTime';

export function ReviewThreadCard({ location, resolved, outdated = false, localId, privateLabel, defaultOpen, code, children, controls }: {
  location: ReactNode;
  resolved: boolean;
  outdated?: boolean;
  localId?: string;
  privateLabel?: string;
  defaultOpen?: boolean;
  code?: ReactNode;
  children: ReactNode;
  controls: ReactNode;
}) {
  const local = localId !== undefined || privateLabel !== undefined;
  const label = local && <span className="pr-local-review__label">{privateLabel ?? 'Local review · Private'}</span>;
  const old = outdated && <span className="pr-thread-outdated">Outdated</span>;
  if (resolved) {
    return <details className={`pr-thread-row${local ? ' is-local-review' : ''}`} data-local-review-thread={localId} open={defaultOpen ?? false}>
      <summary>
        <span className="pr-thread-chevron" aria-hidden="true">›</span>
        {location}{label}{local && old}
        <span className="pr-thread-row__resolved"><CircleCheck size={14} aria-hidden="true" /> Resolved</span>
      </summary>
      <div className="pr-thread-row__comments">
        {local && code}{children}{controls}
      </div>
    </details>;
  }
  return <details className={`pr-inline-thread${local ? ' is-local-review' : ''}`} data-local-review-thread={localId} open={defaultOpen ?? true}>
    <summary>
      <span className="pr-thread-chevron" aria-hidden="true">›</span>
      {location}{label}{old}
    </summary>
    {code}{children}{controls}
  </details>;
}

export function ReviewThreadMessage({ author, timestamp, menu, children, reactions }: {
  author: ReactNode;
  timestamp: string | null;
  menu?: ReactNode;
  children: ReactNode;
  reactions?: ReactNode;
}) {
  return <div className="pr-thread-comment">
    <div className="pr-thread-comment__author">
      {author}<time>{relativeTime(timestamp)}</time>
      <div className="pr-thread-comment__menu">{menu}</div>
    </div>
    {children}{reactions}
  </div>;
}
