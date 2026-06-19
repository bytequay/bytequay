import type { NotificationDto } from '../types';

export type PrRef = { owner: string; repo: string; prNumber: number };

/**
 * The PR a notification points at, when it carries one — e.g. a
 * NEEDS_ATTENTION row about a PR with failing CI, whose payload holds
 * {@code repoFullName} ("owner/repo") and {@code prNumber}. Returns null when
 * the payload has no resolvable repo + PR, so the caller falls back to a plain
 * jump-in instead of navigating nowhere.
 */
export function prRefFromNotification(n: NotificationDto): PrRef | null {
  if (!n.payloadJson) {
    return null;
  }
  let payload: Record<string, unknown> | null = null;
  try {
    payload = JSON.parse(n.payloadJson);
  }
  catch {
    return null;
  }
  if (!payload) {
    return null;
  }
  const repoFullName = typeof payload.repoFullName === 'string' ? payload.repoFullName : null;
  const prNumber = typeof payload.prNumber === 'number' ? payload.prNumber : null;
  if (repoFullName === null || prNumber === null) {
    return null;
  }
  const slash = repoFullName.indexOf('/');
  if (slash <= 0 || slash === repoFullName.length - 1) {
    return null;
  }
  return {
    owner: repoFullName.slice(0, slash),
    repo: repoFullName.slice(slash + 1),
    prNumber,
  };
}
