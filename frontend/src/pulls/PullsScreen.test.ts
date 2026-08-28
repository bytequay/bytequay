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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { PullTab } from './model';
import {
  compactToolbar,
  mergePullRequestDetailStatus,
  PullFilterTabs,
  pullRequestDetailRefreshDelay,
  pullRequestSyncNotice,
  returnedFromInAppBrowser,
  shortcutTab,
  showBlockingDetailLoading,
  tabKeyTarget,
} from './PullsScreen';

describe('mergePullRequestDetailStatus', () => {
  it('changes only mergeability and CI fields', () => {
    const result = mergePullRequestDetailStatus(
      { title: 'Keep me', body: 'Still here' },
      {
        mergeable: false,
        mergeableState: 'dirty',
        ciStatus: 'FAILING',
        checkRuns: [],
        unavailableSections: [],
      },
    );

    expect(result).toEqual({
      title: 'Keep me',
      body: 'Still here',
      mergeable: false,
      mergeableState: 'dirty',
      ciStatus: 'FAILING',
      checkRuns: [],
      unavailableSections: [],
    });
  });

  it('pauses conditional refreshes until an exhausted rate limit resets', () => {
    const now = Date.parse('2026-08-24T17:00:00Z');
    const resetAt = Date.parse('2026-08-24T17:33:20Z');

    expect(pullRequestDetailRefreshDelay(new Error(
      'GitHub core API rate limit reached; retry after 2026-08-24T17:33:20Z',
    ), now)).toBe(resetAt - now + 1_000);
  });

  it('retries focused pull-request refreshes after six seconds', () => {
    expect(pullRequestDetailRefreshDelay(new Error('temporary failure'))).toBe(6_000);
  });

  it('forces a full refresh only when returning from the in-app browser', () => {
    expect(returnedFromInAppBrowser(false, false)).toBe(false);
    expect(returnedFromInAppBrowser(false, true)).toBe(false);
    expect(returnedFromInAppBrowser(true, true)).toBe(false);
    expect(returnedFromInAppBrowser(true, false)).toBe(true);
  });

  it('keeps existing detail visible while it refreshes', () => {
    expect(showBlockingDetailLoading(true, false)).toBe(true);
    expect(showBlockingDetailLoading(true, true)).toBe(false);
    expect(showBlockingDetailLoading(false, false)).toBe(false);
  });

  it('turns an overlapping list refresh into a transient notice', () => {
    expect(pullRequestSyncNotice(new Error(
      "Error invoking remote method 'prs:sync': Error: A pull request refresh is already in progress; try again shortly",
    ))).toBe('Pull requests are already refreshing.');
    expect(pullRequestSyncNotice(new Error('GitHub is unavailable'))).toBeNull();
  });
});

const css = readFileSync(resolve(process.cwd(), 'src/css/pulls.css'), 'utf8');

/** The declarations of one exact selector, as `property: value` strings. */
function declarations(selector: string): string[] {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} is missing from pulls.css`).toBeGreaterThan(-1);
  return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
    .split(';')
    .map(declaration => declaration.replace(/\/\*[^]*?\*\//g, '').trim())
    .filter(declaration => declaration !== '');
}

function properties(selector: string): string[] {
  return declarations(selector).map(declaration => declaration.split(':')[0].trim());
}

function tabsMarkup(tab: PullTab, compact = false): string {
  return renderToStaticMarkup(createElement(PullFilterTabs, {
    tab,
    searching: false,
    compact,
    panelId: 'rows',
    counts: ['42', '6', '4', '11', '3'],
    onSelect: () => undefined,
  }));
}

/**
 * jsdom is not installed and would report zero-sized boxes anyway, so the
 * no-reflow rule is asserted where it actually lives: identical markup for
 * every selection, and a selected-state rule that changes no metric. Same
 * boxes plus same declarations means the same
 * `getBoundingClientRect().left` for every tab.
 */
describe('filter tabs hold their position', () => {
  it('renders the same boxes whichever tab is selected', () => {
    const withoutState = (markup: string) => markup
      .replace(/ aria-selected="(?:true|false)"/g, '')
      .replace(/ tabindex="(?:0|-1)"/g, '');

    for (const tab of ['active', 'req', 'reviewed', 'done'] as PullTab[]) {
      expect(withoutState(tabsMarkup(tab))).toBe(withoutState(tabsMarkup('all')));
    }
  });

  it('marks selection with more than color, and with nothing that resizes a tab', () => {
    expect(tabsMarkup('req')).toContain('aria-selected="true"');
    expect(properties('.pulls-tab[aria-selected=\'true\']')).toEqual(['background', 'color']);
    expect(declarations('.pulls-tab')).toContain('font-weight: 600');
  });

  it('clips the tab strip rather than the buttons pinned after it', () => {
    expect(declarations('.pulls-toolbar__tabs')).toEqual(
      expect.arrayContaining(['flex: 1', 'min-width: 0', 'overflow: hidden']),
    );
    expect(declarations('.pulls-toolbar .pulls-toolbar-button')).toContain('flex: 0 0 26px');
    expect(properties('.pulls-toolbar')).not.toContain('overflow');
  });
});

describe('narrow toolbars', () => {
  it('keeps counts only at 420px and wider', () => {
    expect([320, 420, 560, 760].map(compactToolbar)).toEqual([true, false, false, false]);
  });

  it('drops every count at once, never one tab alone', () => {
    expect(tabsMarkup('all', true)).not.toContain('pulls-tab__count');
    expect(tabsMarkup('all').match(/pulls-tab__count/g)).toHaveLength(5);
  });
});

describe('filter keyboard', () => {
  it('moves with the arrows and jumps with Home and End', () => {
    expect(tabKeyTarget('ArrowRight', 0, 5)).toBe(1);
    expect(tabKeyTarget('ArrowLeft', 0, 5)).toBe(4);
    expect(tabKeyTarget('ArrowRight', 4, 5)).toBe(0);
    expect(tabKeyTarget('Home', 3, 5)).toBe(0);
    expect(tabKeyTarget('End', 0, 5)).toBe(4);
    expect(tabKeyTarget('Enter', 2, 5)).toBeNull();
  });

  it('selects a filter for each Command digit it has a tab for', () => {
    const press = { altKey: false, ctrlKey: false, metaKey: true };
    expect(shortcutTab({ ...press, key: '1' })).toBe('all');
    expect(shortcutTab({ ...press, key: '4' })).toBe('reviewed');
    expect(shortcutTab({ ...press, key: '6' })).toBeNull();
    expect(shortcutTab({ ...press, key: 'f' })).toBeNull();
    expect(shortcutTab({ ...press, altKey: true, key: '1' })).toBeNull();
    expect(shortcutTab({ ...press, metaKey: false, key: '1' })).toBeNull();
  });
});
