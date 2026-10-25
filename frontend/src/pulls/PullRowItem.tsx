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
import Avatar from '../Avatar';
import { Bell, BellRing, GitMerge, GitPullRequest, LoaderCircle, MessageCircle } from 'lucide-react';
import type { PullRow } from './model';

export default function PullRowItem({ row, wide, selected, monitoring = false, onPick, onMonitor }: {
  row: PullRow;
  wide: boolean;
  selected: boolean;
  monitoring?: boolean;
  onPick: () => void;
  onMonitor: () => void;
}) {
  const monitorLabel = row.kind === 'merged' ? 'Merged PRs cannot be monitored'
    : monitoring ? 'Saving PR monitoring…' : row.monitored ? 'Stop monitoring PR' : 'Monitor PR';
  const MonitorIcon = monitoring ? LoaderCircle : row.monitored ? BellRing : Bell;
  return (
    <div className={`pull-card${wide ? ' pull-card--wide' : ''}`}>
      <button
        type="button"
        className={`pull-row${wide ? ' pull-row--wide' : ''}${selected ? ' is-selected' : ''}`}
        aria-pressed={selected}
        onClick={onPick}
      >
        {wide ? <WideRow row={row} /> : <NarrowRow row={row} />}
      </button>
      <button
        type="button"
        className={`pull-card__monitor${row.monitored ? ' is-active' : ''}`}
        title={monitorLabel}
        aria-label={`${monitorLabel}: ${row.repo} #${row.num}`}
        aria-pressed={row.monitored}
        aria-busy={monitoring}
        disabled={monitoring || row.kind === 'merged'}
        onClick={onMonitor}
      >
        <span className={`bq-spinner${monitoring ? ' is-spinning' : ''}`}><MonitorIcon size={16} aria-hidden="true" /></span>
      </button>
    </div>
  );
}

function WideRow({ row }: { row: PullRow }) {
  return (
    <>
      <div className="pull-row__primary">
        <Title row={row} />
        <Labels row={row} />
        <Meta row={row} />
      </div>
      <Status row={row} />
      <Changes row={row} />
      <span className="pull-row__comments"><CommentIcon />{row.comments}</span>
    </>
  );
}

function NarrowRow({ row }: { row: PullRow }) {
  return (
    <>
      <Title row={row} />
      <Labels row={row} />
      <div className="pull-row__stats">
        <Status row={row} />
        <Changes row={row} />
        <span className="pull-row__comments"><CommentIcon />{row.comments}</span>
      </div>
      <Meta row={row} />
    </>
  );
}

function Title({ row }: { row: PullRow }) {
  return (
    <span className="pull-row__title">
      <span className={`pull-row__state pull-row__state--${row.kind}`} aria-hidden="true">
        <PullRequestIcon merged={row.kind === 'merged'} />
      </span>
      <span>{row.title} <small>#{row.num}</small></span>
    </span>
  );
}

function Labels({ row }: { row: PullRow }) {
  if (row.labels.length === 0) return null;
  return (
    <span className="pull-row__labels">
      {row.labels.map(label => {
        const hex = row.labelColors?.[label];
        return (
          <span key={label} style={{ background: hex ? `#${hex}24` : undefined }}>
            {label}
          </span>
        );
      })}
    </span>
  );
}

function Meta({ row }: { row: PullRow }) {
  const owner = row.repo.split('/')[0] ?? row.repo;
  return (
    <span className="pull-row__meta">
      <Avatar login={owner} size={16} />
      <span className="pull-row__meta-name" title={row.repo}>{row.repo}</span>
      <span>·</span>
      <Avatar login={row.author} size={16} />
      <span className="pull-row__meta-name" title={row.author}>{row.author}</span>
      <span>·</span>
      <span className="pull-row__meta-time">{row.time}</span>
      {row.review && <><span>·</span><span className="pull-row__review">{row.review}</span></>}
    </span>
  );
}

function Status({ row }: { row: PullRow }) {
  if (row.status === null) return <span className="pull-row__ci">No CI</span>;
  const label = row.status === 'passing' ? 'Passed' : row.status === 'failing' ? 'Failed' : 'Running';
  return (
    <span className={`pull-row__ci is-${row.status}`}>
      <span aria-hidden="true">{row.status === 'passing' ? '✓' : row.status === 'failing' ? '✕' : '●'}</span>
      {label}
    </span>
  );
}

function Changes({ row }: { row: PullRow }) {
  return (
    <span className="pull-row__changes">
      {row.additions > 0 && <span>+{shortCount(row.additions)}</span>}
      {row.deletions > 0 && <span>−{shortCount(row.deletions)}</span>}
    </span>
  );
}

function shortCount(value: number) {
  return value >= 1000 ? `${Math.round(value / 100) / 10}k` : value;
}

export function PullRequestIcon({ merged = false }: { merged?: boolean }) {
  return merged ? <GitMerge size={16} /> : <GitPullRequest size={16} />;
}

function CommentIcon() {
  return <MessageCircle size={12} strokeWidth={1.8} aria-hidden="true" />;
}
