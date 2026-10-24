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
import { plural } from '@bytequay/ui/displayText';
import type { JiraIssue } from '../jira/contracts';
import { pullRequestIsMine } from '../pulls/model';
import { teamAttentionMatch, type TeamAttentionPreference } from '../teams/priority';
import type { TeamCircle } from '../teams/types';
import type { AgentTaskActivityDto, AgentTaskDto, AttentionMarkDto, PullRequestDto, RepositoryIssueSnapshotDto } from '../types';

/** Why an item is on the "Needs attention" list. */
export type AttentionKind = 'approval' | 'review' | 'assigned' | 'changes' | 'failed' | 'blocked' | 'ci' | 'commits' | 'merge' | 'finished' | 'updated';

/** Where an item comes from; it picks the card's colours, icon and filter tab. */
export type AttentionSource = 'pr' | 'issue' | 'jira' | 'task';

export const ATTENTION_SOURCES: Record<AttentionSource, { label: string; filter: string }> = {
  pr: { label: 'Pull request', filter: 'PRs' },
  issue: { label: 'Issue', filter: 'Issues' },
  jira: { label: 'Jira ticket', filter: 'Jira' },
  task: { label: 'Agent task', filter: 'Tasks' },
};

// For simultaneous updates, show work waiting on the user first.
const PRIORITY: AttentionKind[] = ['approval', 'review', 'assigned', 'changes', 'failed', 'ci', 'blocked', 'commits', 'merge', 'finished', 'updated'];

/** One source update: a pull request, an issue or ticket, or a local agent task. */
export type AttentionItem = {
  jira?: JiraIssue;
  /** An agent task and what it last said and waits on. */
  task?: { dto: AgentTaskDto; activity: AgentTaskActivityDto | null };
  reference?: string;
  key: string;
  type: 'pr' | 'issue' | 'task';
  kind: AttentionKind;
  /** owner/repo */
  repo: string;
  number: number;
  title: string;
  author: string | null;
  authorAvatarUrl: string | null;
  createdAt: string | null;
  updatedAt: string;
  labels: string[];
  /** The pull request row the list already loaded; null for issues. */
  pr: PullRequestDto | null;
  /** Issues carry their body and comment count in the list payload. */
  body: string;
  commentCount: number;
  /** Retained reminders can outlive the workspace's open-issue cache. */
  issueState?: string;
  /** Whether this issue or ticket can launch a new development task. */
  canStartDevelopment?: boolean;
  /** Whether this issue or ticket is marked as an Epic. */
  isEpic?: boolean;
  /** Issues open inside their workspace. */
  workspaceId: string | null;
  workspaceName?: string;
  /** Stable upstream identity, independent of which workspace supplied this issue. */
  sourceKey?: string;
  /** Items this one is tied to, such as a task's ticket and pull request. */
  links?: AttentionLink[];
};

/** Another item a card is tied to, as its folded links row names it. */
export type AttentionLink = {
  source: AttentionSource;
  key: string;
  title: string;
  status: string | null;
  tone: AttentionTone;
};

/** Every discovered PR can report an update; personal relationships give it a more specific reason. */
export function attentionKind(pr: PullRequestDto, viewerLogin?: string | null): AttentionKind {
  const mine = pullRequestIsMine(pr, viewerLogin);
  const requested = pr.origin === 'REVIEW_REQUESTED'
    || viewerLogin != null && pr.requestedReviewers.some(login => login.toLowerCase() === viewerLogin.toLowerCase());
  const reviewing = requested || pr.reviewRequestedAt != null || pr.reviewedAt !== null;
  const monitoredChange = pr.monitoredAt != null
    && Date.parse(pr.activityAt ?? pr.updatedAt) > Date.parse(pr.monitoredAt);
  const followsAllActivity = mine || monitoredChange;

  if (pr.state !== 'open' || pr.draft) return 'updated';
  // The backend clears approval for an actual re-request; the list timestamp can come from an older inbox notification.
  if (!mine && requested && pr.handledAction !== 'APPROVED' && (pr.reviewedAt === null || pr.handledAction === null
    || pr.reviewRequestedAt != null && Date.parse(pr.reviewRequestedAt) > Date.parse(pr.reviewedAt))) return 'review';
  if (followsAllActivity && pr.reviewDecision === 'CHANGES_REQUESTED') return 'changes';
  if (followsAllActivity && pr.ciStatus === 'FAILING') return 'ci';
  if (followsAllActivity && pr.mergeableState === 'blocked') return 'blocked';
  if ((followsAllActivity || reviewing) && hasCommitsSinceReview(pr)) return 'commits';
  if ((followsAllActivity || reviewing) && pr.mergeable === true && pr.mergeableState === 'clean'
    && pr.ciStatus !== 'FAILING' && pr.ciStatus !== 'PENDING') return 'merge';
  return 'updated';
}

function hasCommitsSinceReview(pr: PullRequestDto): boolean {
  const since = pr.reviewedAt ?? pr.reviewRequestedAt;
  return since != null && pr.headPushedAt !== null && Date.parse(pr.headPushedAt) > Date.parse(since);
}

export function pullRequestItem(pr: PullRequestDto, kind: AttentionKind = 'updated'): AttentionItem {
  return {
    key: `${pr.repo}#${pr.number}`,
    type: 'pr',
    kind,
    repo: pr.repo,
    number: pr.number,
    title: pr.title,
    author: pr.author,
    authorAvatarUrl: pr.authorAvatarUrl ?? null,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
    labels: pr.labels,
    pr,
    body: '',
    commentCount: pr.commentCount,
    workspaceId: null,
  };
}

/** The same source identity and snapshot for an issue card and its detail page. */
export function issueAttentionItem(issue: RepositoryIssueSnapshotDto['issues'][number], repo: string, workspaceId: string | null): AttentionItem {
  return {
    key: `${repo}#${issue.number}`, sourceKey: `github.com:issue:${issue.id}`, type: 'issue', kind: 'updated',
    repo, number: issue.number, title: issue.title, author: issue.author, authorAvatarUrl: issue.authorAvatarUrl,
    createdAt: issue.createdAt, updatedAt: issue.updatedAt, labels: issue.labels, pr: null,
    body: issue.body, commentCount: issue.commentCount, workspaceId,
    canStartDevelopment: !issue.isEpic && issue.developmentTask == null, isEpic: issue.isEpic === true,
  };
}

/** All cached issues in one workspace, with assignment used only to name the card. */
export function issueAttentionItems(
  snapshot: RepositoryIssueSnapshotDto,
  workspaceId: string,
  login: string,
): AttentionItem[] {
  const repo = `${snapshot.repository.owner}/${snapshot.repository.repo}`;
  const me = login.toLowerCase();
  return snapshot.issues.map(issue => ({
    ...issueAttentionItem(issue, repo, workspaceId),
    kind: issue.assignees.some(assignee => assignee.toLowerCase() === me) ? 'assigned' : 'updated',
  }));
}

/**
 * The dataCache entries Home and every workspace Home paint their first frame from: the last
 * /prs/attention rows, the viewer's profile, and every workspace's issues. Rows are ranked
 * with all three, so seeding the rows alone would re-sort them once the login and issues land.
 */
export const ATTENTION_SEED = { prs: 'home:attention', profile: 'home:profile', issues: 'home:issues' };

/**
 * The backend refreshes recent issues once a minute, while pull-request updates arrive every few seconds; re-reading
 * every issue of a large repository on each of them only competes with the page for the backend's one connection.
 */
export const ISSUE_ATTENTION_REFRESH_MS = 30_000;

export function attentionCandidates(
  prs: PullRequestDto[],
  issues: AttentionItem[] = [],
  viewerLogin?: string | null,
): AttentionItem[] {
  // The same upstream can be watched through multiple forks. Keep one stable, explicit route.
  const bySource = new Map<string, AttentionItem>();
  for (const issue of issues) {
    const key = issue.sourceKey ?? issue.key.toLowerCase();
    const previous = bySource.get(key);
    if (!previous || (issue.workspaceId ?? '').localeCompare(previous.workspaceId ?? '') < 0) bySource.set(key, issue);
  }
  const items = prs
    .map(pr => pullRequestItem(pr, attentionKind(pr, viewerLogin)))
    .concat([...bySource.values()]);
  return items.sort(compareAttention);
}

/** The source may have changed since its latest cached timeline event. */
export function attentionTime(item: AttentionItem): string {
  const eventTime = item.pr?.latestActivity?.timestamp ?? item.jira?.latestActivity?.at;
  return eventTime !== undefined && Date.parse(eventTime) > Date.parse(item.updatedAt) ? eventTime : item.updatedAt;
}

// A sort compares each of thousands of items many times; parse an item's time once.
const attentionMillis = new WeakMap<AttentionItem, number>();
function attentionAt(item: AttentionItem): number {
  let at = attentionMillis.get(item);
  if (at === undefined) {
    at = Date.parse(attentionTime(item)) || 0;
    attentionMillis.set(item, at);
  }
  return at;
}

function compareAttention(left: AttentionItem, right: AttentionItem): number {
  return attentionAt(right) - attentionAt(left) || PRIORITY.indexOf(left.kind) - PRIORITY.indexOf(right.kind);
}

export function attentionSource(item: AttentionItem): AttentionSource {
  return item.jira ? 'jira' : item.type;
}

/** A reason tag's colour. */
export type AttentionTone = 'blue' | 'amber' | 'red' | 'green' | 'neutral' | 'orange' | 'purple';

export type AttentionLabel = { name: string; color: string | null };

export type AttentionCommit = { sha: string; message: string };

/** What a card's detail block says about the event that put the item on the list. */
export type AttentionDetail =
  | { kind: 'text'; text: string }
  | { kind: 'quote'; text: string; file: string | null }
  | { kind: 'commits'; lead: string | null; commits: AttentionCommit[] }
  | { kind: 'ci'; commits: AttentionCommit[]; checks: string[] }
  | { kind: 'command'; lead: string | null; command: string }
  | { kind: 'labels'; lead: string | null; labels: AttentionLabel[] }
  | { kind: 'status'; from: AttentionStatus; to: AttentionStatus; note: string | null };

/** A ticket status as a pill: blocked red, in progress blue, done green, anything else grey. */
export type AttentionStatus = { name: string; tone: AttentionTone };

export function statusPill(status: { name: string; category: string }): AttentionStatus {
  const tone: AttentionTone = /blocked/i.test(status.name) ? 'red'
    : status.category === 'indeterminate' ? 'blue' : status.category === 'done' ? 'green' : 'neutral';
  return { name: status.name, tone };
}

export type AttentionSummary = {
  /** Where the item stands, for the card's strip; what moved it is the actor line. */
  tag: { label: string; tone: AttentionTone };
  /** Who did it; null when the source does not say. */
  actor: { name: string; avatarUrl: string | null } | null;
  verb: string;
  detail: AttentionDetail | null;
};

const KIND_TAGS: Record<AttentionKind, AttentionSummary['tag']> = {
  approval: { label: 'Approval needed', tone: 'amber' },
  review: { label: 'Review requested', tone: 'blue' },
  assigned: { label: 'Assigned to you', tone: 'green' },
  changes: { label: 'Changes requested', tone: 'red' },
  failed: { label: 'Turn failed', tone: 'red' },
  blocked: { label: 'Merge blocked', tone: 'amber' },
  ci: { label: 'Checks failing', tone: 'red' },
  commits: { label: 'New commits', tone: 'orange' },
  merge: { label: 'Ready to merge', tone: 'green' },
  finished: { label: 'Turn finished', tone: 'green' },
  updated: { label: 'Open', tone: 'neutral' },
};

function commitLines(pr: PullRequestDto): AttentionCommit[] {
  return (pr.commitsSinceReview ?? []).map(commit => ({ sha: commit.sha.slice(0, 7), message: commit.message }));
}



const EVENT_VERBS: Record<string, string> = {
  commented: 'commented', review_thread: 'left a review comment', committed: 'pushed a commit',
  review_request_removed: 'removed a review request', labeled: 'added a label', unlabeled: 'removed a label',
  renamed: 'changed the title', 'cross-referenced': 'mentioned this', head_ref_force_pushed: 'force-pushed',
  closed: 'closed this', merged: 'merged this', reopened: 'reopened this',
  convert_to_draft: 'converted this to a draft', ready_for_review: 'marked this ready for review',
  milestoned: 'set the milestone', demilestoned: 'removed the milestone',
  assigned: 'changed the assignees', unassigned: 'changed the assignees',
  added_to_merge_queue: 'queued this for merge', removed_from_merge_queue: 'removed this from the merge queue',
};

/** Where a pull request that only moved stands: merged, closed, a draft, approved, or just open. */
function pullRequestState(pr: PullRequestDto): AttentionSummary['tag'] {
  const state = pr.state?.toLowerCase();
  if (state === 'merged') return { label: 'Merged', tone: 'purple' };
  if (state === 'closed') return { label: 'Closed', tone: 'neutral' };
  if (pr.draft) return { label: 'Draft', tone: 'neutral' };
  return pr.reviewDecision === 'APPROVED' ? { label: 'Approved', tone: 'green' } : KIND_TAGS.updated;
}

/** Whether a pull request was merged or closed, an issue closed, or a Jira ticket done; null while still open. */
export function attentionEnded(item: AttentionItem): 'merged' | 'closed' | null {
  if (item.jira !== undefined) return item.jira.status.category === 'done' ? 'closed' : null;
  const state = item.pr?.state?.toLowerCase() ?? item.issueState;
  return state === 'merged' || state === 'closed' ? state : null;
}

function pullRequestSummary(item: AttentionItem, pr: PullRequestDto, viewerLogin: string | null): AttentionSummary {
  const activity = pr.latestActivity ?? null;
  const tag = item.kind === 'updated' ? pullRequestState(pr) : KIND_TAGS[item.kind];
  const name = activity?.actor ?? pr.author;
  const actor = name === null ? null : { name, avatarUrl: name === pr.author ? pr.authorAvatarUrl ?? null : null };
  const requested = activity?.requestedReviewer ?? null;
  const verb = activity === null ? 'updated this pull request'
    : activity.event === 'review_requested'
      ? requested === null ? 'requested a review'
        : viewerLogin !== null && requested.toLowerCase() === viewerLogin.toLowerCase() ? 'requested your review' : `requested review from ${requested}`
      : activity.event === 'reviewed'
        ? activity.state?.toUpperCase() === 'APPROVED' ? 'approved these changes'
          : activity.state?.toUpperCase() === 'CHANGES_REQUESTED' ? 'requested changes' : 'reviewed'
        : EVENT_VERBS[activity.event] ?? 'updated this pull request';
  const label = activity?.label ?? null;
  const since = pr.reviewedAt ?? pr.reviewRequestedAt ?? null;
  const commits = commitLines(pr);
  const pushed = commits.length > 0 && activity?.event === 'committed';
  const excerpt = activity?.excerpt ?? null;
  const file = activity?.path == null ? null : activity.line == null ? activity.path : `${activity.path}:${activity.line}`;
  // The detail shows the event the actor line names; what the item waits on fills in when that event has nothing to show.
  const detail: AttentionDetail | null = excerpt !== null ? { kind: 'quote', text: excerpt, file }
    : (activity?.event === 'labeled' || activity?.event === 'unlabeled') && label !== null
      ? { kind: 'labels', lead: null, labels: [{ name: label, color: pr.labelColors?.[label] ?? null }] }
      : item.kind === 'ci' ? { kind: 'ci', commits: commits.slice(0, 1), checks: pr.failingChecks ?? [] }
        : (item.kind === 'review' || item.kind === 'commits') && commits.length > 0
          ? { kind: 'commits', lead: `${plural(commits.length, 'commit')} since your last review`, commits }
          : since !== null && pr.headPushedAt !== null && Date.parse(pr.headPushedAt) > Date.parse(since)
            ? { kind: 'text', text: 'New commits since your last review' }
            : null;
  const pushedVerb = item.kind === 'ci' ? `pushed ${plural(commits.length, 'commit')}, then CI failed` : `pushed ${plural(commits.length, 'commit')}`;
  return { tag, actor, verb: pushed ? pushedVerb : verb, detail };
}

/** An approval's command, when the provider sent the tool input rather than the command line. */
export function approvalCommand(detail: string): string {
  try {
    const input: unknown = JSON.parse(detail);
    if (typeof input === 'object' && input !== null && 'command' in input && typeof input.command === 'string') return input.command;
  }
  catch { /* Codex sends the command itself. */ }
  return detail;
}

/** The questions a question-tool approval asks, joined for a card to quote. */
function approvalQuestionText(detail: string): string | null {
  try {
    const input: unknown = JSON.parse(detail);
    const questions = typeof input === 'object' && input !== null && 'questions' in input && Array.isArray(input.questions) ? input.questions : [];
    const asked = questions.flatMap((entry: unknown) => typeof entry === 'object' && entry !== null && 'question' in entry && typeof entry.question === 'string'
      ? [entry.question] : []);
    return asked.length === 0 ? null : asked.join(' ');
  }
  catch {
    return null;
  }
}

/** The tool an approval stands in for when an agent asks the user a question. */
export const QUESTION_TOOL = 'AskUserQuestion';

function taskSummary(item: AttentionItem, task: NonNullable<AttentionItem['task']>): AttentionSummary {
  const actor: AttentionSummary['actor'] = { name: item.author ?? 'Agent', avatarUrl: null };
  const approval = task.activity?.pendingApproval ?? null;
  const message = task.activity?.lastMessage ?? null;
  const said: AttentionDetail | null = message !== null && message.author === 'AGENT' ? { kind: 'quote', text: message.text, file: null } : null;
  if (task.dto.state !== 'OPEN') return {
    tag: { label: task.dto.state === 'FAILED' ? 'Failed' : 'Completed', tone: task.dto.state === 'FAILED' ? 'red' : 'neutral' },
    actor, verb: task.dto.state === 'FAILED' ? 'stopped with an error' : 'completed this task', detail: said,
  };
  if (approval !== null && approval.scope.includes(QUESTION_TOOL)) {
    const question = approvalQuestionText(approval.detail);
    return { tag: { label: 'Question for you', tone: 'amber' }, actor, verb: 'asked you a question', detail: question === null ? said : { kind: 'quote', text: question, file: null } };
  }
  if (approval !== null) {
    const verb = approval.approvalKind === 'COMMAND' ? 'wants to run a command' : approval.approvalKind === 'FILE_CHANGE' ? 'wants to change files' : 'is waiting for your approval';
    return { tag: KIND_TAGS.approval, actor, verb, detail: { kind: 'command', lead: approval.title, command: approvalCommand(approval.detail) } };
  }
  if (task.dto.mutationClaim?.kind === 'RECOVERY') {
    return { tag: { label: 'Recovery required', tone: 'red' }, actor, verb: 'could not be shown to have stopped', detail: said };
  }
  if (task.dto.mutationClaim != null || task.dto.developmentContinuationUnfinished) {
    return { tag: { label: 'Running', tone: 'blue' }, actor, verb: 'is working on this task', detail: said };
  }
  if (task.activity?.latestTurn == null) return { tag: { label: 'Idle', tone: 'green' }, actor, verb: 'is ready for a prompt', detail: said };
  if (item.kind === 'failed') return { tag: KIND_TAGS.failed, actor, verb: 'stopped with an error', detail: said };
  if (item.kind === 'finished') return { tag: KIND_TAGS.finished, actor, verb: 'finished a turn', detail: said };
  return { tag: { label: 'Idle', tone: 'green' }, actor, verb: 'updated this task', detail: said };
}

function words(names: string[]): string {
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** A ticket's actor line says what last changed on it, or else where it stands. */
function jiraSummary(item: AttentionItem, jira: NonNullable<AttentionItem['jira']>, labels: AttentionDetail | null): AttentionSummary {
  const tag: AttentionSummary['tag'] = jira.status.category === 'done'
    ? { label: jira.status.name, tone: 'green' }
    : jira.task !== null ? { label: 'In development', tone: 'blue' }
      : item.kind === 'assigned' ? KIND_TAGS.assigned : { label: jira.status.name, tone: statusPill(jira.status).tone };
  const activity = jira.latestActivity ?? null;
  if (activity?.kind === 'STATUS_CHANGED' && activity.from !== null && activity.to !== null) {
    return {
      tag,
      actor: null,
      verb: `Moved to ${activity.to.name}`,
      detail: { kind: 'status', from: statusPill(activity.from), to: statusPill(activity.to), note: activity.note },
    };
  }
  if (activity?.kind === 'COMMENTED') {
    return {
      tag,
      actor: activity.actor === null ? null : { name: activity.actor.displayName, avatarUrl: activity.actor.avatarUrl },
      verb: 'commented',
      detail: activity.note === null ? null : { kind: 'quote', text: activity.note, file: null },
    };
  }
  if (activity?.kind === 'FIELDS_CHANGED' && activity.fields.length > 0) {
    return { tag, actor: null, verb: 'Updated in Jira', detail: { kind: 'text', text: `Changed ${words(activity.fields)}` } };
  }
  return { tag, actor: null, verb: `${jira.status.name} · ${item.workspaceName ?? 'current sprint'}`, detail: labels };
}

/** The card's reason tag, who did what, and what the detail block shows. */
export function attentionSummary(item: AttentionItem, viewerLogin: string | null = null): AttentionSummary {
  if (item.task !== undefined) return taskSummary(item, item.task);
  if (item.pr !== null) return pullRequestSummary(item, item.pr, viewerLogin);
  const labels = item.labels.map((name): AttentionLabel => ({ name, color: null }));
  const detail: AttentionDetail | null = labels.length === 0 ? null : { kind: 'labels', lead: null, labels };
  if (item.jira) return jiraSummary(item, item.jira, detail);
  return {
    tag: item.issueState === 'closed' ? { label: 'Closed', tone: 'neutral' } : KIND_TAGS[item.kind],
    actor: item.author === null ? null : { name: item.author, avatarUrl: item.authorAvatarUrl },
    verb: 'opened this issue',
    detail,
  };
}

/** Both Homes show at most this many cards; acknowledging or snoozing one brings in the next. */
export const ATTENTION_CARD_LIMIT = 5;

export type AttentionFilter = 'all' | AttentionSource;

/**
 * Where an item ranks by how it involves the viewer, lowest first: their own pull requests, issues and tasks;
 * what asks them (review requests, @mentions, assignments), a circle member's ask first; pull requests they
 * reviewed or monitor; then circle members' own pull requests and issues. Null keeps it off the list, such as
 * a circle member's comment on someone else's pull request.
 */
export function attentionRank(item: AttentionItem, circles: TeamCircle[], viewerLogin: string | null): number | null {
  if (item.task) return 0;
  // The Jira attention read lists only tickets assigned to the viewer.
  if (item.jira) return 2;
  const pr = item.pr;
  if (pr !== null ? pullRequestIsMine(pr, viewerLogin) : viewerLogin !== null && item.author?.toLowerCase() === viewerLogin.toLowerCase()) return 0;
  // Approval keeps routine updates in Approved by me; an explicit re-request clears the approval in the backend.
  if (pr?.state === 'open' && pr.handledAction === 'APPROVED') return null;
  const circle = teamAttentionMatch(item, circles, viewerLogin);
  if (item.kind === 'review' || item.kind === 'assigned' || pr?.mentioned === true) return circle === null ? 2 : 1;
  if (pr !== null && (pr.reviewedAt !== null || pr.handledAction !== null || pr.monitoredAt != null)) return 3;
  return circle === null ? null : 4;
}

/** Activity this recent is ranked; nobody acknowledges every old item, so older ones would bury the news. */
const RANKED_WINDOW_MS = 7 * 86_400_000;

/** The cards a filter draws, capped: the last week's activity by rank, then older items newest first. */
export function attentionCards(live: AttentionItem[], filter: AttentionFilter, limit = ATTENTION_CARD_LIMIT,
  team: TeamAttentionPreference = { circles: [], viewerLogin: null }, now = Date.now()): AttentionItem[] {
  return live.flatMap(item => {
    const rank = attentionRank(item, team.circles, team.viewerLogin);
    if (rank === null || (filter !== 'all' && attentionSource(item) !== filter)
      || (team.onlyTeam && teamAttentionMatch(item, team.circles, team.viewerLogin) === null)) return [];
    const recent = now - attentionAt(item) <= RANKED_WINDOW_MS;
    return [{ item, order: recent ? rank : Number.MAX_SAFE_INTEGER }];
  }).sort((left, right) => left.order - right.order || compareAttention(left.item, right.item)).slice(0, limit).map(entry => entry.item);
}

/* ── Acknowledged and snoozed rows ──────────────────────────────── */

export type HandledEntry = {
  kind: 'ack' | 'snooze';
  /** Epoch millis the row comes back; null means "until new activity". */
  until: number | null;
  /** The item's updatedAt when it was handled, or a pull request's later attention activity. */
  seenUpdatedAt: string;
  /** Includes the CI result and mergeability, which need not advance GitHub's updatedAt. */
  seenActivityVersion?: string;
  /** Toast/footer wording, e.g. "next monday". */
  label: string;
  /** A timed reminder survives the item leaving the ordinary attention candidates. */
  snoozedItem?: AttentionItem;
};

export type Handled = Record<string, HandledEntry>;

// The marks as this window last read or wrote them. The backend keeps them, so every build and the assistant share one set.
let handledNow: Handled = {};

export function loadHandled(): Handled {
  return handledNow;
}

/** Keeps the lists' marks, sending the backend only the entries that changed and the keys that went. */
export function saveHandled(handled: Handled): void {
  const saved = Object.entries(handled).filter(([key, entry]) => handledNow[key] !== entry).map(([key, entry]) => markOf(key, entry));
  const removed = Object.keys(handledNow).filter(key => handled[key] === undefined);
  handledNow = handled;
  // A mark that fails to save comes back on the next read, as one did when browser storage was full.
  if (saved.length > 0 || removed.length > 0) void window.bridge?.changeAttentionMarks?.({ saved, removed }).catch(() => {});
  void window.bridge?.setPrAttentionHandled?.(handled).catch(() => {});
  window.dispatchEvent(new CustomEvent('bytequay:attention-handled-changed'));
}

/**
 * Completes the marks the assistant makes, which carry no fingerprint of the item: the first time a list draws such an item, its mark
 * takes the item's activity version, and a timed snooze the item's copy, as the row's own buttons record them. An acknowledgment the item
 * has outgrown already lets the row back, so it is left as it is.
 */
export function completeHandled(candidates: AttentionItem[]): void {
  const completed = candidates.flatMap(item => {
    const entry = handledNow[item.key];
    const timed = entry?.kind === 'snooze' && entry.until !== null;
    return entry === undefined || entry.seenActivityVersion !== undefined || !timed && Date.parse(item.updatedAt) > Date.parse(entry.seenUpdatedAt) ? []
      : [[item.key, { ...entry, seenActivityVersion: attentionActivityVersion(item), ...(timed ? { snoozedItem: item } : {}) }] as const];
  });
  if (completed.length > 0) saveHandled({ ...handledNow, ...Object.fromEntries(completed) });
}

/** Reads the marks the backend keeps and tells the lists and the notifier; a build's first read brings its older marks along. */
export async function reloadHandled(): Promise<void> {
  let read: Handled = Object.fromEntries((await window.bridge.getAttentionMarks()).map(mark => [mark.key, entryOf(mark)]));
  const legacy = Object.entries(legacyHandled()).filter(([key]) => read[key] === undefined);
  try {
    if (legacy.length > 0) await window.bridge.changeAttentionMarks({ saved: legacy.map(([key, entry]) => markOf(key, entry)), removed: [] });
    read = { ...Object.fromEntries(legacy), ...read };
    window.localStorage.removeItem(LEGACY_KEY);
  }
  catch { /* The next start tries the import again. */ }
  handledNow = read;
  void window.bridge.setPrAttentionHandled(read).catch(() => {});
  window.dispatchEvent(new CustomEvent('bytequay:attention-handled-changed'));
}

// ponytail: v0.5.0 kept marks in each build's own browser storage. A build's first read sends them along once, and a mark the backend
// already has wins; delete this once those builds' marks have aged out.
const LEGACY_KEY = 'bytequay.attention.handled.v1';

function legacyHandled(): Handled {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(LEGACY_KEY) ?? 'null');
    return parsed !== null && typeof parsed === 'object' ? Object.fromEntries(Object.entries(parsed as Record<string, unknown>).filter(wellFormed)) as Handled : {};
  }
  catch {
    return {};
  }
}

/** An entry the backend accepts, unlike one an older build left half written, which would fail the whole import every time. */
function wellFormed([key, entry]: [string, unknown]): boolean {
  if (entry === null || typeof entry !== 'object' || key.trim() === '' || key.length > 500) return false;
  const { kind, until, label, seenUpdatedAt } = entry as Partial<HandledEntry>;
  return (kind === 'ack' || kind === 'snooze') && typeof label === 'string' && label.trim() !== '' && label.length <= 500
    && typeof seenUpdatedAt === 'string' && seenUpdatedAt.length <= 64 && (until === null || kind === 'snooze' && typeof until === 'number' && Number.isFinite(until));
}

function markOf(key: string, entry: HandledEntry): AttentionMarkDto {
  return {
    key, kind: entry.kind === 'ack' ? 'ACK' : 'SNOOZE', until: entry.until === null ? null : new Date(entry.until).toISOString(), seenUpdatedAt: entry.seenUpdatedAt,
    seenActivityVersion: entry.seenActivityVersion ?? null, label: entry.label, snoozedItem: entry.snoozedItem === undefined ? null : JSON.stringify(entry.snoozedItem),
  };
}

function entryOf(mark: AttentionMarkDto): HandledEntry {
  return {
    kind: mark.kind === 'ACK' ? 'ack' : 'snooze', until: mark.until === null ? null : Date.parse(mark.until), seenUpdatedAt: mark.seenUpdatedAt, label: mark.label,
    ...(mark.seenActivityVersion === null ? {} : { seenActivityVersion: mark.seenActivityVersion }),
    ...(mark.snoozedItem === null ? {} : { snoozedItem: JSON.parse(mark.snoozedItem) as AttentionItem }),
  };
}

export function attentionActivityVersion(item: AttentionItem): string {
  if (item.task) {
    const { dto, activity } = item.task;
    return JSON.stringify([dto.updatedAt, dto.state, activity?.latestTurn?.id, activity?.latestTurn?.state,
      activity?.latestTurn?.endedAt, activity?.pendingApproval?.approvalId, item.updatedAt]);
  }
  if (item.jira) return JSON.stringify([item.updatedAt, item.title, item.jira.status.id, item.jira.assignee?.accountId,
    [...item.labels].sort(), item.jira.priority?.id, item.jira.dueDate]);
  const pr = item.pr;
  // Not pr.activityAt: it moves whenever a single CI job starts or finishes. What people do on GitHub moves updatedAt instead.
  // pullRequestNews reads these fields by position.
  return JSON.stringify(pr === null ? [item.updatedAt, item.title, item.body, item.commentCount, [...item.labels].sort(), item.issueState ?? 'open'] : [
    pr.updatedAt, pr.title, pr.state, pr.draft, pr.ciStatus, pr.mergeable, pr.mergeableState,
    pr.reviewDecision, pr.reviewRequestedAt, pr.headPushedAt, pr.commentCount, [...pr.labels].sort(),
  ]);
}

export function handledEntry(item: AttentionItem, kind: HandledEntry['kind'], until: number | null, label: string): HandledEntry {
  return { kind, until, label, seenUpdatedAt: seenThrough(item), seenActivityVersion: attentionActivityVersion(item),
    ...(kind === 'snooze' && until !== null ? { snoozedItem: item } : {}) };
}

/** Merge-queue and milestone events can postdate GitHub's updatedAt; handling the card covers them too. */
function seenThrough(item: AttentionItem): string {
  const activity = item.task || item.jira ? null : item.pr?.attentionActivityAt;
  return activity != null && Date.parse(activity) > Date.parse(item.updatedAt) ? activity : item.updatedAt;
}

/** Timed snoozes stay quiet through activity; acknowledgment and activity snoozes wake on a change. */
export function stillHandled(entry: HandledEntry, item: AttentionItem, now: number): boolean {
  if (entry.kind === 'snooze' && entry.until !== null) return now < entry.until;
  if (entry.seenActivityVersion === undefined) return Date.parse(item.updatedAt) <= Date.parse(entry.seenUpdatedAt);
  const seen = withoutLegacyActivityAt(entry.seenActivityVersion);
  const current = attentionActivityVersion(item);
  const pr = item.task || item.jira ? null : item.pr;
  return pr === null ? seen === current : !pullRequestNews(pr, seen, current, entry.seenUpdatedAt);
}

// Where a pull request's activity version keeps the fields that bring it back on their own.
const TITLE = 1;
const STATE = 2;
const DRAFT = 3;
const CI_STATUS = 4;
const MERGEABLE = 5;
const MERGEABLE_STATE = 6;
const REVIEW_REQUESTED_AT = 8;

/**
 * News is activity the backend counts after the card was handled, a new title or state, leaving draft, a new review request for the
 * viewer, a CI result on the viewer's own pull request, or becoming ready to merge. Pushes, labels, bot comments, re-runs going pending
 * and a ready pull request turning blocked stay quiet.
 */
function pullRequestNews(pr: PullRequestDto, seenVersion: string, currentVersion: string, seenThrough: string): boolean {
  const seen = versionFields(seenVersion);
  const current = versionFields(currentVersion);
  if (seen === null || current === null || seen.length !== current.length) return true;
  const changed = (index: number) => JSON.stringify(seen[index]) !== JSON.stringify(current[index]);
  const activity = pr.attentionActivityAt != null && Date.parse(pr.attentionActivityAt) > Date.parse(seenThrough);
  const ready = seen[DRAFT] === true && pr.draft === false;
  const ciResult = pullRequestIsMine(pr) && (pr.ciStatus === 'PASSING' || pr.ciStatus === 'FAILING') && pr.ciStatus !== seen[CI_STATUS];
  const readyToMerge = pr.mergeable === true && pr.mergeableState === 'clean' && !(seen[MERGEABLE] === true && seen[MERGEABLE_STATE] === 'clean');
  return activity || changed(TITLE) || changed(STATE) || ready || changed(REVIEW_REQUESTED_AT) || ciResult || readyToMerge;
}

function versionFields(version: string): unknown[] | null {
  try {
    const parsed: unknown = JSON.parse(version);
    return Array.isArray(parsed) ? parsed : null;
  }
  catch {
    return null;
  }
}

// ponytail: v0.5.1 pull-request versions led with activityAt, the only 13-field version. Dropping it keeps those marks; delete this once
// they have aged out.
function withoutLegacyActivityAt(version: string): string {
  const fields = versionFields(version);
  return fields?.length === 13 ? JSON.stringify(fields.slice(1)) : version;
}

export function visibleAttention(
  candidates: AttentionItem[],
  handled: Handled,
  now: number,
): AttentionItem[] {
  return candidates.filter(item => {
    const entry = handled[item.key];
    return entry === undefined || !stillHandled(entry, item, now);
  });
}

/** Open own PRs, including drafts, remain after reading. Unread activity comes before recent reads. */
export function myPullRequestCards(candidates: AttentionItem[], handled: Handled, viewerLogin: string | null, now: number): AttentionItem[] {
  return candidates.filter(item => {
    const entry = handled[item.key];
    return item.pr?.state === 'open' && pullRequestIsMine(item.pr, viewerLogin)
      && !(entry?.kind === 'snooze' && stillHandled(entry, item, now));
  }).sort((left, right) => {
    const read = (item: AttentionItem) => handled[item.key] !== undefined && stillHandled(handled[item.key], item, now);
    return Number(read(left)) - Number(read(right)) || compareAttention(left, right);
  }).slice(0, 2);
}

/** Approved work stays within reach after reading; newer activity moves it up without asking for attention. */
export function approvedPullRequestCards(candidates: AttentionItem[], handled: Handled, viewerLogin: string | null, now: number): AttentionItem[] {
  return candidates.filter(item => {
    const entry = handled[item.key];
    return item.pr?.state === 'open' && item.pr.handledAction === 'APPROVED' && !pullRequestIsMine(item.pr, viewerLogin)
      && !(entry?.kind === 'snooze' && stillHandled(entry, item, now));
  }).sort(compareAttention);
}

/* ── Snooze menu ────────────────────────────────────────────────── */

export type SnoozeId = 'hour' | 'tomorrow' | 'monday';

export const SNOOZE_OPTIONS: { id: SnoozeId; label: string; icon: string }[] = [
  { id: 'hour', label: '1 hour', icon: '◷' },
  { id: 'tomorrow', label: 'Tomorrow', icon: '☀' },
  { id: 'monday', label: 'Next Monday', icon: '▤' },
];

function atNineAm(from: Date, addDays: number): Date {
  const day = new Date(from);
  day.setDate(day.getDate() + addDays);
  day.setHours(9, 0, 0, 0);
  return day;
}

function nextMonday(from: Date): Date {
  // getDay(): Sunday is 0, Monday is 1. Always land on a future Monday.
  return atNineAm(from, ((8 - from.getDay()) % 7) || 7);
}

export function snoozeUntil(id: SnoozeId, now: number): number {
  const from = new Date(now);
  switch (id) {
    case 'hour': return now + 3_600_000;
    case 'tomorrow': return atNineAm(from, 1).getTime();
    case 'monday': return nextMonday(from).getTime();
  }
}

export function snoozeHint(id: SnoozeId, now: number): string {
  const until = snoozeUntil(id, now);
  const date = new Date(until);
  return id === 'monday'
    ? date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** "2 acknowledged · 1 snoozed", or an empty string when nothing was handled. */
export function handledSummary(entries: HandledEntry[]): string {
  const acknowledged = entries.filter(entry => entry.kind === 'ack').length;
  const snoozed = entries.length - acknowledged;
  return [
    acknowledged > 0 ? `${acknowledged} acknowledged` : null,
    snoozed > 0 ? `${snoozed} snoozed` : null,
  ].filter(Boolean).join(' · ');
}
