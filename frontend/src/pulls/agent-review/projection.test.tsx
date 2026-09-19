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
import type { ComponentProps } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DiffViewer } from '../PullChanges';
import { PullDetailHeader } from '../PullsScreen';
import { reviewFixture, runningReviewFixture } from './fixtures/review';
import type { AgentReviewDto } from './contracts';
import type { DiffFileDto, PullRequestDetailDto, PullRequestDto, UserProfileDto } from '../../types';

const PATCH = [
  '@@ -114,4 +114,18 @@ class IcebergSortField',
  '     }',
  ' ',
  '     // sort fields are stored by field id',
  ' ',
  '+    private static int resolveNestedFieldId(Types.StructType struct, List<String> path)',
  '+    {',
  '+        Types.NestedField head = struct.field(path.get(0));',
  '+        if (path.size() == 1) {',
  '+            return head.fieldId();',
  '+        }',
  '+        // fall back to any descendant whose name matches the leaf',
  '+        for (Types.NestedField field : struct.fields()) {',
  '+            Optional<Integer> match = findByLeafName(field, path.get(path.size() - 1));',
  '+            if (match.isPresent()) {',
  '+                return match.get();',
  '+            }',
  '+        }',
  '+        throw new TrinoException(INVALID_TABLE_PROPERTY, "Unknown sort field");',
  '+    }',
].join('\n');

const FILE: DiffFileDto = {
  filename: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java',
  status: 'modified',
  additions: 41,
  deletions: 6,
  patch: PATCH,
};

const viewer = { login: 'jackchen', name: 'Jack', avatarUrl: null } as unknown as UserProfileDto;

const pr = {
  id: 1, repo: 'trinodb/trino', number: 30304, title: 'Support sorted by nested fields in Iceberg',
  author: 'ezra-b', htmlUrl: '', createdAt: null, updatedAt: '2026-09-06T16:00:00Z',
  origin: 'REVIEW_REQUESTED', labels: [], labelColors: null, draft: false, viewedAt: null,
  reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: 'FAILING',
  additions: 4366, deletions: 82, commentCount: 0, attentionReason: null, state: 'open',
  mergeable: true, mergeableState: 'clean', headPushedAt: null, headRef: 'nested-sort',
} as unknown as PullRequestDto;

const detail = {
  id: 1, repo: 'trinodb/trino', number: 30304, title: pr.title, htmlUrl: null, state: 'open',
  draft: false, mergeable: true, mergeableState: 'clean', author: 'ezra-b', authorAvatarUrl: null,
  createdAt: null, updatedAt: null, mergedAt: null, baseRef: 'master', headRef: 'nested-sort',
  body: null, labels: [], assignees: [], milestone: null, reviewers: [], additions: 4366,
  deletions: 82, changedFiles: 30, approvalCount: 0, ciStatus: 'FAILING', recentActivity: [],
  checkRuns: [], unavailableSections: [], viewerCanWrite: true,
} as unknown as PullRequestDetailDto;

function header(
  tab: 'overview' | 'changes' | 'review' | null,
  review: AgentReviewDto | null,
  options: Pick<ComponentProps<typeof PullDetailHeader>, 'agentTab' | 'onZoom'> = {},
): string {
  return renderToStaticMarkup(
    <PullDetailHeader
      pr={pr} detail={detail} tab={tab} review={review} onTab={() => {}}
      onRefresh={async () => {}} onReviewSubmitted={() => {}} refreshing={false}
      pendingComments={[]} onPendingCommentsChange={() => {}} onZoom={() => {}} {...options} />);
}

function changes(review: AgentReviewDto | null, navigation: { path: string; side: 'LEFT' | 'RIGHT'; startLine: number; endLine: number } | null = null): string {
  return renderToStaticMarkup(
    <DiffViewer
      files={[FILE]}
      agentReview={review === null ? undefined : {
        review,
        viewer,
        navigation: navigation === null ? null : { ...navigation, findingId: 'finding-1', token: 1 },
        onReviewChange: () => {},
        onOpenInReview: () => {},
      }} />);
}

describe('the pane tab row', () => {
  it('adds Agent review beside the existing tabs without changing them', () => {
    const html = header('overview', null);
    expect(html).toContain('PR #30304');
    expect(html).toContain('Changes');
    expect(html).toContain('Review &amp; Learning');
    // The existing tabs keep their counters and classes.
    expect(html).toContain('pull-detail__change-count is-addition');
    expect(html).toContain('pull-detail__change-count is-deletion');
  });

  it('marks whichever tab is open, including the third one', () => {
    const active = (html: string) => html.split('<button').filter(part => part.includes('aria-pressed="true"'));
    expect(active(header('review', null))).toHaveLength(1);
    expect(active(header('review', null))[0]).toContain('Review &amp; Learning');
    expect(active(header('changes', null))[0]).toContain('Changes');
    expect(active(header('overview', null))[0]).toContain('PR #30304');
  });

  it('reuses the PR toolbar with a selected task Subagents tab', () => {
    const html = header(null, null, {
      agentTab: <button type="button" aria-pressed="true">Subagents 2</button>,
    });
    expect(html).toContain('Subagents 2');
    expect(html).toContain('Review &amp; Learning');
    expect(html.match(/aria-pressed="true"/g)).toHaveLength(1);
    expect(html).toContain('aria-label="Zoom pull request details"');
    expect(html).toContain('aria-label="Refresh pull request"');
    expect(html).toContain('Submit review');
  });

  it('keeps Review & Learning when the task supplies no Subagents tab or zoom action', () => {
    const html = header('overview', null, { agentTab: null, onZoom: undefined });
    expect(html).toContain('PR #30304');
    expect(html).toContain('Changes');
    expect(html).toContain('Review &amp; Learning');
    expect(html).not.toContain('Zoom pull request details');
    expect(html).toContain('aria-label="Refresh pull request"');
  });

  it('appends the review status to the tab, with a spinner only while it runs', () => {
    expect(header('overview', reviewFixture())).toContain('pull-detail__review-chip is-ready');
    expect(header('overview', reviewFixture())).toContain('3 findings');
    const running = header('overview', runningReviewFixture());
    expect(running).toContain('pull-detail__review-chip is-running');
    expect(running).toContain('agr-spinner');
    // No review, no chip.
    expect(header('overview', null)).not.toContain('pull-detail__review-chip');
  });

  it('counts promoted findings in Submit review alongside manual comments', () => {
    const promoted = reviewFixture({
      draft: {
        repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', version: 1,
        updatedAt: '2026-09-06T16:30:00Z',
        items: [{
          id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1,
          path: 'a.java', side: 'RIGHT', line: 131, startLine: 118, body: 'text',
          included: true, published: false, publication: null,
        }],
      },
    });
    expect(header('overview', promoted)).toContain('1 pending review comment');
    expect(header('overview', reviewFixture())).not.toContain('pending review comment');
  });
});

describe('the same finding in Changes', () => {
  const review = reviewFixture();

  it('mounts the finding card at the end line of its range', () => {
    const html = changes(review);
    expect(html).toContain('is-review-finding');
    expect(html).toContain('data-finding-id="finding-1"');
    // One card, at one line: the range ends at R131.
    expect(html.match(/data-finding-id="finding-1"/g) ?? []).toHaveLength(1);
    expect(html).toContain('data-diff-row="plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java:RIGHT:131"');
  });

  it('offers the same actions and the way back to Review & Learning', () => {
    const html = changes(review);
    expect(html).toContain('Ask agent');
    expect(html).toContain('Resolve locally');
    expect(html).toContain('Add to my review');
    expect(html).toContain('Post comment now');
    expect(html).toContain('Open in Review &amp; Learning');
    expect(html).not.toContain('View in Changes');
  });

  it('shows the same private discussion, not a copy', () => {
    const talking = reviewFixture({
      messages: [{
        id: 'm1', findingId: 'finding-1', author: 'USER', body: 'Is the leaf name really ambiguous?',
        targetRevision: 1, turnId: 'turn-2', queuedInputId: null, createdAt: '2026-09-06T16:20:00Z',
      }],
    });
    expect(changes(talking)).toContain('Is the leaf name really ambiguous?');
  });

  it('leaves an outdated finding out of the diff, since its lines no longer map', () => {
    const outdated = reviewFixture({ findings: [{ ...review.findings[0], outdated: true }] });
    expect(changes(outdated)).not.toContain('data-finding-id');
  });

  it('leaves a withdrawn finding out of the diff', () => {
    const withdrawn = reviewFixture({ findings: [{ ...review.findings[0], state: 'WITHDRAWN' }] });
    expect(changes(withdrawn)).not.toContain('data-finding-id');
  });

  it('highlights exactly the navigated range', () => {
    const html = changes(review, { path: FILE.filename, side: 'RIGHT', startLine: 118, endLine: 131 });
    const highlighted = html.match(/is-review-target/g) ?? [];
    expect(highlighted).toHaveLength(14);
    expect(changes(review, null)).not.toContain('is-review-target');
  });

  it('draws nothing of its own when the pull request has no review', () => {
    const html = changes(null);
    expect(html).not.toContain('is-review-finding');
    expect(html).not.toContain('is-review-target');
    expect(html).toContain('resolveNestedFieldId');
  });
});
