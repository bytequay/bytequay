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

/** A GitHub review thread. Private cards draw `LocalCard` instead and share none of this markup. */
export function ReviewThreadCard({ location, resolved, outdated = false, defaultOpen, code, children, controls }: {
  location: ReactNode;
  resolved: boolean;
  outdated?: boolean;
  defaultOpen?: boolean;
  code?: ReactNode;
  children: ReactNode;
  controls: ReactNode;
}) {
  if (resolved) {
    return <details className="pr-thread-row" open={defaultOpen ?? false}>
      <summary>
        <span className="pr-thread-chevron" aria-hidden="true">›</span>
        {location}
        <span className="pr-thread-row__resolved"><CircleCheck size={14} aria-hidden="true" /> Resolved</span>
      </summary>
      <div className="pr-thread-row__comments">
        {children}{controls}
      </div>
    </details>;
  }
  return <details className="pr-inline-thread" open={defaultOpen ?? true}>
    <summary>
      <span className="pr-thread-chevron" aria-hidden="true">›</span>
      {location}{outdated && <span className="pr-thread-outdated">Outdated</span>}
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
