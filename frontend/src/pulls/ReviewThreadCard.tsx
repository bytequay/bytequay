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
import { ChevronRight, CircleCheck } from 'lucide-react';
import { relativeTime } from '@bytequay/ui/relativeTime';

/** Shared frame; callers supply destination badges, code, messages and their own actions. */
export function ReviewThreadCard({ title, tone = 'github', icon, badges, chip, resolved, outdated = false, defaultOpen, code, children, controls, className, attributes }: {
  /** Omit for a timeline comment without a file/range disclosure. */
  title?: ReactNode;
  tone?: 'github' | 'purpose' | 'commit' | 'local';
  icon?: ReactNode;
  badges?: ReactNode;
  chip?: ReactNode;
  resolved: boolean;
  outdated?: boolean;
  defaultOpen?: boolean;
  code?: ReactNode;
  children: ReactNode;
  controls: ReactNode;
  className?: string;
  attributes?: Record<`data-${string}`, string>;
}) {
  const Frame = title === undefined ? 'article' : 'details';
  const githubClass = tone === 'github' && title !== undefined ? resolved ? ' pr-thread-row' : ' pr-inline-thread' : '';
  return <Frame className={`lrc is-${tone}${githubClass}${className ? ` ${className}` : ''}`}
    open={title === undefined ? undefined : defaultOpen ?? !resolved} data-review-closed={resolved || undefined} {...attributes}>
    {title !== undefined && <summary className="lrc__head">
      <ChevronRight className="lrc__chevron" size={14} strokeWidth={2} aria-hidden="true" />
      {icon}{title}
      {outdated && <span className="lrc__badge is-warn">Outdated</span>}
      {badges}
      {resolved && <span className="lrc__resolved"><CircleCheck size={14} aria-hidden="true" />Resolved</span>}
      {chip}
    </summary>}
    {code}{children}{controls}
  </Frame>;
}

export function ReviewThreadMessage({ avatar, author, tag, timestamp, menu, children, reactions, className = 'pr-thread-comment', attributes }: {
  avatar?: ReactNode;
  author: ReactNode;
  tag?: ReactNode;
  timestamp: string | null;
  menu?: ReactNode;
  children: ReactNode;
  reactions?: ReactNode;
  className?: string;
  attributes?: Record<`data-${string}`, string>;
}) {
  return <div className={`lrc-message${className ? ` ${className}` : ''}`} {...attributes}>
    <div className="lrc-message__head">
      {avatar}{author}{tag}<time>{relativeTime(timestamp)}</time>
      {menu && <div className="lrc-message__menu">{menu}</div>}
    </div>
    <div className="lrc-message__body">{children}</div>
    {reactions}
  </div>;
}
