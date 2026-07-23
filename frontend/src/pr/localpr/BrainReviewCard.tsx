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
import { MarkdownProse } from '../../threads/MarkdownProse';
import { ReviewThreadCard } from './ReviewThreadCard';
import { displayName } from './prViewMeta';

function roots(comments: LocalPRComment[]): LocalPRComment[] {
  return comments.filter(comment => comment.parentCommentId === null);
}

function threadFor(root: LocalPRComment, comments: LocalPRComment[]): LocalPRComment[] {
  return [root, ...comments.filter(comment => comment.parentCommentId === root.id)]
    .sort((a, b) => a.createdAt - b.createdAt);
}

/** A local brain pass rendered with the same anatomy as a GitHub review:
 *  verdict header, written review comments, then file/line thread cards. */
export function BrainReviewCard({
  pr, verdict, comments, findingCount, body,
}: {
  pr: LocalPR;
  verdict: string | null;
  comments: LocalPRComment[];
  findingCount: number;
  body: string | null;
}) {
  const rootComments = roots(comments);
  const prComments = rootComments.filter(comment => comment.scope === 'pr');
  const inlineComments = rootComments.filter(comment => comment.scope === 'file-line');
  const approved = verdict === 'approved' || verdict === 'APPROVED';
  const count = Math.max(findingCount, rootComments.length);

  return (
    <div className={`brain-pr-review-card${approved ? ' approved' : ''}`}>
      <div className="brain-pr-review-card__header">
        <strong>Brain left an adversarial code review</strong>
        <span className={`verdict-pill ${approved ? 'ok' : 'chg'}`}>
          {approved ? 'Approved' : verdict === null ? 'No verdict recorded' : 'Changes requested'}
        </span>
      </div>
      <div className="brain-pr-review-card__meta">
        {count > 0
          ? <>Adversarial review finished with {count} finding{count === 1 ? '' : 's'}</>
          : <>Adversarial review finished</>}
      </div>
      {body !== null && body.trim().length > 0 && (
        <div className="brain-pr-review-card__comment">
          <MarkdownProse text={body} />
        </div>
      )}
      {prComments.map(comment => (
        <div className="brain-pr-review-card__comment" key={comment.id}>
          <MarkdownProse text={comment.body} />
          {threadFor(comment, comments).slice(1).map(reply => (
            <div className="brain-pr-review-card__reply" key={reply.id}>
              <strong>{displayName(reply.author)}</strong> · <MarkdownProse text={reply.body} />
            </div>
          ))}
        </div>
      ))}
      {inlineComments.map(comment => (
        <ReviewThreadCard
          key={comment.id}
          pr={pr}
          filePath={comment.filePath ?? ''}
          lineNumber={comment.lineNumber ?? 0}
          comments={threadFor(comment, comments)}
          resolved={comment.resolvedAt !== null || comment.dismissedAt !== null}
        />
      ))}
    </div>
  );
}
