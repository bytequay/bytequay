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
import type { LocalPRCommit } from '../../types/localPr';
import { commitSubject, formatShortSha } from '../../diff/commitDisplay';
import { formatRelativeTime } from '../utils';

/** The Commits tab's content — a plain read-only list (sha, message,
 *  author, +/- stats, relative time), unlike the compact Changes sidebar's
 *  interactive range-picker sidebar (built for the diff view's selection
 *  model, which doesn't apply here). */
export function CommitsList({ commits, author }: { commits: LocalPRCommit[]; author: string | null }) {
  return (
    <div className="pr-commits-tab">
      {commits.map(c => (
        <div className="pr-commits-tab__row" key={c.id}>
          <span className="sha">{formatShortSha(c.sha)}</span>
          <span className="pr-commits-tab__subject">{commitSubject(c.message)}</span>
          <span className="pr-commits-tab__meta">
            {author !== null && <span className="who">{author}</span>}
            {' · '}{formatRelativeTime(new Date(c.authoredAt).toISOString())}
            {(c.additions > 0 || c.deletions > 0) && (
              <>
                {' · '}
                <span className="add">+{c.additions}</span> <span className="del">−{c.deletions}</span>
              </>
            )}
          </span>
        </div>
      ))}
    </div>
  );
}
