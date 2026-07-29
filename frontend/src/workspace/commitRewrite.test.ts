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
import { describe, expect, it } from 'vitest';
import {
  buildRewritePlan,
  firstPushedIndex,
  hasStagedEdits,
  moveCommits,
  needsForcePush,
  rewordCommit,
  squashCommits,
  toEditable,
  unchangedTail,
  type EditableCommit,
} from './commitRewrite';
import { githubHandle } from './CommitEditorUi';
import type { RewritableCommitDto } from './workspaceApi';

/** Newest first, matching git log and the list. `local` commits sit on
 *  top; everything else is already on the remote. */
function history(...rows: Array<[string, boolean]>): RewritableCommitDto[] {
  return rows.map(([sha, pushed]) => ({
    sha,
    shortSha: sha.slice(0, 7),
    subject: `subject ${sha}`,
    body: `body ${sha}`,
    authorName: 'chenjian2664',
    authorEmail: 'c@example.com',
    authoredAt: '2026-07-29T10:00:00Z',
    additions: 10,
    deletions: 2,
    pushed,
  }));
}

const SAMPLE = history(
  ['aaaaaaa1', false],
  ['bbbbbbb2', false],
  ['ccccccc3', true],
  ['ddddddd4', true],
  ['eeeeeee5', true],
);

const editable = (): EditableCommit[] => SAMPLE.map(toEditable);

/** Every op returns null when it can't apply; the tests that expect an
 *  op to work say so once, here, instead of asserting non-null inline. */
function applied(
  result: { list: EditableCommit[]; op: { label: string } } | null,
): { list: EditableCommit[]; op: { label: string } } {
  if (result === null) throw new Error('expected the op to apply');
  return result;
}

describe('the local group boundary', () => {
  it('is everything above the first still-pushed commit', () => {
    expect(firstPushedIndex(editable())).toBe(2);
    expect(needsForcePush(editable())).toBe(false);
  });

  it('grows to cover pushed commits an op pulled into the rewrite zone', () => {
    // Move the newest commit below a pushed one: everything above the
    // deepest participant becomes rewritable, so the LOCAL group and the
    // force-push warning both follow.
    const moved = applied(moveCommits(editable(), ['aaaaaaa1'], 'ddddddd4', 'after'));
    expect(firstPushedIndex(moved.list)).toBe(4);
    expect(needsForcePush(moved.list)).toBe(true);
  });

  it('is derived, never stored — undoing back to the original list clears it', () => {
    const start = editable();
    const moved = applied(moveCommits(start, ['aaaaaaa1'], 'ddddddd4', 'after'));
    expect(needsForcePush(moved.list)).toBe(true);
    // The snapshot the Undo stack holds is the pre-op list itself.
    expect(needsForcePush(start)).toBe(false);
    expect(firstPushedIndex(start)).toBe(2);
  });
});

describe('reorder', () => {
  it('moves a commit before another and keeps everything else in place', () => {
    const next = applied(moveCommits(editable(), ['bbbbbbb2'], 'aaaaaaa1', 'before'));
    expect(next.list.map(c => c.id)).toEqual([
      'bbbbbbb2', 'aaaaaaa1', 'ccccccc3', 'ddddddd4', 'eeeeeee5']);
    expect(next.op.label).toBe('Reorder bbbbbbb');
  });

  it('moves a whole multi-selection as one block, preserving its order', () => {
    const next = applied(moveCommits(editable(), ['aaaaaaa1', 'bbbbbbb2'], 'ddddddd4', 'after'));
    expect(next.list.map(c => c.id)).toEqual([
      'ccccccc3', 'ddddddd4', 'aaaaaaa1', 'bbbbbbb2', 'eeeeeee5']);
    expect(next.op.label).toBe('Reorder 2 commits');
  });

  it('refuses to drop a selection onto itself', () => {
    expect(moveCommits(editable(), ['aaaaaaa1', 'bbbbbbb2'], 'aaaaaaa1', 'after')).toBeNull();
  });
});

describe('squash', () => {
  it('lands on the anchor and merges line counts and picks', () => {
    const next = applied(squashCommits(
      editable(), ['aaaaaaa1', 'bbbbbbb2'], 'bbbbbbb2', 'Combined', 'why', 1));
    expect(next.list.map(c => c.id)).toEqual([
      'squash-1', 'ccccccc3', 'ddddddd4', 'eeeeeee5']);
    const merged = next.list[0];
    expect(merged.subject).toBe('Combined');
    expect(merged.additions).toBe(20);
    expect(merged.deletions).toBe(4);
    // Oldest first, which is the order git replays them in.
    expect(merged.picks).toEqual(['bbbbbbb2', 'aaaaaaa1']);
    expect(merged.squashedFrom).toBe(2);
    expect(next.op.label).toBe('Squash 2 → 1');
  });

  it('takes the anchor position even when the anchor is the newer commit', () => {
    const next = applied(squashCommits(
      editable(), ['aaaaaaa1', 'bbbbbbb2'], 'aaaaaaa1', 'Combined', '', 1));
    expect(next.list.map(c => c.id)).toEqual([
      'squash-1', 'ccccccc3', 'ddddddd4', 'eeeeeee5']);
  });

  it('needs a force push once a pushed commit takes part', () => {
    const next = applied(squashCommits(
      editable(), ['bbbbbbb2', 'ccccccc3'], 'ccccccc3', 'Combined', '', 1));
    expect(needsForcePush(next.list)).toBe(true);
    // The merged row sits at the anchor's old position, below aaaaaaa1.
    expect(next.list.map(c => c.id)).toEqual(['aaaaaaa1', 'squash-1', 'ddddddd4', 'eeeeeee5']);
    expect(next.list[1].pushed).toBe(true);
  });

  it('folds a previous squash result without losing its picks', () => {
    const once = applied(squashCommits(
      editable(), ['aaaaaaa1', 'bbbbbbb2'], 'bbbbbbb2', 'One', '', 1));
    const twice = applied(squashCommits(
      once.list, ['squash-1', 'ccccccc3'], 'ccccccc3', 'Two', '', 2));
    expect(twice.list[0].picks).toEqual(['ccccccc3', 'bbbbbbb2', 'aaaaaaa1']);
    expect(twice.list[0].additions).toBe(30);
  });

  it('does nothing with fewer than two participants', () => {
    expect(squashCommits(editable(), ['aaaaaaa1'], 'aaaaaaa1', 'x', '', 1)).toBeNull();
  });
});

describe('reword', () => {
  it('marks only the target and keeps its body when only the title changed', () => {
    const next = applied(rewordCommit(editable(), 'bbbbbbb2', 'New title', 'body bbbbbbb2'));
    expect(next.list[1].subject).toBe('New title');
    expect(next.list[1].body).toBe('body bbbbbbb2');
    expect(next.list[1].reworded).toBe(true);
    expect(next.list[0].reworded).toBe(false);
    expect(next.op.label).toBe('Reword bbbbbbb');
  });

  it('drags pushed history into the rewrite zone when it reaches down', () => {
    const next = applied(rewordCommit(editable(), 'ddddddd4', 'New title', ''));
    expect(needsForcePush(next.list)).toBe(true);
    expect(firstPushedIndex(next.list)).toBe(4);
  });
});

describe('the rebase plan', () => {
  it('reports no staged edits for an untouched list', () => {
    expect(hasStagedEdits(SAMPLE, editable())).toBe(false);
    expect(unchangedTail(SAMPLE, editable())).toBe(5);
  });

  it('replays onto the newest untouched commit, not the whole loaded range', () => {
    const next = applied(moveCommits(editable(), ['aaaaaaa1'], 'bbbbbbb2', 'after'));
    const plan = buildRewritePlan(SAMPLE, next.list, 'main');
    // ccccccc3 and older are untouched, so the rebase starts there.
    expect(plan.base).toBe('ccccccc3');
    // Oldest first: aaaaaaa1 now replays before bbbbbbb2.
    expect(plan.commits).toEqual([
      { picks: ['aaaaaaa1'], message: null },
      { picks: ['bbbbbbb2'], message: null },
    ]);
    expect(plan.forcePush).toBe(false);
  });

  it('keeps the range tight after a squash, which shortens the list', () => {
    const next = applied(squashCommits(
      editable(), ['aaaaaaa1', 'bbbbbbb2'], 'bbbbbbb2', 'Combined', 'why', 1));
    const plan = buildRewritePlan(SAMPLE, next.list, 'main');
    // Index-by-index comparison would have diverged all the way down and
    // swept in the pushed commits; walking up from the oldest end does not.
    expect(plan.base).toBe('ccccccc3');
    expect(plan.commits).toEqual([
      { picks: ['bbbbbbb2', 'aaaaaaa1'], message: 'Combined\n\nwhy' },
    ]);
  });

  it('carries a message only for the rows that changed one', () => {
    const reworded = applied(rewordCommit(editable(), 'bbbbbbb2', 'New title', 'New body'));
    const plan = buildRewritePlan(SAMPLE, reworded.list, 'main');
    expect(plan.base).toBe('ccccccc3');
    expect(plan.commits).toEqual([
      { picks: ['bbbbbbb2'], message: 'New title\n\nNew body' },
      { picks: ['aaaaaaa1'], message: null },
    ]);
  });

  it('asks for a force push once the edit reaches pushed history', () => {
    const next = applied(moveCommits(editable(), ['aaaaaaa1'], 'ddddddd4', 'after'));
    const plan = buildRewritePlan(SAMPLE, next.list, 'main');
    expect(plan.base).toBe('eeeeeee5');
    expect(plan.forcePush).toBe(true);
    expect(plan.commits.map(c => c.picks[0]))
      .toEqual(['aaaaaaa1', 'ddddddd4', 'ccccccc3', 'bbbbbbb2']);
  });

  it('refuses to rewrite past the oldest loaded commit', () => {
    const next = applied(moveCommits(editable(), ['eeeeeee5'], 'aaaaaaa1', 'before'));
    expect(() => buildRewritePlan(SAMPLE, next.list, 'main'))
      .toThrow(/Load more history/);
  });
});

describe('the GitHub handle behind a commit', () => {
  it('reads the login out of a private commit address', () => {
    expect(githubHandle('Jack Chen', '12345+chenjian2664@users.noreply.github.com'))
      .toBe('chenjian2664');
    expect(githubHandle('Jack Chen', 'chenjian2664@users.noreply.github.com'))
      .toBe('chenjian2664');
  });

  it('falls back to the author name for any other address', () => {
    // Often the handle anyway; when it isn't, the avatar 404s and the
    // shared Avatar renders the initial instead.
    expect(githubHandle('chenjian2664', 'chenjian2664@example.com')).toBe('chenjian2664');
    expect(githubHandle('Jack Chen', 'jack@example.com')).toBe('Jack Chen');
  });

  it('is not fooled by an address that merely mentions the noreply host', () => {
    expect(githubHandle('Someone', 'evil@users.noreply.github.com.attacker.test'))
      .toBe('Someone');
  });
});
