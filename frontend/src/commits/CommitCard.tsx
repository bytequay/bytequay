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
import { useEffect, useId, useState } from 'react';
import { Check, Copy, GitPullRequest } from 'lucide-react';
import Avatar from '../Avatar';
import { useHoverCard } from '../ReferenceCard';
import { ReferencePreviewCard, useReferencePreview } from '../pulls/MarkdownBody';
import type { GitHubReference } from '../githubReference';
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

/**
 * The pull request's own title, which only previews. Jumping to the pull
 * request is the number's job, so a reader can read the description without
 * leaving the commit list.
 */
function PullRequestTitle({ repo, number, title }: { repo: string; number: number; title: string }) {
  const previewId = useId();
  const hover = useHoverCard();
  const open = hover.anchor !== null;
  const [owner, name] = repo.split('/');
  const reference: GitHubReference = { kind: 'pull', owner, repo: name, number, href: `https://github.com/${owner}/${name}/pull/${number}` };
  // Nothing is fetched for a title the reader only passed over.
  const preview = useReferencePreview(reference, open);
  return <>
    <span
      className="cmt-commit-pr-title"
      aria-describedby={open ? previewId : undefined}
      onMouseEnter={event => hover.show(event.currentTarget)}
      onMouseLeave={() => hover.hide()}
      onClick={event => event.stopPropagation()}
    >
      {title}
    </span>
    <ReferencePreviewCard id={previewId} hover={hover} reference={reference} preview={preview} />
  </>;
}

/** Copying is silent until it happens: no hover state, just a word once it has. */
function CopySha({ sha }: { sha: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  return (
    <button
      type="button"
      className={`cmt-commit-sha${copied ? ' is-copied' : ''}`}
      aria-label={copied ? `Copied commit SHA ${sha}` : `Copy full commit SHA ${sha}`}
      onClick={event => {
        event.stopPropagation();
        void navigator.clipboard.writeText(sha).then(() => setCopied(true)).catch(() => {});
      }}
    >
      {copied
        ? <><span>Copied</span><Check size={12} strokeWidth={2} aria-hidden="true" /></>
        : <><span>{shortSha(sha)}</span><Copy size={12} strokeWidth={2} aria-hidden="true" /></>}
    </button>
  );
}

type CommitCardProps = {
  commit: GitCommitDto;
  selected: boolean;
  viewed?: boolean;
  /** Range-picker rows: tighter padding and no checkbox, since picking is by row. */
  dense?: boolean;
  /** `owner/repo` the commit's pull requests live in; without it the title cannot preview. */
  pullRequestRepo?: string | null;
  onSelect: () => void;
  onToggle?: () => void;
  onOpenPullRequest: (number: number) => void;
};

export default function CommitCard({
  commit,
  selected,
  viewed = false,
  dense = false,
  pullRequestRepo = null,
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
          <div className="cmt-commit-pr">
            <button
              type="button"
              className="cmt-chip is-pr"
              title={`Open pull request #${pullRequestNumber}`}
              onClick={event => {
                event.stopPropagation();
                onOpenPullRequest(pullRequestNumber);
              }}
            >
              <GitPullRequest size={12} strokeWidth={2} aria-hidden="true" />
              #{pullRequestNumber}
            </button>
            {pullRequestTitle !== null && (pullRequestRepo
              ? <PullRequestTitle repo={pullRequestRepo} number={pullRequestNumber} title={pullRequestTitle} />
              : <span className="cmt-commit-pr-title">{pullRequestTitle}</span>)}
          </div>
        )}
      </div>
      <CopySha sha={commit.sha} />
    </div>
  );
}
