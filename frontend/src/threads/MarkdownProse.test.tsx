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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MarkdownProse,
  isClassName,
  isCommitSha,
  matchMetaNote,
  parsePrNumber,
} from './MarkdownProse';

afterEach(() => {
  cleanup();
});

describe('isClassName', () => {
  it('matches PascalCase class/type tokens, not fields or methods', () => {
    expect(isClassName('DynamicFilterSnapshot')).toBe(true);
    expect(isClassName('TupleDomain<ColumnHandle>')).toBe(true);
    expect(isClassName('currentPredicate')).toBe(false);
    expect(isClassName('requireNonNull')).toBe(false);
    expect(isClassName('io.trino.spi')).toBe(false);
  });
});

describe('MarkdownProse code rendering', () => {
  it('numbers the lines of a multi-line code block', () => {
    const { container } = render(
      <MarkdownProse text={'```java\nline one\nline two\nline three\n```'} />,
    );
    const pre = container.querySelector('pre');
    expect(pre).toBeTruthy();
    // One gutter number per line.
    expect(pre?.textContent).toContain('1');
    expect(pre?.textContent).toContain('line two');
    expect(pre?.querySelectorAll('div').length).toBe(3);
  });

  it('renders a PascalCase inline token as a distinct class code span', () => {
    render(<MarkdownProse text={'the `DynamicFilterSnapshot` record'} />);
    const el = screen.getByText('DynamicFilterSnapshot');
    expect(el.tagName).toBe('CODE');
    // Class colour, not the default inline-code colour.
    expect(el.style.color).toContain('teal');
  });
});

describe('parsePrNumber', () => {
  it('reads the number out of a github PR url', () => {
    expect(parsePrNumber('https://github.com/trinodb/trino/pull/29897')).toBe(29897);
  });

  it('tolerates trailing path / query / fragment', () => {
    expect(parsePrNumber('https://github.com/a/b/pull/12/files')).toBe(12);
    expect(parsePrNumber('http://github.com/a/b/pull/3#issuecomment-1')).toBe(3);
  });

  it('ignores non-PR links (issues, commits, bare repo)', () => {
    expect(parsePrNumber('https://github.com/a/b/issues/4')).toBeNull();
    expect(parsePrNumber('https://github.com/a/b')).toBeNull();
    expect(parsePrNumber('https://example.com/a/b/pull/4')).toBeNull();
  });
});

describe('isCommitSha', () => {
  it('matches a 7–40 char hex run', () => {
    expect(isCommitSha('13370bc9491')).toBe(true);
    expect(isCommitSha('a1b2c3d')).toBe(true);
  });

  it('rejects short hex, non-hex, and decorated tokens', () => {
    expect(isCommitSha('abc123')).toBe(false); // < 7
    expect(isCommitSha('LabelEvaluator.java:43')).toBe(false);
    expect(isCommitSha('searchEnd')).toBe(false);
    expect(isCommitSha('git push')).toBe(false);
  });
});

describe('matchMetaNote', () => {
  it('flags tooling/process asides and returns a title-cased label', () => {
    expect(matchMetaNote('Note on tooling: the MCP tools timed out')).toBe('Note on tooling');
    expect(matchMetaNote('tooling note: pushed with raw git')).toBe('Tooling note');
  });

  it('leaves substantive notes alone', () => {
    expect(matchMetaNote('Note: this changes the public API')).toBeNull();
    expect(matchMetaNote('Fix: removed the duplicate word')).toBeNull();
  });
});

describe('MarkdownProse rendering', () => {
  it('renders a PR url as a compact chip', () => {
    render(<MarkdownProse text="Opened [the PR](https://github.com/trinodb/trino/pull/29897)." />);
    const chip = screen.getByText('PR #29897', { exact: false });
    expect(chip.closest('a')?.getAttribute('href'))
      .toBe('https://github.com/trinodb/trino/pull/29897');
  });

  it('collapses a tooling note behind a disclosure', () => {
    const { container } = render(
      <MarkdownProse text={'Done.\n\nNote on tooling: the MCP tools timed out, so I used git.'} />,
    );
    const details = container.querySelector('details');
    expect(details).toBeTruthy();
    expect(details?.querySelector('summary')?.textContent).toBe('Note on tooling');
    // The substantive first paragraph stays out of the disclosure.
    expect(screen.getByText('Done.').closest('details')).toBeNull();
  });

  it('renders a bare commit sha as a git chip but leaves code spans alone', () => {
    const { container } = render(
      <MarkdownProse text={'Commit `13370bc9491` touches `LabelEvaluator.java`.'} />,
    );
    const codes = Array.from(container.querySelectorAll('code'));
    const sha = codes.find(c => c.textContent?.includes('13370bc9491'));
    const file = codes.find(c => c.textContent === 'LabelEvaluator.java');
    // The SHA chip carries the git glyph; the filename code span does not.
    expect(sha?.textContent).toContain('⎇');
    expect(file?.textContent).toBe('LabelEvaluator.java');
  });
});
