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
import { describe, it, expect } from 'vitest';
import type { LinkedIssueDto } from '../types';
import { inlineLinkedIssueTitles } from './inlineLinkedIssueTitles';

const issue = (n: number, title: string, state = 'open'): LinkedIssueDto => ({
  number: n,
  title,
  state,
  htmlUrl: `https://github.com/trinodb/trino/issues/${n}`,
});

describe('inlineLinkedIssueTitles', () => {
  it('replaces a same-repo URL link text with "#N Title"', () => {
    const html = '<p>Fixes <a href="https://github.com/trinodb/trino/issues/1234">https://github.com/trinodb/trino/issues/1234</a></p>';
    const out = inlineLinkedIssueTitles(html, [issue(1234, 'Store extended statistics filename')]);
    expect(out).toContain('>#1234 Store extended statistics filename<');
    // href stays intact so the click still navigates to the issue.
    expect(out).toContain('href="https://github.com/trinodb/trino/issues/1234"');
  });

  it('leaves links that do not match any linked issue alone', () => {
    const html = '<p>See <a href="https://github.com/other/repo/issues/9">other</a></p>';
    const out = inlineLinkedIssueTitles(html, [issue(1234, 'Whatever')]);
    expect(out).toContain('>other<');
  });

  it('handles multiple matching links in one body', () => {
    const html = '<p><a href="https://github.com/trinodb/trino/issues/1">a</a> and <a href="https://github.com/trinodb/trino/issues/2">b</a></p>';
    const out = inlineLinkedIssueTitles(html, [
      issue(1, 'First'),
      issue(2, 'Second'),
    ]);
    expect(out).toContain('>#1 First<');
    expect(out).toContain('>#2 Second<');
  });

  it('returns the input unchanged when linkedIssues is empty', () => {
    const html = '<p><a href="https://github.com/trinodb/trino/issues/1234">link</a></p>';
    expect(inlineLinkedIssueTitles(html, [])).toBe(html);
  });

  it('returns the input unchanged when html is empty', () => {
    expect(inlineLinkedIssueTitles('', [issue(1, 'x')])).toBe('');
  });
});
