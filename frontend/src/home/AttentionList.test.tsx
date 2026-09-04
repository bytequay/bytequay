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
import AttentionList from './AttentionList';
import type { AttentionItem } from './attention';

describe('AttentionList', () => {
  it('keeps the pull request identity together and renders shared action buttons', () => {
    const item: AttentionItem = {
      key: 'trinodb/trino#30920',
      type: 'pr',
      kind: 'review',
      repo: 'trinodb/trino',
      number: 30920,
      title: 'Add retry policy for listing tables in BigQuery',
      author: 'alice',
      authorAvatarUrl: null,
      createdAt: '2026-08-27T00:00:00Z',
      updatedAt: '2026-08-28T00:00:00Z',
      labels: [],
      pr: null,
      body: '',
      commentCount: 0,
      workspaceId: null,
    };
    const html = renderToStaticMarkup(
      <AttentionList
        candidates={[item]}
        handled={{}}
        onHandledChange={() => {}}
        onOpen={() => {}}
        onSeeAll={() => {}}
      />,
    );

    const title = html.indexOf(item.title);
    const number = html.indexOf(`#${item.number}`);
    const repositoryAvatar = html.indexOf('aria-label="trinodb/trino"');
    const authorAvatar = html.indexOf('alt="alice"');
    expect(title).toBeGreaterThan(-1);
    expect(number).toBeGreaterThan(title);
    expect(repositoryAvatar).toBeGreaterThan(number);
    expect(authorAvatar).toBeGreaterThan(repositoryAvatar);
    expect(html).toContain('class="bq-button bq-button--ghost"');
    expect(html.match(/class="bq-button/g)).toHaveLength(3);
    expect(html).toContain('Ack</button>');
    expect(html).toContain('Snooze</button>');
    expect(html).toContain('Open');
  });
});
