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
import type { GitCommitDto } from '../types';
import SelectRangeDialog from './SelectRangeDialog';

function commit(sha: string, title: string, pullRequestNumber?: number): GitCommitDto {
  return {
    sha,
    title,
    body: '',
    author: 'marlo-t',
    committer: 'marlo-t',
    authoredAt: '2026-09-20T10:00:00Z',
    committedAt: '2026-09-20T10:00:00Z',
    pullRequestNumber: pullRequestNumber ?? null,
    pullRequestTitle: pullRequestNumber === undefined ? null : 'Iceberg: REST catalog views',
  };
}

const COMMITS = [
  commit('aaaa1110000000000000000000000000000000000', 'Fix flaky S3 commit protocol test', 20000),
  commit('bbbb2220000000000000000000000000000000000', 'Bump netty to 4.2.7'),
];

function render() {
  return renderToStaticMarkup(<SelectRangeDialog
    commits={COMMITS}
    scope="trinodb/trino · master"
    onOpenPullRequest={() => {}}
    onCancel={() => {}}
    onApply={() => {}}
  />);
}

describe('select range dialog', () => {
  it('opens over the whole window as a labelled modal', () => {
    const html = render();

    expect(html).toContain('class="cmt-rd-scrim"');
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('Select a commit range');
    expect(html).toContain('trinodb/trino · master');
  });

  it('starts on the oldest end with both endpoints empty', () => {
    const html = render();

    expect(html).toContain('pick the oldest commit');
    expect(html).toContain('pick the newest commit');
    // The first pick lands in the left card, so it is the focused one.
    expect(html).toMatch(/class="cmt-rd-end is-focused"[^>]*aria-pressed="true"/);
    expect(html).not.toContain('cmt-rd-end is-focused is-set');
    expect(html).toContain('Pick both ends — or paste 8fd3d32…741339a');
    // Nothing to apply yet.
    expect(html).toContain('disabled=""');
    expect(html).not.toContain('commits in this range');
  });

  it('renders each result with the shared commit card, denser and unbolded', () => {
    const html = render();

    expect(html).toContain('cmt-commit-row is-dense');
    expect(html).toContain('Fix flaky S3 commit protocol test');
    expect(html).toContain('cmt-commit-sha');
    expect(html).toContain('cmt-chip is-pr');
    // Picking is by row, so the card's multi-select checkbox is not rendered.
    expect(html).not.toContain('type="checkbox"');
  });

  it('keeps the dialog chrome free of glyphs and emoji', () => {
    const html = render();
    const chrome = html.slice(0, html.indexOf('cmt-rd-results'));

    expect(chrome).not.toMatch(/[⑂⇅⇲▾▼✓→]/);
    expect(chrome).toContain('lucide-hash');
    expect(chrome).toContain('lucide-rotate-ccw');
    expect(chrome).toContain('lucide-arrow-right');
    expect(chrome).toContain('lucide-git-commit-horizontal');
    expect(chrome).toContain('lucide-flag');
  });
});
