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
import type { AgentReviewDto, ReviewDraftItemDto, ReviewFindingDto } from './contracts';

const body = 'Guard the empty value.\n\n```suggestion\n    return input.orElseThrow();\n```';
const finding: ReviewFindingDto = { ...reviewFixture().findings[0], startLine: 118, endLine: 119, range: 'R118–R119', body,
  excerpt: ['    Value result = input.get();', '    return result;'] };
const draftItem: ReviewDraftItemDto = { id: 'draft-suggestion', source: 'AGENT_FINDING', findingId: finding.id,
  findingRevision: finding.revision, path: finding.path, side: 'RIGHT', line: finding.endLine, startLine: finding.startLine,
  body, included: true, published: false, publication: null };

function render(value = finding, item?: ReviewDraftItemDto, projection: 'review' | 'changes' = 'review') {
  const review: AgentReviewDto = reviewFixture({ findings: [value], draft: item === undefined ? null : {
    repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', items: [item], version: 1, updatedAt: value.updatedAt,
  } });
  return renderToStaticMarkup(<ReviewFindingCard review={review} finding={value} projection={projection}
    viewerLogin="reviewer" viewerAvatarUrl={null} actions={{
      onAsk: async () => {}, onResolve: async () => {}, onReopen: async () => {}, onAddToReview: async () => {},
      onPostNow: async () => {}, onEditDraft: async () => {}, onNavigate: () => {},
    }} />);
}

describe('finding suggestions', () => {
  it.each(['review', 'changes'] as const)('compares a suggestion with its exact reviewed range in %s', projection => {
    const html = render(finding, undefined, projection);
    expect(html).toContain('<span class="pr-commented-code-line__number">118</span>');
    expect(html).toContain('<span class="pr-commented-code-line__number">119</span>');
    expect(html).toContain('<code>    Value result = input.get();</code>');
    expect(html).toContain('<code>    return result;</code>');
    expect(html.match(/class="md-suggestion"/g)).toHaveLength(1);
    expect(html).toContain('<td class="md-suggestion__number">118</td><td class="md-suggestion__code"><span>−</span>    Value result = input.get();</td>');
    expect(html).toContain('<td class="md-suggestion__number">119</td><td class="md-suggestion__code"><span>−</span>    return result;</td>');
    expect(html).toContain('<td class="md-suggestion__number">118</td><td class="md-suggestion__code"><span>+</span>    return input.orElseThrow();</td>');
    expect(html).not.toContain('<span>suggestion</span>');
  });

  it('uses the recorded code for an outdated finding, which remains blocked from publication', () => {
    const html = render({ ...finding, outdated: true });
    expect(html).toContain('Recorded code');
    expect(html).toContain('md-suggestion__row is-del');
    expect(html).not.toContain('Post comment now');
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

  it('previews outgoing suggestions only against the same finding revision and anchor', () => {
    expect(render(finding, draftItem).match(/class="md-suggestion"/g)).toHaveLength(2);
    for (const stale of [{ ...draftItem, findingRevision: finding.revision - 1 }, { ...draftItem, startLine: 119, line: 120 }]) {
      const html = render(finding, stale);
      expect(html.match(/class="md-suggestion"/g)).toHaveLength(1);
      expect(html).toContain('<span>suggestion</span>');
    }
  });
});
