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

import { ChevronDown, MessageSquare } from 'lucide-react';
import type { ReactNode } from 'react';

export type ReviewRequest = {
  action: string;
  topic: string;
  body: string;
  detail?: string;
  turnId?: string | null;
  messageEventId?: string | null;
};

/** Only saved user text belongs in this card; the provider's context prompt stays out of the UI. */
export function ReviewRequestCard({ action, topic, body, detail, children }: ReviewRequest & { children?: ReactNode }) {
  return <details className="agt-user__request agt-review-request">
    <summary>
      <MessageSquare aria-hidden="true" />
      <span className="agt-review-request__heading">
        <span className="agt-user__request-action">{action}</span>
        <span className="agt-review-request__topic" title={topic}>{topic}</span>
      </span>
      <ChevronDown className="agt-review-request__chevron" aria-hidden="true" />
      <span className="agt-review-request__preview">{body}</span>
    </summary>
    <div className="agt-review-request__content">
      {detail && <div className="agt-review-request__detail">{detail}</div>}
      {children ?? <div className="agt-review-request__body">{body}</div>}
    </div>
  </details>;
}
