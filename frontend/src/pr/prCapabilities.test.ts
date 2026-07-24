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
    syncedAt: null, syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
  };
}

describe('derivePRCapabilities', () => {
  it('allows push only for a task-origin PR that is local-open', () => {
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').push).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-drafted'), 'task').push).toBe(false);
    expect(derivePRCapabilities(pr('external', 'local-open'), 'task').push).toBe(false);
  });

  it('allows direct merge only for an open external PR; task PRs use the notification gate', () => {
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').merge).toBe(false);
    expect(derivePRCapabilities(pr('task', 'remote-drafted'), 'task').merge).toBe(false);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'task').merge).toBe(true);
    expect(derivePRCapabilities(pr('external', 'remote-drafted'), 'task').merge).toBe(false);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').merge).toBe(false);
    expect(derivePRCapabilities(pr('task', 'merged'), 'task').merge).toBe(false);
  });

  it('allows publishReview for external PRs and for task PRs once they reach the remote stage', () => {
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('external', 'merged'), 'details').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-drafted'), 'task').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').publishReview).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').publishReview).toBe(false);
    expect(derivePRCapabilities(pr('task', 'merged'), 'task').publishReview).toBe(false);
  });

  it('allows task drafts during Local Review and again once the PR is on GitHub; external drafts until terminal', () => {
    expect(derivePRCapabilities(pr('task', 'local-drafted'), 'task').draftLocalComments).toBe(false);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-drafted'), 'task').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').draftLocalComments).toBe(true);
    expect(derivePRCapabilities(pr('task', 'merged'), 'task').draftLocalComments).toBe(false);
    expect(derivePRCapabilities(pr('external', 'closed'), 'details').draftLocalComments).toBe(false);
  });

  it('gates chatAgent on surface, not on the PR at all', () => {
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'task').chatAgent).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'details').chatAgent).toBe(false);
  });

  it('allows postRemoteComment for remote and merged pull requests', () => {
    expect(derivePRCapabilities(pr('external', 'remote-drafted'), 'details').postRemoteComment).toBe(true);
    expect(derivePRCapabilities(pr('external', 'remote-open'), 'details').postRemoteComment).toBe(true);
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').postRemoteComment).toBe(false);
    expect(derivePRCapabilities(pr('external', 'merged'), 'details').postRemoteComment).toBe(true);
    expect(derivePRCapabilities(pr('external', 'closed'), 'details').postRemoteComment).toBe(false);
  });

  it('routes inline comments to Development locally and GitHub once remote', () => {
    expect(derivePRCapabilities(pr('task', 'local-open'), 'task').inlineCommentTarget).toBe('agent');
    expect(derivePRCapabilities(pr('task', 'remote-open'), 'task').inlineCommentTarget).toBe('remote');
    expect(derivePRCapabilities(pr('external', 'remote-drafted'), 'details').inlineCommentTarget).toBe('remote');
    expect(derivePRCapabilities(pr('task', 'local-drafted'), 'task').inlineCommentTarget).toBeNull();
    expect(derivePRCapabilities(pr('external', 'closed'), 'details').inlineCommentTarget).toBeNull();
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
  const MERGE_TRUE = new Set(['external/remote-open']);
  const PUBLISH_REVIEW_TRUE = new Set([
    'external/remote-drafted', 'external/remote-open', 'external/merged', 'external/closed',
    'task/remote-drafted', 'task/remote-open',
  ]);
  const POST_REMOTE_COMMENT_TRUE = new Set([
    'task/remote-drafted', 'task/remote-open', 'external/remote-drafted', 'external/remote-open',
    'task/merged', 'external/merged',
  ]);
  const DRAFT_LOCAL_COMMENTS_TRUE = new Set([
    'task/local-open', 'task/remote-drafted', 'task/remote-open',
    'external/remote-drafted', 'external/remote-open',
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
              draftLocalComments: DRAFT_LOCAL_COMMENTS_TRUE.has(key),
              publishReview: PUBLISH_REVIEW_TRUE.has(key),
              push: PUSH_TRUE.has(key),
              merge: MERGE_TRUE.has(key),
              chatAgent: surface === 'task',
              postRemoteComment: POST_REMOTE_COMMENT_TRUE.has(key),
              inlineCommentTarget: key === 'task/local-open'
                ? 'agent'
                : DRAFT_LOCAL_COMMENTS_TRUE.has(key) ? 'remote' : null,
            });
          });
        }
      }
    }
  });
});
