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
import type { RewritableCommitDto, RewritePlanDto } from './workspaceApi';

/**
 * One row of the history editor. Ordered NEWEST FIRST throughout this
 * module, matching what the list renders and what `git log` returns —
 * "above" means newer, "deepest" means oldest.
 *
 * Edits are staged here and only ever leave as a {@link RewritePlanDto};
 * nothing in this file touches git.
 */
export type EditableCommit = {
  /** Stable React key. The original sha, or a synthetic id for a squash
   *  result (which has no sha until the rebase actually runs). */
  id: string;
  shortSha: string;
  subject: string;
  body: string;
  authorName: string;
  /** Kept because GitHub's noreply commit address is the only place a
   *  commit reliably names the account behind it — see githubHandle. */
  authorEmail: string;
  authoredAt: string | null;
  additions: number;
  deletions: number;
  /** The remote already has this commit — rewriting it needs a force push. */
  pushed: boolean;
  /** A staged op has pulled this pushed commit into the rewrite zone. */
  rewritten: boolean;
  /** Original full shas folded into this row, OLDEST first. More than
   *  one means a squash; the first is where the change lands. */
  picks: string[];
  /** Participant count when this row came out of a squash, else 0. */
  squashedFrom: number;
  /** Subject or body edited relative to the original commit. */
  reworded: boolean;
};

export type PendingOp = {
  key: string;
  kind: 'reorder' | 'squash' | 'reword';
  label: string;
};

export function toEditable(dto: RewritableCommitDto): EditableCommit {
  return {
    id: dto.sha,
    shortSha: dto.shortSha,
    subject: dto.subject,
    body: dto.body,
    authorName: dto.authorName,
    authorEmail: dto.authorEmail,
    authoredAt: dto.authoredAt,
    additions: dto.additions,
    deletions: dto.deletions,
    pushed: dto.pushed,
    rewritten: false,
    picks: [dto.sha],
    squashedFrom: 0,
    reworded: false,
  };
}

/**
 * Index of the first commit the remote still holds unmodified. Everything
 * above it is the LOCAL group: either never pushed, or pushed but already
 * dragged into the rewrite zone by a staged op. This is the ONLY source
 * for the LOCAL divider, the "N ahead of origin" count, and the
 * force-push warning — deliberately derived on every render rather than
 * cached, because any op can move the boundary.
 */
export function firstPushedIndex(list: EditableCommit[]): number {
  const at = list.findIndex(c => c.pushed && !c.rewritten);
  return at < 0 ? list.length : at;
}

export function needsForcePush(list: EditableCommit[]): boolean {
  return list.some(c => c.pushed && c.rewritten);
}

/**
 * Marks every participant — and everything above the deepest one — as
 * rewritten. Rewriting a commit necessarily rewrites its descendants,
 * so a single drag near the bottom of the list can pull a long run of
 * pushed commits into the force-push zone.
 */
export function markRewritten(list: EditableCommit[], indexes: number[]): EditableCommit[] {
  const deepest = Math.max(...indexes);
  return list.map((c, i) => (i <= deepest && c.pushed && !c.rewritten
    ? { ...c, rewritten: true }
    : c));
}

function indexesOf(list: EditableCommit[], ids: string[]): number[] {
  return ids.map(id => list.findIndex(c => c.id === id)).filter(i => i >= 0);
}

/**
 * Moves `ids` so they sit immediately before or after `targetId`,
 * keeping their relative order. Returns the input untouched when the
 * move is a no-op (target inside the moved set, unknown ids).
 */
export function moveCommits(
  list: EditableCommit[],
  ids: string[],
  targetId: string,
  mode: 'before' | 'after',
): { list: EditableCommit[]; op: PendingOp } | null {
  if (ids.includes(targetId)) return null;
  const indexes = indexesOf(list, [...ids, targetId]);
  if (indexes.length !== ids.length + 1) return null;

  const marked = markRewritten(list, indexes);
  const moved = marked.filter(c => ids.includes(c.id));
  const rest = marked.filter(c => !ids.includes(c.id));
  let at = rest.findIndex(c => c.id === targetId);
  if (at < 0) return null;
  if (mode === 'after') at += 1;
  return {
    list: [...rest.slice(0, at), ...moved, ...rest.slice(at)],
    op: {
      key: `reorder-${moved.map(c => c.id).join('-')}-${targetId}-${mode}`,
      kind: 'reorder',
      label: moved.length > 1
        ? `Reorder ${moved.length} commits`
        : `Reorder ${moved[0].shortSha}`,
    },
  };
}

/**
 * Folds `ids` into one commit that takes `anchorId`'s position. Line
 * counts and changed files merge; the resulting row keeps the anchor's
 * author and time, and is pushed/rewritten if ANY participant was.
 */
export function squashCommits(
  list: EditableCommit[],
  ids: string[],
  anchorId: string,
  subject: string,
  body: string,
  seq: number,
): { list: EditableCommit[]; op: PendingOp } | null {
  const indexes = indexesOf(list, ids);
  if (indexes.length !== ids.length || ids.length < 2) return null;

  const marked = markRewritten(list, indexes);
  const parts = marked.filter(c => ids.includes(c.id));
  const anchor = parts.find(c => c.id === anchorId) ?? parts[parts.length - 1];
  const merged: EditableCommit = {
    id: `squash-${seq}`,
    shortSha: anchor.shortSha,
    subject: subject.trim() || anchor.subject,
    body,
    authorName: anchor.authorName,
    authorEmail: anchor.authorEmail,
    authoredAt: anchor.authoredAt,
    additions: parts.reduce((n, c) => n + c.additions, 0),
    deletions: parts.reduce((n, c) => n + c.deletions, 0),
    pushed: parts.some(c => c.pushed),
    rewritten: parts.some(c => c.pushed),
    // Oldest first for the rebase todo: the list runs newest → oldest,
    // so reverse before flattening each participant's own picks.
    picks: [...parts].reverse().flatMap(c => c.picks),
    squashedFrom: parts.length,
    reworded: false,
  };
  const next: EditableCommit[] = [];
  for (const c of marked) {
    if (c.id === anchor.id) next.push(merged);
    else if (!ids.includes(c.id)) next.push(c);
  }
  return {
    list: next,
    op: {
      key: `squash-${seq}`,
      kind: 'squash',
      label: `Squash ${parts.length} → 1`,
    },
  };
}

/**
 * Rewrites one commit's message. Applied during the same rebase as
 * everything else, so history stays linear — the row is just marked so
 * the plan knows to carry a message for it.
 */
export function rewordCommit(
  list: EditableCommit[],
  id: string,
  subject: string,
  body: string,
): { list: EditableCommit[]; op: PendingOp } | null {
  const at = list.findIndex(c => c.id === id);
  if (at < 0) return null;
  const marked = markRewritten(list, [at]);
  return {
    list: marked.map(c => (c.id === id ? { ...c, subject, body, reworded: true } : c)),
    op: {
      key: `reword-${id}-${subject}`,
      kind: 'reword',
      label: `Reword ${list[at].shortSha}`,
    },
  };
}

/**
 * How many commits at the OLDEST end are still byte-for-byte what git
 * has. Walking up from the bottom (rather than diffing by index from the
 * top) is what makes a squash — which shortens the list and shifts every
 * index below it — still resolve to a tight rebase range instead of
 * sweeping in untouched pushed history.
 */
export function unchangedTail(
  original: RewritableCommitDto[],
  list: EditableCommit[],
): number {
  let count = 0;
  while (count < list.length && count < original.length) {
    const edited = list[list.length - 1 - count];
    const source = original[original.length - 1 - count];
    if (edited.reworded || edited.picks.length !== 1 || edited.picks[0] !== source.sha) break;
    count++;
  }
  return count;
}

export function hasStagedEdits(
  original: RewritableCommitDto[],
  list: EditableCommit[],
): boolean {
  return list.length !== original.length || unchangedTail(original, list) !== list.length;
}

function fullMessage(commit: EditableCommit): string {
  const body = commit.body.trim();
  return body.length === 0 ? commit.subject : `${commit.subject}\n\n${body}`;
}

/**
 * Turns the staged list into the single rebase the backend runs. The
 * base is the newest commit nothing touched, so the rebase range covers
 * exactly the edited span and no more.
 *
 * @throws Error when the edit reaches past the loaded history and there
 *         is no untouched commit left to replay onto.
 */
export function buildRewritePlan(
  original: RewritableCommitDto[],
  list: EditableCommit[],
  branch: string,
): RewritePlanDto {
  const tail = unchangedTail(original, list);
  if (tail === 0) {
    throw new Error(
      'This edit reaches the oldest commit loaded. Load more history before rewriting.');
  }
  const base = original[original.length - tail].sha;
  const edited = list.slice(0, list.length - tail);
  return {
    branch,
    base,
    // Oldest first — the order git replays them in.
    commits: [...edited].reverse().map(c => ({
      picks: c.picks,
      // Null keeps the original message, which skips the amend entirely
      // for a pure reorder.
      message: c.picks.length > 1 || c.reworded ? fullMessage(c) : null,
    })),
    forcePush: needsForcePush(list),
  };
}
