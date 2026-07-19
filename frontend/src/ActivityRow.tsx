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
import Avatar from './Avatar';
import {
  commitsUrl,
  followingNarrativeSegments,
  issueUrl,
  prUrl,
  repoUrl,
  type NarrativeSegment,
} from './activityNarrative';
import type { RecentEventDto } from './types';

/** Renders a list of narrative segments. Linked segments become
 *  borderless link-buttons that fire {@link onLinkClick} (so the host
 *  component decides between an in-app jump vs. an external open). */
export function NarrativeText({
  segments,
  onLinkClick,
}: {
  segments: NarrativeSegment[];
  onLinkClick: (url: string) => void;
}) {
  return (
    <>
      {segments.map((s, i) => (
        s.url
          ? (
            <button
              key={i}
              type="button"
              className={`home-following-item__link${s.emphasized ? ' home-following-item__object' : ''}`}
              onClick={event => {
                event.stopPropagation();
                if (s.url) onLinkClick(s.url);
              }}
              title={s.url}
            >
              {s.text}
            </button>
          )
          : <span key={i} className={s.emphasized ? 'home-following-item__object' : undefined}>{s.text}</span>
      ))}
    </>
  );
}

type Props = {
  event: RecentEventDto;
  /** Optional actor avatar + login. When omitted (own-activity card),
   *  the row uses the prebuilt actor block from the parent. */
  actor: { login: string; profileUrl: string; avatarUrl?: string | null } | null;
  /** Whether to show the actor login as a clickable name before the
   *  narrative. The "your recent activity" card hides this since every
   *  row is implicitly the user. */
  showActorName: boolean;
  formatTime: (createdAt: string) => string;
  onOpenUrl: (url: string) => void;
};

/**
 * One row in either home-page activity card. Single component so a
 * forgotten reference (e.g. the {@code followingNarrative is not defined}
 * regression that hit production after a rename) breaks both cards
 * uniformly — and is caught by the ActivityRow render test.
 */
function ActivityRow({ event, actor, showActorName, formatTime, onOpenUrl }: Props) {
  const segments = followingNarrativeSegments(event).map((segment, index) => (
    {
      ...segment,
      text: !showActorName && index === 0
        ? segment.text.charAt(0).toUpperCase() + segment.text.slice(1)
        : !showActorName && segment.text === event.repo && event.actorLogin
          && event.repo.startsWith(`${event.actorLogin}/`)
            ? event.repo.slice(event.repo.indexOf('/') + 1)
            : segment.text,
    }
  ));
  const detail = activityDetail(event);
  const targetUrl = activityUrl(event);
  const openEvent = () => onOpenUrl(targetUrl);
  return (
    <div
      className="home-following-item"
      role="button"
      tabIndex={0}
      onClick={openEvent}
      onKeyDown={keyboardEvent => {
        if (keyboardEvent.target !== keyboardEvent.currentTarget) return;
        if (keyboardEvent.key === 'Enter' || keyboardEvent.key === ' ') {
          keyboardEvent.preventDefault();
          openEvent();
        }
      }}
    >
      {showActorName && actor ? (
        <button
          className="home-following-item__actor"
          onClick={clickEvent => {
            clickEvent.stopPropagation();
            onOpenUrl(actor.profileUrl);
          }}
          type="button"
        >
          <Avatar
            login={actor.login}
            avatarUrl={actor.avatarUrl}
            size={24}
            className="home-following-item__avatar"
          />
        </button>
      ) : (
        <ActivityIcon type={event.type} />
      )}
      <div className="home-following-item__body">
        <div className="home-following-item__text">
          {showActorName && actor && (
            <>
              <button
                className="home-following-item__name"
                onClick={clickEvent => {
                  clickEvent.stopPropagation();
                  onOpenUrl(actor.profileUrl);
                }}
                type="button"
              >
                {actor.login}
              </button>
              {' '}
            </>
          )}
          <span className="home-following-item__verb">
            <NarrativeText segments={segments} onLinkClick={onOpenUrl} />
          </span>
        </div>
        {detail && (
          <div className="home-following-item__detail" title={detail}>
            {detail}
          </div>
        )}
      </div>
      <time className="home-following-item__time">{formatTime(event.createdAt)}</time>
    </div>
  );
}

function activityDetail(event: RecentEventDto): string {
  if (event.detail) return event.detail;
  switch (event.type) {
    case 'PushEvent':
      return `${event.commitCount || 1} commit${event.commitCount === 1 ? '' : 's'}`;
    case 'CreateEvent':
      return event.ref ? `Branch ${event.ref}` : 'Repository created';
    case 'PullRequestEvent':
      return event.prNumber > 0
        ? `PR #${event.prNumber} ${event.action ?? 'updated'}`
        : event.prTitle ?? 'Pull request updated';
    case 'PullRequestReviewEvent':
      return event.prNumber > 0 ? `Review submitted on PR #${event.prNumber}` : 'Pull request review submitted';
    case 'PullRequestReviewCommentEvent':
      return event.prNumber > 0 ? `Review comment on PR #${event.prNumber}` : 'Pull request review comment';
    case 'IssueCommentEvent':
    case 'IssuesEvent':
      return event.prTitle ?? (event.prNumber > 0 ? `Issue #${event.prNumber}` : 'Issue updated');
    case 'WatchEvent':
      return 'Repository starred';
    case 'ForkEvent':
      return 'Repository forked';
    default:
      return event.prTitle ?? event.type.replace(/Event$/, '').replace(/([a-z])([A-Z])/g, '$1 $2');
  }
}

function activityUrl(event: RecentEventDto): string {
  if (event.type === 'PushEvent') {
    return event.prNumber > 0 ? prUrl(event.repo, event.prNumber) : commitsUrl(event.repo);
  }
  if (event.type === 'PullRequestEvent'
      || event.type === 'PullRequestReviewEvent'
      || event.type === 'PullRequestReviewCommentEvent') {
    return event.prNumber > 0 ? prUrl(event.repo, event.prNumber) : repoUrl(event.repo);
  }
  if (event.type === 'IssueCommentEvent' || event.type === 'IssuesEvent') {
    return event.prNumber > 0 ? issueUrl(event.repo, event.prNumber) : repoUrl(event.repo);
  }
  return repoUrl(event.repo);
}

function ActivityIcon({ type }: { type: string }) {
  const kind = type === 'PushEvent'
    ? 'commit'
    : type === 'CreateEvent'
      ? 'branch'
      : type === 'PullRequestReviewEvent' || type === 'PullRequestReviewCommentEvent'
        ? 'review'
        : type === 'PullRequestEvent'
          ? 'pr'
          : 'sync';
  return (
    <span className={`home-activity-icon home-activity-icon--${kind}`} aria-hidden="true">
      {kind === 'commit' && <svg viewBox="0 0 24 24"><path d="M2.8 12h5.3M15.9 12h5.3" /><circle cx="12" cy="12" r="3.3" /></svg>}
      {kind === 'branch' && <svg viewBox="0 0 24 24"><path d="M6 3v12M18 8a9 9 0 0 1-9 9" /><circle cx="6" cy="18" r="2.3" /><circle cx="18" cy="5.7" r="2.3" /></svg>}
      {kind === 'review' && <svg viewBox="0 0 24 24"><path d="m20 6.5-10.6 10.6-5.2-5.2" /></svg>}
      {kind === 'pr' && <svg viewBox="0 0 24 24"><circle cx="6" cy="5.5" r="2.3" /><circle cx="6" cy="18.5" r="2.3" /><circle cx="18" cy="18.5" r="2.3" /><path d="M6 7.8v8.4M11.3 5.5H15a3 3 0 0 1 3 3v7.7" /></svg>}
      {kind === 'sync' && <svg viewBox="0 0 24 24"><path d="M20.8 12a8.8 8.8 0 1 1-2.6-6.2M20.8 3.4v4.4h-4.4" /></svg>}
    </span>
  );
}

export default ActivityRow;
