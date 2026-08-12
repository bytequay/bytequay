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
import type { UpstreamCherryPickRunDto } from './workspaceApi';

/**
 * A mid-range sync run: two picks behind it (one clean, one whose conflict was
 * carried), one in flight, a queue below. Shapes the run view's tests and the
 * visual fixture off one payload so they cannot drift apart.
 */
export function syncRun(): UpstreamCherryPickRunDto {
  return {
    baseBranch: 'main',
    job: {
      jobId: 'job-1',
      runNumber: 1,
      rangeFromSha: null,
      rangeToSha: null,
      prResult: null,
      status: 'RUNNING',
      sourceBranch: 'upstream/main',
      resultBranch: 'upstream-2-31',
      baseRef: 'a9f03c2b7d41e88aa0d19c3f5b6e7a8c9d0e1f23',
      requestedCount: 5,
      appliedCount: 2,
      skippedCount: 0,
      conflictedCount: 1,
      pauseRequested: false,
      budgetMilliUsd: 5_000,
      spentMilliUsd: 240,
      localGateUnavailable: false, agentSessionId: null,
      conflictPaths: [],
      worktreePath: '/repos/trino.bytequay-worktrees/upstream-cherry-pick/job-1',
      prNumber: null,
      prUrl: null,
      harnessWatchId: null,
      errorMessage: null,
      closedAt: null,
      createdAt: '2026-08-05T11:54:00Z',
      updatedAt: '2026-08-05T14:08:00Z',
    },
    commits: [
      {
        index: 0, sha: '41c9b02', shortSha: '41c9b02',
        subject: 'Fix flaky TestDynamicFilters timeout', state: 'applied',
      },
      {
        index: 1, sha: '9be22d1', shortSha: '9be22d1',
        subject: 'Extract CoordinatorModule config into CoordinatorConfig',
        state: 'conflicted',
      },
      {
        index: 2, sha: 'e8c1f4a', shortSha: 'e8c1f4a',
        subject: 'Refactor expression visitors to a registry', state: 'current',
      },
      {
        index: 3, sha: '7f20c3d', shortSha: '7f20c3d',
        subject: 'Add retry budget to exchange client', state: 'waiting',
      },
      {
        index: 4, sha: 'b3d91e0', shortSha: 'b3d91e0',
        subject: 'Remove deprecated legacy-timestamp flag', state: 'waiting',
      },
    ],
    events: [
      {
        id: '1', ordinal: 1, pickIndex: null, kind: 'start',
        title: 'Sync run started — 5 commits from upstream/main onto upstream-2-31',
        detail: 'worktree /repos/trino.bytequay-worktrees/upstream-cherry-pick/job-1',
        exitCode: null, durationMs: null, at: '2026-08-05T11:54:00Z',
      },
      {
        id: '2', ordinal: 2, pickIndex: 0, kind: 'command',
        title: 'git cherry-pick -x 41c9b02',
        detail: '[upstream-2-31 f21ac09] Fix flaky TestDynamicFilters timeout\n'
          + ' 1 file changed, 4 insertions(+), 2 deletions(-)',
        exitCode: 0, durationMs: 800, at: '2026-08-05T14:02:00Z',
      },
      {
        id: '3', ordinal: 3, pickIndex: 1, kind: 'command',
        title: 'git cherry-pick -x 9be22d1',
        detail: 'CONFLICT (content): Merge conflict in core/server/src/main/CoordinatorModule.java\n'
          + 'CONFLICT (content): Merge conflict in core/server/src/main/CoordinatorConfig.java\n'
          + 'error: could not apply 9be22d1… Extract CoordinatorModule config',
        exitCode: 1, durationMs: 1_400, at: '2026-08-05T14:05:00Z',
      },
      {
        id: '4', ordinal: 4, pickIndex: 1, kind: 'note',
        title: "Committed git's three-way resolution",
        detail: 'core/server/src/main/CoordinatorModule.java\n'
          + 'core/server/src/main/CoordinatorConfig.java',
        exitCode: null, durationMs: null, at: '2026-08-05T14:05:00Z',
      },
      {
        id: '5', ordinal: 5, pickIndex: 1, kind: 'command',
        title: './mvnw -pl core/server -am compile -DskipTests',
        detail: '[ERROR] CoordinatorModule.java:[118,9] illegal start of expression\n'
          + '[INFO] BUILD FAILURE',
        exitCode: 1, durationMs: 54_000, at: '2026-08-05T14:06:00Z',
      },
      {
        id: '6', ordinal: 6, pickIndex: 1, kind: 'agent',
        title: 'Both conflicts trace to the fork-only catalogOverrides binding: upstream '
          + 'moved the whole binding block from CoordinatorModule into the new '
          + 'CoordinatorConfig. Carrying the fork\u2019s binding into the new location and '
          + 'dropping the now-stale import — no behaviour change on the fork side.',
        detail: 'attempt 1 of 5',
        exitCode: null, durationMs: null, at: '2026-08-05T14:06:00Z',
      },
      {
        id: '7', ordinal: 7, pickIndex: 1, kind: 'fixup',
        title: '5d1ae74',
        detail: 'fixup! Extract CoordinatorModule config into CoordinatorConfig',
        exitCode: null, durationMs: null, at: '2026-08-05T14:07:00Z',
      },
      {
        id: '8', ordinal: 8, pickIndex: 1, kind: 'command',
        title: './mvnw -pl core/server -am compile -DskipTests',
        detail: '[INFO] core/server ........................ SUCCESS [51.2 s]\n'
          + '[INFO] BUILD SUCCESS',
        exitCode: 0, durationMs: 51_200, at: '2026-08-05T14:07:00Z',
      },
      {
        id: '9', ordinal: 9, pickIndex: 1, kind: 'note',
        title: 'Repaired — the fixup compiles beside its pick',
        detail: null, exitCode: null, durationMs: null, at: '2026-08-05T14:07:00Z',
      },
    ],
  };
}
