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
import { shortSha } from '@bytequay/ui/displayText';
import type { ReactNode } from 'react';
import { GitCommitHorizontal, Lock, Target } from 'lucide-react';
import { shortPath } from '../agent-review/model';
import { ReviewThreadCard, ReviewThreadMessage } from '../ReviewThreadCard';

/** A change's intention, one commit's design notes, or a review comment about code. */
export type LocalCardTone = 'purpose' | 'commit' | 'local';

/**
 * Private destination and tone on the shared review frame. Actions stay with each caller.
 */
export default function LocalCard({ tone = 'local', compact = false, title, badges, chip, resolved = false, outdated = false, open, code, children, controls, attributes }: {
  tone?: LocalCardTone;
  /** The quiet frame findings and private threads on code share. */
  compact?: boolean;
  title: ReactNode;
  badges?: ReactNode;
  chip?: string;
  resolved?: boolean;
  outdated?: boolean;
  /** Resolved cards start folded, like resolved GitHub threads. */
  open?: boolean;
  code?: ReactNode;
  children: ReactNode;
  controls: ReactNode;
  attributes?: Record<`data-${string}`, string>;
}) {
  const Icon = tone === 'purpose' ? Target : tone === 'commit' ? GitCommitHorizontal : null;
  return <ReviewThreadCard tone={tone} className={compact ? 'is-compact' : undefined} title={title} badges={badges} resolved={resolved} outdated={outdated}
    defaultOpen={open} code={code} controls={controls} attributes={attributes}
    icon={Icon !== null && <Icon className="lrc__icon" size={16} strokeWidth={2} aria-hidden="true" />}
    chip={<span className="lrc__chip"><Lock size={11} strokeWidth={2} aria-hidden="true" />{chip ?? (tone === 'local' ? 'Local' : 'Private')}</span>}>
    {children}
  </ReviewThreadCard>;
}

export function LocalCardMessage({ avatar, author, tag, timestamp, menu, children, attributes }: {
  avatar: ReactNode;
  author: string;
  tag?: ReactNode;
  timestamp: string | null;
  menu?: ReactNode;
  children: ReactNode;
  attributes?: Record<`data-${string}`, string>;
}) {
  return <ReviewThreadMessage className="" avatar={avatar} author={<strong>{author}</strong>} tag={tag} timestamp={timestamp} menu={menu} attributes={attributes}>
    {children}
  </ReviewThreadMessage>;
}

/** A private card on code names its file and lines the same way on every surface. */
export function CodeCardTitle({ path, range }: { path: string; range: string }) {
  return <code className="lrc__title" title={path}>{shortPath(path)} <span className="lrc__range">{range}</span></code>;
}

/** A summary card names what it explains; a commit's own title is already on its timeline row. */
export function SummaryCardTitle({ sha, tooltip }: { sha?: string; tooltip?: string }) {
  return sha === undefined
    ? <span className="lrc__title" title={tooltip}>Change intention</span>
    : <span className="lrc__title" title={tooltip}>Design &amp; implementation <code>{shortSha(sha)}</code></span>;
}
