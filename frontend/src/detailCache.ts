import type { PullRequestDetailDto } from './types';

const TTL_KEY = 'settings:detail-cache-ttl-s';
const DEFAULT_TTL_S = 30;
const MAX_SIZE = 50;

type Entry = { data: PullRequestDetailDto; fetchedAt: number };

const cache = new Map<number, Entry>();

export function getTtlSeconds(): number {
  const raw = localStorage.getItem(TTL_KEY);
  const n = raw !== null ? parseInt(raw, 10) : NaN;
  return Number.isFinite(n) && n >= 5 ? n : DEFAULT_TTL_S;
}

export function setTtlSeconds(s: number): void {
  localStorage.setItem(TTL_KEY, String(s));
}

export function getCached(prId: number): { data: PullRequestDetailDto; stale: boolean } | null {
  const entry = cache.get(prId);
  if (!entry) return null;
  const stale = Date.now() - entry.fetchedAt > getTtlSeconds() * 1000;
  return { data: entry.data, stale };
}

export function putCache(prId: number, data: PullRequestDetailDto): void {
  if (cache.size >= MAX_SIZE) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  cache.set(prId, { data, fetchedAt: Date.now() });
}

export function clearCache(): void {
  cache.clear();
}
