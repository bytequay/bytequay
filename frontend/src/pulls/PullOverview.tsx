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
import { shortSha } from '@bytequay/ui/displayText';
import { Check, CircleCheck, CircleDot, CircleMinus, CircleSlash2, CircleX, Clock3, Copy, Ellipsis, ExternalLink, Eye, FileText, GitCommitHorizontal, Link, LoaderCircle, Pencil, Quote, RotateCw, SmilePlus, Tag, User } from 'lucide-react';
import { remoteErrorMessage } from '../remoteError';
import { useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import Avatar from '../Avatar';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import { parseUnifiedDiff } from '../diffParse';
import { openWebLink } from '../githubReference';
import { recordAttentionAction } from '../home/useAttentionVisit';
import LearnFromPullButton from '../memory/LearnFromPullButton';
import ReplayReviewButton from '../memory/ReplayReviewButton';
import { relativeTime } from '@bytequay/ui/relativeTime';
import RepoAvatar from '../RepoAvatar';
import type {
  Bridge,
  PullCommentTarget,
  PullReactionContent,
  PullReactionDto,
  PullReactionTarget,
  PullRequestDetailDto,
  PullRequestDto,
  PullTimelineCommentDto,
  PullTimelinePayloadDto,
  RepoLabelDto,
  RepoUserDto,
  UserProfileDto,
} from '../types';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import UserAvatar from '../UserAvatar';
import BetterWordsButton from './BetterWordsButton';
import CodeExcerpt, { type ReviewCommentLine } from './CodeExcerpt';
import LocalReviewThreadCard from './local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto, LocalReviewThreads } from './local-review/types';
import MergeQueueRemoval from './MergeQueueRemoval';
import MarkdownBody, { GitHubReferenceLink, linkIssueNumbers, toggleMarkdownTask, type SuggestionBase } from './MarkdownBody';
import MarkdownEditor from './MarkdownEditor';
import { EMPTY_TIMELINE_PAYLOAD } from './model';
import { PrivateThreadControls, PrivateThreadForward, privateThreadKey, PrivateThreadsToggle, PrivateThreadTab, withMention, type PrivateThreads } from './PrivateThread';
import { PullRequestEpicCard, PullRequestEpicLink, usePullRequestEpic } from './PullRequestEpic';
import { PullRequestIcon } from './PullRowItem';
import ReviewCommentIndex, { commentIndexTarget } from './ReviewCommentIndex';
import { PendingReviewComments, useReviewDraft } from './ReviewDrafts';
import { ReviewThreadCard, ReviewThreadMessage } from './ReviewThreadCard';
import { usePrCommentCommand } from './usePrCommentCommand';

type Activity = PullRequestDetailDto['recentActivity'][number];
const LABEL_EVENT_TYPES = new Set(['labeled', 'unlabeled']);
const TIMELINE_EVENT_GROUP_WINDOW_MS = 5 * 60 * 1000;
const HIDDEN_TIMELINE_EVENTS = new Set([
  'convert_to_draft',
  'description_reactions',
  'mentioned',
  'ready_for_review',
  'review_request_removed',
  'subscribed',
  'unsubscribed',
]);

export type PullDetailUpdater = (
  repo: string,
  number: number,
  update: (detail: PullRequestDetailDto) => PullRequestDetailDto,
) => void;

/** The signed-in account, for review composers, local pending comments, and editing your own comments. */
export function useViewer(enabled: boolean): UserProfileDto | null {
  const [viewer, setViewer] = useState<UserProfileDto | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    window.bridge.getUserProfile()
      .then(profile => { if (live) setViewer(profile); })
      .catch(() => { /* the composer just falls back to an initial */ });
    return () => { live = false; };
  }, [enabled]);
  return viewer;
}

export function isViewerComment(actor: string | null | undefined, viewerLogin: string | null | undefined) {
  return Boolean(actor && viewerLogin && actor.toLowerCase() === viewerLogin.toLowerCase());
}

export default function PullOverview({ pr, detail, contributorsWorkspaceId, workspaceId, localReview, privateThreads, taskActions, onRefresh, onDetailUpdate = () => {},
  onDetailChange = () => { void onRefresh(); } }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  contributorsWorkspaceId?: string;
  /** The workspace watching the pull request's repository, whose work may name it. */
  workspaceId?: string;
  taskActions?: ReactNode;
  localReview?: LocalReviewThreads;
  /** The host task's private threads on this pull request's comments, when available. */
  privateThreads?: PrivateThreads;
  onRefresh: () => Promise<void>;
  onDetailUpdate?: PullDetailUpdater;
  onDetailChange?: (detail: PullRequestDetailDto) => void;
}) {
  const [quotedReply, setQuotedReply] = useState<QuoteRequest>();
  const viewerLogin = useViewer(true)?.login;
  // Only the user's own pull requests come from their development tasks, so only those can deliver an Epic's work.
  const authored = pr.origin === 'AUTHORED' || (viewerLogin !== undefined && detail.author?.toLowerCase() === viewerLogin.toLowerCase());
  const epic = usePullRequestEpic(authored ? workspaceId : undefined, detail.repo, detail.number);
  const [repoOwner, repoName = ''] = detail.repo.split('/');
  const state = detail.state ?? pr.state;
  const displayState = state === 'merged'
    ? 'Merged'
    : state === 'closed'
      ? 'Closed'
      : detail.draft
        ? 'Draft'
        : isQueued(detail.recentActivity)
          ? 'Queued'
          : 'Open';

  const timeline: ({ kind: 'remote'; activities: Activity[]; key: string; at: string }
    | { kind: 'local'; thread: LocalReviewThreadDto; key: string; at: string })[] = [
    ...groupTimelineActivities(detail.recentActivity).map((activities, index) => ({
      kind: 'remote' as const, activities, key: `remote-${activities[0].githubId ?? `${activities[0].eventType}-${index}`}`,
      at: activities[0].timestamp ?? '',
    })),
    ...(localReview?.threads ?? []).filter(thread => thread.repo.toLowerCase() === pr.repo.toLowerCase() && thread.number === pr.number)
      .map(thread => ({ kind: 'local' as const, thread, key: `local-${thread.id}`, at: thread.createdAt })),
  ];
  timeline.sort((left, right) => left.at.localeCompare(right.at));

  return (
    <ReviewCommentIndex className="pr-overview pull-request-overview">
      <section className="pr-overview__summary">
        <div className="pr-overview__title">
          <PullTitle key={`${detail.repo.toLowerCase()}#${detail.number}`} pr={pr} detail={detail} onDetailUpdate={onDetailUpdate} />
          <div className="pr-overview__number">
            <span>#{detail.number}</span>
            <CopyLinkButton value={detail.htmlUrl ?? pr.htmlUrl} label="pull request link" />
          </div>
        </div>
        <div className="pr-overview__branches">
          <span className={`pr-state is-${displayState.toLowerCase()}`}>
            <PullRequestIcon merged={displayState === 'Merged'} /> {displayState}
          </span>
          <span className="pr-overview__base-branch">
            <RepoAvatar owner={repoOwner} repo={repoName} size={24} />
            <span>{detail.repo}</span>
            <code>{detail.baseRef ?? 'base unavailable'}</code>
          </span>
          <span aria-hidden="true">←</span>
          <span className="pr-overview__head-branch">
            <code title={detail.headRef ?? pr.headRef ?? undefined}><bdi>{detail.headRef ?? pr.headRef ?? 'branch unavailable'}</bdi></code>
            <CopyLinkButton value={detail.headRef ?? pr.headRef ?? ''} label="head branch" />
          </span>
          <button type="button" aria-label="Open on GitHub" title="Open on GitHub"
            onClick={() => { void window.bridge.openInAppBrowser(detail.htmlUrl ?? pr.htmlUrl); }}>
            <Avatar login="github" size={18} />
          </button>
          {privateThreads !== undefined && <PrivateThreadsToggle threads={privateThreads} />}
          {epic !== null && <PullRequestEpicLink value={epic} />}
          {contributorsWorkspaceId !== undefined && (displayState === 'Merged' || displayState === 'Closed')
            && <LearnFromPullButton workspaceId={contributorsWorkspaceId} repo={detail.repo} number={detail.number} />}
          {contributorsWorkspaceId !== undefined && displayState === 'Merged'
            && <ReplayReviewButton workspaceId={contributorsWorkspaceId} repo={detail.repo} number={detail.number} />}
          {taskActions}
        </div>
      </section>

      <div className="pr-overview__rule" />
      <Metadata detail={detail} contributorsWorkspaceId={contributorsWorkspaceId} onDetailUpdate={onDetailUpdate} />
      {detail.unavailableSections.length > 0 && (
        <div className="pr-unavailable" role="status">
          Some GitHub sections are temporarily unavailable: {detail.unavailableSections.join(', ')}.
        </div>
      )}

      <section className="pr-timeline" aria-label="Pull request activity">
        {epic !== null && <PullRequestEpicCard value={epic} />}
        <DescriptionCard pr={pr} detail={detail} fallbackAuthor={pr.author} fallbackUrl={pr.htmlUrl}
          onDetailUpdate={onDetailUpdate}
          onQuote={body => setQuotedReply(current => ({ id: (current?.id ?? 0) + 1, body }))} />
        {timeline.map(item => item.kind === 'local' && localReview !== undefined ? (
          <div className="pr-timeline__slot" key={item.key}>
            <LocalReviewThreadCard {...localReview} thread={item.thread} />
          </div>
        ) : item.kind === 'remote' ? (
          <div className="pr-timeline__slot" key={item.key}
            id={item.activities[0].githubId === null ? undefined : `pr-activity-${item.activities[0].githubId}`}>
            <TimelineItem activities={item.activities} viewerLogin={viewerLogin} privateThreads={privateThreads}
              pr={pr} headRef={detail.headRef ?? pr.headRef} workspaceId={contributorsWorkspaceId} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate}
              onQuote={body => setQuotedReply(current => ({ id: (current?.id ?? 0) + 1, body }))} />
          </div>
        ) : null)}
        <ChecksCard pr={pr} detail={detail} onRefresh={onRefresh} />
      </section>
      <DiscussionComposer
        betterWords
        workspaceId={contributorsWorkspaceId}
        candidates={mentionCandidates(detail)}
        repository={pr.repo}
        pullRequestNumber={pr.number}
        quotedReply={quotedReply}
        onDraftChange={body => { void saveDraft(pr.repo, pr.number, body); }}
        onClose={state?.toLowerCase() === 'open' && (detail.viewerCanWrite || pr.origin === 'AUTHORED')
          ? async () => {
              await window.bridge.closePr(pr.repo, pr.number);
              recordAttentionAction(`${pr.repo}#${pr.number}`, 'update');
              await onRefresh();
            }
          : undefined}
        onSubmit={async body => {
          await submitPullRequestComment(window.bridge, pr.repo, pr.number, body, onDetailChange);
        }}
      />
    </ReviewCommentIndex>
  );
}

function PullTitle({ pr, detail, onDetailUpdate }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onDetailUpdate: PullDetailUpdater;
}) {
  const title = detail.title ?? pr.title;
  const canEdit = detail.viewerCanWrite || pr.origin === 'AUTHORED';
  const [edit, setEdit] = useState<{ base: string; draft: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const writeInFlight = useRef(false);
  const nextTitle = edit?.draft.trim() ?? '';

  const cancel = () => {
    if (writeInFlight.current) return;
    setEdit(null);
    setError(null);
  };
  const save = async () => {
    if (!canEdit || edit === null || !nextTitle || nextTitle === title || writeInFlight.current) return;
    if (title !== edit.base) {
      setError('The title changed while you were editing. Reopen the editor to use the latest version.');
      return;
    }
    writeInFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await window.bridge.updatePrTitle(detail.repo, detail.number, nextTitle);
      onDetailUpdate(detail.repo, detail.number, current => ({ ...current, title: nextTitle }));
      recordAttentionAction(`${detail.repo}#${detail.number}`, 'update');
      setEdit(null);
    }
    catch (failure) {
      setError(`Could not update title. ${remoteErrorMessage(failure)}`);
    }
    finally {
      writeInFlight.current = false;
      setSaving(false);
    }
  };

  return <div className="pr-title">
    {edit === null ? <h1>
      <span className="pr-title__text">{linkIssueNumbers(title, detail.repo, true)}</span>
      {canEdit && <button className="pr-title__edit" type="button" aria-label="Edit pull request title" title="Edit title" onClick={() => {
        setEdit({ base: title, draft: title });
        setError(null);
      }}><Pencil size={15} /></button>}
    </h1> : <form className="pr-title-editor" onSubmit={event => { event.preventDefault(); void save(); }}>
      <textarea aria-label="Pull request title" value={edit.draft} disabled={saving} autoFocus required rows={3}
        onChange={event => setEdit({ ...edit, draft: event.target.value.replace(/[\r\n]+/g, ' ') })}
        onKeyDown={event => {
          if (event.key === 'Escape') { event.preventDefault(); cancel(); }
          if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); void save(); }
        }} />
      <div className="pr-description-editor__actions">
        <button type="button" disabled={saving} onClick={cancel}>Cancel</button>
        <button className="is-primary" type="submit" disabled={saving || !canEdit || !nextTitle || nextTitle === title}>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {error && <div className="pr-title-editor__error" role="alert">{error}</div>}
    </form>}
  </div>;
}

type PeoplePickerKind = 'reviewers' | 'assignees';
type MetadataPickerKind = PeoplePickerKind | 'labels';
type PeoplePickerCandidate = RepoUserDto & { selected: boolean; locked: boolean };

function Metadata({ detail, contributorsWorkspaceId, onDetailUpdate }: {
  detail: PullRequestDetailDto;
  contributorsWorkspaceId?: string;
  onDetailUpdate: PullDetailUpdater;
}) {
  const [open, setOpen] = useState<MetadataPickerKind | null>(null);
  const [repoUsers, setRepoUsers] = useState<RepoUserDto[]>([]);
  const [repoTeams, setRepoTeams] = useState<string[] | null>(null);
  const [repoLabels, setRepoLabels] = useState<RepoLabelDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  const [optimisticDetail, setOptimisticDetail] = useState<{
    key: string;
    value: PullRequestDetailDto;
  } | null>(null);
  const optionsRequest = useRef(0);
  const optionsKey = useRef(key);
  const updatingRef = useRef(false);
  const visibleDetail = optimisticDetail?.key === key ? optimisticDetail.value : detail;
  optionsKey.current = key;

  const root = usePopoverDismiss(() => setOpen(null), false, open !== null);

  useEffect(() => {
    optionsRequest.current += 1;
    setRepoUsers([]);
    setRepoTeams(null);
    setRepoLabels([]);
    setOptimisticDetail(null);
    setLoading(false);
    setOpen(null);
    setError(null);
  }, [key]);

  const toggle = (kind: MetadataPickerKind) => {
    setQuery('');
    setError(null);
    setOpen(current => current === kind ? null : kind);
    const needsUsers = repoUsers.length === 0;
    const needsTeams = kind === 'reviewers' && repoTeams === null;
    if (loading || (kind === 'labels' ? repoLabels.length > 0 : !needsUsers && !needsTeams)) return;
    const requestId = ++optionsRequest.current;
    const requestKey = key;
    setLoading(true);
    const request = kind === 'labels'
      ? window.bridge.fetchRepoLabels(visibleDetail.repo).then(labels => {
          if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoLabels(labels);
        })
      : Promise.all([
          needsUsers && window.bridge.fetchRepoUsers(visibleDetail.repo, contributorsWorkspaceId).then(users => {
            if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoUsers(users);
          }),
          needsTeams && window.bridge.fetchRepoTeams(visibleDetail.repo).then(teams => {
            if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoTeams(teams);
          }),
        ]);
    void request
      .catch(failure => {
        if (requestId === optionsRequest.current && requestKey === optionsKey.current) {
          setError(remoteErrorMessage(failure));
        }
      })
      .finally(() => {
        if (requestId === optionsRequest.current && requestKey === optionsKey.current) setLoading(false);
      });
  };

  const update = async (
    optimistic: (current: PullRequestDetailDto) => PullRequestDetailDto,
    rollback: (current: PullRequestDetailDto) => PullRequestDetailDto,
    action: () => Promise<void>,
  ) => {
    if (updatingRef.current) return;
    updatingRef.current = true;
    setUpdating(true);
    setError(null);
    const next = optimistic(visibleDetail);
    setOptimisticDetail({ key, value: next });
    onDetailUpdate(visibleDetail.repo, visibleDetail.number, optimistic);
    try {
      await action();
      recordAttentionAction(`${visibleDetail.repo}#${visibleDetail.number}`, 'update');
      onDetailUpdate(visibleDetail.repo, visibleDetail.number, optimistic);
      setOptimisticDetail(null);
      setOpen(null);
    }
    catch (failure) {
      onDetailUpdate(visibleDetail.repo, visibleDetail.number, rollback);
      setOptimisticDetail(null);
      setError(remoteErrorMessage(failure));
    }
    finally {
      updatingRef.current = false;
      setUpdating(false);
    }
  };

  return (
    <div className="pr-meta" ref={root}>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'reviewers' ? ' is-open' : ''}`}
          aria-expanded={open === 'reviewers'} aria-haspopup="listbox" onClick={() => toggle('reviewers')}>
          <small>Reviewers</small>
          {visibleDetail.reviewers.length === 0 && 'None'}
          {visibleDetail.reviewers.slice(0, visibleDetail.reviewers.length > 3 ? 2 : 3).map(reviewer => (
            <span className="pr-meta__item" key={reviewer.login}>
              <Avatar login={reviewer.login} avatarUrl={reviewer.avatarUrl} size={18} allowGitHubFallback={!reviewer.login.includes('/')} />
              {reviewer.login}
            </span>
          ))}
          {visibleDetail.reviewers.length > 3 && `${visibleDetail.reviewers.length - 2} other reviewers`}
        </button>
        {open === 'reviewers' && <PeoplePicker detail={visibleDetail} kind="reviewers" loading={loading} query={query}
          repoUsers={[...(repoTeams ?? []).map((login): RepoUserDto => ({ login, avatarUrl: null, bot: false })), ...repoUsers]}
          setQuery={setQuery} updating={updating}
          onToggle={candidate => {
            if (candidate.locked) return;
            const previous = visibleDetail.reviewers;
            const next = candidate.selected
              ? previous.filter(reviewer => reviewer.login.toLowerCase() !== candidate.login.toLowerCase())
              : [...previous, { login: candidate.login, avatarUrl: candidate.avatarUrl, state: 'PENDING' }];
            void update(
              current => ({ ...current, reviewers: next }),
              current => current.reviewers === next ? { ...current, reviewers: previous } : current,
              () => window.bridge.updatePrReviewer(
                visibleDetail.repo, visibleDetail.number, candidate.login, !candidate.selected),
            );
          }} />}
      </div>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'assignees' ? ' is-open' : ''}`}
          aria-expanded={open === 'assignees'} aria-haspopup="listbox" onClick={() => toggle('assignees')}>
          <User size={15} strokeWidth={1.4} aria-hidden="true" /> <small>Assignees</small>
          {visibleDetail.assignees.slice(0, visibleDetail.assignees.length > 3 ? 2 : 3).map(login => (
            <span className="pr-meta__item" key={login}>
              <Avatar login={login} size={18} />
              {login}
            </span>
          ))}
          {visibleDetail.assignees.length > 3 && `${visibleDetail.assignees.length - 2} other assignees`}
        </button>
        {open === 'assignees' && <PeoplePicker detail={visibleDetail} kind="assignees" loading={loading}
          query={query} repoUsers={repoUsers} setQuery={setQuery} updating={updating}
          onToggle={candidate => {
            const previous = visibleDetail.assignees;
            const next = toggleSelection(previous, candidate.login);
            void update(
              current => ({ ...current, assignees: next }),
              current => current.assignees === next ? { ...current, assignees: previous } : current,
              () => window.bridge.updatePrMetadata(
                visibleDetail.repo, visibleDetail.number, next, visibleDetail.labels.map(label => label.name)),
            );
          }} />}
      </div>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'labels' ? ' is-open' : ''}`}
          aria-expanded={open === 'labels'} aria-haspopup="listbox" onClick={() => toggle('labels')}>
          {visibleDetail.labels.length === 0 && 'No labels'}
          {visibleDetail.labels.slice(0, visibleDetail.labels.length > 3 ? 2 : 3).map(label => (
            <span className="pr-meta__item" key={label.name}>
              <i className="pr-meta__label-dot" aria-hidden="true" style={{ background: labelPalette(label.name, label.color).color }} />
              {label.name}
            </span>
          ))}
          {visibleDetail.labels.length > 3 && `${visibleDetail.labels.length - 2} other labels`}
        </button>
        {open === 'labels' && <LabelPicker labels={repoLabels} selected={visibleDetail.labels.map(label => label.name)}
          loading={loading} query={query} setQuery={setQuery} updating={updating}
          onToggle={label => {
            const previous = visibleDetail.labels;
            const selected = previous.some(value => value.name.toLowerCase() === label.name.toLowerCase());
            const next = selected
              ? previous.filter(value => value.name.toLowerCase() !== label.name.toLowerCase())
              : [...previous, { name: label.name, color: label.color }];
            void update(
              current => ({ ...current, labels: next }),
              current => current.labels === next ? { ...current, labels: previous } : current,
              () => window.bridge.updatePrMetadata(
                visibleDetail.repo, visibleDetail.number, visibleDetail.assignees, next.map(value => value.name)),
            );
          }} />}
      </div>
      {visibleDetail.milestone && <span><small>Milestone</small> {visibleDetail.milestone}</span>}
      {error && <div className="pr-meta__error" role="alert">Could not update pull request. {error}</div>}
    </div>
  );
}

function PeoplePicker({ detail, kind, loading, query, repoUsers, setQuery, updating, onToggle }: {
  detail: PullRequestDetailDto;
  kind: PeoplePickerKind;
  loading: boolean;
  query: string;
  repoUsers: RepoUserDto[];
  setQuery: (value: string) => void;
  updating: boolean;
  onToggle: (candidate: PeoplePickerCandidate) => void;
}) {
  const normalized = query.trim().toLowerCase();
  const candidates = peoplePickerCandidates(detail, repoUsers, kind)
    .filter(candidate => candidate.login.toLowerCase().includes(normalized));
  return <MetadataPicker ariaLabel={kind} placeholder={kind === 'reviewers' ? 'Search users and teams...' : 'Search users...'} query={query} setQuery={setQuery}
    loading={loading} updating={updating} emptyText="No users found" loadingText="Loading repository users…"
    options={candidates.map(candidate => ({
      key: candidate.login,
      label: displayActor(candidate.login),
      login: candidate.login,
      avatarUrl: candidate.avatarUrl,
      bot: candidate.bot,
      selected: candidate.selected,
      disabled: candidate.locked,
      disabledReason: candidate.locked ? 'Completed reviews cannot be removed' : undefined,
    }))}
    onToggle={option => {
      const candidate = candidates.find(value => value.login === option.key);
      if (candidate) onToggle(candidate);
    }} />;
}

function LabelPicker({ labels, selected, loading, query, setQuery, updating, onToggle }: {
  labels: RepoLabelDto[];
  selected: string[];
  loading: boolean;
  query: string;
  setQuery: (value: string) => void;
  updating: boolean;
  onToggle: (label: RepoLabelDto) => void;
}) {
  const selectedNames = new Set(selected.map(label => label.toLowerCase()));
  const candidates = labels.filter(label => label.name.toLowerCase().includes(query.trim().toLowerCase()));
  return <MetadataPicker ariaLabel="labels" placeholder="Search labels..." query={query} setQuery={setQuery}
    loading={loading} updating={updating} emptyText="No labels found" loadingText="Loading repository labels…"
    options={candidates.map(label => ({
      key: label.name,
      label: label.name,
      color: labelPalette(label.name, label.color).color,
      selected: selectedNames.has(label.name.toLowerCase()),
    }))}
    onToggle={option => {
      const label = candidates.find(value => value.name === option.key);
      if (label) onToggle(label);
    }} />;
}

type MetadataPickerOption = {
  key: string;
  label: string;
  login?: string;
  avatarUrl?: string | null;
  color?: string;
  bot?: boolean;
  selected: boolean;
  disabled?: boolean;
  disabledReason?: string;
  /** A second line under the name, such as the circles a person is already in. */
  hint?: string;
};

export const MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS = 100;

export function displayedMetadataPickerOptions(options: MetadataPickerOption[]) {
  let unselectedPeople = 0;
  return options.filter(option => {
    if (!option.login || option.selected) return true;
    unselectedPeople++;
    return unselectedPeople <= MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS;
  });
}

export function MetadataPicker({
  ariaLabel,
  placeholder,
  query,
  setQuery,
  options,
  loading,
  updating,
  loadingText,
  emptyText,
  onToggle,
}: {
  ariaLabel: string;
  placeholder: string;
  query: string;
  setQuery: (value: string) => void;
  options: MetadataPickerOption[];
  loading: boolean;
  updating: boolean;
  loadingText: string;
  emptyText: string;
  onToggle: (option: MetadataPickerOption) => void;
}) {
  const displayedOptions = displayedMetadataPickerOptions(options);
  return (
    <div className="pr-people-picker">
      <input autoFocus aria-label={`Search ${ariaLabel}`} placeholder={placeholder} value={query}
        onChange={event => setQuery(event.target.value)} />
      <div className="pr-people-picker__list" role="listbox" aria-label={ariaLabel}>
        {displayedOptions.map(option => (
          <button className={`pr-people-picker__row${option.color ? ' is-label' : ''}`} type="button"
            disabled={updating || option.disabled} aria-label={option.label} aria-selected={option.selected} role="option" key={option.key}
            title={option.disabledReason} onClick={() => onToggle(option)}>
            <span className="pr-people-picker__check">{option.selected && <CheckIcon size={16} />}</span>
            {option.login
              ? <Avatar login={option.login} avatarUrl={option.avatarUrl} size={20} allowGitHubFallback={false} />
              : <i className="pr-meta__label-dot" style={{ background: option.color }} />}
            <span>{option.label}{option.hint && <span className="pr-people-picker__hint">{option.hint}</span>}</span>
            {option.bot && <span className="pr-bot">Bot</span>}
          </button>
        ))}
        {loading && options.length === 0 && <div className="pr-people-picker__empty">{loadingText}</div>}
        {!loading && options.length === 0 && <div className="pr-people-picker__empty">{emptyText}</div>}
      </div>
    </div>
  );
}

export function toggleSelection(values: string[], value: string) {
  const selected = values.some(candidate => candidate.toLowerCase() === value.toLowerCase());
  return selected
    ? values.filter(candidate => candidate.toLowerCase() !== value.toLowerCase())
    : [...values, value];
}

export function peoplePickerCandidates(
  detail: PullRequestDetailDto,
  repoUsers: RepoUserDto[],
  kind: PeoplePickerKind,
): PeoplePickerCandidate[] {
  const participantAvatars = new Map<string, string | null>(mentionCandidates(detail)
    .map(candidate => [candidate.login.toLowerCase(), candidate.avatarUrl] as const));
  const selected = kind === 'reviewers'
    ? detail.reviewers.map(reviewer => ({
      login: reviewer.login,
      avatarUrl: reviewer.avatarUrl,
      locked: reviewer.state.toUpperCase() !== 'PENDING',
    }))
    : detail.assignees.map(login => ({
      login,
      avatarUrl: participantAvatars.get(login.toLowerCase()) ?? null,
      locked: false,
    }));
  const selectedLogins = new Set(selected.map(user => user.login.toLowerCase()));
  const result: PeoplePickerCandidate[] = selected.map(user => {
    const repositoryUser = repoUsers.find(candidate => candidate.login.toLowerCase() === user.login.toLowerCase());
    return {
      ...user,
      avatarUrl: repositoryUser?.avatarUrl ?? null,
      bot: repositoryUser?.bot ?? user.login.endsWith('[bot]'),
      selected: true,
      locked: user.locked,
    };
  });
  for (const user of repoUsers) {
    if (selectedLogins.has(user.login.toLowerCase())) continue;
    result.push({ ...user, selected: false, locked: false });
  }
  return result;
}

function DescriptionCard({ pr, detail, fallbackAuthor, fallbackUrl, onDetailUpdate, onQuote }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  fallbackAuthor: string | null;
  fallbackUrl: string;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const actor = detail.author ?? fallbackAuthor ?? 'Unknown author';
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  const serverBody = detail.body ?? '';
  const [localBody, setLocalBody] = useState<{ key: string; value: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [editBase, setEditBase] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const writeInFlight = useRef(false);
  const body = localBody?.key === key ? localBody.value : serverBody;
  const displayBody = body || 'No description provided.';
  const serverReactions = detail.recentActivity
    .find(activity => activity.eventType === 'description_reactions')?.payload.reactions ?? [];
  const reaction = useReactionAction(
    detail.repo,
    detail.number,
    'pull_request',
    null,
    serverReactions,
    true,
    (reactions, onlyIf) => onDetailUpdate(detail.repo, detail.number, current =>
      updatePullReactionDetail(current, 'pull_request', null, reactions, onlyIf)),
  );
  const canEdit = detail.viewerCanWrite || pr.origin === 'AUTHORED';

  useEffect(() => {
    if (!saving && localBody?.key === key && localBody.value === serverBody) setLocalBody(null);
  }, [key, localBody, saving, serverBody]);

  useEffect(() => {
    setEditing(false);
    setDraft('');
    setEditBase('');
    setError(null);
    writeInFlight.current = false;
    setSaving(false);
  }, [key]);

  const persistBody = async (nextBody: string) => {
    if (writeInFlight.current || nextBody === body) return false;
    const previousLocal = localBody?.key === key ? localBody : null;
    const previousBody = previousLocal?.value ?? detail.body;
    writeInFlight.current = true;
    setSaving(true);
    setError(null);
    setLocalBody({ key, value: nextBody });
    onDetailUpdate(detail.repo, detail.number, current => ({ ...current, body: nextBody }));
    try {
      await window.bridge.updatePrDescription(detail.repo, detail.number, nextBody);
      recordAttentionAction(`${detail.repo}#${detail.number}`, 'update');
      onDetailUpdate(detail.repo, detail.number, current => ({ ...current, body: nextBody }));
      return true;
    }
    catch (failure) {
      onDetailUpdate(detail.repo, detail.number, current => current.body === nextBody
        ? { ...current, body: previousBody }
        : current);
      setLocalBody(current => current?.key === key && current.value === nextBody
        ? previousLocal
        : current);
      setError(`Could not update description. ${remoteErrorMessage(failure)}`);
      return false;
    }
    finally {
      writeInFlight.current = false;
      setSaving(false);
    }
  };

  const saveEdit = async () => {
    if (body !== editBase) {
      setError('The description changed while you were editing. Reopen the editor to use the latest version.');
      return;
    }
    setEditing(false);
    if (!await persistBody(draft)) setEditing(true);
  };

  return (
    <article className="pr-card pr-description">
      <CardHeader actor={actor} avatarUrl={detail.authorAvatarUrl} timestamp={detail.createdAt}
        reaction={reaction}
        actions={{
          link: detail.htmlUrl ?? fallbackUrl,
          markdown: body,
          onQuote: () => onQuote(body),
          editLabel: 'Edit description',
          onEdit: canEdit ? () => {
            setDraft(body);
            setEditBase(body);
            setError(null);
            setEditing(true);
          } : undefined,
        }} />
      {editing ? (
        <div className="pr-description-editor">
          <textarea aria-label="Pull request description" value={draft} rows={10} autoFocus
            onChange={event => setDraft(event.target.value)}
            onKeyDown={event => {
              if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                void saveEdit();
              }
            }} />
          <div className="pr-description-editor__actions">
            <button type="button" disabled={saving} onClick={() => {
              setEditing(false);
              setError(null);
            }}>Cancel</button>
            <button className="is-primary" type="button" disabled={saving || draft === body}
              onClick={() => { void saveEdit(); }}>Save</button>
          </div>
        </div>
      ) : (
        <MarkdownBody body={displayBody} repository={detail.repo} tasksDisabled={saving}
          onTaskToggle={canEdit ? (taskIndex, checked) => {
            const nextBody = toggleMarkdownTask(body, taskIndex, checked);
            if (nextBody !== body) void persistBody(nextBody);
          } : undefined} />
      )}
      {error && <div className="pr-description__error" role="alert">{error}</div>}
      <Reactions reactions={reaction?.reactions ?? serverReactions} reaction={reaction} alwaysShowPicker />
    </article>
  );
}

function TimelineItem({ activities, viewerLogin, privateThreads, pr, headRef, workspaceId, onRefresh, onDetailUpdate, onQuote }: {
  activities: Activity[];
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  headRef: string | null;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const activity = activities[0];
  if (activity.eventType === 'reviewed' && activities.some(item => item.eventType === 'review_thread')) {
    return <ReviewGroup activities={activities} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} workspaceId={workspaceId}
      onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} onQuote={onQuote} />;
  }
  if (activity.eventType === 'review_thread') {
    return <ReviewThread activity={activity} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} workspaceId={workspaceId}
      onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />;
  }
  if (activity.eventType === 'commented' || activity.eventType === 'reviewed') {
    return <CommentCard activity={activity} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr}
      onDetailUpdate={onDetailUpdate} onQuote={onQuote} />;
  }
  if (activity.eventType === 'committed') return <CommitEvent activity={activity} />;
  return <CompactEvent activities={activities} headRef={headRef} pr={pr} />;
}

export function groupTimelineActivities(activities: Activity[]) {
  const candidates = activities.filter(activity => !HIDDEN_TIMELINE_EVENTS.has(activity.eventType));
  const reviewIds = new Set(candidates
    .filter(activity => activity.eventType === 'reviewed' && activity.githubId !== null)
    .map(activity => activity.githubId));
  const threadsByReview = new Map<number, Activity[]>();
  candidates.forEach((activity) => {
    const reviewId = reviewIdForThread(activity);
    if (reviewId === null || !reviewIds.has(reviewId)) return;
    const threads = threadsByReview.get(reviewId) ?? [];
    threads.push(activity);
    threadsByReview.set(reviewId, threads);
  });

  return candidates.filter(activity => isVisibleTimelineActivity(activity, threadsByReview)).reduce<Activity[][]>((groups, activity) => {
    const reviewId = reviewIdForThread(activity);
    if (reviewId !== null && threadsByReview.has(reviewId)) return groups;
    if (activity.eventType === 'reviewed' && activity.githubId !== null) {
      groups.push([activity, ...(threadsByReview.get(activity.githubId) ?? [])]);
      return groups;
    }
    const previous = groups.at(-1);
    const previousActivity = previous?.at(-1);
    if (previous && previousActivity && canGroupTimelineEvents(previousActivity, activity)) {
      previous.push(activity);
    }
    else {
      groups.push([activity]);
    }
    return groups;
  }, []);
}

function reviewIdForThread(activity: Activity) {
  if (activity.eventType !== 'review_thread') return null;
  return activity.payload.comments.find(comment => comment.inReplyToId === null)?.reviewId
    ?? activity.payload.comments[0]?.reviewId
    ?? null;
}

function canGroupTimelineEvents(previous: Activity, activity: Activity) {
  if (activity.eventType !== previous.eventType
      || activity.actor !== previous.actor) {
    return false;
  }
  if (activity.eventType === 'head_ref_force_pushed') return true;
  if (activity.eventType !== 'review_requested' && !LABEL_EVENT_TYPES.has(activity.eventType)) return false;
  const previousTime = Date.parse(previous.timestamp ?? '');
  const activityTime = Date.parse(activity.timestamp ?? '');
  return Number.isFinite(previousTime)
    && Number.isFinite(activityTime)
    && activityTime - previousTime >= 0
    && activityTime - previousTime <= TIMELINE_EVENT_GROUP_WINDOW_MS;
}

function isVisibleTimelineActivity(activity: Activity, threadsByReview: Map<number, Activity[]>) {
  return !(activity.eventType === 'reviewed'
    && activity.payload.state?.toUpperCase() === 'COMMENTED'
    && !activity.body?.trim()
    && (activity.githubId === null || !threadsByReview.has(activity.githubId)));
}

function CommitEvent({ activity }: { activity: Activity }) {
  const { payload } = activity;
  return (
    <div className="pr-commit-event">
      <span aria-hidden="true"><GitCommitHorizontal size={14} aria-hidden="true" /></span>
      <div>
        {payload.commitMessage?.split('\n')[0] ?? 'Committed'}
        {payload.commitSha && <code>{shortSha(payload.commitSha)}</code>}
        <time> · {relativeTime(activity.timestamp)}</time>
      </div>
    </div>
  );
}

function CommentCard({ activity, viewerLogin, privateThreads, pr, onDetailUpdate, onQuote }: {
  activity: Activity;
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const state = activity.payload.state?.toUpperCase();
  const actor = displayActor(activity.actor || 'github');
  const body = activity.body?.trim() ? activity.body : null;
  const [editDraft, setEditDraft] = useState<string | null>(null);
  const commentId = activity.githubId;
  const canEdit = body !== null && commentId !== null && isViewerComment(activity.actor, viewerLogin);
  const reaction = useReactionAction(
    pr.repo,
    pr.number,
    'issue_comment',
    activity.githubId,
    activity.payload.reactions,
    activity.eventType === 'commented' && activity.githubId !== null,
    (reactions, onlyIf) => onDetailUpdate(pr.repo, pr.number, current =>
      updatePullReactionDetail(current, 'issue_comment', activity.githubId, reactions, onlyIf)),
  );
  // The backend resolves review: keys from the same reviewed event this card renders.
  const threadKey = privateThreads === undefined || body === null || commentId === null ? null
    : privateThreadKey(activity.eventType === 'reviewed' ? 'review' : 'issue_comment', commentId);
  const badge = reviewBadge(state);
  if (!body) {
    return <article className="pr-card pr-review-event">
      <CardHeader actor={actor} avatarUrl={activity.payload.actorAvatarUrl} bot={activity.payload.actorBot}
        timestamp={activity.timestamp} badge={badge} reaction={reaction} />
    </article>;
  }
  if (privateThreads !== undefined && threadKey !== null && privateThreads.forward === threadKey) {
    return (
      <div className="pr-card pr-private-card" {...commentIndexTarget(activity.payload.actorBot ? 'agent' : 'human', actor, body)}>
        <PrivateThreadForward threads={privateThreads} threadKey={threadKey} author={actor} avatarUrl={activity.payload.actorAvatarUrl}
          timestamp={activity.timestamp} body={body} repository={pr.repo} viewerLogin={viewerLogin} />
      </div>
    );
  }
  return (
    <ReviewThreadCard className="pr-comment-card" resolved={false} controls={null}
      attributes={commentIndexTarget(activity.payload.actorBot ? 'agent' : 'human', actor, body)}>
      <ReviewThreadMessage timestamp={activity.timestamp}
        author={<UserAvatar login={actor} avatarUrl={activity.payload.actorAvatarUrl} size={22} showLogin />}
        tag={<>{activity.payload.actorBot && <span className="pr-bot">Bot</span>}
          {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}</>}
        menu={<>{reaction && <ReactionPicker action={reaction} />}
          {privateThreads !== undefined && threadKey !== null && <PrivateThreadControls threads={privateThreads} threadKey={threadKey}
            own={isViewerComment(activity.actor, viewerLogin)} comment={{ author: actor, body, url: activity.payload.htmlUrl ?? pr.htmlUrl, location: null }} />}
          <CardActionsMenu actions={{ link: activity.payload.htmlUrl ?? pr.htmlUrl, markdown: body,
            onQuote: () => onQuote(body), onEdit: canEdit ? () => setEditDraft(body) : undefined }} />
        </>}
        reactions={<Reactions reactions={reaction?.reactions ?? activity.payload.reactions} reaction={reaction} />}>
        {editDraft !== null && commentId !== null
          ? <CommentEditor pr={pr} target={activity.eventType === 'reviewed' ? 'review' : 'issue_comment'} commentId={commentId}
            body={body} draft={editDraft} onDraftChange={setEditDraft} onDetailUpdate={onDetailUpdate} onClose={() => setEditDraft(null)} />
          : <MarkdownBody body={body} repository={pr.repo} />}
        {privateThreads !== undefined && threadKey !== null && <PrivateThreadTab threads={privateThreads} threadKey={threadKey} />}
      </ReviewThreadMessage>
    </ReviewThreadCard>
  );
}

function ReviewGroup({ activities, viewerLogin, privateThreads, pr, workspaceId, onRefresh, onDetailUpdate, onQuote }: {
  activities: Activity[];
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const review = activities[0];
  const actor = displayActor(review.actor || 'github');
  const badge = reviewBadge(review.payload.state?.toUpperCase());
  return (
    <section className="pr-review-group">
      <div className="pr-review-group__summary">
        <span className="pr-review-group__marker" aria-hidden="true"><ReviewIcon /></span>
        <UserAvatar login={actor} avatarUrl={review.payload.actorAvatarUrl} size={24} showLogin />
        {review.payload.actorBot && <span className="pr-bot-badge">Bot</span>}
        <span>reviewed</span>
        <time>{relativeTime(review.timestamp)}</time>
        {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}
      </div>
      {review.body?.trim() && (
        <CommentCard activity={review} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} onDetailUpdate={onDetailUpdate} onQuote={onQuote} />
      )}
      {activities.slice(1).map(thread => (
        <ReviewThread key={thread.githubId ?? `${thread.timestamp}-${thread.payload.path}`} activity={thread} privateThreads={privateThreads}
          viewerLogin={viewerLogin} pr={pr} workspaceId={workspaceId} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />
      ))}
    </section>
  );
}

export function updateReviewThreadResolution(
  detail: PullRequestDetailDto,
  activityId: number | null,
  rootCommentId: number,
  resolved: boolean,
  onlyIfResolved?: boolean,
) {
  return {
    ...detail,
    recentActivity: detail.recentActivity.map(candidate => {
      const matches = candidate.eventType === 'review_thread'
        && (activityId !== null
          ? candidate.githubId === activityId
          : candidate.payload.comments.some(comment => comment.githubId === rootCommentId));
      if (!matches || (onlyIfResolved !== undefined && candidate.payload.resolved !== onlyIfResolved)) {
        return candidate;
      }
      return { ...candidate, payload: { ...candidate.payload, resolved } };
    }),
  };
}

export function ReviewThread({ activity, viewerLogin, privateThreads, pr, workspaceId, onRefresh, onDetailUpdate, showCode = true, pendingReview }: {
  activity: Activity;
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  showCode?: boolean;
  pendingReview?: React.ContextType<typeof PendingReviewComments>;
}) {
  const { payload } = activity;
  const hostPending = useContext(PendingReviewComments);
  const pending = pendingReview ?? hostPending;
  const [replying, setReplying] = useReviewDraft(`github:${activity.githubId}:replying`, false);
  const [body, setBody] = useReviewDraft(`github:${activity.githubId}:body`, '');
  const [preview, setPreview] = useState(false);
  const [posting, setPosting] = useState(false);
  const [changingResolution, setChangingResolution] = useState(false);
  const [optimisticResolved, setOptimisticResolved] = useState<boolean | null>(null);
  const [edit, setEdit] = useReviewDraft<{ commentId: number; draft: string } | null>(`github:${activity.githubId}:edit`, null);
  const [error, setError] = useState('');
  const [drafting, setDrafting] = useState(false);
  // The editor holds the agent's draft until the user chooses to post it or add it to a review.
  const [drafted, setDrafted] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const resolutionInFlight = useRef(false);
  const targetLine = payload.line ?? payload.originalLine;
  const commentedLines = showCode ? threadCommentedLines(payload) : [];
  const suggestion = reviewThreadSuggestion(payload);
  const rootCommentId = payload.comments.find(comment => comment.inReplyToId === null)?.githubId
    ?? payload.comments[0]?.githubId;
  const pendingReplies = pending?.comments.filter(comment => comment.replyToCommentId === rootCommentId) ?? [];
  const resolved = optimisticResolved ?? payload.resolved;
  const startLine = payload.line === null ? payload.originalStartLine : payload.startLine;
  const sidePrefix = payload.side === 'LEFT' ? 'L' : payload.side === 'RIGHT' ? 'R' : '';
  const isRange = startLine !== null && startLine !== targetLine;
  const location = showCode
    ? <bdi dir="ltr"><code>{threadLocation(payload.path, resolved || commentedLines.length === 0 ? targetLine : null, payload.side, startLine)}</code></bdi>
    : targetLine === null
      ? <span>Comment on file</span>
      : <span>Comment on {isRange ? 'lines' : 'line'} <strong>{isRange ? `${sidePrefix}${startLine}–` : ''}{sidePrefix}{targetLine}</strong></span>;
  const submit = async () => {
    if (!rootCommentId || !body.trim()) return;
    setPosting(true);
    setError('');
    try {
      await window.bridge.replyToReviewComment(pr.repo, pr.number, rootCommentId, body.trim());
      recordAttentionAction(`${pr.repo}#${pr.number}`, 'comment');
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not post reply.');
      setPosting(false);
      return;
    }
    setBody('');
    setPreview(false);
    setReplying(false);
    setPosting(false);
    setDrafted(false);
    await onRefresh();
  };
  const changeResolution = async () => {
    if (!rootCommentId || resolutionInFlight.current) return;
    const previous = resolved;
    const desired = !previous;
    resolutionInFlight.current = true;
    setChangingResolution(true);
    setOptimisticResolved(desired);
    setError('');
    onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
      current, activity.githubId, rootCommentId, desired));
    try {
      await window.bridge.setReviewThreadResolved(pr.repo, pr.number, rootCommentId, desired);
      recordAttentionAction(`${pr.repo}#${pr.number}`, 'update');
      onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
        current, activity.githubId, rootCommentId, desired));
    }
    catch (cause) {
      onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
        current, activity.githubId, rootCommentId, previous, desired));
      setError(cause instanceof Error ? cause.message : `Could not ${previous ? 'unresolve' : 'resolve'} thread.`);
    }
    setOptimisticResolved(null);
    resolutionInFlight.current = false;
    setChangingResolution(false);
  };
  const focusReply = () => requestAnimationFrame(() => {
    textareaRef.current?.focus();
    textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  const quoteReply = (quotedBody: string) => {
    setBody(current => appendQuotedReply(current, quotedBody));
    setPreview(false);
    setError('');
    setReplying(true);
    focusReply();
  };
  const draftWithAgent = async (thread: TaskDiscussionDto, author: string) => {
    if (privateThreads === undefined || drafting) return;
    privateThreads.setForward(null);
    setDrafting(true);
    setError('');
    try {
      const text = thread.replyDraft ?? await privateThreads.draftReply(thread);
      // The draft can take many seconds; if the user started typing elsewhere meanwhile, the caret stays there.
      const active = document.activeElement;
      const typingElsewhere = active !== textareaRef.current && active instanceof HTMLElement
        && (active.isContentEditable || active instanceof HTMLTextAreaElement || active instanceof HTMLInputElement);
      setBody(current => withMention(current, text, author));
      setReplying(true);
      setPreview(false);
      setDrafted(true);
      if (!typingElsewhere) focusReply();
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
    }
    finally {
      setDrafting(false);
    }
  };
  const addToReview = () => {
    if (!pending || !rootCommentId || !body.trim()) return;
    pending.onChange([...pending.comments, {
      replyToCommentId: rootCommentId, body: body.trim(), path: payload.path ?? '',
      line: targetLine ?? 1, side: payload.side === 'LEFT' ? 'LEFT' : 'RIGHT',
      startLine: null, startSide: null, commitId: null,
    }]);
    discard();
  };
  const discard = () => { setReplying(false); setBody(''); setPreview(false); setError(''); setDrafted(false); };
  const controls = drafting ? (
    <footer className="lrc__foot pr-thread-draft" role="status">
      <LoaderCircle className="pr-task-spinner" size={14} aria-hidden="true" /> Agent is drafting a reply from your private thread…
    </footer>
  ) : replying ? (
    <footer className="lrc__foot is-composing">
      <MarkdownEditor value={body} onChange={setBody} repository={pr.repo} suggestion={suggestion}
        textareaRef={textareaRef} autoFocus={!drafted} preview={preview} onPreviewChange={setPreview}
        ariaLabel="Reply to review thread" placeholder="Reply to this thread…" busy={posting} error={error}
        // Agent drafts require an explicit button click, never the private composer's send key.
        onSubmit={drafted ? undefined : () => { void submit(); }}
        header={drafted && <div className="pr-thread-draft__head">
          <span className="pr-thread-draft__badge">Draft · not posted</span>
          <span>Written by the agent from your private thread. Edit it before you post.</span>
        </div>}
        note={drafted && <span className="pr-thread-draft__note">Posts to GitHub as you</span>}>
        <BetterWordsButton text={body} textareaRef={textareaRef} repository={pr.repo} workspaceId={workspaceId}
          disabled={preview || posting}
          onError={failure => setError(failure === null ? '' : remoteErrorMessage(failure))} />
        <button type="button" disabled={posting} onClick={discard}>{drafted ? 'Discard' : 'Cancel'}</button>
        {pending && pr.state?.toLowerCase() === 'open' && <button type="button" disabled={posting || !body.trim()}
          onClick={addToReview}>Add to review</button>}
        <button className="is-primary" type="button" disabled={posting || !body.trim()}
          onClick={() => { void submit(); }}>{drafted ? posting ? 'Commenting…' : 'Comment' : posting ? 'Replying…' : 'Reply'}</button>
      </MarkdownEditor>
    </footer>
  ) : (
    <footer className="lrc__foot">
      <button className="lrc__ask" type="button" disabled={!rootCommentId}
        onClick={() => { setError(''); setPreview(false); setReplying(true); }}>Reply</button>
      {error && <span className="pr-thread-reply__error" role="alert">{error}</span>}
      {pendingReplies.map((comment, index) => <button key={index} className="lrc__action" type="button"
        title={comment.body} aria-label={`Remove pending reply${pendingReplies.length > 1 ? ` ${index + 1}` : ''}`}
        onClick={() => pending?.onChange(pending.comments.filter(value => value !== comment))}>
        {pendingReplies.length > 1 ? `Remove reply ${index + 1}` : 'Remove'}
      </button>)}
      <button className="lrc__action" type="button" disabled={!rootCommentId || changingResolution}
        onClick={() => { void changeResolution(); }}>
        {resolved ? 'Unresolve' : 'Resolve'}
      </button>
    </footer>
  );
  return (
    <ReviewThreadCard title={<span className={`lrc__title${showCode ? ' is-path' : ''}`} title={showCode ? payload.path ?? undefined : undefined}>{location}</span>}
      resolved={resolved} outdated={payload.outdated}
      badges={pendingReplies.length > 0 && <span className="pull-diff-pending__badge">Pending</span>}
      code={commentedLines.length > 0 && <CodeExcerpt path={payload.path} lines={commentedLines} />} controls={controls}>
      {payload.comments.map(comment => <ThreadComment comment={comment} fallbackUrl={payload.htmlUrl ?? pr.htmlUrl} suggestion={suggestion}
        location={threadLocation(payload.path, targetLine, payload.side, startLine)}
        indexLabel={`${threadLocation(payload.path, targetLine, payload.side, startLine)} · ${resolved ? 'Resolved' : 'Open'} · ${payload.outdated ? 'Outdated · ' : ''}`}
        viewerLogin={viewerLogin} pr={pr} onDetailUpdate={onDetailUpdate} onQuote={quoteReply} edit={edit} onEditChange={setEdit}
        privateThreads={privateThreads}
        onDraftReply={rootCommentId === undefined || rootCommentId === null ? undefined
          : thread => { void draftWithAgent(thread, displayActor(comment.actor ?? '')); }}
        key={comment.githubId ?? comment.timestamp} />)}
      {pendingReplies.map((comment, index) => (
        <ReviewThreadMessage key={index} timestamp={null}
          author={viewerLogin ? <UserAvatar login={viewerLogin} size={22} showLogin /> : <strong>You</strong>}>
          <MarkdownBody body={comment.body} repository={pr.repo} />
        </ReviewThreadMessage>
      ))}
    </ReviewThreadCard>
  );
}

function ThreadComment({ comment, fallbackUrl, suggestion, viewerLogin, pr, onDetailUpdate, onQuote, edit, onEditChange, privateThreads, onDraftReply, location, indexLabel }: {
  comment: PullTimelineCommentDto;
  location: string;
  privateThreads?: PrivateThreads;
  onDraftReply?: (thread: TaskDiscussionDto) => void;
  fallbackUrl: string;
  suggestion?: SuggestionBase;
  indexLabel: string;
  viewerLogin?: string;
  pr: PullRequestDto;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
  edit: { commentId: number; draft: string } | null;
  onEditChange: (edit: { commentId: number; draft: string } | null) => void;
}) {
  const commentId = comment.githubId;
  const canEdit = Boolean(comment.body) && commentId !== null && isViewerComment(comment.actor, viewerLogin);
  const editDraft = commentId !== null && edit?.commentId === commentId ? edit.draft : null;
  const reaction = useReactionAction(
    pr.repo,
    pr.number,
    'review_comment',
    comment.githubId,
    comment.reactions,
    comment.githubId !== null,
    (reactions, onlyIf) => onDetailUpdate(pr.repo, pr.number, current =>
      updatePullReactionDetail(current, 'review_comment', comment.githubId, reactions, onlyIf)),
  );
  const threadKey = privateThreads === undefined || commentId === null || !comment.body ? null : privateThreadKey('review_comment', commentId);
  if (privateThreads !== undefined && threadKey !== null && privateThreads.forward === threadKey) {
    return <div {...commentIndexTarget(comment.actorBot ? 'agent' : 'human', `${comment.actor ?? 'github'} · ${indexLabel}`, comment.body ?? '')}>
      <PrivateThreadForward threads={privateThreads} threadKey={threadKey} author={displayActor(comment.actor ?? 'github')}
      avatarUrl={comment.actorAvatarUrl} timestamp={comment.timestamp} body={comment.body ?? ''} repository={pr.repo}
      viewerLogin={viewerLogin} onDraftReply={onDraftReply} /></div>;
  }
  return (
    <ReviewThreadMessage timestamp={comment.timestamp}
      attributes={commentIndexTarget(comment.actorBot ? 'agent' : 'human', `${comment.actor ?? 'github'} · ${indexLabel}`, comment.body ?? '')} author={<>
        <UserAvatar login={comment.actor ?? 'github'} avatarUrl={comment.actorAvatarUrl} size={22} showLogin />
        {comment.actorBot && <span className="pr-bot">Bot</span>}
      </>}
      menu={<>{reaction && <ReactionPicker action={reaction} />}
        {privateThreads !== undefined && threadKey !== null && <PrivateThreadControls threads={privateThreads} threadKey={threadKey}
          own={isViewerComment(comment.actor, viewerLogin)}
          comment={{ author: displayActor(comment.actor ?? 'github'), body: comment.body ?? '', url: comment.htmlUrl ?? fallbackUrl, location }} />}
        {comment.body && <CardActionsMenu actions={{
          link: comment.htmlUrl ?? fallbackUrl,
          markdown: comment.body,
          onQuote: () => onQuote(comment.body ?? ''),
          onEdit: canEdit && commentId !== null ? () => onEditChange({ commentId, draft: comment.body ?? '' }) : undefined,
        }} />}</>}
      reactions={<Reactions reactions={reaction?.reactions ?? comment.reactions} reaction={reaction} />}>
      {comment.body && (editDraft !== null && commentId !== null
        ? <CommentEditor pr={pr} target="review_comment" commentId={commentId} body={comment.body} suggestion={suggestion}
          draft={editDraft} onDraftChange={draft => onEditChange({ commentId, draft })}
          onDetailUpdate={onDetailUpdate} onClose={() => onEditChange(null)} />
        : <MarkdownBody body={comment.body} repository={pr.repo} suggestion={suggestion} />)}
      {privateThreads !== undefined && threadKey !== null && <PrivateThreadTab threads={privateThreads} threadKey={threadKey} />}
    </ReviewThreadMessage>
  );
}

/** GitHub stores web-typed comments with CRLF, but a textarea only ever yields LF. */
export function canUpdateComment(draft: string, body: string) {
  return draft.trim() !== '' && draft.replaceAll('\r\n', '\n') !== body.replaceAll('\r\n', '\n');
}

/**
 * Edits a posted comment in place. The owner seeds `draft` from the body once, when Edit is chosen, so a
 * refresh that changes the body never clobbers it.
 */
export function CommentEditor({ pr, target, commentId, body, draft, suggestion, onDraftChange, onDetailUpdate, onClose }: {
  pr: PullRequestDto;
  target: PullCommentTarget;
  commentId: number;
  body: string;
  draft: string;
  suggestion?: SuggestionBase;
  onDraftChange: (draft: string) => void;
  onDetailUpdate: PullDetailUpdater;
  onClose: () => void;
}) {
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const canSave = !saving && canUpdateComment(draft, body);
  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    setError('');
    try {
      const response = await window.bridge.editPrComment(pr.repo, pr.number, target, commentId, draft);
      recordAttentionAction(`${pr.repo}#${pr.number}`, 'update');
      onDetailUpdate(pr.repo, pr.number, current => mergePullRequestWriteDetail(current, response));
      onClose();
    }
    catch (failure) {
      setError(`Could not update comment. ${remoteErrorMessage(failure)}`);
      setSaving(false);
    }
  };
  return (
    <MarkdownEditor value={draft} onChange={onDraftChange} repository={pr.repo} suggestion={suggestion}
      textareaRef={textareaRef} autoFocus ariaLabel="Edit comment" preview={preview} onPreviewChange={setPreview}
      busy={saving} error={error} onSubmit={() => { void save(); }}>
      <button type="button" disabled={saving} onClick={onClose}>Cancel</button>
      <button className="is-primary" type="button" disabled={!canSave}
        onClick={() => { void save(); }}>{saving ? 'Updating…' : 'Update comment'}</button>
    </MarkdownEditor>
  );
}

/** The commented lines of a range, read from a thread's diff_hunk or from a whole-file patch of several hunks. */
export function commentedCodeLines(patch: string | null, side: string | null, startLine: number | null, endLine: number | null): ReviewCommentLine[] {
  if (endLine === null) return [];
  const left = side?.toUpperCase() === 'LEFT';
  const first = startLine ?? endLine;
  return parseUnifiedDiff(patch).flatMap(hunk => hunk.rows)
    .map((row): ReviewCommentLine => ({
      lineNumber: left ? row.oldLine : row.newLine,
      prefix: row.kind === 'add' ? '+' : row.kind === 'del' ? '-' : ' ',
      text: row.content,
    }))
    .filter(line => line.lineNumber !== null && line.lineNumber >= first && line.lineNumber <= endLine);
}

/** A thread's range in its own diff_hunk, which GitHub records as the diff at the comment's original commit. */
function threadHunkRange(payload: PullTimelinePayloadDto): { start: number; end: number } | null {
  const end = payload.originalLine ?? payload.line;
  if (end === null) return null;
  return { start: (payload.originalLine !== null ? payload.originalStartLine : payload.startLine) ?? end, end };
}

/** The code a review thread comments on, as its diff_hunk recorded it. */
export function threadCommentedLines(payload: PullTimelinePayloadDto): ReviewCommentLine[] {
  const range = threadHunkRange(payload);
  return range === null ? [] : commentedCodeLines(payload.diffHunk, payload.side, range.start, range.end);
}

/** The head-side lines a RIGHT review thread's suggestion replaces. */
export function reviewThreadSuggestion(payload: PullTimelinePayloadDto): SuggestionBase | undefined {
  const range = threadHunkRange(payload);
  if (payload.side?.toUpperCase() !== 'RIGHT' || !payload.diffHunk || range === null) return undefined;
  const lines = threadCommentedLines(payload);
  return {
    startLine: payload.line !== null ? payload.startLine ?? payload.line : range.start,
    lines: lines.length > 0 && lines.length === range.end - range.start + 1 ? lines.map(line => line.text) : null,
  };
}

function CompactEvent({ activities, headRef, pr }: { activities: Activity[]; headRef: string | null; pr: PullRequestDto }) {
  const activity = activities[0];
  const { payload } = activity;
  const actor = displayActor(activity.actor || 'github');
  if (activity.eventType === 'review_requested') {
    return (
      <div className="pr-review-group__summary">
        <span className="pr-review-group__marker" aria-hidden="true"><ReviewIcon /></span>
        <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
        {payload.actorBot && <span className="pr-bot">Bot</span>}
        <span>{eventText(activity, activities, headRef)}</span>
        <time dateTime={activity.timestamp ?? undefined}>{relativeTime(activity.timestamp, { style: 'long' })}</time>
      </div>
    );
  }
  if (LABEL_EVENT_TYPES.has(activity.eventType)) {
    return (
      <div className="pr-event pr-label-event">
        <span className="pr-label-event__marker" aria-hidden="true"><Tag size={15} strokeWidth={1.4} aria-hidden="true" /></span>
        <div className="pr-label-event__content">
          <span className="pr-label-event__actor">
            <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
            {payload.actorBot && <span className="pr-bot">Bot</span>}
          </span>
          <span>{activity.eventType === 'labeled' ? 'added' : 'removed'}</span>
          <span className="pr-label-event__labels">
            {activities.map(labelActivity => (
              <LabelBadge name={labelActivity.payload.label ?? 'label'} color={labelActivity.payload.labelColor}
                key={labelActivity.githubId ?? labelActivity.payload.label} />
            ))}
          </span>
          {activities.length > 1 && <span>labels</span>}
          <time>· {relativeTime(activity.timestamp)}</time>
        </div>
      </div>
    );
  }
  return (
    <div className="pr-event pr-actor-event">
      <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
      <div>
        {payload.actorBot && <span className="pr-bot">Bot</span>}{' '}
        <span>{eventText(activity, activities, headRef)}</span>
        <time> · {relativeTime(activity.timestamp)}</time>
        {activity.eventType === 'removed_from_merge_queue' && <MergeQueueRemoval activity={activity} repo={pr.repo} number={pr.number} />}
      </div>
    </div>
  );
}

function eventText(activity: Activity, activities: Activity[], headRef: string | null) {
  const p = activity.payload;
  switch (activity.eventType) {
    case 'labeled': return <>added <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'unlabeled': return <>removed <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'renamed': return <>changed the title from <del>{p.renameFrom}</del> to <strong>{p.renameTo}</strong></>;
    case 'cross-referenced': return <>mentioned this in <GitHubReferenceLink href={p.sourceUrl}>{p.sourceTitle ?? `#${p.sourceNumber ?? ''}`}</GitHubReferenceLink></>;
    case 'review_requested': {
      const reviewers = [...new Set(activities
        .map(item => item.payload.requestedReviewer)
        .filter((reviewer): reviewer is string => typeof reviewer === 'string' && reviewer.length > 0))]
        .sort((left, right) => left.localeCompare(right));
      if (reviewers.length === 0) return <>requested a review</>;
      return <>requested a review from {reviewers.slice(0, 3).map((reviewer, index) => {
        const [owner, team] = reviewer.split('/');
        const href = team ? `https://github.com/orgs/${encodeURIComponent(owner)}/teams/${encodeURIComponent(team)}`
          : `https://github.com/${encodeURIComponent(reviewer)}`;
        return <span key={reviewer}>{index === 0 ? '' : index === reviewers.length - 1 ? ' and ' : ', '}
          <a href={href} onClick={event => { event.preventDefault(); void openWebLink(href); }}><strong>{reviewer}</strong></a>
        </span>;
      })}{reviewers.length > 3 && ` and ${reviewers.length - 3} other ${reviewers.length === 4 ? 'reviewer' : 'reviewers'}`}</>;
    }
    case 'review_request_removed': return <>removed a review request</>;
    case 'assigned': return <>assigned{p.assignee ? <> <strong>{p.assignee}</strong></> : null}</>;
    case 'unassigned': return <>unassigned{p.assignee ? <> <strong>{p.assignee}</strong></> : null}</>;
    case 'milestoned': return <>added this to the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'demilestoned': return <>removed this from the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'added_to_merge_queue': return <>added this to the merge queue</>;
    case 'removed_from_merge_queue': return <>removed this from the merge queue</>;
    case 'head_ref_force_pushed': return <>force-pushed the head branch{activities.length > 1 ? ` ${activities.length} times` : ''}</>;
    case 'head_ref_deleted': return <>deleted the {headRef ? <code>{headRef}</code> : 'head'} branch</>;
    case 'convert_to_draft': return <>converted this pull request to draft</>;
    case 'ready_for_review': return <>marked this pull request ready for review</>;
    case 'closed': return <>closed this</>;
    case 'merged': return <>merged</>;
    case 'reopened': return <>reopened this</>;
    case 'ci_autofix': return <>{activity.body ?? 'updated this pull request'}</>;
    default: return <>{activity.eventType.replaceAll('_', ' ')}</>;
  }
}

type CardActions = {
  link: string;
  markdown: string;
  onQuote: () => void;
  editLabel?: string;
  onEdit?: () => void;
};

export function CardHeader({ actor, avatarUrl, bot = false, timestamp, badge, reaction, actions }: {
  actor: string;
  avatarUrl?: string | null;
  bot?: boolean;
  timestamp: string | null;
  badge?: { text: string; className: string } | null;
  reaction?: ReactionAction;
  actions?: CardActions;
}) {
  return (
    <header className="pr-card__header">
      <UserAvatar login={actor} avatarUrl={avatarUrl} size={22} showLogin />
      {bot && <span className="pr-bot">Bot</span>}
      {timestamp && <><span>·</span><time>{relativeTime(timestamp)}</time></>}
      {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}
      {(reaction || actions) && <div className="pr-card__menu">
        {reaction && <ReactionPicker action={reaction} />}
        {actions && <CardActionsMenu actions={actions} />}
      </div>}
    </header>
  );
}

function CardActionsMenu({ actions }: { actions: CardActions }) {
  const [open, setOpen] = useState(false);

  const root = usePopoverDismiss(() => setOpen(false), false, open);

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value);
    setOpen(false);
  };

  return (
    <div ref={root} className={`pr-card-actions${open ? ' is-open' : ''}`}>
      <button type="button" className="pr-card-actions__trigger" aria-label="More actions"
        aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(value => !value)}><Ellipsis size={18} aria-hidden="true" /></button>
      <div className="pr-card-actions__menu" role="menu" aria-label="Comment actions">
        {actions.onEdit && <button type="button" role="menuitem" onClick={() => {
          actions.onEdit?.();
          setOpen(false);
        }}><Pencil size={16} strokeWidth={1.4} aria-hidden="true" />{actions.editLabel ?? 'Edit'}</button>}
        <button type="button" role="menuitem" onClick={() => { void copy(actions.link); }}><Link size={16} strokeWidth={1.4} aria-hidden="true" />Copy link</button>
        <button type="button" role="menuitem" onClick={() => { void copy(actions.markdown); }}><CopyIcon />Copy Markdown</button>
        <button type="button" role="menuitem" onClick={() => { actions.onQuote(); setOpen(false); }}><Quote size={16} strokeWidth={1.4} aria-hidden="true" />Quote reply</button>
      </div>
    </div>
  );
}

function ChecksCard({ pr, detail, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onRefresh: () => Promise<void>;
}) {
  const [rerunning, setRerunning] = useState(false);
  const [rerunRequested, setRerunRequested] = useState(false);
  const [rerunError, setRerunError] = useState<string | null>(null);
  const rerunLabel = rerunning ? 'Requesting…' : rerunRequested ? 'Rerun requested' : 'Rerun failed tests';
  const groups = useMemo(() => {
    const out = Map.groupBy(detail.checkRuns, check => check.category);
    return (['failing', 'pending', 'successful', 'neutral', 'skipped', 'cancelled'] as const)
      .map(category => [category, out.get(category) ?? []] as const)
      .filter(([, checks]) => checks.length > 0);
  }, [detail.checkRuns]);
  const passing = detail.ciStatus === 'PASSING';
  const failing = detail.ciStatus === 'FAILING';
  const pending = detail.ciStatus === 'PENDING';
  const running = detail.checkRuns.some(check => check.status === 'in_progress');
  const SummaryIcon = passing ? CircleCheck : failing ? CircleX : pending ? running ? LoaderCircle : Clock3 : CircleDot;
  const canRerun = detail.viewerCanWrite && detail.checkRuns.some(check =>
    check.category === 'failing'
      && check.source === 'check_run'
      && check.detailsUrl?.includes('/actions/runs/'));
  useEffect(() => {
    if (detail.ciStatus !== 'FAILING') setRerunRequested(false);
  }, [detail.ciStatus]);
  const rerun = async () => {
    setRerunning(true);
    setRerunError(null);
    try {
      await window.bridge.rerunFailedPrChecks(pr.repo, pr.number);
      recordAttentionAction(`${pr.repo}#${pr.number}`, 'update');
      setRerunRequested(true);
      await onRefresh();
    }
    catch (failure) {
      setRerunError(remoteErrorMessage(failure));
    }
    finally {
      setRerunning(false);
    }
  };
  return (
    <article className="pr-checks">
      <header><span className={passing ? 'is-success' : failing ? 'is-failure' : pending ? 'is-pending' : 'is-neutral'}>
        <SummaryIcon className={pending && running ? 'is-running' : undefined} aria-hidden="true" /></span>
        <strong>{passing ? 'All checks have passed' : failing ? 'Some checks were not successful' : pending ? 'Checks are in progress' : 'No checks reported'}</strong>
        {canRerun && <button type="button" className="pr-checks__rerun" disabled={rerunning || rerunRequested}
          aria-label={rerunLabel} title={rerunLabel} onClick={() => { void rerun(); }}>
          {rerunning ? <LoaderCircle size={16} className="is-running" aria-hidden="true" />
            : rerunRequested ? <Check size={16} aria-hidden="true" /> : <RotateCw size={16} aria-hidden="true" />}
        </button>}
      </header>
      {rerunError && <div className="pr-checks__error" role="alert">{rerunError}</div>}
      <div className="pr-checks__groups">
        {groups.length === 0 && <span>No checks reported.</span>}
        {groups.map(([category, checks], index) => (
          <details key={category} open={index === 0 && category === 'failing'}>
            <summary>{groupLabel(category)} ({checks.length}) <span>›</span></summary>
            {checks.map((check, checkIndex) => (
              <CheckRow key={`${pr.repo}:${pr.number}:${pr.headPushedAt}:${check.source}:${check.githubId ?? checkIndex}:${check.detailsUrl}:${check.startedAt}:${check.completedAt}:${check.category}`}
                pr={pr} check={check} />
            ))}
          </details>
        ))}
      </div>
    </article>
  );
}

export function githubActionsJobId(check: PullRequestDetailDto['checkRuns'][number]): number | null {
  if (check.source !== 'check_run' || !check.detailsUrl) return null;
  try {
    const url = new URL(check.detailsUrl);
    const match = url.pathname.match(/^\/[^/]+\/[^/]+\/actions\/runs\/\d+\/job\/(\d+)\/?$/);
    const id = Number(match?.[1]);
    return url.origin === 'https://github.com' && Number.isSafeInteger(id) && id > 0 ? id : null;
  }
  catch {
    return null;
  }
}

const CI_LOG_KEYWORDS = /(\b(?:errors?|exceptions?|failed|failures?|fatal|panic|traceback|assertion|assert|mismatch|\w*(?:Exception|Error))\b|\b(?:Caused by|Suppressed|timed out|segmentation fault|cannot find symbol)\b:?)/gi;

function CiLogText({ text }: { text: string }) {
  return text.split(CI_LOG_KEYWORDS).map((part, index) => index % 2 === 0 ? part : <mark key={index}>{part}</mark>);
}

function CheckRow({ pr, check }: { pr: PullRequestDto; check: PullRequestDetailDto['checkRuns'][number] }) {
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [log, setLog] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [browserError, setBrowserError] = useState<string | null>(null);
  const logId = useId();
  const logLabel = loading ? 'Loading…' : error ? 'Retry log' : expanded ? 'Hide log' : 'Show log';
  const jobId = githubActionsJobId(check);
  const browserUrl = check.detailsUrl && /^https?:\/\//i.test(check.detailsUrl) ? check.detailsUrl : null;
  const openBrowser = async () => {
    if (browserUrl === null) return;
    setBrowserError(null);
    try {
      await window.bridge.openExternal(browserUrl);
    }
    catch (failure) {
      setBrowserError(remoteErrorMessage(failure));
    }
  };
  const toggleLog = async () => {
    if (expanded && error === null) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if ((log !== null && error === null) || jobId === null) return;
    setLoading(true);
    setError(null);
    try {
      const result = await window.bridge.getPrCheckLog(pr.repo, pr.number, jobId);
      setLog(previous => result.unavailableReason === null ? result.text : result.text || previous);
      setError(result.unavailableReason);
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
    }
    finally {
      setLoading(false);
    }
  };
  return (
    <div className="pr-check">
      <CheckStatusIcon check={check} />
      <strong>{check.name ?? 'Check'}</strong>
      {check.completedAt
        ? <time dateTime={check.completedAt}>Finished {relativeTime(check.completedAt)}</time>
        // GitHub stamps started_at when a run is created, so only in_progress has really started.
        : check.startedAt && <time dateTime={check.startedAt}>
          {check.status === 'in_progress' ? 'Started' : 'Queued'} {relativeTime(check.startedAt)}</time>}
      <span className="pr-check__actions">
        {check.source === 'status' && <small>status</small>}
        {check.category === 'failing' && <>
          <button type="button" disabled={browserUrl === null} aria-label="Open in GitHub" title="Open in GitHub"
            onClick={() => { void openBrowser(); }}>
            <ExternalLink size={16} aria-hidden="true" />
          </button>
          <button type="button" aria-label={logLabel} aria-expanded={expanded} aria-controls={logId} disabled={loading || jobId === null}
            title={jobId === null ? 'Inline logs are available for GitHub Actions jobs' : logLabel}
            onClick={() => { void toggleLog(); }}>
            {loading ? <LoaderCircle size={16} className="is-running" aria-hidden="true" />
              : error ? <RotateCw size={16} aria-hidden="true" /> : <FileText size={16} aria-hidden="true" />}
          </button>
        </>}
      </span>
      {browserError && <div className="pr-check__log"><div role="alert">{browserError}</div></div>}
      {expanded && <div className="pr-check__log" id={logId}>
        {loading && <div role="status">Loading log…</div>}
        {error && <div role="alert">{error}</div>}
        {log !== null && <>
          <p>{error ? 'GitHub failure annotations. Open in GitHub for job details.' : 'Diagnostic excerpt. Open in GitHub for the complete log.'}</p>
          <pre tabIndex={0} aria-label={`${check.name ?? 'Check'} log`}><CiLogText text={log || 'This job returned an empty log.'} /></pre>
        </>}
      </div>}
    </div>
  );
}

const CHECK_STATUS_ICONS = {
  failing: { Icon: CircleX, label: 'Failed' },
  pending: { Icon: Clock3, label: 'Pending' },
  successful: { Icon: CircleCheck, label: 'Successful' },
  neutral: { Icon: CircleDot, label: 'Neutral' },
  skipped: { Icon: CircleMinus, label: 'Skipped' },
  cancelled: { Icon: CircleSlash2, label: 'Cancelled' },
} as const;

function CheckStatusIcon({ check }: { check: PullRequestDetailDto['checkRuns'][number] }) {
  const running = check.category === 'pending' && check.status === 'in_progress';
  const queued = check.category === 'pending' && check.status === 'queued';
  const { Icon, label } = running
    ? { Icon: LoaderCircle, label: 'Running' }
    : queued ? { Icon: Clock3, label: 'Queued' } : CHECK_STATUS_ICONS[check.category];
  return <Icon className={`pr-check__icon is-${check.category}${running ? ' is-running' : ''}`} role="img" aria-label={label} />;
}

export type MentionCandidate = { login: string; avatarUrl: string | null };

// ponytail: one pending request at a time; asking to comment on another pull request first replaces it.
let commentRequest: string | null = null;

/** Has the next comment box drawn for this pull request take focus, as the Assistant's Comment opens it to write in. */
export function requestPullComment(repo: string, number: number): void {
  commentRequest = `${repo.toLowerCase()}#${number}`;
}
type ActiveMention = { start: number; query: string };
export type QuoteRequest = { id: number; body: string };

export function DiscussionComposer({
  betterWords = false,
  workspaceId,
  candidates,
  repository,
  pullRequestNumber,
  quotedReply,
  closeLabel = 'Close pull request',
  onClose,
  onDraftChange,
  onSubmit,
}: {
  betterWords?: boolean;
  workspaceId?: string;
  candidates: MentionCandidate[];
  repository: string;
  pullRequestNumber?: number;
  quotedReply?: QuoteRequest;
  closeLabel?: string;
  onClose?: () => Promise<void>;
  /** Called as the composer settles, so a reply never posted stays findable. */
  onDraftChange?: (body: string) => void;
  onSubmit: (body: string) => Promise<void>;
}) {
  const [body, setBody] = useState('');
  const [preview, setPreview] = useState(false);
  const [posting, setPosting] = useState(false);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftRevision, setDraftRevision] = useState(0);
  const hadDraft = useRef(false);
  const [mention, setMention] = useState<ActiveMention | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const command = usePrCommentCommand({ repository, number: pullRequestNumber, value: body, onChange: setBody, textareaRef });
  const visibleCandidates = mention
    ? candidates.filter(candidate => candidate.login.toLowerCase().includes(mention.query.toLowerCase())).slice(0, 8)
    : [];
  useEffect(() => {
    if (pullRequestNumber === undefined || commentRequest !== `${repository.toLowerCase()}#${pullRequestNumber}`) return;
    commentRequest = null;
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, [repository, pullRequestNumber]);
  useEffect(() => {
    if (!quotedReply) return;
    setBody(current => appendQuotedReply(current, quotedReply.body));
    setPreview(false);
    setMention(null);
    setError(null);
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, [quotedReply]);
  useEffect(() => {
    if (!onDraftChange || (!body && !hadDraft.current)) return;
    // An untouched composer must not clear the saved text from an earlier visit.
    hadDraft.current = true;
    const timer = window.setTimeout(() => onDraftChange(body), 1200);
    return () => window.clearTimeout(timer);
  }, [body, onDraftChange]);
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
    if (!body.trim() || command.loading) return;
    command.dismiss();
    setPosting(true);
    setError(null);
    try {
      await onSubmit(body.trim());
      setBody('');
      setDraftRevision(revision => revision + 1);
    }
    finally { setPosting(false); }
  };
  const close = async () => {
    if (!onClose) return;
    setClosing(true);
    setError(null);
    // A draft goes out first, as GitHub's "Close with comment" does; a failed post leaves it open.
    try {
      await submit();
      await onClose();
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setClosing(false); }
  };
  return (
    <section className="pr-composer">
      <header>
        <button className={!preview ? 'is-active' : ''} type="button" onClick={() => {
          setPreview(false);
          requestAnimationFrame(() => textareaRef.current?.focus());
        }}>Write</button>
        <button className={preview ? 'is-active' : ''} type="button" onClick={() => { setPreview(true); setMention(null); command.dismiss(); }}>Preview</button>
      </header>
      {preview && <MarkdownBody body={body || 'Nothing to preview.'} repository={repository} />}
      <div className="pr-composer__input" hidden={preview}>
          <textarea ref={textareaRef} value={body} placeholder="Add a comment" rows={7}
            disabled={posting || closing}
            {...command.inputProps}
            aria-controls={mention !== null ? 'pr-mention-list' : command.inputProps['aria-controls']}
            aria-expanded={mention !== null || command.inputProps['aria-expanded']}
            onBlur={() => { setMention(null); command.dismiss(); }}
            onChange={event => {
              command.change(event);
              setError(null);
              setMention(activeMention(event.target.value, event.target.selectionStart));
              setMentionIndex(0);
            }}
            onKeyDown={event => {
              if (command.keyDown(event)) return;
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
          {command.suggestion}
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
        </div>
      {error && <span className="pr-composer__error" role="alert">{error}</span>}
      <footer>
        {betterWords && <BetterWordsButton key={draftRevision} text={body} textareaRef={textareaRef}
          repository={repository} workspaceId={workspaceId}
          disabled={preview || posting || closing || command.loading}
          onError={failure => setError(failure === null ? null : remoteErrorMessage(failure))} />}
        {onClose && <button className="pr-composer__close" type="button" disabled={posting || closing || command.loading}
          onClick={() => { void close(); }}>{closing ? 'Closing…' : body.trim() ? 'Close with comment' : closeLabel}</button>}
        <button type="button" disabled={posting || closing || command.loading || !body.trim()}
          onClick={() => { void submit().catch(failure => setError(remoteErrorMessage(failure))); }}>
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

export function appendQuotedReply(draft: string, body: string) {
  const normalized = body.replaceAll('\r\n', '\n').replace(/^\n+|\n+$/g, '');
  const quote = normalized.split('\n').map(line => `> ${line}`).join('\n');
  const existing = draft.trimEnd();
  return `${existing}${existing ? '\n\n' : ''}${quote}\n\n`;
}

type ReactionAction = {
  repo: string;
  number: number;
  target: PullReactionTarget;
  commentId: number | null;
  reactions: PullReactionDto[];
  saving: boolean;
  error: string | null;
  intent: (content: PullReactionContent) => boolean | null;
  submit: (content: PullReactionContent) => Promise<boolean>;
};

type ReactionRequest = Pick<ReactionAction, 'repo' | 'number' | 'target' | 'commentId' | 'reactions'>;
type ReactionChange = (reactions: PullReactionDto[], onlyIf?: PullReactionDto[]) => void;

export function nextViewerReaction(reactions: PullReactionDto[], content: PullReactionContent) {
  const existing = reactions.find(reaction => reaction.content === content);
  if (!existing) return true;
  return typeof existing.viewerHasReacted === 'boolean' ? !existing.viewerHasReacted : null;
}

export function applyViewerReaction(
  reactions: PullReactionDto[],
  content: PullReactionContent,
  viewerHasReacted: boolean,
) {
  const index = reactions.findIndex(reaction => reaction.content === content);
  if (index < 0) {
    return viewerHasReacted
      ? [...reactions, { content, count: 1, viewerHasReacted: true }]
      : reactions;
  }
  const existing = reactions[index];
  const delta = typeof existing.viewerHasReacted === 'boolean'
    ? Number(viewerHasReacted) - Number(existing.viewerHasReacted)
    : viewerHasReacted ? 1 : -1;
  const count = Math.max(0, existing.count + delta);
  if (count === 0) return reactions.filter((_, reactionIndex) => reactionIndex !== index);
  return reactions.map((reaction, reactionIndex) => reactionIndex === index
    ? { ...reaction, count, viewerHasReacted }
    : reaction);
}

export function updatePullReactionDetail(
  detail: PullRequestDetailDto,
  target: PullReactionTarget,
  commentId: number | null,
  reactions: PullReactionDto[],
  onlyIf?: PullReactionDto[],
) {
  if (target === 'pull_request') {
    let found = false;
    const recentActivity = detail.recentActivity.map(activity => {
      if (activity.eventType !== 'description_reactions') return activity;
      found = true;
      if (onlyIf !== undefined && activity.payload.reactions !== onlyIf) return activity;
      return { ...activity, payload: { ...activity.payload, reactions } };
    });
    if (!found && onlyIf === undefined) {
      recentActivity.push({
        actor: '',
        eventType: 'description_reactions',
        timestamp: null,
        body: null,
        githubId: null,
        payload: { ...EMPTY_TIMELINE_PAYLOAD, reactions },
      });
    }
    return { ...detail, recentActivity };
  }
  if (commentId === null) return detail;
  const recentActivity = detail.recentActivity.map(activity => {
    if (target === 'issue_comment') {
      if (activity.eventType !== 'commented' || activity.githubId !== commentId
          || (onlyIf !== undefined && activity.payload.reactions !== onlyIf)) return activity;
      return { ...activity, payload: { ...activity.payload, reactions } };
    }
    const comments = activity.payload.comments.map(comment => {
      if (comment.githubId !== commentId || (onlyIf !== undefined && comment.reactions !== onlyIf)) return comment;
      return { ...comment, reactions };
    });
    return comments.some((comment, index) => comment !== activity.payload.comments[index])
      ? { ...activity, payload: { ...activity.payload, comments } }
      : activity;
  });
  return { ...detail, recentActivity };
}

function reviewThreadKey(activity: Activity) {
  const rootCommentId = activity.payload.comments.find(comment => comment.inReplyToId === null)?.githubId
    ?? activity.payload.comments[0]?.githubId;
  return activity.githubId !== null ? `thread:${activity.githubId}` : `comment:${rootCommentId ?? ''}`;
}

/** Applies a write response without discarding independent optimistic fields. */
export function mergePullRequestWriteDetail(
  current: PullRequestDetailDto,
  response: PullRequestDetailDto,
): PullRequestDetailDto {
  const issueReactions = new Map<number, PullReactionDto[]>();
  const reviewReactions = new Map<number, PullReactionDto[]>();
  const threadResolution = new Map<string, boolean>();
  let descriptionReactions: PullReactionDto[] | undefined;
  current.recentActivity.forEach(activity => {
    if (activity.eventType === 'description_reactions') descriptionReactions = activity.payload.reactions;
    if (activity.eventType === 'commented' && activity.githubId !== null) {
      issueReactions.set(activity.githubId, activity.payload.reactions);
    }
    if (activity.eventType === 'review_thread') {
      threadResolution.set(reviewThreadKey(activity), activity.payload.resolved);
    }
    activity.payload.comments.forEach(comment => {
      if (comment.githubId !== null) reviewReactions.set(comment.githubId, comment.reactions);
    });
  });
  let merged = {
    ...response,
    title: current.title,
    body: current.body,
    labels: current.labels,
    assignees: current.assignees,
    reviewers: current.reviewers,
    recentActivity: response.recentActivity.map(activity => ({
      ...activity,
      payload: {
        ...activity.payload,
        resolved: activity.eventType === 'review_thread'
          ? threadResolution.get(reviewThreadKey(activity)) ?? activity.payload.resolved
          : activity.payload.resolved,
        reactions: activity.eventType === 'commented' && activity.githubId !== null
          ? issueReactions.get(activity.githubId) ?? activity.payload.reactions
          : activity.payload.reactions,
        comments: activity.payload.comments.map(comment => ({
          ...comment,
          reactions: comment.githubId === null
            ? comment.reactions
            : reviewReactions.get(comment.githubId) ?? comment.reactions,
        })),
      },
    })),
  };
  if (descriptionReactions !== undefined) {
    merged = updatePullReactionDetail(merged, 'pull_request', null, descriptionReactions);
  }
  return merged;
}

function serverReflectsReaction(
  reactions: PullReactionDto[],
  content: PullReactionContent,
  viewerHasReacted: boolean,
) {
  const reaction = reactions.find(value => value.content === content);
  if (!reaction) return !viewerHasReacted;
  return reaction.viewerHasReacted === viewerHasReacted;
}

function useReactionAction(
  repo: string,
  number: number,
  target: PullReactionTarget,
  commentId: number | null,
  serverReactions: PullReactionDto[],
  enabled = true,
  onChange?: ReactionChange,
): ReactionAction | undefined {
  const key = `${repo.toLowerCase()}#${number}:${target}:${commentId ?? ''}`;
  const [local, setLocal] = useState<{ key: string; reactions: PullReactionDto[] } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const confirmation = useRef<{
    key: string;
    content: PullReactionContent;
    viewerHasReacted: boolean;
  } | null>(null);
  const optimisticEcho = useRef<{ key: string; reactions: PullReactionDto[] } | null>(null);
  const reactions = local?.key === key ? local.reactions : serverReactions;

  useEffect(() => {
    setError(null);
    confirmation.current = null;
    optimisticEcho.current = null;
    setLocal(current => current?.key === key ? current : null);
  }, [key]);

  useEffect(() => {
    const expected = confirmation.current;
    if (inFlight.current || local?.key !== key || expected?.key !== key
        || (optimisticEcho.current?.key === key && optimisticEcho.current.reactions === serverReactions)
        || !serverReflectsReaction(serverReactions, expected.content, expected.viewerHasReacted)) return;
    confirmation.current = null;
    optimisticEcho.current = null;
    setLocal(null);
  }, [key, local, serverReactions]);

  const submit = async (content: PullReactionContent) => {
    if (inFlight.current) return false;
    const base = local?.key === key ? local.reactions : serverReactions;
    const previousLocal = local?.key === key ? local : null;
    const previousConfirmation = confirmation.current;
    const previousEcho = optimisticEcho.current;
    const desired = nextViewerReaction(base, content);
    const optimistic = desired === null ? null : applyViewerReaction(base, content, desired);
    inFlight.current = true;
    setSaving(true);
    setError(null);
    if (optimistic !== null) {
      setLocal({ key, reactions: optimistic });
      onChange?.(optimistic);
    }
    try {
      const result = await submitPullReaction(window.bridge, { repo, number, target, commentId, reactions: base }, content);
      recordAttentionAction(`${repo}#${number}`, 'update');
      const confirmed = applyViewerReaction(
        optimistic ?? base, content, result.viewerHasReacted);
      confirmation.current = { key, content, viewerHasReacted: result.viewerHasReacted };
      optimisticEcho.current = { key, reactions: confirmed };
      setLocal({ key, reactions: confirmed });
      onChange?.(confirmed);
      return true;
    }
    catch (failure) {
      confirmation.current = previousConfirmation;
      optimisticEcho.current = previousEcho;
      setLocal(current => current?.key === key
        ? previousLocal
        : current);
      if (optimistic !== null) onChange?.(base, optimistic);
      setError(`Could not update reaction. ${remoteErrorMessage(failure)}`);
      return false;
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  return enabled ? {
    repo,
    number,
    target,
    commentId,
    reactions,
    saving,
    error,
    intent: content => nextViewerReaction(reactions, content),
    submit,
  } : undefined;
}

export async function submitPullReaction(
  bridge: Pick<Bridge, 'setPrReaction'>,
  action: ReactionRequest,
  content: PullReactionContent,
) {
  return bridge.setPrReaction(
    action.repo,
    action.number,
    action.target,
    action.commentId,
    content,
    nextViewerReaction(action.reactions, content),
  );
}

/**
 * Keeps the composer's unsent text in Recent, tagged "Never posted" in Words
 * mode. Posting clears it, because the reply is no longer the one that got away.
 */
function saveDraft(repo: string, number: number, body: string) {
  return window.bridge.saveRecentDraft({ type: 'PULL_REQUEST', repo, number, body })
    .catch(() => {
      // Losing a draft record is never worth interrupting the composer.
    });
}

export async function submitPullRequestComment(
  bridge: Pick<Bridge, 'commentPr'>,
  repo: string,
  number: number,
  body: string,
  onDetailChange: (detail: PullRequestDetailDto) => void,
) {
  onDetailChange(await bridge.commentPr(repo, number, body));
  recordAttentionAction(`${repo}#${number}`, 'comment');
}

function Reactions({ reactions, reaction, alwaysShowPicker = false }: {
  reactions: PullReactionDto[];
  reaction?: ReactionAction;
  alwaysShowPicker?: boolean;
}) {
  if (reactions.length === 0 && (!alwaysShowPicker || !reaction) && !reaction?.error) return null;
  return <div className="pr-reactions">{reactions.map(value => (
    reaction
      ? <ReactionButton key={value.content} value={value} action={reaction} />
      : <span key={value.content}>{reactionEmoji(value.content)} {value.count}</span>
  ))}{reaction && <ReactionPicker action={reaction} compact key="add-reaction" />}
  {reaction?.error && <span className="pr-reaction-error" role="alert">{reaction.error}</span>}</div>;
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

function ReactionButton({ value, action }: { value: PullReactionDto; action: ReactionAction }) {
  const label = REACTION_CHOICES.find(([content]) => content === value.content)?.[2] ?? value.content;
  const active = value.viewerHasReacted === true;
  return (
    <button type="button" className={`pr-reaction${active ? ' is-active' : ''}`} disabled={action.saving}
      aria-pressed={active}
      aria-label={`Add or remove ${label} reaction`} title={`Add or remove ${label} reaction`}
      onClick={() => { void action.submit(value.content); }}>{reactionEmoji(value.content)} {value.count}</button>
  );
}

function ReactionPicker({ action, compact = false }: { action: ReactionAction; compact?: boolean }) {
  const [open, setOpen] = useState(false);
  const menuId = useId();

  const pickerRef = usePopoverDismiss(() => setOpen(false), false, open && !action.saving);

  const choose = async (content: PullReactionContent) => {
    const optimistic = action.intent(content) !== null;
    if (optimistic) setOpen(false);
    const succeeded = await action.submit(content);
    if (succeeded) setOpen(false);
    else if (optimistic) setOpen(true);
  };

  return (
    <div ref={pickerRef} className={`pr-reaction-picker${compact ? ' is-compact' : ''}${open ? ' is-open' : ''}`}
      data-reaction-target={action.target} data-reaction-comment-id={action.commentId ?? undefined}>
      <button type="button" className="pr-reaction-picker__trigger" aria-label="Add reaction" title="Add reaction"
        aria-expanded={open} aria-haspopup="menu" aria-controls={menuId} disabled={action.saving}
        onClick={() => setOpen(value => !value)}><SmilePlus size={16} strokeWidth={1.3} aria-hidden="true" /></button>
      <div className="pr-reaction-picker__menu" id={menuId} role="menu" aria-label="Choose a reaction" aria-busy={action.saving}>
        {REACTION_CHOICES.map(([content, emoji, label]) => {
          const active = action.reactions.some(reaction => reaction.content === content && reaction.viewerHasReacted === true);
          return <button type="button" role="menuitemcheckbox" disabled={action.saving}
            className={active ? 'is-active' : undefined}
            aria-checked={active}
            aria-label={`Add or remove ${label} reaction`} key={content}
            onClick={() => { void choose(content); }}>
            {emoji}
          </button>;
        })}
        {action.error && <div className="pr-reaction-picker__error" role="alert">{action.error}</div>}
      </div>
    </div>
  );
}

function LabelBadge({ name, color }: { name: string; color: string | null }) {
  const palette = labelPalette(name, color);
  return <span className="pr-label" style={{ background: palette.background, color: palette.color }}>{name}</span>;
}

function labelPalette(name: string, color: string | null) {
  const normalized = name.toLowerCase();
  if (normalized === 'no-release-notes') return { background: 'light-dark(#e3f2b5, var(--dark-green-soft))', color: 'var(--fg-success)' };
  if (['cla-signed', 'docs', 'iceberg'].includes(normalized)) return { background: 'light-dark(#f2e7e7, var(--dark-red-soft))', color: 'light-dark(#8a5551, var(--dark-red))' };
  return { background: `#${color ?? 'eaeef2'}24`, color: 'var(--text-2)' };
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

function threadLocation(path: string | null, line: number | null, side: string | null, startLine: number | null) {
  const prefix = side === 'RIGHT' ? 'R' : 'L';
  const range = startLine !== null && startLine !== line ? `${prefix}${startLine}–` : '';
  return `${path ?? 'Changed file'}${line ? `:${range}${prefix}${line}` : ''}`;
}

export function reactionEmoji(content: string) {
  return REACTION_CHOICES.find(([choice]) => choice === content)?.[1] ?? content;
}

function CopyIcon() {
  return <Copy size={16} strokeWidth={1.5} aria-hidden="true" />;
}

function ReviewIcon() {
  return <Eye size={15} strokeWidth={1.4} aria-hidden="true" />;
}

function CheckIcon({ size = 12 }: { size?: number }) {
  return <Check size={size} strokeWidth={2} aria-hidden="true" />;
}
