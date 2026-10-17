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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import ReviewFindingCard from './ReviewFindingCard';
import { reviewFixture } from './fixtures/review';
import type { AgentReviewDto, ReviewDraftItemDto, ReviewFindingDto, ReviewMessageDto } from './contracts';

const body = 'Guard the empty value.\n\n```suggestion\n    return input.orElseThrow();\n```';
const finding: ReviewFindingDto = { ...reviewFixture().findings[0], startLine: 118, endLine: 119, range: 'R118–R119', body,
  excerpt: ['    Value result = input.get();', '    return result;'] };
const draftItem: ReviewDraftItemDto = { id: 'draft-suggestion', source: 'AGENT_FINDING', findingId: finding.id,
  findingRevision: finding.revision, commitId: finding.validatedHeadSha, path: finding.path, side: 'RIGHT', line: finding.endLine, startLine: finding.startLine,
  body, included: true, published: false, publication: null };

function render(value = finding, item?: ReviewDraftItemDto, projection: 'review' | 'changes' = 'review', messages: ReviewMessageDto[] = []) {
  const review: AgentReviewDto = reviewFixture({ findings: [value], messages, draft: item === undefined ? null : {
    repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', items: [item], reviewPublication: null, postedSummaryFindingIds: [], version: 1, updatedAt: value.updatedAt,
  } });
  return renderToStaticMarkup(<ReviewFindingCard review={review} finding={value} projection={projection}
    viewerLogin="reviewer" viewerAvatarUrl={null} actions={{
      onAsk: async () => {}, onResolve: async () => {}, onReopen: async () => {}, onAddToReview: async () => {},
      onPostNow: async () => {}, onEditDraft: async () => {}, onRemoveFromReview: async () => {}, onNavigate: () => {},
    }} />);
}

describe('finding suggestions', () => {
  it.each(['review', 'changes'] as const)('shows unconfirmed delivery without a success message in %s', projection => {
    const html = render(finding, { ...draftItem, publication: {
      state: 'UNCERTAIN', remoteReviewId: null, remoteCommentId: null, url: null, at: finding.updatedAt,
    } }, projection);
    expect(html).toContain('Delivery unconfirmed');
    expect(html).toContain('GitHub did not confirm this comment');
    expect(html).toContain('Your comment is saved here');
    expect(html).toContain('Guard the empty value.');
    expect(html).toContain('<button type="button" disabled="">Edit</button>');
    expect(html).toContain('<button type="button" disabled="">Remove</button>');
    expect(html).not.toContain('Posted to GitHub');
    expect(html).not.toContain('is-published');
    expect(html).not.toContain('Post comment now');
    expect(html).not.toContain('Add to my review');
  });

  it.each(['review', 'changes'] as const)('compares a suggestion with its exact reviewed range in %s', projection => {
    const html = render(finding, undefined, projection);
    if (projection === 'review') {
      expect(html).toContain('<span class="pr-commented-code-line__number">118</span>');
      expect(html).toContain('<span class="pr-commented-code-line__number">119</span>');
      expect(html).toContain('<code>    Value result = input.get();</code>');
      expect(html).toContain('<code>    return result;</code>');
    }
    else {
      // The diff row above shows the code, as for GitHub threads there.
      expect(html).not.toContain('lrc__code');
      expect(html).toContain('<span class="lrc__title">Comment on lines <strong>R118–R119</strong></span>');
    }
    expect(html.match(/class="md-suggestion"/g)).toHaveLength(1);
    expect(html).toContain('<td class="md-suggestion__number">118</td><td class="md-suggestion__code"><span>−</span>    Value result = input.get();</td>');
    expect(html).toContain('<td class="md-suggestion__number">119</td><td class="md-suggestion__code"><span>−</span>    return result;</td>');
    expect(html).toContain('<td class="md-suggestion__number">118</td><td class="md-suggestion__code"><span>+</span>    return input.orElseThrow();</td>');
    expect(html).not.toContain('<span>suggestion</span>');
    expect(html).not.toContain('Reviewed code');
    expect(html).not.toContain('pr-local-review__snapshot');
  });

  it("names a development agent's reply apart from the reviewer's own", () => {
    const message: Omit<ReviewMessageDto, 'id' | 'body' | 'createdAt'> = { findingId: finding.id, author: 'AGENT', targetRevision: finding.revision, turnId: null, queuedInputId: null };
    const html = render(finding, undefined, 'review', [
      { ...message, id: 'answer', body: 'The value can be empty.', createdAt: '2026-09-06T16:19:00Z' },
      { ...message, id: 'reply', body: 'Fixed in abc1234.', createdAt: '2026-09-06T16:20:00Z', developerTaskId: 'developer-task' },
    ]);
    expect(html).toContain('data-review-comment-label="Agent review" data-review-comment-preview="The value can be empty."');
    expect(html).toContain('data-review-comment-label="Developer" data-review-comment-preview="Fixed in abc1234."');
    expect(html).toContain('<strong>Developer</strong>');
    // Only the reviewer's own messages carry the reviewer's mark: the finding and its answer, not the developer's reply.
    expect(html.match(/class="lrc-bot"/g)).toHaveLength(2);
  });

  it('says who resolved a finding and keeps Reopen either way', () => {
    const mine = render({ ...finding, state: 'RESOLVED', stateReason: 'Handled elsewhere' });
    expect(mine).toContain('Resolved locally by you · “Handled elsewhere”');
    const developer = render({ ...finding, state: 'RESOLVED', stateReason: null, resolvedByTaskId: 'developer-task' });
    expect(developer).toContain('Resolved by Developer');
    expect(developer).not.toContain('Resolved locally by you');
    for (const html of [mine, developer]) expect(html).toContain('>Reopen</button>');
  });

  it('folds the repository memories the reviewer cited', () => {
    expect(render()).not.toContain('Memory used');
    const html = render({ ...finding, memories: [{ id: 'm12', line: 'Document every new table property. (PR #31188 @alice)' }] });
    expect(html).not.toContain('Document every new table property');
    expect(html).toContain('Memory used · 1</button>');
  });

  it('publishes an accepted earlier finding at the recorded code and commit', () => {
    const html = render({ ...finding, outdated: true });
    expect(html).not.toContain('Recorded code');
    expect(html).toContain('This finding describes an earlier reviewed commit');
    expect(html).toContain('md-suggestion__row is-del');
    expect(html).toContain('Post comment now');
  });

  it('keeps suggestions on deleted lines as ordinary code, like GitHub review comments', () => {
    const html = render({ ...finding, side: 'LEFT', range: 'L118–L119' });
    expect(html.match(/class="pr-commented-code-line is-del"/g)).toHaveLength(2);
    expect(html).toContain('<span>suggestion</span>');
    expect(html).not.toContain('md-suggestion');
  });

  it('does not invent line numbers when a saved deletion excerpt has gaps', () => {
    const html = render({ ...finding, side: 'LEFT', endLine: 123, range: 'L118–L123' });
    expect(html).toContain('L118–L123');
    expect(html.match(/class="pr-commented-code-line__number"><\/span>/g)).toHaveLength(2);
    expect(html).toContain('<code>    return result;</code>');
  });

  it('shows a finding about the whole pull request without code, and offers only the review summary', () => {
    const whole: ReviewFindingDto = { ...finding, title: 'Nit: the commit subject names the file', severity: null,
      body: 'Commit message: name the behavior. Proposed subject: Reject null widget input',
      path: null, side: null, startLine: null, endLine: null, range: null, excerpt: [] };
    const html = render(whole);
    expect(html).toContain('Whole change');
    expect(html).not.toContain('Reviewed code');
    expect(html).toContain('Add to my review');
    expect(html).not.toContain('Post comment now');
    expect(html).not.toContain('View in Changes');

    const inSummary = renderToStaticMarkup(<ReviewFindingCard projection="review" viewerLogin="reviewer" viewerAvatarUrl={null} finding={whole}
      review={reviewFixture({ findings: [whole], draft: {
        repo: 'trinodb/trino', number: 30304, body: `Thanks.\n\n${whole.body}`, event: 'COMMENT', items: [], reviewPublication: null, postedSummaryFindingIds: [], version: 2, updatedAt: whole.updatedAt,
      } })}
      actions={{ onAsk: async () => {}, onResolve: async () => {}, onReopen: async () => {}, onAddToReview: async () => {},
        onPostNow: async () => {}, onEditDraft: async () => {}, onRemoveFromReview: async () => {}, onNavigate: () => {} }} />);
    expect(inSummary).toContain('In your review summary');
    expect(inSummary).toContain('Outgoing review summary');
    expect(inSummary).toContain('>Edit</button>');
    expect(inSummary).toContain('>Remove</button>');
    expect(inSummary).toContain('<p>Thanks.</p>');
    expect(inSummary).not.toContain('Add to my review');
  });

  it('renders the saved outgoing text independently of later finding revisions and resolution', () => {
    const original = render(finding, draftItem).match(/<div class="agr-draft__body">([\s\S]*?)<\/div>/)?.[1];
    expect(original).toContain('Guard the empty value.');
    expect(original).toContain('orElseThrow');
    for (const state of ['OPEN', 'RESOLVED', 'WITHDRAWN'] as const) {
      const changed = { ...finding, state, revision: 2, body: 'The agent changed its conclusion',
        path: 'src/Other.java', startLine: 1, endLine: 1, range: 'R1', excerpt: ['Different code'] };
      const html = render(changed, draftItem);
      expect(html.match(/<div class="agr-draft__body">([\s\S]*?)<\/div>/)?.[1]).toBe(original);
      expect(html).toContain('Outgoing comment · in your pending review');
      expect(html).not.toContain('Excluded from publication');
      expect(html).not.toContain('can no longer be published');
      if (state !== 'OPEN') expect(html.toLowerCase()).toContain('your saved review is unchanged');
    }
  });
});
