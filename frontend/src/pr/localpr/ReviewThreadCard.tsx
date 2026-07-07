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
import type { LocalPR, LocalPRComment } from '../../types/localPr';
import Avatar from '../../Avatar';
import { actorRole, agoLabel, displayName } from './prViewMeta';

/**
 * A file-line comment thread (U13d): a mono file-header bar, the root
 * comment plus its replies (avatar + name + badges + body), and a footer
 * offering resolve/dismiss. No diff snippet — that needs the diff itself,
 * which this milestone doesn't fetch yet (Code Diff / Files-changed page).
 */
export function ReviewThreadCard({
  pr, filePath, lineNumber, comments, resolved, onResolve, onDismiss,
}: {
  pr: LocalPR;
  filePath: string;
  lineNumber: number;
  /** Root comment first, then replies, oldest-first. */
  comments: LocalPRComment[];
  resolved: boolean;
  onResolve?: () => void;
  onDismiss?: () => void;
}) {
  return (
    <div className="pr-thread">
      <div className="th-file">▾ {filePath}:{lineNumber}</div>
      {comments.map(c => {
        const role = actorRole(c.author, pr);
        const pending = c.origin === 'local' && c.publishedAt === null;
        return (
          <div className="th-cmt" key={c.id}>
            <Avatar login={displayName(c.author)} size={22} className={`pr-avatar ${role === 'other' ? '' : role}`} />
            <div className="m">
              <div className="mh">
                <span className="who">{displayName(c.author)}</span> · {agoLabel(c.createdAt)}
                {role === 'agent' && <span className="pr-badge agent">Agent</span>}
                {role === 'author' && <span className="pr-badge author">Author</span>}
                {pending && <span className="pr-badge pending">Pending</span>}
              </div>
              <div className="mb">{c.body}</div>
            </div>
          </div>
        );
      })}
      <div className="th-foot">
        <span className="reply">Reply…</span>
        {!resolved && onResolve !== undefined && (
          <button type="button" className="btn sm" onClick={onResolve}>Resolve conversation</button>
        )}
        {!resolved && onDismiss !== undefined && (
          <button type="button" className="btn sm" onClick={onDismiss}>Discard draft</button>
        )}
      </div>
    </div>
  );
}
