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
import type { LocalPRBundle, LocalPRCheck } from '../types/localPr';
import type { PullRow } from './model';
import { agoLabel, displayName } from '../pr/localpr/prViewMeta';
import { relativeTime } from '../notificationDisplay';
import { shortCount } from './atoms';

/**
 * View-model builders for the redesigned PR detail pane — the shapes mirror
 * the DC prototype's detailFor()/checksFor() rows (docs/mockups/design/
 * pr-redesign/Pull Requests.dc.html) but are fed from the real
 * {@link LocalPRBundle} + dashboard row instead of the prototype's mock data.
 */

/** Bot actors render the square avatar per the prototype's legend. */
export function isBotActor(actor: string): boolean {
  return /\[bot]$/i.test(actor) || actor === 'claude-code';
}

export type PullDetailHeader = {
  title: string;
  numS: string;
  isMerged: boolean;
  /** null until the bundle loads — the base/branch chips wait for it. */
  base: string | null;
  branch: string | null;
  ovCount: number;
  addP: string;
  delP: string;
  agentAssigned: boolean;
  agentRunning: boolean;
  agentTitle: string;
  wsTitle: string;
};

export function buildHeader(row: PullRow, bundle: LocalPRBundle | null | undefined): PullDetailHeader {
  const running = row.dto.reviewState === 'running';
  return {
    title: bundle?.pr.title ?? row.title,
    numS: `#${row.num}`,
    isMerged: bundle?.pr.status === 'merged' || row.kind === 'merged',
    base: bundle?.pr.baseBranch ?? null,
    branch: bundle?.pr.branchName ?? null,
    ovCount: bundle?.comments.length ?? row.comments,
    addP: `+${shortCount(row.add)}`,
    delP: `−${shortCount(row.del)}`,
    agentAssigned: row.hasAgent,
    agentRunning: running,
    agentTitle: `Work with agent${running ? ' — agent running' : ' — idle'}`,
    wsTitle: `Open in workspace — locate this review task in the ${row.repo.split('/')[1] ?? row.repo} trunk`,
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

/** The 9px labels-chip dot: the first label's real GitHub color when synced,
 *  else the prototype's hardcoded #d4622a. */
export function labelDotColor(row: PullRow): string {
  const first = row.dto.labels[0];
  const hex = first !== undefined ? row.dto.labelColors?.[first] : undefined;
  return hex !== undefined && hex !== null && /^[0-9a-fA-F]{6}$/.test(hex) ? `#${hex}` : '#d4622a';
}

export type TimelineReply = { id: string; author: string; bot: boolean; body: string; time: string };

export type TimelineItem =
  | { kind: 'commit'; id: string; at: number; time: string; message: string; sha: string }
  | { kind: 'review'; id: string; at: number; time: string; author: string; bot: boolean;
      verdict: 'approved' | 'changes' | null; body: string | null }
  | { kind: 'comment'; id: string; at: number; time: string; author: string; bot: boolean;
      body: string; replies: TimelineReply[] }
  | { kind: 'merged'; id: string; at: number; time: string; author: string; sha: string | null; base: string };

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

const APPROVED_VERDICTS = new Set(['APPROVED', 'approved']);

/**
 * Maps the local timeline + PR-level comments to the template's card shapes:
 * commit rows, review cards (concluded reviews only — the brain's
 * started/addressing lifecycle rows have no counterpart card), comment cards
 * with grouped replies, and a synthetic merged row. Event types with no
 * template counterpart (ci/amend/branch/status/follow-up/plan-finalized,
 * plus `comment` events which render from `comments`) are omitted.
 */
export function buildTimeline(bundle: LocalPRBundle): TimelineItem[] {
  const items: TimelineItem[] = [];
  for (const event of bundle.timeline) {
    if (event.eventType === 'commit') {
      const sha = str(event.payload, 'sha');
      items.push({
        kind: 'commit', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
        message: str(event.payload, 'message') ?? '', sha: sha !== null ? sha.slice(0, 7) : '',
      });
      continue;
    }
    if (event.eventType === 'review') {
      const reviewEvent = str(event.payload, 'reviewEvent');
      if (reviewEvent === 'started' || reviewEvent === 'addressing-started') continue;
      const verdict = str(event.payload, 'verdict');
      const body = str(event.payload, 'body');
      if (verdict === null && (body === null || body.trim().length === 0)) continue;
      items.push({
        kind: 'review', id: event.id, at: event.createdAt, time: agoLabel(event.createdAt),
        author: displayName(event.actor), bot: isBotActor(event.actor),
        verdict: verdict === null ? null : APPROVED_VERDICTS.has(verdict) ? 'approved' : 'changes',
        body: body !== null && body.trim().length > 0 ? body : null,
      });
    }
  }
  const prComments = bundle.comments.filter(c => c.scope === 'pr');
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
    });
  }
  items.sort((a, b) => a.at - b.at);
  if (bundle.pr.status === 'merged') {
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

export type CheckRowState = 'fail' | 'prog' | 'ok' | 'skip';
export type ChecksGroup = {
  key: string;
  label: string;
  defaultOpen: boolean;
  rows: { name: string; note: string; state: CheckRowState }[];
};
export type ChecksModel = { state: 'fail' | 'prog' | 'ok'; title: string; sub: string; groups: ChecksGroup[] };

/** Prototype checksFor() shapes from real checks; null (omit card) when empty. */
export function buildChecks(checks: LocalPRCheck[]): ChecksModel | null {
  if (checks.length === 0) return null;
  const row = (c: LocalPRCheck, state: CheckRowState) => ({
    name: c.name, state, note: state === 'skip' ? 'skipped' : c.kind === 'local' ? 'local' : 'ci',
  });
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
