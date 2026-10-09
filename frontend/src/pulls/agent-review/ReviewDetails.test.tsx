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
import ReviewDetails from './ReviewDetails';
import { parseAgentReview } from './contracts';
import { reviewFixture } from './fixtures/review';

/** The fixture review as the backend would send it, with these verdicts on its validation. */
function reviewWithVerdicts(threadVerdicts: unknown[]) {
  const base = reviewFixture();
  const steps = base.runs[0].steps.map(step => step.validation === null ? step : { ...step, validation: { ...step.validation, threadVerdicts } });
  return parseAgentReview(JSON.parse(JSON.stringify({ ...base, runs: [{ ...base.runs[0], steps }] })));
}

describe('ReviewDetails', () => {
  it('lists the verdicts on the comments the user made, what is still open first', () => {
    const review = reviewWithVerdicts([
      { threadId: '51', verdict: 'ADDRESSED', explanation: 'Both paths run now.', evidenceRefs: ['E4'], path: 'src/App.java', line: 12,
        opening: 'Please preserve both paths.', resolved: true },
      { threadId: '61', verdict: 'NOT_ADDRESSED', explanation: 'The second lookup is still there.', evidenceRefs: [], path: 'src/Lookup.java', line: null,
        opening: 'Can the second lookup go?', resolved: false },
      { threadId: '71', verdict: 'AWAITING_REPLY', explanation: 'The author asks which caller needs it.', evidenceRefs: [], path: 'src/Lookup.java', line: 40,
        opening: 'Is this cache needed?', resolved: false },
    ]);

    const html = renderToStaticMarkup(<ReviewDetails run={review.runs[0]} repository={review.repo} />);

    expect(html).toContain('Your comments · 1 not addressed · 1 awaiting your reply · 1 addressed');
    expect(html).toContain('3 of your comments followed up');
    expect(html.indexOf('Can the second lookup go?')).toBeLessThan(html.indexOf('Is this cache needed?'));
    expect(html.indexOf('Is this cache needed?')).toBeLessThan(html.indexOf('Please preserve both paths.'));
    expect(html).toContain('<code title="src/App.java">App.java:12</code> · marked resolved · “Please preserve both paths.”');
    expect(html).toContain('<code title="src/Lookup.java">Lookup.java</code> · “Can the second lookup go?”');
    expect(html).toContain('The second lookup is still there.');
    expect(html).toContain('E4');
  });

  it('says nothing about comments when the review judged none', () => {
    const review = reviewWithVerdicts([]);
    expect(review.runs[0].steps[0].validation?.threadVerdicts).toEqual([]);

    const html = renderToStaticMarkup(<ReviewDetails run={review.runs[0]} repository={review.repo} />);

    expect(html).not.toContain('Your comments');
    expect(html).not.toContain('followed up');
    // A review stored before verdicts existed has no such field at all.
    const stored = JSON.parse(JSON.stringify(reviewFixture()));
    expect(parseAgentReview(stored).runs[0].steps[0].validation?.threadVerdicts).toEqual([]);
  });

  it('refuses a verdict the screen has no words for', () => {
    expect(() => reviewWithVerdicts([{ threadId: '51', verdict: 'FIXED', explanation: 'Done.', evidenceRefs: [], path: 'src/App.java', line: 12,
      opening: 'Please preserve both paths.', resolved: true }])).toThrow('Malformed thread verdict');
  });
});
