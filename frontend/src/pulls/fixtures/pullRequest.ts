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

import type { PullRequestDetailDto, PullRequestDto, PullTimelineCommentDto, PullTimelinePayloadDto } from '../../types';
import { EMPTY_TIMELINE_PAYLOAD } from '../model';

/** A pull request at rest; a test sets only the fields its case turns on. */
export function pr(overrides: Partial<PullRequestDto>): PullRequestDto {
  return {
    id: 1,
    repo: 'bytequay/app',
    number: 42,
    title: 'Keep PR details',
    author: 'jack',
    htmlUrl: 'https://github.com/bytequay/app/pull/42',
    createdAt: null,
    updatedAt: '2026-08-15T00:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    additions: 1,
    deletions: 0,
    commentCount: 0,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    headRef: 'feature',
    ...overrides,
  };
}

export function prDetail(overrides: Partial<PullRequestDetailDto>): PullRequestDetailDto {
  return {
    id: 1,
    repo: 'bytequay/app',
    number: 42,
    title: 'Keep PR details',
    htmlUrl: 'https://github.com/bytequay/app/pull/42',
    state: 'open',
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    author: 'jack',
    authorAvatarUrl: null,
    createdAt: null,
    updatedAt: '2026-08-15T00:00:00Z',
    mergedAt: null,
    baseRef: 'main',
    headRef: 'feature',
    headSha: 'head',
    body: null,
    labels: [],
    assignees: [],
    milestone: null,
    reviewers: [],
    additions: 1,
    deletions: 0,
    changedFiles: 1,
    approvalCount: 0,
    ciStatus: null,
    recentActivity: [],
    checkRuns: [],
    unavailableSections: [],
    viewerCanWrite: true,
    ...overrides,
  };
}

type TimelineActivity = PullRequestDetailDto['recentActivity'][number];

export function timelineActivity(
  overrides: Partial<Omit<TimelineActivity, 'payload'>> = {},
  payload: Partial<PullTimelinePayloadDto> = {},
): TimelineActivity {
  return {
    actor: 'bob',
    eventType: 'review_thread',
    timestamp: '2026-08-20T09:35:00Z',
    body: null,
    githubId: 4,
    ...overrides,
    payload: { ...EMPTY_TIMELINE_PAYLOAD, reactions: [], comments: [], ...payload },
  };
}

export function timelineComment(overrides: Partial<PullTimelineCommentDto> = {}): PullTimelineCommentDto {
  return {
    githubId: 99,
    actor: 'bob',
    actorAvatarUrl: null,
    actorBot: false,
    timestamp: '2026-08-20T09:35:00Z',
    body: 'Please update this.',
    htmlUrl: null,
    reviewId: 9,
    inReplyToId: null,
    reactions: [],
    ...overrides,
  };
}
