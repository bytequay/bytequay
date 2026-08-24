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
import type { PullRow } from './model';

export default function PullRowItem({ row, wide, selected, onPick }: {
  row: PullRow;
  wide: boolean;
  selected: boolean;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      className={`pull-row${wide ? ' pull-row--wide' : ''}${selected ? ' is-selected' : ''}`}
      aria-pressed={selected}
      onClick={onPick}
    >
      {wide ? <WideRow row={row} /> : <NarrowRow row={row} />}
    </button>
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
  return merged ? (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="5.5" r="2.4" /><circle cx="6" cy="18.5" r="2.4" />
      <circle cx="18" cy="12" r="2.4" /><path d="M6 8v8M6 8a7.5 7.5 0 0 0 7.5 4H15" />
    </svg>
  ) : (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="5.5" r="2.4" /><circle cx="6" cy="18.5" r="2.4" />
      <circle cx="18" cy="18.5" r="2.4" /><path d="M6 8v8M11.5 5.5H15a3 3 0 0 1 3 3V16" />
    </svg>
  );
}

function CommentIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.3-.7L3 21l1.8-5.7a8.4 8.4 0 1 1 16.2-3.8z" />
    </svg>
  );
}
