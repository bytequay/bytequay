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
import { derivePRCapabilities } from './prCapabilities';
import type { LocalPR, LocalPRStatus, PROrigin } from '../types/localPr';

function pr(origin: PROrigin, status: LocalPRStatus): LocalPR {
  return {
    id: 'pr1', taskId: origin === 'task' ? 't1' : null, branchName: 'feat/x', baseBranch: 'main',
    title: 'T', description: '', status, createdAt: 1, pushedAt: null, remotePrNumber: null,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin, repo: origin === 'external' ? 'acme/widget' : null, author: origin === 'external' ? '@octocat' : null,
    syncedAt: null,
  };
}

describe('derivePRCapabilities', () => {
  it('allows push only for a task-origin PR that is local-open', () => {
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').push).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-drafted'), 'task').push).toBe(false);
    expect(derivePRCapabilities(pr('external', 'local-open'), 'task').push).toBe(false);
  });

  it('allows merge only for a task-origin PR that is remote-open', () => {
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').merge).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-drafted'), 'task').merge).toBe(false);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'task').merge).toBe(false);
  });

  it('allows publishReview only for external-origin PRs, regardless of status', () => {
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('external', 'merged'), 'details').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').publishReview).toBe(false);
  });

  it('allows draftLocalComments everywhere except terminal states', () => {
    expect(derivePRCapabilities(pr('task', 'local-drafted'), 'task').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('task', 'merged'), 'task').draftLocalComments).toBe(false);
    expect(derivePRCapabilities(pr('external', 'closed'), 'details').draftLocalComments).toBe(false);
  });

  it('gates chatAgent on surface, not on the PR at all', () => {
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'task').chatAgent).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'details').chatAgent).toBe(false);
  });

  it('allows postRemoteComment only in a remote-* status', () => {
    expect(derivePRCapabilities(pr('external', 'remote-drafted'), 'details').postRemoteComment).toBe(true);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').postRemoteComment).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').postRemoteComment).toBe(false);
    expect(derivePRCapabilities(pr('external', 'merged'), 'details').postRemoteComment).toBe(false);
  });

  // Exhaustive matrix — every (origin, status, surface) cell the unified PR
  // aggregate can actually reach, checked against an explicit whitelist per
  // capability (not the same boolean expressions derivePRCapabilities uses)
  // so a regression in the source logic actually fails a test here.
  // `external` never occupies a local-only status (PR.EXTERNAL_STATUSES on
  // the backend), so those cells are skipped rather than asserted against
  // a value that can't occur.
  const ORIGINS: PROrigin[] = ['task', 'external'];
  const STATUSES: LocalPRStatus[] = [
    'local-drafted', 'local-open', 'remote-drafted', 'remote-open', 'merged', 'closed',
  ];
  const SURFACES: Array<'task' | 'details'> = ['task', 'details'];
  const LOCAL_ONLY_STATUSES = new Set<LocalPRStatus>(['local-drafted', 'local-open']);

  // Whitelisted TRUE cells per capability, as `${origin}/${status}` keys
  // (chatAgent depends only on surface, handled separately below).
  const PUSH_TRUE = new Set(['task/local-open']);
  const MERGE_TRUE = new Set(['task/remote-open']);
  const PUBLISH_REVIEW_TRUE = new Set([
    'external/remote-drafted', 'external/remote-open', 'external/merged', 'external/closed',
  ]);
  const POST_REMOTE_COMMENT_TRUE = new Set([
    'task/remote-drafted', 'task/remote-open', 'external/remote-drafted', 'external/remote-open',
  ]);
  const DRAFT_LOCAL_COMMENTS_FALSE = new Set([
    'task/merged', 'task/closed', 'external/merged', 'external/closed',
  ]);

  describe('capability matrix', () => {
    for (const origin of ORIGINS) {
      for (const status of STATUSES) {
        if (origin === 'external' && LOCAL_ONLY_STATUSES.has(status)) continue;
        const key = `${origin}/${status}`;
        for (const surface of SURFACES) {
          it(`${key}/${surface}`, () => {
            const caps = derivePRCapabilities(pr(origin, status), surface);
            expect(caps).toEqual({
              draftLocalComments: !DRAFT_LOCAL_COMMENTS_FALSE.has(key),
              publishReview: PUBLISH_REVIEW_TRUE.has(key),
              push: PUSH_TRUE.has(key),
              merge: MERGE_TRUE.has(key),
              chatAgent: surface === 'task',
              postRemoteComment: POST_REMOTE_COMMENT_TRUE.has(key),
            });
          });
        }
      }
    }
  });
});
