import type { PullRequestDto } from './types';

const LAST_REVIEWING_KEY = 'settings:last-reviewing-pr-id';
const SIDEBAR_WIDTH_KEY = 'settings:pr-sidebar-width';

export const SIDEBAR_WIDTH_MIN = 260;
export const SIDEBAR_WIDTH_MAX = 600;
export const SIDEBAR_WIDTH_DEFAULT = 380;

export function clampSidebarWidth(width: number): number {
  return Math.max(SIDEBAR_WIDTH_MIN, Math.min(SIDEBAR_WIDTH_MAX, width));
}

export function loadSidebarWidth(storage: Pick<Storage, 'getItem'> = localStorage): number {
  const raw = storage.getItem(SIDEBAR_WIDTH_KEY);
  const width = raw ? parseInt(raw, 10) : NaN;
  if (!Number.isFinite(width)) {
    return SIDEBAR_WIDTH_DEFAULT;
  }
  return clampSidebarWidth(width);
}

export function loadLastReviewingId(storage: Pick<Storage, 'getItem'> = localStorage): number | null {
  const raw = storage.getItem(LAST_REVIEWING_KEY);
  const prId = raw ? parseInt(raw, 10) : NaN;
  return Number.isFinite(prId) ? prId : null;
}

export function persistLastReviewingId(
  prId: number,
  storage: Pick<Storage, 'setItem'> = localStorage,
): void {
  storage.setItem(LAST_REVIEWING_KEY, String(prId));
}

export function isTextEntryTarget(target: EventTarget | null): boolean {
  const tagName = (target as HTMLElement | null)?.tagName;
  return tagName === 'INPUT' || tagName === 'TEXTAREA';
}

export function getNextKeyboardSelection(
  prs: PullRequestDto[],
  selectedId: number | null,
  key: 'ArrowDown' | 'ArrowUp',
): PullRequestDto | null {
  if (prs.length === 0) {
    return null;
  }

  const selectedIndex = selectedId === null
    ? -1
    : prs.findIndex((pr) => pr.id === selectedId);

  if (key === 'ArrowDown') {
    const nextIndex = selectedIndex < prs.length - 1 ? selectedIndex + 1 : 0;
    return prs[nextIndex];
  }

  const nextIndex = selectedIndex > 0 ? selectedIndex - 1 : prs.length - 1;
  return prs[nextIndex];
}
