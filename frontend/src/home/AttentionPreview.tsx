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
import { createPortal } from 'react-dom';
import { ArrowUpRight, Check, Clock3, Play } from 'lucide-react';
import Avatar from '../Avatar';
import { getCached, setCached } from '../dataCache';
import MarkdownBody from '../pulls/MarkdownBody';
import RepoAvatar from '../RepoAvatar';
import type { PullRequestDetailDto } from '../types';
import { ago, anchorPanel, ATTENTION_KINDS, previewMarkdown, type AttentionItem } from './attention';

const CARD_WIDTH = 500;
const CARD_HEIGHT = { pr: 400, issue: 460 };

type Props = {
  item: AttentionItem;
  anchor: DOMRect;
  onHold: () => void;
  onRelease: () => void;
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

export default function AttentionPreview({
  item, anchor, onHold, onRelease, onOpen, onAck, onSnooze, onStartIssue,
}: Props) {
  const detail = useDetail(item);
  const kind = ATTENTION_KINDS[item.kind];
  const [owner, repo] = item.repo.split('/');
  // A pull request body arrives with the detail; an issue already has it.
  const loading = item.type === 'pr' && detail === null;
  const body = previewMarkdown(item.type === 'issue' ? item.body : detail?.body);
  const labels = item.labels.slice(0, 3);
  const { top, left } = anchorPanel(anchor, CARD_WIDTH, CARD_HEIGHT[item.type], anchor.left + 40);

  return createPortal(
    <div
      className="atn-preview"
      style={{ top, left }}
      onMouseEnter={onHold}
      onMouseLeave={onRelease}
    >
      <div className="atn-preview__head">
        <div className="atn-preview__ref">
          <RepoAvatar owner={owner} repo={repo ?? ''} size={16} radius={4} />
          <span className="atn-preview__meta">{item.repo} #{item.number}</span>
          <span
            className="atn-chip"
            style={{ color: kind.color, background: kind.background }}
          >
            {kind.label}
          </span>
        </div>
        <div className="atn-preview__title">{item.title}</div>
        <div className="atn-preview__byline">
          <Avatar login={item.author ?? ''} avatarUrl={item.authorAvatarUrl ?? detail?.authorAvatarUrl} size={19} />
          <span>{byline(item)}</span>
        </div>
      </div>

      {item.type === 'issue' && (
        <div className="atn-preview__grid">
          <div className="atn-preview__cell">
            <div className="atn-preview__label">DISCUSSION</div>
            <div className="atn-preview__value">
              {item.commentCount} comment{item.commentCount === 1 ? '' : 's'}
            </div>
          </div>
          <div className="atn-preview__cell">
            <div className="atn-preview__label">ACTIVITY</div>
            <div className="atn-preview__value">last reply {ago(item.updatedAt)}</div>
          </div>
        </div>
      )}

      <div className={`atn-preview__foot${item.type === 'pr' ? ' is-pr' : ''}`}>
        {item.type === 'issue' && <div className="atn-preview__label">ISSUE DESCRIPTION</div>}
        <div className={`atn-preview__body${body === '' && !loading ? ' is-empty' : ''}`}>
          {loading ? null : body === '' ? 'No description.' : <MarkdownBody body={body} repository={item.repo} />}
        </div>
        <div className="atn-preview__actions" role="group" aria-label={`Actions for #${item.number}`}>
          {labels.map(label => (
            <span className="atn-preview__tag" key={label}>{label}</span>
          ))}
          <div className="atn-preview__spacer" />
          {item.type === 'issue' && onStartIssue && (
            <button className="bq-button bq-button--primary" type="button" onClick={onStartIssue}>
              <Play aria-hidden="true" /> Start development
            </button>
          )}
          <button className="bq-button bq-button--ghost" type="button" onClick={onAck}>
            <Check aria-hidden="true" /> Ack
          </button>
          <button
            className="bq-button"
            type="button"
            onClick={event => onSnooze(event.currentTarget)}
          >
            <Clock3 aria-hidden="true" /> Snooze
          </button>
          <button className="bq-button" type="button" onClick={onOpen}>
            Open <ArrowUpRight aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
