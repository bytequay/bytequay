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
import type { LocalPRBundle, LocalPRCheck, LocalPRComment, LocalPRStatus } from '../types/localPr';
import type { PullRequestCreatedData } from '../types/brainView';
import type { PullRow } from './model';
import { agoLabel, displayName } from '../pr/localpr/prViewMeta';
import { activelySubmittedCommentIds } from '../pr/localpr/localReviewSubmission';
import { relativeTime } from '../relativeTime';
import { shortCount } from './atoms';

/**
 * View-model builders for the redesigned PR detail pane — the shapes mirror
 * the DC prototype's detailFor()/checksFor() rows (docs/mockups/design/
 * pr-redesign/Pull Requests.dc.html) but are fed from the real
 * {@link LocalPRBundle} + dashboard row instead of the prototype's mock data.
 */

/** GitHub Bot actors render the square avatar per the prototype's legend. */
export function isBotActor(actor: string): boolean {
  return /\[bot]$/i.test(actor);
}

/** The status badge: local phases are blue, remote-open green, merged purple. */
export type StatePill = { label: string; bg: string; icon: 'open' | 'merged' };

export function statePill(status: LocalPRStatus | null): StatePill {
  switch (status) {
    case 'local-drafted': return { label: 'Local Draft', bg: '#0969da', icon: 'open' };
    case 'local-open': return { label: 'Local Open', bg: '#0969da', icon: 'open' };
    case 'remote-drafted': return { label: 'Draft', bg: '#6e7781', icon: 'open' };
    case 'merged': return { label: 'Merged', bg: '#8250df', icon: 'merged' };
    case 'closed': return { label: 'Closed', bg: '#cf222e', icon: 'open' };
    default: return { label: 'Open', bg: '#1f883d', icon: 'open' };
  }
}

export type PullDetailHeader = {
  title: string;
  numS: string;
  isMerged: boolean;
  pill: StatePill;
  /** null until the bundle loads — the base/branch chips wait for it. */
  base: string | null;
  branch: string | null;
  ovCount: number;
  addP: string;
  delP: string;
  agentState: 'none' | 'running' | 'done' | 'stale';
};

export function buildHeader(row: PullRow, bundle: LocalPRBundle | null | undefined): PullDetailHeader {
  const agentState = row.dto.reviewState ?? (row.hasAgent ? 'done' : 'none');
  // ponytail: bundle-less rows only know merged-or-open from row.kind.
  const status = bundle?.pr.status ?? (row.kind === 'merged' ? 'merged' : null);
  return {
    title: bundle?.pr.title ?? row.title,
    numS: bundle?.pr.remotePrNumber === null || row.num <= 0 ? 'Local PR' : `#${row.num}`,
    isMerged: status === 'merged',
    pill: statePill(status),
    base: bundle?.pr.baseBranch ?? null,
    branch: bundle?.pr.branchName ?? null,
    ovCount: bundle?.comments.length ?? row.comments,
    addP: `+${shortCount(row.add)}`,
    delP: `−${shortCount(row.del)}`,
    agentState,
  };
}

export type OpenedCard = {
  author: string;
  bot: boolean;
  time: string;
  /** null while the bundle is loading — render neither description state. */
  description: string | null;
};

export function buildOpenedCard(row: PullRow, bundle: LocalPRBundle | null | undefined): OpenedCard {
  if (bundle !== null && bundle !== undefined) {
    const author = bundle.pr.author ?? row.author;
    return {
      author: displayName(author),
      bot: isBotActor(author),
      time: agoLabel(bundle.pr.createdAt),
      description: bundle.pr.description,
    };
  }
  return {
    author: row.author,
    bot: isBotActor(row.author),
    time: row.dto.createdAt !== null ? relativeTime(row.dto.createdAt) : row.time,
    description: null,
  };
}

/** Requested reviewers plus anyone with a recorded verdict, deduped. */
export function reviewerLogins(row: PullRow): string[] {
  const out: string[] = [];
  for (const login of [...row.dto.requestedReviewers, ...Object.keys(row.dto.reviewerVerdicts ?? {})]) {
    const name = login.replace(/^@/, '');
    if (!out.includes(name)) out.push(name);
  }
  return out;
}

export type TimelineReply = { id: string; author: string; bot: boolean; body: string; time: string };

export type TimelineReviewVerdict = 'approved' | 'changes' | 'commented' | 'dismissed' | null;
export type TimelineReviewScope = 'plan' | 'dev' | 'round' | null;

export type TimelineItem =
  | { kind: 'commit'; id: string; at: number; time: string; message: string; sha: string }
  | { kind: 'ci'; id: string; at: number; time: string; status: string;
      previousStatus: string | null; headSha: string | null; checkCount: number | null;
      name: string | null; trigger: string | null }
  | { kind: 'ci-harness'; id: string; at: number; time: string; message: string;
      phase: string | null; status: string | null; sha: string | null }
  | { kind: 'milestone'; id: string; at: number; time: string; label: string;
      tone: 'neutral' | 'attention' | 'success'; sha: string | null }
  | { kind: 'review-activity'; id: string; at: number; time: string; author: string;
      activity: 'started' | 'addressing-started' | 'failed'; scope: TimelineReviewScope;
      iteration: number | null; roundId: string | null; reason: string | null }
  | { kind: 'review'; id: string; at: number; time: string; author: string; bot: boolean;
      verdict: TimelineReviewVerdict; body: string | null; remoteId: number | null;
      scope?: TimelineReviewScope; iteration?: number | null; roundId?: string | null }
  | { kind: 'pull-request'; id: string; at: number; time: string; pullRequest: PullRequestCreatedData }
  | { kind: 'local-thread'; id: string; at: number; comments: LocalPRComment[]; submitted: boolean }
  | { kind: 'comment'; id: string; at: number; time: string; author: string; bot: boolean;
      body: string; replies: TimelineReply[]; remoteId: number | null }
  | { kind: 'merged'; id: string; at: number; time: string; author: string; sha: string | null; base: string };

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

function num(payload: Record<string, unknown> | null, key: string): number | null {
  const value = payload?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function reviewVerdict(value: string | null): TimelineReviewVerdict {
  switch (value?.toUpperCase().replaceAll('-', '_')) {
    case 'APPROVE':
    case 'APPROVED':
      return 'approved';
    case 'REQUEST_CHANGES':
    case 'CHANGES_REQUESTED':
      return 'changes';
    case 'COMMENT':
    case 'COMMENTED':
      return 'commented';
    case 'DISMISS':
    case 'DISMISSED':
      return 'dismissed';
    default:
      return null;
  }
}

function reviewScope(value: string | null): TimelineReviewScope {
  return value === 'plan' || value === 'dev' || value === 'round' ? value : null;
}

const REVIEW_RECONCILE_WINDOW_MS = 10_000;

/** Pair ByteQuay's local "submitted" audit event with the canonical review
 *  that the following GitHub sync adds. The publish API currently does not
 *  retain GitHub's review id on the local event, so verdict + timestamp is the
 *  only shared identity available for historical rows. */
function duplicateLocalReviewIds(bundle: LocalPRBundle): Set<string> {
  const local = bundle.timeline.filter(event => event.eventType === 'review'
    && event.isLocalOnly && str(event.payload, 'reviewEvent') === 'submitted');
  const remote = bundle.timeline.filter(event => event.eventType === 'review'
    && typeof event.remoteEventId === 'number');
  const duplicates = new Set<string>();

  // ponytail: review timelines are small; capture the GitHub id at publish
  // time if this bounded local scan ever becomes measurable.
  for (const canonical of remote) {
    const verdict = reviewVerdict(str(canonical.payload, 'verdict'));
    if (verdict === null) continue;
    const match = local
      .filter(candidate => !duplicates.has(candidate.id)
        && reviewVerdict(str(candidate.payload, 'verdict')) === verdict
        && Math.abs(candidate.createdAt - canonical.createdAt) <= REVIEW_RECONCILE_WINDOW_MS)
      .sort((a, b) => Math.abs(a.createdAt - canonical.createdAt) - Math.abs(b.createdAt - canonical.createdAt))[0];
    if (match !== undefined) duplicates.add(match.id);
  }
  return duplicates;
}

/**
 * Maps the local timeline + comments to the template's card shapes: commit
 * rows, review lifecycle rows, review cards, local conversation threads,
 * remote PR-level comment cards, aggregate CI transitions, sparse local CI
 * Harness and V2 lifecycle milestones, and a synthetic merged row. Event types with no template
 * counterpart (amend/branch/follow-up/plan-finalized, plus `comment`
 * events which render from `comments`) are omitted.
 */
export function buildTimeline(bundle: LocalPRBundle): TimelineItem[] {
  const items: TimelineItem[] = [];
  const seenCommits = new Set<string>();
  let lastAggregateCi: string | null = null;
  let hasMergedEvent = false;
  const remoteCommentIds = new Map<string, number>();
  const submittedCommentIds = activelySubmittedCommentIds(bundle.timeline);
  const duplicateReviews = duplicateLocalReviewIds(bundle);
  for (const event of bundle.timeline) {
    if (event.eventType === 'pull-request-progress' || event.eventType === 'pull-request-created') {
      const phase = str(event.payload, 'phase')
        ?? (event.eventType === 'pull-request-created' ? 'created' : null);
      if (phase === 'created' || phase === 'failed') {
        items.push({
          kind: 'pull-request', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          pullRequest: {
            phase,
            branch: str(event.payload, 'branch'),
            baseBranch: str(event.payload, 'baseBranch'),
            failedStep: str(event.payload, 'failedStep'),
            reason: str(event.payload, 'reason'),
            number: num(event.payload, 'number'),
            url: str(event.payload, 'url'),
            additions: num(event.payload, 'additions'),
            deletions: num(event.payload, 'deletions'),
          },
        });
      }
      continue;
    }
    if (event.eventType === 'comment') {
      const commentId = str(event.payload, 'commentId');
      if (commentId !== null && typeof event.remoteEventId === 'number') {
        remoteCommentIds.set(commentId, event.remoteEventId);
      }
    }
    if (event.eventType === 'commit') {
      const sha = str(event.payload, 'sha');
      const canonicalSha = bundle.commits.find(commit => sha !== null
        && (commit.sha === sha || commit.sha.startsWith(sha) || sha.startsWith(commit.sha)))?.sha ?? sha;
      if (canonicalSha === null || seenCommits.has(canonicalSha)) continue;
      seenCommits.add(canonicalSha);
      const message = str(event.payload, 'message') ?? '';
      items.push({
        kind: 'commit', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
        message: message.split(/\r?\n/, 1)[0] ?? '', sha: canonicalSha,
      });
      continue;
    }
    if (event.eventType === 'status') {
      const target = str(event.payload, 'to');
      if (target === 'local-open') {
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label: 'Development completed · local review opened', tone: 'success',
          sha: str(event.payload, 'sha'),
        });
      }
      else if (target === 'remote-drafted') {
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label: 'First push completed · draft pull request opened', tone: 'success',
          sha: str(event.payload, 'sha'),
        });
      }
      else if (target === 'remote-open') {
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label: 'Draft pull request marked ready for review', tone: 'success',
          sha: str(event.payload, 'sha'),
        });
      }
      else if (target === 'merged') {
        hasMergedEvent = true;
        items.push({
          kind: 'merged', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          author: displayName(bundle.pr.author ?? 'you'),
          sha: str(event.payload, 'sha'), base: bundle.pr.baseBranch,
        });
      }
      else if (target === 'closed') {
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label: 'Pull request closed without merge', tone: 'attention',
          sha: str(event.payload, 'sha'),
        });
      }
      else if (target === 'cleanup-started' || target === 'cleanup-completed') {
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label: target === 'cleanup-started' ? 'Cleanup started' : 'Cleanup completed',
          tone: target === 'cleanup-completed' ? 'success' : 'neutral', sha: null,
        });
      }
      continue;
    }
    if (event.eventType === 'ci' && event.actor === 'ci-harness') {
      items.push({
        kind: 'ci-harness', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
        message: str(event.payload, 'message') ?? 'CI Harness updated the local branch',
        phase: str(event.payload, 'phase'), status: str(event.payload, 'status'),
        sha: str(event.payload, 'sha')?.slice(0, 7) ?? null,
      });
      continue;
    }
    if (event.eventType === 'ci') {
      const status = str(event.payload, 'status');
      if (status === null) continue;
      if (status === 'repair_started' || status === 'repair_addressed'
          || status === 'repair_succeeded' || status === 'repair_exhausted'
          || status === 'repair_stopped') {
        const classification = str(event.payload, 'classification');
        const reason = str(event.payload, 'reason');
        const label = status === 'repair_started'
          ? `CI repair started${classification === null ? '' : ` · ${classification.toLowerCase().replaceAll('_', ' ')}`}`
          : status === 'repair_addressed'
            ? 'CI repair addressed the failing head'
            : status === 'repair_succeeded'
              ? 'CI repair completed'
              : status === 'repair_exhausted'
                ? 'CI repair budget exhausted'
                : `CI repair stopped${reason === null ? '' : ` · ${reason}`}`;
        items.push({
          kind: 'milestone', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          label,
          tone: status === 'repair_addressed' || status === 'repair_succeeded'
            ? 'success' : 'attention',
          sha: str(event.payload, 'headSha'),
        });
        continue;
      }
      const signature = JSON.stringify([
        status, str(event.payload, 'headSha'), num(event.payload, 'checkCount'),
        str(event.payload, 'name'), str(event.payload, 'trigger'),
      ]);
      if (signature === lastAggregateCi) continue;
      lastAggregateCi = signature;
      items.push({
        kind: 'ci', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt), status,
        previousStatus: str(event.payload, 'previousStatus'),
        headSha: str(event.payload, 'headSha'), checkCount: num(event.payload, 'checkCount'),
        name: str(event.payload, 'name'), trigger: str(event.payload, 'trigger'),
      });
      continue;
    }
    if (event.eventType === 'review') {
      if (duplicateReviews.has(event.id)) continue;
      const reviewEvent = str(event.payload, 'reviewEvent');
      const scope = str(event.payload, 'scope');
      if (reviewEvent === 'submitted' && event.actor === 'you' && bundle.pr.origin === 'task') {
        // The selected threads are the task-local review UI. A second empty
        // "You commented" card adds no information and makes the dispatch
        // look like a GitHub review, which task PRs never publish.
        continue;
      }
      if (reviewEvent === 'started' || reviewEvent === 'addressing-started'
          || reviewEvent === 'failed' || reviewEvent === 'parked') {
        if (scope !== 'plan' && scope !== 'dev' && scope !== 'round') continue;
        items.push({
          kind: 'review-activity', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
          author: displayName(event.actor), activity: reviewEvent === 'parked' ? 'failed' : reviewEvent,
          scope: reviewScope(scope),
          iteration: num(event.payload, 'iteration'),
          roundId: str(event.payload, 'roundId'),
          reason: str(event.payload, 'reason'),
        });
        continue;
      }
      const verdict = str(event.payload, 'verdict');
      const body = str(event.payload, 'body');
      const normalizedScope = reviewScope(scope);
      const isBrainLifecycleReview = event.actor === 'brain'
        && event.isLocalOnly
        && normalizedScope !== null;
      const structuredSummary = event.payload?.['structuredSummary'] === true;
      // Brain review bodies are raw turn transcripts, not authored review
      // summaries. Structured concerns already render as local comment
      // cards, so repeating the transcript here leaks progress narration.
      const visibleBody = (!isBrainLifecycleReview || structuredSummary)
          && body !== null && body.trim().length > 0
        ? body
        : null;
      if (verdict === null && visibleBody === null) continue;
      items.push({
        kind: 'review', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
        author: displayName(event.actor), bot: isBotActor(event.actor),
        verdict: reviewVerdict(verdict),
        body: visibleBody,
        remoteId: typeof event.remoteEventId === 'number' ? event.remoteEventId : null,
        scope: normalizedScope,
        iteration: num(event.payload, 'iteration'),
        roundId: str(event.payload, 'roundId'),
      });
    }
  }

  // Once an external draft is published, GitHub's canonical review thread is
  // the timeline source. Keep task-local (stripped-on-push) audit threads, but
  // never render the published external draft a second time.
  const localComments = bundle.comments.filter(comment => comment.origin === 'local'
    && comment.publishedAt === null);
  const allLocalById = new Map(bundle.comments
    .filter(comment => comment.origin === 'local')
    .map(comment => [comment.id, comment]));
  const localById = new Map(localComments.map(comment => [comment.id, comment]));
  const localGroups = new Map<string, LocalPRComment[]>();
  for (const comment of localComments) {
    // A child left behind after its external draft root was published belongs
    // to GitHub's canonical thread; it is not a new top-level local draft.
    if (comment.parentCommentId !== null
        && allLocalById.has(comment.parentCommentId)
        && !localById.has(comment.parentCommentId)) continue;
    const rootId = comment.parentCommentId !== null && localById.has(comment.parentCommentId)
      ? comment.parentCommentId
      : comment.id;
    const group = localGroups.get(rootId);
    if (group === undefined) localGroups.set(rootId, [comment]);
    else group.push(comment);
  }
  for (const [rootId, comments] of localGroups) {
    comments.sort((a, b) => a.createdAt - b.createdAt);
    const root = comments[0];
    if (root === undefined) continue;
    items.push({
      kind: 'local-thread', id: `local-thread-${rootId}`, at: root.createdAt,
      comments, submitted: submittedCommentIds.has(rootId),
    });
  }

  const prComments = bundle.comments.filter(c => c.scope === 'pr' && c.origin !== 'local');
  const ids = new Set(prComments.map(c => c.id));
  for (const root of prComments) {
    // Same root rule as groupLocalCommentThreads: a missing parent makes
    // the comment its own root.
    if (root.parentCommentId !== null && ids.has(root.parentCommentId)) continue;
    const replies: TimelineReply[] = prComments
      .filter(c => c.parentCommentId === root.id)
      .sort((a, b) => a.createdAt - b.createdAt)
      .map(c => ({
        id: c.id, author: displayName(c.author), bot: isBotActor(c.author),
        body: c.body, time: agoLabel(c.createdAt),
      }));
    items.push({
      kind: 'comment', id: root.id, at: root.createdAt, time: agoLabel(root.createdAt),
      author: displayName(root.author), bot: isBotActor(root.author), body: root.body, replies,
      remoteId: remoteCommentIds.get(root.id) ?? null,
    });
  }
  items.sort((a, b) => a.at - b.at);
  if (bundle.pr.status === 'merged' && !hasMergedEvent) {
    const lastSha = bundle.commits[bundle.commits.length - 1]?.sha ?? null;
    const at = bundle.pr.mergedAt ?? items[items.length - 1]?.at ?? bundle.pr.createdAt;
    items.push({
      kind: 'merged', id: 'merged', at, time: agoLabel(at),
      author: displayName(bundle.pr.author ?? 'you'),
      sha: lastSha !== null ? lastSha.slice(0, 7) : null, base: bundle.pr.baseBranch,
    });
  }
  return items;
}

/** The lines worth picking out of a CI log excerpt: Maven prefixes its
 *  failures with `[ERROR]`, the Actions runner writes `##[error]`, and a Java
 *  stack trace chains its root cause with `Caused by:`. Everything else in the
 *  excerpt is surrounding context. */
export function isCiErrorLine(line: string): boolean {
  return line.includes('[ERROR]') || line.includes('##[error]') || line.includes('Caused by:');
}

export type CheckRowState = 'fail' | 'prog' | 'ok' | 'skip';
export type ChecksGroup = {
  key: string;
  label: string;
  defaultOpen: boolean;
  /** `time` is the last-run label ("3h ago"), empty for never-run/skipped
   *  checks; `title` is the absolute timestamp shown on hover. `checkRunId`
   *  is the GitHub check-run id, the key the annotations fetch needs — null
   *  for local checks and for remote rows recorded without one (the agent's
   *  own record-check tool passes no run id), which simply don't unfold. */
  rows: { name: string; note: string; time: string; title: string; state: CheckRowState; checkRunId: number | null }[];
};
export type ChecksModel = { state: 'fail' | 'prog' | 'ok'; title: string; sub: string; groups: ChecksGroup[] };

/** Prototype checksFor() shapes from real checks; null (omit card) when empty. */
export function buildChecks(checks: LocalPRCheck[]): ChecksModel | null {
  if (checks.length === 0) return null;
  const row = (c: LocalPRCheck, state: CheckRowState) => {
    // "Last time it ran" = when it finished, falling back to when it started
    // (still-running checks have no finish yet). Skipped checks never ran.
    const ranAt = c.finishedAt ?? c.startedAt;
    return {
      name: c.name, state,
      note: state === 'skip' ? 'skipped' : c.kind === 'local' ? 'local' : 'ci',
      time: state === 'skip' ? '' : agoLabel(ranAt),
      title: state === 'skip' ? '' : new Date(ranAt).toLocaleString(),
      checkRunId: c.kind === 'remote' && c.runId !== null && /^\d+$/.test(c.runId)
        ? Number(c.runId)
        : null,
    };
  };
  const failing = checks.filter(c => c.status === 'failed');
  const inProgress = checks.filter(c => c.status === 'pending' || c.status === 'running');
  const ok = checks.filter(c => c.status === 'passed');
  const neutral = checks.filter(c => c.status === 'neutral');
  const groups: ChecksGroup[] = [];
  if (failing.length > 0) {
    groups.push({ key: 'g-fail', label: `Failing (${failing.length})`, defaultOpen: true, rows: failing.map(c => row(c, 'fail')) });
  }
  if (inProgress.length > 0) {
    groups.push({ key: 'g-prog', label: `In progress (${inProgress.length})`, defaultOpen: true, rows: inProgress.map(c => row(c, 'prog')) });
  }
  if (ok.length > 0) {
    groups.push({ key: 'g-ok', label: `Successful (${ok.length})`, defaultOpen: false, rows: ok.map(c => row(c, 'ok')) });
  }
  if (neutral.length > 0) {
    groups.push({ key: 'g-neu', label: `Neutral (${neutral.length})`, defaultOpen: false, rows: neutral.map(c => row(c, 'skip')) });
  }
  if (failing.length > 0) {
    const parts = [`${failing.length} failing`];
    if (inProgress.length > 0) parts.push(`${inProgress.length} in progress`);
    parts.push(`${ok.length + neutral.length} completed`);
    return { state: 'fail', title: 'Some checks were not successful', sub: parts.join(', '), groups };
  }
  if (inProgress.length > 0) {
    return {
      state: 'prog', title: "Some checks haven't completed yet",
      sub: `${inProgress.length} in progress, ${ok.length} successful`, groups,
    };
  }
  return {
    state: 'ok', title: 'All checks have passed',
    sub: `${ok.length} successful ${ok.length === 1 ? 'check' : 'checks'}`, groups,
  };
}
