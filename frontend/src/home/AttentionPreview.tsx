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
import { useEffect, useState } from 'react';
import { ArrowUpRight, Check, Clock3, Play } from 'lucide-react';
import { getCached, setCached } from '../dataCache';
import MarkdownBody from '../pulls/MarkdownBody';
import ReferenceCard, { previewMarkdown, type HoverCard } from '../ReferenceCard';
import { ago } from '../relativeTime';
import type { PullRequestDetailDto } from '../types';
import { ATTENTION_KINDS, type AttentionItem } from './attention';

type Props = {
  item: AttentionItem;
  hover: HoverCard;
  onOpen: () => void;
  onAck: () => void;
  onStartIssue?: () => void;
  onSnooze: (button: HTMLElement) => void;
};

/** One cached detail read per pull request — hovering the same row again, or
 *  re-opening Home, never repeats it. Issues need none: the list payload
 *  already carries their body, comment count and last activity. */
function useDetail(item: AttentionItem): PullRequestDetailDto | null {
  const key = `attention-preview:${item.key}`;
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(
    () => getCached<PullRequestDetailDto>(key) ?? null);

  useEffect(() => {
    if (detail !== null || item.pr === null) return;
    const prId = item.pr.id;
    let cancelled = false;
    window.bridge.fetchPullRequestDetail(prId)
      .then(value => {
        setCached(key, value);
        if (!cancelled) setDetail(value);
      })
      .catch(() => { /* The card falls back to the list row's own fields. */ });
    return () => { cancelled = true; };
  }, [detail, item.pr, key]);

  return detail;
}

function byline(item: AttentionItem): string {
  const author = item.author ?? 'unknown';
  const updated = `updated ${ago(item.updatedAt)}`;
  if (item.type === 'issue') {
    return `${author} · opened ${ago(item.createdAt)} · assigned to you`;
  }
  if (item.pr?.origin === 'AUTHORED') return `your PR · ${updated}`;
  return `${author} · opened ${ago(item.createdAt)} · ${updated}`;
}

export default function AttentionPreview({ item, hover, onOpen, onAck, onSnooze, onStartIssue }: Props) {
  const detail = useDetail(item);
  const kind = ATTENTION_KINDS[item.kind];
  // A pull request body arrives with the detail; an issue already has it.
  const loading = item.type === 'pr' && detail === null;
  const body = previewMarkdown(item.type === 'issue' ? item.body : detail?.body);

  return (
    <ReferenceCard
      hover={hover}
      item={{
        type: item.type,
        repo: item.repo,
        number: item.number,
        title: item.title,
        author: item.author,
        authorAvatarUrl: item.authorAvatarUrl ?? detail?.authorAvatarUrl,
        createdAt: item.createdAt,
        updatedAt: item.updatedAt,
        labels: item.labels,
        chip: { label: kind.label, color: kind.color, background: kind.background },
        byline: byline(item),
        facts: item.type === 'issue' ? [
          { label: 'DISCUSSION', value: `${item.commentCount} comment${item.commentCount === 1 ? '' : 's'}` },
          { label: 'ACTIVITY', value: `last reply ${ago(item.updatedAt)}` },
        ] : undefined,
      }}
      body={loading ? null
        : body === '' ? <span className="ref-card__muted">No description.</span>
          : <MarkdownBody body={body} repository={item.repo} />}
      actions={<>
        {item.type === 'issue' && onStartIssue && (
          <button className="bq-button bq-button--primary" type="button" onClick={onStartIssue}>
            <Play aria-hidden="true" /> Start development
          </button>
        )}
        <button className="bq-button bq-button--ghost" type="button" onClick={onAck}>
          <Check aria-hidden="true" /> Ack
        </button>
        <button className="bq-button" type="button" onClick={event => onSnooze(event.currentTarget)}>
          <Clock3 aria-hidden="true" /> Snooze
        </button>
        <button className="bq-button" type="button" onClick={onOpen}>
          Open <ArrowUpRight aria-hidden="true" />
        </button>
      </>}
    />
  );
}
