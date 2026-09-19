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
import { describe, expect, it, vi } from 'vitest';
import { parseAgentTask } from '../agents/contracts';
import contracts from '../agents/fixtures/agent-contracts-v1.json';
import type { Bridge, WorkspaceDto } from '../types';
import { reviewFixture } from './agent-review/fixtures/review';
import { PULL_TABS, type PullTab } from './model';
import {
  compactToolbar,
  findCodeTask,
  loadAgentReviewContext,
  mergePullRequestDetailStatus,
  PullFilterTabs,
  pullRequestDetailRefreshDelay,
  pullRequestSyncNotice,
  returnedFromInAppBrowser,
  shortcutTab,
  showBlockingDetailLoading,
  tabKeyTarget,
  tabSlots,
  visibleTabs,
} from './PullsScreen';

function workspace(id: string, overrides: Partial<WorkspaceDto> = {}): WorkspaceDto {
  return {
    id,
    name: 'my-fork/trino',
    selectedRepositoryId: 1,
    selectedOwner: 'my-fork',
    selectedRepo: 'trino',
    upstreamRepositoryId: 2,
    upstreamOwner: 'trinodb',
    upstreamRepo: 'trino',
    monitoredRepositoryId: 2,
    monitoredOwner: 'trinodb',
    monitoredRepo: 'trino',
    topology: 'FORK',
    defaultBranch: 'master',
    upstreamDefaultBranch: 'master',
    localRepositoryPath: '/tmp/trino',
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
    ...overrides,
  };
}

describe('loadAgentReviewContext', () => {
  it('opens the exact ended review from Tasks even when the PR has a newer open review', async () => {
    const review = reviewFixture({ id: 'ended-review', state: 'CLOSED' });
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>().mockResolvedValue(review),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>(),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>(),
    };

    await expect(loadAgentReviewContext(bridge, review.repo, review.number, review.workspaceId, review.id))
      .resolves.toEqual({ workspaceId: review.workspaceId, review });
    expect(bridge.getAgentReview).toHaveBeenCalledWith(review.id);
    expect(bridge.findAgentReview).not.toHaveBeenCalled();
    expect(bridge.listWorkspaces).not.toHaveBeenCalled();
    await expect(loadAgentReviewContext(bridge, review.repo, review.number, 'other-workspace', review.id))
      .rejects.toThrow('does not belong');
    await expect(loadAgentReviewContext(bridge, review.repo, review.number + 1, review.workspaceId, review.id))
      .rejects.toThrow('does not belong');
    await expect(loadAgentReviewContext(bridge, 'other/repo', review.number, review.workspaceId, review.id))
      .rejects.toThrow('does not belong');
  });

  it('keeps an explicitly opened workspace as the review context', async () => {
    const review = reviewFixture();
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>(),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockResolvedValue(review),
    };

    await expect(loadAgentReviewContext(bridge, review.repo, review.number, review.workspaceId))
      .resolves.toEqual({ workspaceId: review.workspaceId, review });
    expect(bridge.listWorkspaces).not.toHaveBeenCalled();
    expect(bridge.findAgentReview).toHaveBeenCalledWith(review.workspaceId, review.repo, review.number);
  });

  it('opens the first existing global PR review across matching watched repositories, regardless of casing', async () => {
    const review = reviewFixture({ workspaceId: 'workspace-2' });
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>().mockResolvedValue([
        workspace('workspace-1'),
        workspace('workspace-2', { monitoredOwner: 'TrinoDB', monitoredRepo: 'Trino' }),
        workspace('workspace-3'),
      ]),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockResolvedValueOnce(null).mockResolvedValue(review),
    };

    await expect(loadAgentReviewContext(bridge, review.repo, review.number))
      .resolves.toEqual({ workspaceId: review.workspaceId, review });
    expect(bridge.findAgentReview.mock.calls).toEqual([
      ['workspace-1', review.repo, review.number],
      ['workspace-2', review.repo, review.number],
    ]);
  });

  it('reuses an assigned authored PR task across workspaces before selecting a review', async () => {
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>().mockResolvedValue([
        workspace('workspace-1'), workspace('workspace-2'),
      ]),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockResolvedValue(null),
    };
    const findTask = vi.fn<Parameters<Bridge['findPullRequestTask']>, ReturnType<Bridge['findPullRequestTask']>>()
      .mockResolvedValueOnce(null).mockResolvedValue({ workspaceId: 'workspace-2', taskId: 'assigned-task' });

    await expect(loadAgentReviewContext(bridge, 'trinodb/trino', 30304, undefined, undefined, findTask))
      .resolves.toEqual({ workspaceId: 'workspace-2', review: null });
    expect(bridge.findAgentReview.mock.calls).toEqual([['workspace-2', 'trinodb/trino', 30304]]);
    expect(findTask.mock.calls).toEqual([
      ['workspace-1', 'trinodb/trino', 30304], ['workspace-2', 'trinodb/trino', 30304],
    ]);
  });

  it('allows starting review in the first matching workspace when none has a review', async () => {
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>().mockResolvedValue([
        workspace('workspace-1'), workspace('workspace-2'),
      ]),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockResolvedValue(null),
    };

    await expect(loadAgentReviewContext(bridge, 'trinodb/trino', 30304))
      .resolves.toEqual({ workspaceId: 'workspace-1', review: null });
    expect(bridge.findAgentReview).toHaveBeenCalledTimes(2);
  });

  it('requires a monitored repository match instead of matching only the selected fork', async () => {
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>().mockResolvedValue([workspace('workspace-1')]),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>(),
    };

    await expect(loadAgentReviewContext(bridge, 'my-fork/trino', 30304))
      .resolves.toEqual({ workspaceId: undefined, review: null });
    await expect(loadAgentReviewContext(bridge, 'other/repo', 30304))
      .resolves.toEqual({ workspaceId: undefined, review: null });
    expect(bridge.findAgentReview).not.toHaveBeenCalled();
  });

  it('propagates lookup failures so they cannot appear as an unwatched PR or absent review', async () => {
    const failure = new Error('Backend unavailable');
    const bridge = {
      getAgentReview: vi.fn<Parameters<Bridge['getAgentReview']>, ReturnType<Bridge['getAgentReview']>>(),
      listWorkspaces: vi.fn<Parameters<Bridge['listWorkspaces']>, ReturnType<Bridge['listWorkspaces']>>().mockRejectedValueOnce(failure)
        .mockResolvedValue([workspace('workspace-1')]),
      findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockRejectedValue(failure),
    };

    await expect(loadAgentReviewContext(bridge, 'trinodb/trino', 30304)).rejects.toBe(failure);
    expect(bridge.findAgentReview).not.toHaveBeenCalled();
    await expect(loadAgentReviewContext(bridge, 'trinodb/trino', 30304)).rejects.toBe(failure);
  });
});

describe('findCodeTask', () => {
  function bridge(task: ReturnType<typeof parseAgentTask> | null) {
    return {
      findPullRequestTask: vi.fn<Parameters<Bridge['findPullRequestTask']>, ReturnType<Bridge['findPullRequestTask']>>()
        .mockResolvedValue(task === null ? null : { workspaceId: 'workspace-1', taskId: task.id }),
      getAgentTask: vi.fn<Parameters<Bridge['getAgentTask']>, ReturnType<Bridge['getAgentTask']>>().mockResolvedValue(task ?? parseAgentTask(contracts.task)),
    };
  }
  const task = { ...parseAgentTask(contracts.task), interactive: true };

  it('binds Code & Tests to the owning interactive task while its worktree is present', async () => {
    const ports = bridge(task);
    await expect(findCodeTask(ports, 'workspace-1', 'trinodb/trino', 30304)).resolves.toEqual(task);
    expect(ports.findPullRequestTask).toHaveBeenCalledWith('workspace-1', 'trinodb/trino', 30304);
    expect(ports.getAgentTask).toHaveBeenCalledWith(task.id, 'workspace-1');
  });

  it('ignores a pull request without a task, a removed worktree and a non-interactive task', async () => {
    const none = bridge(null);
    await expect(findCodeTask(none, 'workspace-1', 'trinodb/trino', 30304)).resolves.toBeNull();
    expect(none.getAgentTask).not.toHaveBeenCalled();
    await expect(findCodeTask(bridge({ ...task, checkoutRemovedAt: '2026-09-17T00:00:00Z' }), 'workspace-1', 'trinodb/trino', 30304)).resolves.toBeNull();
    await expect(findCodeTask(bridge({ ...task, interactive: false }), 'workspace-1', 'trinodb/trino', 30304)).resolves.toBeNull();
  });
});

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

  it('retries focused pull-request refreshes after five seconds', () => {
    expect(pullRequestDetailRefreshDelay(new Error('temporary failure'))).toBe(5_000);
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

const COUNTS = ['42', '6', '4', '11', '3', '18'];

function tabsMarkup(tab: PullTab, width = 640): string {
  return renderToStaticMarkup(createElement(PullFilterTabs, {
    tab,
    searching: false,
    width,
    panelId: 'rows',
    counts: COUNTS,
    onSelect: () => undefined,
  }));
}

/** The labels of the rendered tabs, in the order the strip shows them. */
function tabLabels(tab: PullTab, width = 640): string[] {
  return [...tabsMarkup(tab, width).matchAll(/role="tab"[^>]*>([^<]*)</g)].map(match => match[1]);
}

describe('responsive tab slots', () => {
  it('gives the pane two tabs under 320px, three under 430px, and never more than four', () => {
    expect([288, 300, 319, 320, 352, 429, 430, 640, 1200].map(tabSlots))
      .toEqual([2, 2, 2, 3, 3, 3, 4, 4, 4]);
  });

  it('fills slots from the fixed order, so a tab never moves as the pane resizes', () => {
    expect(visibleTabs('all', 300)).toEqual(['req', 'all']);
    expect(visibleTabs('all', 352)).toEqual(['req', 'all', 'active']);
    expect(visibleTabs('all', 640)).toEqual(['req', 'all', 'active', 'monitored']);
    expect(tabLabels('all', 300)).toEqual(['Review', 'Open']);
    expect(tabLabels('all', 640)).toEqual(['Review', 'Open', 'My work', 'Monitored']);
  });

  it('promotes a selected filter the order would hide into the last slot', () => {
    expect(visibleTabs('monitored', 300)).toEqual(['req', 'monitored']);
    expect(visibleTabs('done', 352)).toEqual(['req', 'all', 'done']);
    expect(visibleTabs('reviewed', 640)).toEqual(['req', 'all', 'active', 'reviewed']);
    expect(tabLabels('monitored', 300)).toEqual(['Review', 'Monitored']);
    expect(tabsMarkup('monitored', 300)).toContain('aria-selected="true"');
  });

  it('keeps every filter reachable, on a tab or in the menu', () => {
    for (const width of [288, 300, 352, 430, 640]) {
      for (const item of PULL_TABS) {
        const visible = visibleTabs(item.key, width);
        expect(visible).toContain(item.key);
        expect(visible).toHaveLength(tabSlots(width));
      }
    }
  });
});

/**
 * The menu opens from component state, and these tests have no DOM to click
 * in. What is asserted here is everything that decides the menu without it:
 * which filters land in it, the closed strip around it, and its layout.
 */
describe('overflow menu', () => {
  it('holds exactly the filters that have no tab', () => {
    const overflow = (tab: PullTab, width: number) =>
      PULL_TABS.filter(item => !visibleTabs(tab, width).includes(item.key)).map(item => item.label);

    expect(overflow('all', 300)).toEqual(['My work', 'Monitored', 'Reviewed', 'Closed']);
    expect(overflow('all', 640)).toEqual(['Reviewed', 'Closed']);
    expect(overflow('monitored', 300)).toEqual(['Open', 'My work', 'Reviewed', 'Closed']);
  });

  it('never puts a count on a tab', () => {
    for (const count of COUNTS) expect(tabLabels('all', 640).join(' ')).not.toContain(count);
    expect(tabsMarkup('all', 300)).not.toContain('pulls-tab__count');
  });

  it('announces itself as a menu the tab strip owns, closed until pressed', () => {
    expect(tabsMarkup('all', 300)).toContain('aria-haspopup="menu"');
    expect(tabsMarkup('all', 300)).toContain('aria-expanded="false"');
    expect(tabsMarkup('all', 300)).not.toContain('pulls-filter-menu');
  });

  it('lays the menu out over the list instead of inside a clipped strip', () => {
    expect(declarations('.pulls-filter-menu')).toEqual(expect.arrayContaining([
      'position: absolute', 'width: 218px', 'border: 1px solid #d0d7de', 'border-radius: 10px', 'padding: 5px',
    ]));
    expect(declarations('.pulls-toolbar__filters')).toContain('position: relative');
    expect(properties('.pulls-toolbar__filters')).not.toContain('overflow');
    expect(properties('.pulls-toolbar__tabs')).not.toContain('overflow');
  });

  it('right-aligns a menu count before its shortcut hint', () => {
    expect(declarations('.pulls-filter-menu__label')).toContain('flex: 1');
    expect(declarations('.pulls-filter-menu__key')).toContain('text-align: right');
  });
});

describe('the strip never overflows its row', () => {
  it('marks selection with background, color, and weight, matching the design', () => {
    expect(tabsMarkup('req')).toContain('aria-selected="true"');
    expect(declarations('.pulls-tab[aria-selected=\'true\']')).toEqual(
      expect.arrayContaining(['background: #e4e4e8', 'color: #1f2328', 'font-weight: 600']),
    );
    expect(declarations('.pulls-tab')).toEqual(
      expect.arrayContaining(['flex: none', 'padding: 4px 9px', 'border-radius: 6px', 'font-size: 12.5px']),
    );
  });

  it('lets only the spacer give, so the trailing buttons stay on screen', () => {
    for (const selector of ['.pulls-toolbar__filters', '.pulls-toolbar__tabs', '.pulls-tab', '.pulls-tab-more']) {
      expect(declarations(selector), selector).toContain('flex: none');
    }
    expect(declarations('.pulls-toolbar__spacer')).toEqual(expect.arrayContaining(['flex: 1', 'min-width: 6px']));
    expect(declarations('.pulls-toolbar .pulls-toolbar-button')).toContain('flex: 0 0 26px');
    expect(properties('.pulls-toolbar')).not.toContain('overflow');
  });

  it('moves the repository chip onto the search row, where it can ellipsize', () => {
    expect(declarations('.pulls-search')).toEqual(expect.arrayContaining(['display: flex', 'gap: 7px']));
    expect(declarations('.pulls-search input')).toEqual(expect.arrayContaining(['flex: 1', 'min-width: 0']));
    expect(declarations('.pulls-search .pulls-scope')).toEqual(
      expect.arrayContaining(['flex: none', 'min-width: 0', 'max-width: 118px']),
    );
    expect(declarations('.pulls-scope')).toEqual(expect.arrayContaining(['text-overflow: ellipsis', 'overflow: hidden']));
  });
});

describe('narrow toolbars', () => {
  it('shortens the search placeholder only under 440px', () => {
    expect([320, 420, 440, 560, 760].map(compactToolbar)).toEqual([true, true, false, false, false]);
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

  it('reaches every filter by Command digit, hidden ones included', () => {
    const press = { altKey: false, ctrlKey: false, metaKey: true };
    expect(PULL_TABS.map((_, index) => shortcutTab({ ...press, key: String(index + 1) })))
      .toEqual(['req', 'all', 'active', 'monitored', 'reviewed', 'done']);
    expect(visibleTabs('reviewed', 300)).toContain('reviewed');
    expect(shortcutTab({ ...press, key: '7' })).toBeNull();
    expect(shortcutTab({ ...press, key: 'f' })).toBeNull();
    expect(shortcutTab({ ...press, altKey: true, key: '1' })).toBeNull();
    expect(shortcutTab({ ...press, metaKey: false, key: '1' })).toBeNull();
  });
});
