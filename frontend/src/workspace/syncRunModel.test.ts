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
  durationLabel, syncLogGroups, syncNowLine, syncPhase, syncQueue, worktreeLabel,
  parseTranscript,
} from './syncRunModel';
import type {
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickEventDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

const job: UpstreamCherryPickJobDto = {
  jobId: 'job-1', status: 'RUNNING', sourceBranch: 'master',
  resultBranch: 'trino-2-31', baseRef: 'b'.repeat(40), requestedCount: 5,
  appliedCount: 2, skippedCount: 1, conflictedCount: 1, pauseRequested: false,
  budgetMilliUsd: 5_000, spentMilliUsd: 0, localGateUnavailable: false, conflictPaths: [], worktreePath: '/repos/fork.bytequay-worktrees/upstream-cherry-pick/job-1',
  prNumber: null, prUrl: null, harnessWatchId: null, errorMessage: null,
  closedAt: null,
  createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-05T09:30:00Z',
};

const commits: UpstreamCherryPickCommitDto[] = [
  { index: 0, sha: 'a1', shortSha: 'a1a1a1a', subject: 'One', state: 'applied' },
  { index: 1, sha: 'b2', shortSha: 'b2b2b2b', subject: 'Two', state: 'conflicted' },
  { index: 2, sha: 'c3', shortSha: 'c3c3c3c', subject: 'Three', state: 'skipped' },
  { index: 3, sha: 'd4', shortSha: 'd4d4d4d', subject: 'Four', state: 'current' },
  { index: 4, sha: 'e5', shortSha: 'e5e5e5e', subject: 'Five', state: 'waiting' },
];

const event = (
  id: string,
  pickIndex: number | null,
  kind: UpstreamCherryPickEventDto['kind'],
): UpstreamCherryPickEventDto => ({
  id, ordinal: Number(id), pickIndex, kind, title: id, detail: null,
  exitCode: null, durationMs: null, at: '2026-08-05T09:05:00Z',
});

describe('sync run model', () => {
  it('splits the queue into done, in flight, and a bounded waiting window', () => {
    const queue = syncQueue(commits, 1);
    expect(queue.done.map(commit => commit.sha)).toEqual(['a1', 'b2', 'c3']);
    expect(queue.current?.sha).toBe('d4');
    expect(queue.next.map(commit => commit.sha)).toEqual(['e5']);
    expect(queue.cleanCount).toBe(1);
    expect(queue.carriedCount).toBe(1);
  });

  it('caps the waiting window and counts the rest rather than listing them', () => {
    const many = Array.from({ length: 40 }, (unused, index): UpstreamCherryPickCommitDto => ({
      index, sha: `s${index}`, shortSha: `s${index}`, subject: `Commit ${index}`, state: 'waiting',
    }));
    const queue = syncQueue(many, 10);
    expect(queue.next).toHaveLength(10);
    expect(queue.moreCount).toBe(30);
    expect(queue.last?.sha).toBe('s39');
  });

  it('groups consecutive log lines under the pick that produced them', () => {
    const groups = syncLogGroups([
      event('1', null, 'start'),
      event('2', 0, 'command'),
      event('3', 0, 'note'),
      event('4', 1, 'command'),
      event('5', null, 'push'),
      event('6', null, 'pr'),
    ]);
    expect(groups.map(group => group.pickIndex)).toEqual([null, 0, 1, null]);
    expect(groups[1].events).toHaveLength(2);
    expect(groups[3].events.map(entry => entry.kind)).toEqual(['push', 'pr']);
  });

  it('names the phase from what the run has actually reached', () => {
    expect(syncPhase(job)).toBe('PHASE 1 · PICKING');
    expect(syncPhase({ ...job, pauseRequested: true })).toBe('PHASE 1 · PAUSING');
    expect(syncPhase({ ...job, status: 'PAUSED_CONFLICT' })).toBe('PHASE 1 · PARKED');
    expect(syncPhase({ ...job, status: 'COMPLETED' })).toBe('PHASE 1 · COMPLETE');
    expect(syncPhase({ ...job, status: 'COMPLETED', harnessWatchId: 'watch-1' }))
      .toBe('PHASE 2 · CI HARNESS');
  });

  it('says what the run is doing rather than that it is busy', () => {
    expect(syncNowLine(job, syncQueue(commits))).toBe('Picking Four — pick 4 of 5');
    expect(syncNowLine({ ...job, status: 'PAUSED_CONFLICT' }, syncQueue(commits)))
      .toContain('nothing is pushed');
    expect(syncNowLine(
      { ...job, status: 'COMPLETED', prNumber: 214 }, syncQueue(commits),
    )).toContain('#214');
  });

  it('formats command durations the way a terminal reads', () => {
    expect(durationLabel(800)).toBe('0.8s');
    expect(durationLabel(62_000)).toBe('1m 02s');
    expect(durationLabel(null)).toBe('');
  });

  it('shortens the worktree path to its last two segments', () => {
    expect(worktreeLabel(job.worktreePath)).toBe('…/upstream-cherry-pick/job-1');
    expect(worktreeLabel(null)).toBe('');
  });
});

describe('agent transcript', () => {
  const line = (value: unknown) => JSON.stringify(value);

  it('reads the turn as what it said, ran, and cost', () => {
    const raw = [
      line({ type: 'assistant', message: { content: [
        { type: 'text', text: 'Now validate the pom parses and resolves.' },
        { type: 'tool_use', name: 'Bash', input: { command: 'cd /w && ./mvnw -pl core install' } },
      ] } }),
      line({ type: 'result', is_error: false, total_cost_usd: 0.5669, num_turns: 15 }),
    ].join('\n');

    expect(parseTranscript(raw)).toEqual([
      { kind: 'say', text: 'Now validate the pom parses and resolves.' },
      { kind: 'tool', name: 'Bash', summary: 'cd /w && ./mvnw -pl core install' },
      { kind: 'result', failed: false, costUsdMilli: 567, turns: 15 },
    ]);
  });

  it('keeps a huge tool payload from burying the reasoning', () => {
    // One tool call can carry an entire pom.xml; only its first line reads.
    const raw = line({ type: 'assistant', message: { content: [
      { type: 'tool_use', name: 'Write', input: { file_path: 'pom.xml', content: 'x'.repeat(50_000) } },
    ] } });

    const [entry] = parseTranscript(raw);

    expect(entry).toEqual({ kind: 'tool', name: 'Write', summary: 'pom.xml' });
  });

  it('survives the truncated tail of a capped transcript', () => {
    // The stored transcript is a 64KB tail, so the first line is usually a
    // fragment and the last one may be cut mid-object.
    const raw = ['ckages>\\n  <package>org.apache', line({ type: 'result', is_error: true }),
      '{"type":"assistant","message":{"content":['].join('\n');

    expect(parseTranscript(raw)).toEqual([
      { kind: 'result', failed: true, costUsdMilli: 0, turns: 0 },
    ]);
  });

  it('is empty rather than throwing when there is nothing to read', () => {
    expect(parseTranscript(null)).toEqual([]);
    expect(parseTranscript('   ')).toEqual([]);
  });
});
