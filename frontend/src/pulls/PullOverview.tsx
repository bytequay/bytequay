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
import { useEffect, useMemo, useRef, useState } from 'react';
import Avatar from '../Avatar';
import type {
  PullReactionDto,
  PullRequestDetailDto,
  PullRequestDto,
  PullTimelineCommentDto,
  RepoUserDto,
} from '../types';
import MarkdownBody from './MarkdownBody';
import { PullRequestIcon } from './PullRowItem';

type Activity = PullRequestDetailDto['recentActivity'][number];
const HIDDEN_TIMELINE_EVENTS = new Set([
  'convert_to_draft',
  'description_reactions',
  'head_ref_force_pushed',
  'mentioned',
  'ready_for_review',
  'review_request_removed',
  'review_requested',
  'subscribed',
  'unsubscribed',
]);

export default function PullOverview({ pr, detail, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onRefresh: () => Promise<void>;
}) {
  const title = detail.title ?? pr.title;
  const state = detail.state ?? pr.state;
  const displayState = state === 'merged'
    ? 'Merged'
    : state === 'closed'
      ? 'Closed'
      : detail.draft || pr.draft
        ? 'Draft'
        : isQueued(detail.recentActivity)
          ? 'Queued'
          : 'Open';

  return (
    <div className="pr-overview">
      <section className="pr-overview__summary">
        <div className="pr-overview__title">
          <h2>{title} <span>#{detail.number}</span></h2>
          <PullRequestCopyButton value={detail.htmlUrl ?? pr.htmlUrl} />
        </div>
        <div className="pr-overview__branches">
          <span className={`pr-state is-${displayState.toLowerCase()}`}>
            <PullRequestIcon merged={displayState === 'Merged'} /> {displayState}
          </span>
          <code>{detail.baseRef ?? 'base unavailable'}</code>
          <span aria-hidden="true">←</span>
          <span className="pr-overview__head-branch">
            <code>{detail.headRef ?? pr.headRef ?? 'branch unavailable'}</code>
            <button type="button" aria-label="Copy head branch" title="Copy head branch"
              onClick={() => { void navigator.clipboard?.writeText(detail.headRef ?? pr.headRef ?? ''); }}>
              <CopyIcon />
            </button>
          </span>
        </div>
      </section>

      <div className="pr-overview__rule" />
      <Metadata detail={detail} />
      {detail.unavailableSections.length > 0 && (
        <div className="pr-unavailable" role="status">
          Some GitHub sections are temporarily unavailable: {detail.unavailableSections.join(', ')}.
        </div>
      )}

      <section className="pr-timeline" aria-label="Pull request activity">
        <DescriptionCard detail={detail} fallbackAuthor={pr.author} />
        {detail.recentActivity.filter(isVisibleTimelineActivity).map((activity, index) => (
          <TimelineItem activity={activity} key={activity.githubId ?? `${activity.eventType}-${index}`}
            pr={pr} onRefresh={onRefresh} />
        ))}
        <ChecksCard detail={detail} />
      </section>
      <CommentComposer pr={pr} detail={detail} onRefresh={onRefresh} />
    </div>
  );
}

type PeoplePickerKind = 'reviewers' | 'assignees';
type PeoplePickerCandidate = RepoUserDto & { selected: boolean };

function Metadata({ detail }: { detail: PullRequestDetailDto }) {
  const root = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState<PeoplePickerKind | null>(null);
  const [repoUsers, setRepoUsers] = useState<RepoUserDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState('');

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(null);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  useEffect(() => {
    setRepoUsers([]);
    setOpen(null);
  }, [detail.repo]);

  const toggle = (kind: PeoplePickerKind) => {
    setQuery('');
    setOpen(current => current === kind ? null : kind);
    if (repoUsers.length > 0 || loading) return;
    setLoading(true);
    void window.bridge.fetchRepoUsers(detail.repo)
      .then(setRepoUsers)
      .catch(() => setRepoUsers([]))
      .finally(() => setLoading(false));
  };

  return (
    <div className="pr-meta" ref={root}>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'reviewers' ? ' is-open' : ''}`}
          aria-expanded={open === 'reviewers'} aria-haspopup="listbox" onClick={() => toggle('reviewers')}>
          <small>Reviewers</small>
          {detail.reviewers.length === 0
            ? 'None'
            : detail.reviewers.length < 3
              ? detail.reviewers.map(reviewer => (
                <span className="pr-meta__reviewer" key={reviewer.login}>
                  <Avatar login={reviewer.login} avatarUrl={reviewer.avatarUrl} size={18} />
                  {reviewer.login}
                </span>
              ))
              : `${detail.reviewers.length} people`}
        </button>
        {open === 'reviewers' && <PeoplePicker detail={detail} kind="reviewers" loading={loading}
          query={query} repoUsers={repoUsers} setQuery={setQuery} />}
      </div>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'assignees' ? ' is-open' : ''}`}
          aria-expanded={open === 'assignees'} aria-haspopup="listbox" onClick={() => toggle('assignees')}>
          <PersonIcon /> <small>Assignees</small>{detail.assignees.length > 0 ? ` ${detail.assignees.length}` : ''}
        </button>
        {open === 'assignees' && <PeoplePicker detail={detail} kind="assignees" loading={loading}
          query={query} repoUsers={repoUsers} setQuery={setQuery} />}
      </div>
      <span>
        <span className="pr-meta__label-dots" aria-hidden="true">
          {(detail.labels.length > 1 ? detail.labels.slice(0, 2) : [detail.labels[0]]).filter(Boolean).map((label, index) => (
            <i className="pr-meta__label-dot" style={{ background: labelPalette(label?.name ?? '', label?.color).color }} key={`${label?.name ?? 'label'}-${index}`} />
          ))}
        </span>
        {detail.labels.length === 1 ? detail.labels[0].name : `${detail.labels.length} labels`}
      </span>
      {detail.milestone && <span><small>Milestone</small> {detail.milestone}</span>}
    </div>
  );
}

function PeoplePicker({ detail, kind, loading, query, repoUsers, setQuery }: {
  detail: PullRequestDetailDto;
  kind: PeoplePickerKind;
  loading: boolean;
  query: string;
  repoUsers: RepoUserDto[];
  setQuery: (value: string) => void;
}) {
  const normalized = query.trim().toLowerCase();
  const candidates = peoplePickerCandidates(detail, repoUsers, kind)
    .filter(candidate => candidate.login.toLowerCase().includes(normalized));
  return (
    <div className="pr-people-picker">
      <input autoFocus aria-label={`Search ${kind}`} placeholder="Search users..." value={query}
        onChange={event => setQuery(event.target.value)} />
      <div className="pr-people-picker__list" role="listbox" aria-label={kind}>
        {candidates.map(candidate => (
          <div className="pr-people-picker__row" tabIndex={0}
            aria-selected={candidate.selected} role="option" key={candidate.login}>
            <span className="pr-people-picker__check">{candidate.selected && <CheckIcon size={16} />}</span>
            <Avatar login={candidate.login} avatarUrl={candidate.avatarUrl} size={20} />
            <span>{displayActor(candidate.login)}</span>
            {candidate.bot && <span className="pr-bot">Bot</span>}
          </div>
        ))}
        {loading && repoUsers.length === 0 && <div className="pr-people-picker__empty">Loading repository users…</div>}
        {!loading && candidates.length === 0 && <div className="pr-people-picker__empty">No users found</div>}
      </div>
    </div>
  );
}

export function peoplePickerCandidates(
  detail: PullRequestDetailDto,
  repoUsers: RepoUserDto[],
  kind: PeoplePickerKind,
): PeoplePickerCandidate[] {
  const participantAvatars = new Map<string, string | null>(mentionCandidates(detail)
    .map(candidate => [candidate.login.toLowerCase(), candidate.avatarUrl] as const));
  const selected = kind === 'reviewers'
    ? detail.reviewers.map(reviewer => ({ login: reviewer.login, avatarUrl: reviewer.avatarUrl }))
    : detail.assignees.map(login => ({ login, avatarUrl: participantAvatars.get(login.toLowerCase()) ?? null }));
  const selectedLogins = new Set(selected.map(user => user.login.toLowerCase()));
  const result: PeoplePickerCandidate[] = selected.map(user => {
    const repositoryUser = repoUsers.find(candidate => candidate.login.toLowerCase() === user.login.toLowerCase());
    return {
      ...user,
      avatarUrl: user.avatarUrl ?? repositoryUser?.avatarUrl ?? null,
      bot: repositoryUser?.bot ?? user.login.endsWith('[bot]'),
      selected: true,
    };
  });
  for (const user of repoUsers) {
    if (selectedLogins.has(user.login.toLowerCase())) continue;
    result.push({ ...user, selected: false });
  }
  return result;
}

function DescriptionCard({ detail, fallbackAuthor }: {
  detail: PullRequestDetailDto;
  fallbackAuthor: string | null;
}) {
  const actor = detail.author ?? fallbackAuthor ?? 'Unknown author';
  const reactions = detail.recentActivity.find(activity => activity.eventType === 'description_reactions')?.payload.reactions ?? [];
  return (
    <article className="pr-card pr-description">
      <CardHeader actor={actor} avatarUrl={detail.authorAvatarUrl} timestamp={detail.createdAt} />
      <MarkdownBody body={detail.body || 'No description provided.'} />
      <Reactions reactions={reactions} alwaysShowPicker />
    </article>
  );
}

function TimelineItem({ activity, pr, onRefresh }: {
  activity: Activity;
  pr: PullRequestDto;
  onRefresh: () => Promise<void>;
}) {
  if (activity.eventType === 'review_thread') {
    return <ReviewThread activity={activity} pr={pr} onRefresh={onRefresh} />;
  }
  if (activity.eventType === 'commented' || activity.eventType === 'reviewed') {
    return <CommentCard activity={activity} />;
  }
  if (activity.eventType === 'committed') return <CommitEvent activity={activity} />;
  return <CompactEvent activity={activity} />;
}

function isVisibleTimelineActivity(activity: Activity) {
  if (HIDDEN_TIMELINE_EVENTS.has(activity.eventType)) return false;
  return !(activity.eventType === 'reviewed'
    && activity.payload.state?.toUpperCase() === 'COMMENTED'
    && !activity.body?.trim());
}

function CommitEvent({ activity }: { activity: Activity }) {
  const { payload } = activity;
  return (
    <div className="pr-commit-event">
      <span aria-hidden="true"><CommitIcon /></span>
      <div>
        {payload.commitMessage?.split('\n')[0] ?? 'Committed'}
        {payload.commitSha && <code>{payload.commitSha.slice(0, 7)}</code>}
        <time> · {relativeTime(activity.timestamp)}</time>
      </div>
    </div>
  );
}

function CommentCard({ activity }: { activity: Activity }) {
  const state = activity.payload.state?.toUpperCase();
  const actor = displayActor(activity.actor || 'github');
  const header = (
    <CardHeader
      actor={actor}
      avatarUrl={activity.payload.actorAvatarUrl}
      bot={activity.payload.actorBot}
      timestamp={activity.timestamp}
      badge={reviewBadge(state)}
    />
  );
  if (!activity.body?.trim()) {
    return <article className="pr-card pr-review-event">{header}</article>;
  }
  return (
    <article className="pr-card pr-comment-card">
      {header}
      <MarkdownBody body={activity.body} />
      <Reactions reactions={activity.payload.reactions} />
    </article>
  );
}

function ReviewThread({ activity, pr, onRefresh }: {
  activity: Activity;
  pr: PullRequestDto;
  onRefresh: () => Promise<void>;
}) {
  const { payload } = activity;
  const [replying, setReplying] = useState(false);
  const [body, setBody] = useState('');
  const [posting, setPosting] = useState(false);
  const [error, setError] = useState('');
  const rootCommentId = payload.comments.find(comment => comment.inReplyToId === null)?.githubId
    ?? payload.comments[0]?.githubId;
  const submit = async () => {
    if (!rootCommentId || !body.trim()) return;
    setPosting(true);
    setError('');
    try {
      await window.bridge.replyToReviewComment(pr.repo, pr.number, rootCommentId, body.trim());
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not post reply.');
      setPosting(false);
      return;
    }
    setBody('');
    setReplying(false);
    setPosting(false);
    await onRefresh();
  };
  if (payload.resolved) {
    return (
      <details className="pr-thread-row">
        <summary>
          <span>›</span>
          <code>{threadLocation(payload.path, payload.line ?? payload.originalLine)}</code>
          <span className="pr-thread-row__resolved"><ResolvedIcon /> Resolved</span>
        </summary>
        <div className="pr-thread-row__comments">
          {payload.comments.map(comment => <ThreadComment comment={comment} key={comment.githubId ?? comment.timestamp} />)}
        </div>
      </details>
    );
  }
  return (
    <article className="pr-inline-thread">
      <header><code>{threadLocation(payload.path, payload.line ?? payload.originalLine)}</code></header>
      {payload.diffHunk && <DiffHunk text={payload.diffHunk} />}
      {payload.comments.map(comment => <ThreadComment comment={comment} key={comment.githubId ?? comment.timestamp} />)}
      {replying ? (
        <footer className="pr-thread-reply">
          <textarea autoFocus aria-label="Reply to review thread" value={body} rows={4}
            placeholder="Reply to this thread…" onChange={event => setBody(event.target.value)}
            onKeyDown={event => {
              if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                void submit();
              }
            }} />
          {error && <span className="pr-thread-reply__error" role="alert">{error}</span>}
          <div className="pr-thread-reply__actions">
            <button type="button" onClick={() => { setReplying(false); setBody(''); setError(''); }}>Cancel</button>
            <button className="is-primary" type="button" disabled={posting || !body.trim()}
              onClick={() => { void submit(); }}>{posting ? 'Replying…' : 'Reply'}</button>
          </div>
        </footer>
      ) : (
        <footer>
          <button className="pr-thread-reply__open" type="button" disabled={!rootCommentId}
            onClick={() => setReplying(true)}>Reply</button>
          <ReactionPicker compact />
          <button className="pr-thread-resolve" type="button" disabled={!payload.htmlUrl} title="Resolve on GitHub"
            onClick={() => { if (payload.htmlUrl) void window.bridge.openInAppBrowser(payload.htmlUrl); }}>
            Resolve
          </button>
        </footer>
      )}
    </article>
  );
}

function ThreadComment({ comment }: { comment: PullTimelineCommentDto }) {
  return (
    <div className="pr-thread-comment">
      <div className="pr-thread-comment__author">
        <Avatar login={comment.actor ?? 'github'} avatarUrl={comment.actorAvatarUrl} size={22} />
        <strong>{comment.actor ?? 'github'}</strong>
        {comment.actorBot && <span className="pr-bot">Bot</span>}
        <time>{relativeTime(comment.timestamp)}</time>
        <div className="pr-thread-comment__menu"><ReactionPicker /><span aria-hidden="true"><KebabIcon /></span></div>
      </div>
      {comment.body && <MarkdownBody body={comment.body} />}
      <Reactions reactions={comment.reactions} />
    </div>
  );
}

function DiffHunk({ text }: { text: string }) {
  return (
    <pre className="pr-diff-hunk">{text.split('\n').map((line, index) => (
      <span className={line.startsWith('+') ? 'is-add' : line.startsWith('-') ? 'is-del' : ''} key={index}>{line}{'\n'}</span>
    ))}</pre>
  );
}

function CompactEvent({ activity }: { activity: Activity }) {
  const { payload } = activity;
  const actor = displayActor(activity.actor || 'github');
  return (
    <div className="pr-event">
      <Avatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} />
      <div>
        <strong>{actor}</strong>{payload.actorBot && <span className="pr-bot">Bot</span>}{' '}
        <span>{eventText(activity)}</span>
        <time> · {relativeTime(activity.timestamp)}</time>
      </div>
    </div>
  );
}

function eventText(activity: Activity) {
  const p = activity.payload;
  switch (activity.eventType) {
    case 'labeled': return <>added <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'unlabeled': return <>removed <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'renamed': return <>changed the title from <del>{p.renameFrom}</del> to <strong>{p.renameTo}</strong></>;
    case 'cross-referenced': return <>mentioned this in <span className="md-link">{p.sourceTitle ?? `#${p.sourceNumber ?? ''}`}</span></>;
    case 'review_requested': return <>requested a review</>;
    case 'review_request_removed': return <>removed a review request</>;
    case 'milestoned': return <>added this to the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'demilestoned': return <>removed this from the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'added_to_merge_queue': return <>added this to the merge queue</>;
    case 'removed_from_merge_queue': return <>removed this from the merge queue</>;
    case 'head_ref_force_pushed': return <>force-pushed the head branch</>;
    case 'convert_to_draft': return <>converted this pull request to draft</>;
    case 'ready_for_review': return <>marked this pull request ready for review</>;
    case 'closed': return <>closed this</>;
    case 'merged': return <>merged</>;
    case 'reopened': return <>reopened this</>;
    case 'ci_autofix': return <>{activity.body ?? 'updated this pull request'}</>;
    default: return <>{activity.eventType.replaceAll('_', ' ')}</>;
  }
}

function CardHeader({ actor, avatarUrl, bot = false, timestamp, badge }: {
  actor: string;
  avatarUrl?: string | null;
  bot?: boolean;
  timestamp: string | null;
  badge?: { text: string; className: string } | null;
}) {
  return (
    <header className="pr-card__header">
      <Avatar login={actor} avatarUrl={avatarUrl} size={22} />
      <strong>{actor}</strong>
      {bot && <span className="pr-bot">Bot</span>}
      {timestamp && <><span>·</span><time>{relativeTime(timestamp)}</time></>}
      {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}
      <div className="pr-card__menu"><ReactionPicker /><span aria-hidden="true"><KebabIcon /></span></div>
    </header>
  );
}

function ChecksCard({ detail }: { detail: PullRequestDetailDto }) {
  const groups = useMemo(() => {
    const out = new Map<string, PullRequestDetailDto['checkRuns']>();
    detail.checkRuns.forEach(check => out.set(check.category, [...(out.get(check.category) ?? []), check]));
    return ['failing', 'pending', 'successful', 'neutral', 'skipped', 'cancelled']
      .map(category => [category, out.get(category) ?? []] as const)
      .filter(([, checks]) => checks.length > 0);
  }, [detail.checkRuns]);
  const passing = detail.ciStatus === 'PASSING';
  return (
    <article className="pr-checks">
      <header><span className={passing ? 'is-success' : 'is-failure'}>{passing ? <CheckIcon /> : <XIcon />}</span>
        <strong>{passing ? 'All checks have passed' : detail.ciStatus === 'FAILING' ? 'Some checks were not successful' : 'Checks are in progress'}</strong>
      </header>
      <div className="pr-checks__groups">
        {groups.length === 0 && <span>No checks reported.</span>}
        {groups.map(([category, checks], index) => (
          <details key={category} open={index === 0 && category === 'failing'}>
            <summary>{groupLabel(category)} ({checks.length}) <span>›</span></summary>
            {checks.map((check, checkIndex) => (
              <div className="pr-check" key={check.githubId ?? `${check.name}-${checkIndex}`}>
                <span className={`is-${check.category}`}>{check.category === 'successful' ? '✓' : check.category === 'failing' ? '✕' : '•'}</span>
                <strong>{check.name ?? 'Check'}</strong>
                {check.source === 'status' && <small>status</small>}
              </div>
            ))}
          </details>
        ))}
      </div>
    </article>
  );
}

type MentionCandidate = { login: string; avatarUrl: string | null };
type ActiveMention = { start: number; query: string };

function CommentComposer({ pr, detail, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onRefresh: () => Promise<void>;
}) {
  const [body, setBody] = useState('');
  const [preview, setPreview] = useState(false);
  const [posting, setPosting] = useState(false);
  const [mention, setMention] = useState<ActiveMention | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const candidates = useMemo(() => mentionCandidates(detail), [detail]);
  const visibleCandidates = mention
    ? candidates.filter(candidate => candidate.login.toLowerCase().includes(mention.query.toLowerCase())).slice(0, 8)
    : [];
  const chooseMention = (candidate: MentionCandidate) => {
    if (!mention) return;
    const replacement = replaceMention(body, mention, candidate.login);
    setBody(replacement.value);
    setMention(null);
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.setSelectionRange(replacement.caret, replacement.caret);
    });
  };
  const submit = async () => {
    if (!body.trim()) return;
    setPosting(true);
    try {
      await window.bridge.commentPr(pr.repo, pr.number, body.trim());
      setBody('');
      await onRefresh();
    }
    finally { setPosting(false); }
  };
  return (
    <section className="pr-composer">
      <header>
        <button className={!preview ? 'is-active' : ''} type="button" onClick={() => setPreview(false)}>Write</button>
        <button className={preview ? 'is-active' : ''} type="button" onClick={() => { setPreview(true); setMention(null); }}>Preview</button>
        <span className="pr-composer__tools" aria-hidden="true">
          <span>H</span><strong>B</strong><em>I</em><QuoteIcon /><CodeIcon /><LinkIcon /><ListIcon /><OrderedListIcon />
        </span>
      </header>
      {preview
        ? <MarkdownBody body={body || 'Nothing to preview.'} />
        : <div className="pr-composer__input">
          <textarea ref={textareaRef} value={body} placeholder="Add a comment" rows={7}
            aria-autocomplete="list" aria-controls="pr-mention-list" aria-expanded={mention !== null}
            onBlur={() => setMention(null)}
            onChange={event => {
              setBody(event.target.value);
              setMention(activeMention(event.target.value, event.target.selectionStart));
              setMentionIndex(0);
            }}
            onKeyDown={event => {
              if (!mention) return;
              if (event.key === 'Escape') {
                event.preventDefault();
                setMention(null);
              }
              else if (visibleCandidates.length > 0 && event.key === 'ArrowDown') {
                event.preventDefault();
                setMentionIndex(index => (index + 1) % visibleCandidates.length);
              }
              else if (visibleCandidates.length > 0 && event.key === 'ArrowUp') {
                event.preventDefault();
                setMentionIndex(index => (index - 1 + visibleCandidates.length) % visibleCandidates.length);
              }
              else if (visibleCandidates.length > 0 && (event.key === 'Enter' || event.key === 'Tab')) {
                event.preventDefault();
                chooseMention(visibleCandidates[mentionIndex] ?? visibleCandidates[0]);
              }
            }} />
          {mention && (
            <div className="pr-mention-list" id="pr-mention-list" role="listbox" aria-label="Mention a participant">
              {visibleCandidates.length === 0
                ? <span className="pr-mention-list__empty">No matching participants</span>
                : visibleCandidates.map((candidate, index) => (
                  <button type="button" role="option" aria-selected={index === mentionIndex}
                    className={index === mentionIndex ? 'is-active' : ''} key={candidate.login}
                    onMouseDown={event => { event.preventDefault(); chooseMention(candidate); }}>
                    <Avatar login={candidate.login} avatarUrl={candidate.avatarUrl} size={24} />
                    <strong>{candidate.login}</strong>
                  </button>
                ))}
            </div>
          )}
        </div>}
      <footer>
        <button type="button" disabled={posting || !body.trim()} onClick={() => { void submit(); }}>
          {posting ? 'Posting…' : 'Comment'}
        </button>
      </footer>
    </section>
  );
}

export function mentionCandidates(detail: PullRequestDetailDto): MentionCandidate[] {
  const candidates = new Map<string, MentionCandidate>();
  const add = (login: string | null | undefined, avatarUrl: string | null = null) => {
    const displayLogin = displayActor(login ?? '').trim();
    if (!/^[a-z\d](?:[a-z\d-]{0,38}[a-z\d])?$/i.test(displayLogin)) return;
    const key = displayLogin.toLowerCase();
    const existing = candidates.get(key);
    if (!existing || (!existing.avatarUrl && avatarUrl)) candidates.set(key, { login: displayLogin, avatarUrl });
  };
  add(detail.author, detail.authorAvatarUrl);
  detail.reviewers.forEach(reviewer => add(reviewer.login, reviewer.avatarUrl));
  detail.assignees.forEach(login => add(login));
  detail.recentActivity.forEach(activity => {
    add(activity.actor, activity.payload.actorAvatarUrl);
    activity.payload.comments.forEach(comment => add(comment.actor, comment.actorAvatarUrl));
  });
  return [...candidates.values()];
}

export function activeMention(value: string, caret: number): ActiveMention | null {
  const match = value.slice(0, caret).match(/(^|[\s([{])@([a-z\d-]*)$/i);
  return match ? { start: caret - match[2].length - 1, query: match[2] } : null;
}

export function replaceMention(value: string, mention: ActiveMention, login: string) {
  const end = mention.start + mention.query.length + 1;
  const inserted = `@${login} `;
  return {
    value: value.slice(0, mention.start) + inserted + value.slice(end),
    caret: mention.start + inserted.length,
  };
}

function Reactions({ reactions, alwaysShowPicker = false }: { reactions: PullReactionDto[]; alwaysShowPicker?: boolean }) {
  if (reactions.length === 0 && !alwaysShowPicker) return null;
  return <div className="pr-reactions">{reactions.map(reaction => (
    <span key={reaction.content}>{reactionEmoji(reaction.content)} {reaction.count}</span>
  ))}<ReactionPicker compact /></div>;
}

const REACTION_CHOICES = [
  ['+1', '👍', 'thumbs up'],
  ['-1', '👎', 'thumbs down'],
  ['laugh', '😄', 'laugh'],
  ['hooray', '🎉', 'hooray'],
  ['confused', '😕', 'confused'],
  ['heart', '❤️', 'heart'],
  ['rocket', '🚀', 'rocket'],
  ['eyes', '👀', 'eyes'],
] as const;

function ReactionPicker({ compact = false }: { compact?: boolean }) {
  const [open, setOpen] = useState(false);
  const pickerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!pickerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', closeOnOutsidePointer);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePointer);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div ref={pickerRef} className={`pr-reaction-picker${compact ? ' is-compact' : ''}${open ? ' is-open' : ''}`}>
      <button type="button" className="pr-reaction-picker__trigger" aria-label="Add reaction" title="Add reaction"
        aria-expanded={open} onClick={() => setOpen(value => !value)}><ReactionMenuIcon /></button>
      <span className="pr-reaction-picker__menu" role="menu" aria-label="Choose a reaction">
        {REACTION_CHOICES.map(([content, emoji, label], index) => (
          <button type="button" className={index === 0 ? 'is-active' : ''} role="menuitem"
            aria-label={`Add ${label} reaction`} key={content}
            onClick={() => setOpen(false)}>
            {emoji}
          </button>
        ))}
      </span>
    </div>
  );
}

function LabelBadge({ name, color }: { name: string; color: string | null }) {
  const palette = labelPalette(name, color);
  return <span className="pr-label" style={{ background: palette.background, color: palette.color }}>{name}</span>;
}

function labelPalette(name: string, color: string | null) {
  const normalized = name.toLowerCase();
  if (normalized === 'no-release-notes') return { background: '#e3f2b5', color: '#476b27' };
  if (['cla-signed', 'docs', 'iceberg'].includes(normalized)) return { background: '#f2e7e7', color: '#8a5551' };
  return { background: `#${color ?? 'eaeef2'}`, color: '#5d3b3b' };
}

export function isQueued(activity: Activity[]) {
  return activity.reduce((queued, item) => item.eventType === 'added_to_merge_queue'
    ? true
    : item.eventType === 'removed_from_merge_queue'
      ? false
      : queued, false);
}

function reviewBadge(state?: string | null) {
  if (state === 'APPROVED') return { text: 'Approved', className: 'is-approved' };
  if (state === 'CHANGES_REQUESTED') return { text: 'Changes requested', className: 'is-changes' };
  if (state === 'DISMISSED') return { text: 'Dismissed', className: 'is-dismissed' };
  return null;
}

function displayActor(actor: string) {
  return actor.replace(/\[bot\]$/, '');
}

function groupLabel(category: string) {
  return category.charAt(0).toUpperCase() + category.slice(1);
}

function threadLocation(path: string | null, line: number | null) {
  return `${path ?? 'Changed file'}${line ? `:L${line}` : ''}`;
}

export function relativeTime(value: string | null, now = Date.now()) {
  if (!value) return '';
  const seconds = Math.max(0, Math.floor((now - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.floor(days / 30);
  return months < 12 ? `${months}mo ago` : `${Math.floor(months / 12)}y ago`;
}

function reactionEmoji(content: string) {
  return ({ '+1': '👍', '-1': '👎', laugh: '😄', hooray: '🎉', confused: '😕', heart: '❤️', rocket: '🚀', eyes: '👀' } as Record<string, string>)[content] ?? content;
}

function CopyIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><rect x="5" y="5" width="8" height="8" rx="1.5" /><path d="M3 11H2.5A1.5 1.5 0 0 1 1 9.5v-7A1.5 1.5 0 0 1 2.5 1h7A1.5 1.5 0 0 1 11 2.5V3" /></svg>;
}

function PullRequestCopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  const resetTimer = useRef<number | null>(null);

  useEffect(() => () => {
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
  }, []);

  const copy = async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    resetTimer.current = window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <button type="button" className={`pr-copy-button${copied ? ' is-copied' : ''}`}
      aria-label={copied ? 'Pull request link copied' : 'Copy pull request link'}
      title={copied ? undefined : 'Copy pull request link'} onClick={() => { void copy(); }}>
      {copied ? <CheckIcon size={16} /> : <CopyIcon />}
      {copied && <span className="pr-copy-button__message" role="status">Copied</span>}
    </button>
  );
}

function PersonIcon() {
  return <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="8" cy="5" r="2.4" /><path d="M3.5 14c.3-3 1.8-4.5 4.5-4.5s4.2 1.5 4.5 4.5" /></svg>;
}

function ResolvedIcon() {
  return <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true"><circle cx="8" cy="8" r="7" /><path d="m4.7 8.1 2 2 4.5-4.5" fill="none" stroke="white" strokeWidth="1.5" /></svg>;
}

function CommitIcon() {
  return <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M10.5 7.75a2.75 2.75 0 1 1-5.5 0 2.75 2.75 0 0 1 5.5 0Zm1.957-.75a4.25 4.25 0 0 0-8.414 0H1.75a.75.75 0 0 0 0 1.5h2.293a4.25 4.25 0 0 0 8.414 0h2.293a.75.75 0 0 0 0-1.5h-2.293Z" /></svg>;
}

function ReactionMenuIcon() {
  return <svg viewBox="0 0 16 16" width="21" height="21" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.3" aria-hidden="true"><circle cx="7" cy="8" r="5.4" /><path d="M5 9.2c.8 1.2 3.2 1.2 4 0M5.2 6.5h.1M8.7 6.5h.1M12.5 1.5v4M10.5 3.5h4" /></svg>;
}

function KebabIcon() {
  return <svg viewBox="0 0 16 16" width="18" height="18" fill="currentColor"><circle cx="3" cy="8" r="1.2" /><circle cx="8" cy="8" r="1.2" /><circle cx="13" cy="8" r="1.2" /></svg>;
}

function CheckIcon({ size = 12 }: { size?: number }) {
  return <svg viewBox="0 0 16 16" width={size} height={size} fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" aria-hidden="true"><path d="m3.5 8.2 2.8 2.8 6.2-6" /></svg>;
}

function XIcon() {
  return <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="2"><path d="m4 4 8 8M12 4l-8 8" /></svg>;
}

function QuoteIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.4"><path d="M3 4.5h4v4H4.5v3H2.8v-4c0-1.7.1-2.2.2-3ZM10 4.5h4v4h-2.5v3H9.8v-4c0-1.7.1-2.2.2-3Z" /></svg>;
}

function CodeIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.4"><path d="m5.5 3-4 5 4 5M10.5 3l4 5-4 5" /></svg>;
}

function LinkIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="1.4"><path d="m6.2 9.8 3.6-3.6M5.2 11.8l-1 1a2.8 2.8 0 0 1-4-4l2.2-2.2a2.8 2.8 0 0 1 4 0M10.8 4.2l1-1a2.8 2.8 0 1 1 4 4l-2.2 2.2a2.8 2.8 0 0 1-4 0" /></svg>;
}

function ListIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor"><circle cx="2" cy="4" r="1" /><circle cx="2" cy="8" r="1" /><circle cx="2" cy="12" r="1" /><path d="M5 3.3h10v1.4H5zM5 7.3h10v1.4H5zM5 11.3h10v1.4H5z" /></svg>;
}

function OrderedListIcon() {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor"><path d="M1 2h2v4H2V3H1zM1 8h2.7v1.1L2.2 11H4v1H1v-1l1.6-2H1zM6 3.3h9v1.4H6zM6 8.3h9v1.4H6zM6 12.3h9v1.4H6z" /></svg>;
}
