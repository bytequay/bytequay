import { describe, expect, it } from 'vitest';
import type { PullRequestDto } from './types';
import {
  clampSidebarWidth,
  getNextKeyboardSelection,
  isTextEntryTarget,
  loadLastReviewingId,
  loadSidebarWidth,
} from './pullRequestListHelpers';

function pr(id: number): PullRequestDto {
  return {
    id,
    repo: 'owner/repo',
    number: id,
    title: `PR ${id}`,
    author: 'alice',
    htmlUrl: '',
    createdAt: null,
    updatedAt: '2026-04-23T10:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: 0,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: null,
    mergeableState: null,
    headPushedAt: null,
    reviewerVerdicts: null,
  };
}

describe('clampSidebarWidth', () => {
  it('clamps small values to the minimum', () => {
    expect(clampSidebarWidth(100)).toBe(260);
  });

  it('clamps large values to the maximum', () => {
    expect(clampSidebarWidth(999)).toBe(600);
  });

  it('keeps in-range values unchanged', () => {
    expect(clampSidebarWidth(420)).toBe(420);
  });
});

describe('loadSidebarWidth', () => {
  it('returns the default when no width is stored', () => {
    expect(loadSidebarWidth({ getItem: () => null })).toBe(380);
  });

  it('clamps stored widths into the supported range', () => {
    expect(loadSidebarWidth({ getItem: () => '900' })).toBe(600);
    expect(loadSidebarWidth({ getItem: () => '200' })).toBe(260);
  });
});

describe('loadLastReviewingId', () => {
  it('returns null for a missing value', () => {
    expect(loadLastReviewingId({ getItem: () => null })).toBeNull();
  });

  it('returns the stored numeric id', () => {
    expect(loadLastReviewingId({ getItem: () => '42' })).toBe(42);
  });
});

describe('isTextEntryTarget', () => {
  it('recognizes input and textarea targets', () => {
    expect(isTextEntryTarget({ tagName: 'INPUT' } as HTMLElement)).toBe(true);
    expect(isTextEntryTarget({ tagName: 'TEXTAREA' } as HTMLElement)).toBe(true);
  });

  it('ignores non-editable targets', () => {
    expect(isTextEntryTarget({ tagName: 'DIV' } as HTMLElement)).toBe(false);
    expect(isTextEntryTarget(null)).toBe(false);
  });
});

describe('getNextKeyboardSelection', () => {
  const prs = [pr(1), pr(2), pr(3)];

  it('returns null for an empty list', () => {
    expect(getNextKeyboardSelection([], null, 'ArrowDown')).toBeNull();
  });

  it('moves down through the list and wraps at the end', () => {
    expect(getNextKeyboardSelection(prs, 1, 'ArrowDown')?.id).toBe(2);
    expect(getNextKeyboardSelection(prs, 3, 'ArrowDown')?.id).toBe(1);
  });

  it('moves up through the list and wraps at the start', () => {
    expect(getNextKeyboardSelection(prs, 2, 'ArrowUp')?.id).toBe(1);
    expect(getNextKeyboardSelection(prs, 1, 'ArrowUp')?.id).toBe(3);
  });

  it('starts from the first or last item when nothing is selected', () => {
    expect(getNextKeyboardSelection(prs, null, 'ArrowDown')?.id).toBe(1);
    expect(getNextKeyboardSelection(prs, null, 'ArrowUp')?.id).toBe(3);
  });
});
