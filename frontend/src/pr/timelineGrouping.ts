import type { ActivityItemDto, ReviewThreadDto } from '../types';

/** Time window (ms) that a `review_requested` burst is allowed to span
 *  before we stop collapsing them into one event-group. 60s matches the
 *  typical "user adds several reviewers via one UI interaction" pattern
 *  on github.com — back-to-back reviewer additions arrive within
 *  milliseconds. Anything wider almost certainly represents a deliberate
 *  add-then-add gesture and should render as separate rows. */
export const REVIEW_REQUEST_BURST_MS = 60_000;

/** Event types that collapse same-actor / same-day runs into a single
 *  "pushed N commits" / "force-pushed N times" summary line. Other
 *  event types (review_requested, merged, closed, …) get their own
 *  rules or render individually. */
const GROUPABLE_DAY = new Set(['committed', 'head_ref_force_pushed']);

/**
 * Pre-grouping shape of one timeline entry. The orchestrator builds
 * this list from {@code detail.recentActivity} (one entry per
 * activity) plus standalone {@link ReviewThreadDto}s (one entry per
 * thread that wasn't attached to a `reviewed` event), sorted by
 * {@code ts} ascending.
 */
export type RawTimelineEntry =
  | { kind: 'activity'; ts: number; item: ActivityItemDto; attachedThreads?: ReviewThreadDto[] }
  | { kind: 'thread'; ts: number; thread: ReviewThreadDto };

/**
 * Post-grouping shape — one entry per visible row in the conversation
 * timeline. {@code event-group} is the collapsed-run variant; the
 * other shapes pass through from {@link RawTimelineEntry}.
 */
export type TimelineEntry =
  | { kind: 'date-divider'; label: string }
  | { kind: 'activity'; item: ActivityItemDto; attachedThreads?: ReviewThreadDto[] }
  | {
      kind: 'event-group';
      actor: string;
      eventType: string;
      count: number;
      lastItem: ActivityItemDto;
      /** For review_requested groups, the ordered list of requested
       *  reviewer logins so the renderer can produce
       *  "x requested a, b, c and d for review". Absent for other
       *  event types (committed / force-pushed). */
      reviewers?: string[];
    }
  | { kind: 'thread'; thread: ReviewThreadDto };

/**
 * Walks the timestamp-sorted {@code raw} list and collapses runs:
 *
 * - Same-actor + same-event-type {@code committed} or
 *   {@code head_ref_force_pushed} events that fall on the same local
 *   day collapse into a single {@code event-group} carrying the count
 *   and the last item's metadata (the "last commit was abc1234"
 *   fragment the renderer surfaces).
 *
 * - Same-actor {@code review_requested} events whose timestamps span
 *   ≤ {@link REVIEW_REQUEST_BURST_MS} from the first one collapse
 *   into a single group with each event's {@code requestedReviewer}
 *   collected so the renderer can produce
 *   "x requested @a, @b and @c for review".
 *
 * - Everything else (other event types, threads, lone events of
 *   groupable types) passes through unchanged.
 *
 * Pure function — easy to unit-test against the rules above without
 * touching React state.
 */
export function groupTimelineEntries(raw: RawTimelineEntry[]): TimelineEntry[] {
  const out: TimelineEntry[] = [];
  let i = 0;
  while (i < raw.length) {
    const r = raw[i];
    // We use a local-day key to bound the run-length grouping below —
    // a force-push streak is only collapsed within the same day so
    // the user can still see the boundary when work spans midnight.
    const day = r.ts ? new Date(r.ts).toDateString() : 'unknown';
    if (r.kind === 'activity' && GROUPABLE_DAY.has(r.item.eventType)) {
      const first = r.item;
      let count = 1;
      let lastItem = first;
      let j = i + 1;
      while (j < raw.length) {
        const next = raw[j];
        if (next.kind !== 'activity') break;
        const nextDay = next.ts ? new Date(next.ts).toDateString() : 'unknown';
        if (nextDay !== day) break;
        if (next.item.actor !== first.actor) break;
        if (next.item.eventType !== first.eventType) break;
        count++;
        lastItem = next.item;
        j++;
      }
      if (count > 1) {
        out.push({ kind: 'event-group', actor: first.actor, eventType: first.eventType, count, lastItem });
      }
      else {
        out.push({ kind: 'activity', item: first });
      }
      i = j;
      continue;
    }
    if (r.kind === 'activity' && r.item.eventType === 'review_requested') {
      const first = r.item;
      const firstMs = first.timestamp ? new Date(first.timestamp).getTime() : null;
      const reviewers: string[] = first.requestedReviewer ? [first.requestedReviewer] : [];
      let lastItem = first;
      let j = i + 1;
      while (firstMs !== null && j < raw.length) {
        const next = raw[j];
        if (next.kind !== 'activity') break;
        if (next.item.eventType !== 'review_requested') break;
        if (next.item.actor !== first.actor) break;
        const nextMs = next.item.timestamp ? new Date(next.item.timestamp).getTime() : null;
        if (nextMs === null) break;
        if (Math.abs(nextMs - firstMs) > REVIEW_REQUEST_BURST_MS) break;
        if (next.item.requestedReviewer) reviewers.push(next.item.requestedReviewer);
        lastItem = next.item;
        j++;
      }
      if (reviewers.length > 1) {
        out.push({
          kind: 'event-group',
          actor: first.actor,
          eventType: first.eventType,
          count: reviewers.length,
          lastItem,
          reviewers,
        });
        i = j;
        continue;
      }
      // Single review_requested falls through to the default activity
      // rendering below.
    }
    if (r.kind === 'activity') {
      out.push({ kind: 'activity', item: r.item, attachedThreads: r.attachedThreads });
    }
    else {
      out.push({ kind: 'thread', thread: r.thread });
    }
    i++;
  }
  return out;
}
