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
import { useEffect, useState, type ReactNode } from 'react';
import { ExternalLink, GitMerge } from 'lucide-react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type { PullRequestDto } from '../types';

/** `#123`, `123`, or a link to a pull request of `slug`; anything else is ordinary search text. */
export function typedPullRequestNumber(query: string, slug: string | null): number | null {
  const value = query.trim();
  const link = /^https:\/\/github\.com\/([^/]+\/[^/]+)\/pull\/(\d+)/i.exec(value);
  if (link) return slug !== null && link[1].toLowerCase() === slug.toLowerCase() ? Number(link[2]) : null;
  const number = /^#?(\d+)$/.exec(value);
  return number ? Number(number[1]) : null;
}

function merged(pr: PullRequestDto) {
  return `${pr.author ?? 'unknown'} · merged ${relativeTime(pr.mergedAt ?? pr.updatedAt)}`;
}

/** Merged pull requests of one repository, from the local list repository sync keeps. */
export default function PullRequestsView({ workspaceId, slug, query, selected, listHeader, onSelect, onOpenPullRequest }: {
  /** The workspace whose repository the pull requests belong to. */
  workspaceId: string;
  slug: string | null;
  query: string;
  selected: number | null;
  listHeader: ReactNode;
  onSelect: (number: number) => void;
  onOpenPullRequest: (number: number) => void;
}) {
  const [rows, setRows] = useState<PullRequestDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const typed = typedPullRequestNumber(query, slug);
  const search = typed !== null ? String(typed) : query.trim();

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      window.bridge.listMergedPullRequests(workspaceId, search)
        .then(next => { if (active) { setRows(next); setError(null); } })
        .catch(failure => { if (active) setError(failure instanceof Error ? failure.message : String(failure)); });
    }, 200);
    return () => { active = false; window.clearTimeout(timer); };
  }, [workspaceId, search]);

  const chosen = rows?.find(row => row.number === selected) ?? null;
  // Repository sync keeps about a month of merged pull requests; an older one is still one number away.
  const offerTyped = typed !== null && rows !== null && !rows.some(row => row.number === typed);

  return (
    <div className="cmt-history">
      <div className="cmt-history-left">
        {listHeader}
        <div className="cmt-commit-list">
          {error && <div className="cmt-error" role="alert">{error}</div>}
          {rows === null && error === null && <div className="cmt-loading">Loading merged pull requests…</div>}
          {rows !== null && (rows.length > 0 || offerTyped) && (
            <ul className="cmt-day-card cmt-merged-list" aria-label={`Merged pull requests in ${slug ?? 'this repository'}`}>
              {offerTyped && (
                <li>
                  <button type="button" className={`cmt-merged-row${selected === typed ? ' is-selected' : ''}`}
                    aria-pressed={selected === typed} onClick={() => onSelect(typed)}>
                    <GitMerge className="cmt-merged-icon" size={14} strokeWidth={2} aria-hidden="true" />
                    <span className="cmt-merged-main">
                      <span className="cmt-merged-title">#{typed}</span>
                      <span className="cmt-merged-meta">Not in the recent list · read from GitHub when you pick it</span>
                    </span>
                  </button>
                </li>
              )}
              {rows.map(pr => (
                <li key={pr.number}>
                  <button type="button" className={`cmt-merged-row${selected === pr.number ? ' is-selected' : ''}`}
                    aria-pressed={selected === pr.number} onClick={() => onSelect(pr.number)}>
                    <GitMerge className="cmt-merged-icon" size={14} strokeWidth={2} aria-hidden="true" />
                    <span className="cmt-merged-main">
                      <span className="cmt-merged-title">{pr.title}</span>
                      <span className="cmt-merged-meta">#{pr.number} · {merged(pr)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {rows !== null && rows.length === 0 && !offerTyped && (
            <div className="cmt-loading">
              {query.trim() !== ''
                ? `No merged pull requests match “${query.trim()}”.`
                : 'No merged pull requests in the last month. Type a #number or paste a link to pick an older one.'}
            </div>
          )}
        </div>
      </div>
      <div className="cmt-history-right">
        {selected === null
          ? <div className="cmt-loading">Pick a merged pull request to cherry-pick the commits it landed.</div>
          : (
            <div className="cmt-merged-detail">
              <div className="cmt-merged-detail-title">{chosen?.title ?? `Pull request #${selected}`}</div>
              <div className="cmt-merged-meta">{slug}#{selected}{chosen ? ` · ${merged(chosen)}` : ''}</div>
              <p className="cmt-merged-note">ByteQuay proves which commits this pull request landed, then previews exactly those.</p>
              <button type="button" className="cmt-strip-btn" onClick={() => onOpenPullRequest(selected)}>
                <ExternalLink size={12} strokeWidth={2} aria-hidden="true" />
                Open pull request
              </button>
            </div>
          )}
      </div>
    </div>
  );
}
