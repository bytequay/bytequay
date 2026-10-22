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

import { ChevronDown, MessageSquare, type LucideIcon } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';

export type ReviewRequestTarget =
  | { kind: 'card'; runId: string; cardId: string }
  | { kind: 'finding'; runId?: string; findingId: string }
  | { kind: 'local'; threadId: string };
export type ReviewCardFocus = { target: ReviewRequestTarget; token: number };

export type ReviewRequest = {
  action: string;
  topic: string;
  body: string;
  detail?: string;
  generated?: boolean;
  turnId?: string | null;
  messageEventId?: string | null;
  target?: ReviewRequestTarget;
  icon?: LucideIcon;
};

/** The preview names the saved request; optional children supply its full details on expansion. */
export function ReviewRequestCard({ action, topic, body, detail, icon: Icon = MessageSquare, children, onOpen, prompt }: ReviewRequest & { children?: ReactNode; onOpen?: () => void; prompt?: string }) {
  const [open, setOpen] = useState(false);
  const contentId = useId();
  const toggle = () => setOpen(value => !value);
  return <article className={`agt-user__request agt-review-request${open ? ' is-open' : ''}`}>
    <div className="agt-review-request__bar">
      <button type="button" className="agt-review-request__open" onClick={onOpen ?? toggle}
        aria-label={onOpen ? `Open ${topic} in Summary` : `${action}: ${topic}`}
        title={onOpen ? 'Open in Summary' : 'Show request details'}>
        <Icon aria-hidden="true" />
        <span className="agt-review-request__heading">
          <span className="agt-user__request-action">{action}</span>
          <span className="agt-review-request__topic" title={topic}>{topic}</span>
        </span>
      </button>
      <button type="button" className="agt-review-request__toggle" aria-expanded={open} aria-controls={contentId}
        aria-label={`${open ? 'Hide' : 'Show'} request details: ${topic}`} onClick={toggle}>
        <ChevronDown aria-hidden="true" />
      </button>
    </div>
    {!open && <div className="agt-review-request__preview">{body}</div>}
    <div id={contentId} className="agt-review-request__content" hidden={!open}>
      {detail && <div className="agt-review-request__detail">{detail}</div>}
      {children ?? <div className="agt-review-request__body">{body}</div>}
      {prompt !== undefined && <details className="agt-review-request__prompt">
        <summary>Prompt sent to agent</summary>
        <div className="agt-review-request__body">{prompt}</div>
      </details>}
    </div>
  </article>;
}
