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
import Avatar from '../Avatar';
import { getCached, setCached } from '../dataCache';
import MarkdownBody from '../pulls/MarkdownBody';
import RepoAvatar from '../RepoAvatar';
import type { PullRequestDetailDto } from '../types';
import { ago, anchorPanel, ATTENTION_KINDS, previewMarkdown, type AttentionItem } from './attention';

const CARD_WIDTH = 428;
const CARD_HEIGHT = 400;

type Props = {
  item: AttentionItem;
  anchor: DOMRect;
  onHold: () => void;
  onRelease: () => void;
  onOpen: () => void;
  onAck: () => void;
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

type Cell = { label: string; value: string; tone?: 'good' | 'bad' };

function cells(item: AttentionItem, detail: PullRequestDetailDto | null): Cell[] {
  if (item.type === 'issue') {
    return [
      {
        label: 'DISCUSSION',
        value: `${item.commentCount} comment${item.commentCount === 1 ? '' : 's'}`,
      },
      { label: 'ACTIVITY', value: `last reply ${ago(item.updatedAt)}` },
    ];
  }
  const pr = item.pr;
  const runs = detail?.checkRuns ?? [];
  const failed = runs.filter(run => run.category === 'failing').length;
  const failing = failed > 0 || pr?.ciStatus === 'FAILING';
  const files = detail?.changedFiles ?? null;
  // The list row leaves additions/deletions at 0 for pull requests it never
  // expanded; the detail we already fetched has the real counts.
  const additions = detail?.additions ?? pr?.additions ?? 0;
  const deletions = detail?.deletions ?? pr?.deletions ?? 0;
  return [
    {
      label: 'CHECKS',
      value: runs.length === 0
        ? (failing ? 'Checks failing' : 'No checks reported')
        : failed > 0
          ? `${failed} of ${runs.length} checks failing`
          : `${runs.length} check${runs.length === 1 ? '' : 's'} passed`,
      tone: failing ? 'bad' : 'good',
    },
    {
      label: 'CHANGES',
      value: `+${additions.toLocaleString()}  −${deletions.toLocaleString()}  `
        + (files === null ? 'files loading…' : `in ${files} file${files === 1 ? '' : 's'}`),
    },
  ];
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
  item, anchor, onHold, onRelease, onOpen, onAck, onSnooze,
}: Props) {
  const detail = useDetail(item);
  const kind = ATTENTION_KINDS[item.kind];
  const [owner, repo] = item.repo.split('/');
  // A pull request body arrives with the detail; an issue already has it.
  const loading = item.type === 'pr' && detail === null;
  const body = previewMarkdown(item.type === 'issue' ? item.body : detail?.body);
  const labels = item.labels.slice(0, 3);
  const { top, left } = anchorPanel(anchor, CARD_WIDTH, CARD_HEIGHT, anchor.left + 40);

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

      <div className="atn-preview__grid">
        {cells(item, detail).map(cell => (
          <div className="atn-preview__cell" key={cell.label}>
            <div className="atn-preview__label">{cell.label}</div>
            <div className={`atn-preview__value${cell.tone ? ` is-${cell.tone}` : ''}`}>
              {cell.value}
            </div>
          </div>
        ))}
      </div>

      <div className="atn-preview__foot">
        <div className="atn-preview__label">
          {item.type === 'issue' ? 'ISSUE DESCRIPTION' : 'PULL REQUEST DESCRIPTION'}
        </div>
        <div className={`atn-preview__body${body === '' && !loading ? ' is-empty' : ''}`}>
          {loading ? null : body === '' ? 'No description.' : <MarkdownBody body={body} />}
        </div>
        <div className="atn-preview__actions">
          {labels.map(label => (
            <span className="atn-preview__tag" key={label}>{label}</span>
          ))}
          <div className="atn-preview__spacer" />
          <button className="atn-preview__btn is-ack" type="button" onClick={onAck}>✓ Ack</button>
          <button
            className="atn-preview__btn is-snooze"
            type="button"
            onClick={event => onSnooze(event.currentTarget)}
          >
            ◷ Snooze
          </button>
          <button className="atn-preview__btn is-open" type="button" onClick={onOpen}>Open ›</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
