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
import { relativeTime } from '../relativeTime';
import type { GitCommitDto } from '../types';
import { shortSha } from './model';

export function CommitByline({ commit }: { commit: GitCommitDto }) {
  const sameActor = commit.author === commit.committer;
  return (
    <>
      <span
        aria-hidden="true"
        className={`cmt-commit-avatars${sameActor ? '' : ' is-pair'}`}
      >
        <Avatar login={commit.author} avatarUrl={commit.authorAvatarUrl} size={20} allowGitHubFallback={false} />
        {!sameActor && (
          <Avatar
            login={commit.committer}
            avatarUrl={commit.committerAvatarUrl}
            size={20}
            allowGitHubFallback={false}
          />
        )}
      </span>
      <span className="cmt-commit-byline">
        <strong>{commit.author}</strong>
        {sameActor ? ' committed ' : <>{' authored and '}<strong>{commit.committer}</strong>{' committed '}</>}
        <time
          dateTime={commit.committedAt}
          title={new Date(commit.committedAt).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'long' })}
        >
          {relativeTime(commit.committedAt, { style: 'long' })}
        </time>
      </span>
    </>
  );
}

type CommitCardProps = {
  commit: GitCommitDto;
  selected: boolean;
  viewed?: boolean;
  /** Range-picker rows: tighter padding and no checkbox, since picking is by row. */
  dense?: boolean;
  onSelect: () => void;
  onToggle?: () => void;
  onOpenPullRequest: (number: number) => void;
};

export default function CommitCard({
  commit,
  selected,
  viewed = false,
  dense = false,
  onSelect,
  onToggle,
  onOpenPullRequest,
}: CommitCardProps) {
  const pullRequestNumber = commit.pullRequestNumber ?? null;
  const pullRequestTitle = commit.pullRequestTitle ?? null;
  return (
    <div
      className={`cmt-commit-row${selected ? ' is-selected' : ''}${viewed ? ' is-viewed' : ''}${dense ? ' is-dense' : ''}`}
      onClick={onSelect}
    >
      {!dense && (
        <input
          type="checkbox"
          checked={selected}
          aria-label={`Select ${shortSha(commit.sha)}`}
          onClick={event => event.stopPropagation()}
          onChange={() => onToggle?.()}
        />
      )}
      <div className="cmt-commit-main">
        <div className="cmt-commit-title">{commit.title}</div>
        <div className="cmt-commit-meta">
          <CommitByline commit={commit} />
        </div>
        {pullRequestNumber !== null && (
          <button
            type="button"
            className="cmt-commit-pr"
            title={pullRequestTitle
              ? `Open PR #${pullRequestNumber}: ${pullRequestTitle}`
              : `Open PR #${pullRequestNumber}`}
            onClick={event => {
              event.stopPropagation();
              onOpenPullRequest(pullRequestNumber);
            }}
          >
            <span className="cmt-chip is-pr">⑃ #{pullRequestNumber}</span>
            <span className="cmt-commit-pr-title">
              {pullRequestTitle ?? 'View pull request'}
            </span>
          </button>
        )}
      </div>
      <button
        type="button"
        className="cmt-commit-sha"
        title="Copy full commit SHA"
        aria-label={`Copy full commit SHA ${commit.sha}`}
        onClick={event => {
          event.stopPropagation();
          void navigator.clipboard.writeText(commit.sha);
        }}
      >
        {shortSha(commit.sha)}
      </button>
    </div>
  );
}
