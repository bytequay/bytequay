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
import type { CSSProperties } from 'react';
import type { ReactionsDto } from '../types';

/** GitHub's reaction-content enum — what the API expects in the
 *  {@code content} field of the reactions endpoint. */
export type ReactionContent = '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes';

/** GitHub reaction emoji map. Keys match the field names on ReactionsDto so we
 *  can iterate the record once and emit a chip per non-zero count. */
export const REACTION_EMOJI: Record<keyof ReactionsDto, string> = {
  plusOne: '👍',
  minusOne: '👎',
  laugh: '😄',
  hooray: '🎉',
  confused: '😕',
  heart: '❤️',
  rocket: '🚀',
  eyes: '👀',
};

/** Map a reaction-content value to the matching field on ReactionsDto. */
export const REACTION_FIELD: Record<ReactionContent, keyof ReactionsDto> = {
  '+1': 'plusOne',
  '-1': 'minusOne',
  laugh: 'laugh',
  hooray: 'hooray',
  confused: 'confused',
  heart: 'heart',
  rocket: 'rocket',
  eyes: 'eyes',
};

/** Picker rows: the GitHub-API content string + the emoji we render. */
export const REACTION_PICKER: Array<{ content: ReactionContent; emoji: string; label: string }> = [
  { content: '+1', emoji: '👍', label: 'Thumbs up' },
  { content: '-1', emoji: '👎', label: 'Thumbs down' },
  { content: 'laugh', emoji: '😄', label: 'Laugh' },
  { content: 'hooray', emoji: '🎉', label: 'Hooray' },
  { content: 'confused', emoji: '😕', label: 'Confused' },
  { content: 'heart', emoji: '❤️', label: 'Heart' },
  { content: 'rocket', emoji: '🚀', label: 'Rocket' },
  { content: 'eyes', emoji: '👀', label: 'Eyes' },
];

export function formatRelativeTime(timestamp: string): string {
  const diffMs = Date.now() - new Date(timestamp).getTime();
  const mins = Math.max(0, Math.round(diffMs / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  // Relative form for the recent window — easy to scan when scrolling
  // through today's activity. Beyond 10 days, switch to an absolute
  // "Apr 2"-style date so the user gets the actual when without doing
  // mental arithmetic.
  if (days <= 10) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** The absolute local timestamp we surface in a tooltip behind the
 *  relative label — mirrors github.com's `<relative-time>`, which
 *  keeps the exact "when" one hover away. Returns the empty string
 *  for an unparseable timestamp so the caller can drop the `title`. */
export function formatAbsoluteTime(timestamp: string): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit',
  });
}

/** Translates GitHub's author_association enum into the small pill we
 *  show next to a comment author. NONE / null collapse to no pill (the
 *  default state for outside contributors with no relationship to the
 *  repo). FIRST_TIMER / FIRST_TIME_CONTRIBUTOR collapse into the same
 *  "Contributor" affordance the github.com UI uses. */
export function authorAssociationLabel(association: string | null | undefined): string | null {
  if (!association) return null;
  switch (association) {
    case 'OWNER': return 'Owner';
    case 'COLLABORATOR': return 'Collaborator';
    case 'MEMBER': return 'Member';
    case 'CONTRIBUTOR':
    case 'FIRST_TIME_CONTRIBUTOR':
    case 'FIRST_TIMER': return 'Contributor';
    case 'MANNEQUIN': return 'Mannequin';
    default: return null;
  }
}

/** Conversation tab filters out bot-authored activity (dependabot, renovate,
 *  codecov, etc.). GitHub marks service-account logins with a `[bot]` suffix;
 *  we also catch the `-bot` convention used by a handful of first-party bots. */
export function isBotActor(actor: string | null | undefined): boolean {
  if (!actor) return false;
  const a = actor.toLowerCase();
  return a.endsWith('[bot]') || a.endsWith('-bot');
}

export function activityVerb(eventType: string): string {
  switch (eventType) {
    case 'committed': return 'pushed a commit';
    case 'approved': return 'approved';
    case 'changes_requested': return 'requested changes';
    case 'reviewed': return 'left a review';
    case 'commented': return 'left a comment';
    // For review_requested the proper rendering is "actor requested @reviewer
    // to review" — see renderActivity. This default is only used when the
    // requestedReviewer field is missing (very old data).
    case 'review_requested': return 'requested a review';
    case 'added_to_merge_queue': return 'added this PR to the merge queue';
    case 'removed_from_merge_queue': return 'removed this PR from the merge queue';
    default: return eventType;
  }
}

/** Single-character marker rendered ON the timeline rail for structural
 *  events. Comment cards don't use this — they sit outside the rail with
 *  the avatar acting as the marker. */
export function eventMarker(eventType: string): string {
  switch (eventType) {
    case 'committed': return '○';
    case 'head_ref_force_pushed': return '⊕';
    case 'review_requested': return '👁';
    case 'reviewed': return '👁';
    case 'review_request_removed': return '×';
    case 'merged': return '✓';
    case 'closed': return '×';
    case 'reopened': return '↺';
    case 'added_to_merge_queue': return '⏳';
    case 'removed_from_merge_queue': return '×';
    case 'labeled':
    case 'unlabeled': return '●';
    case 'assigned':
    case 'unassigned': return '◆';
    case 'renamed': return '✎';
    default: return '•';
  }
}

export function conclusionLabel(conclusion: string | null): string {
  if (!conclusion) return 'running';
  // Normalise the GitHub machine value into friendly casing.
  return conclusion.replace(/_/g, ' ');
}

export function isCheckFailing(conclusion: string | null): boolean {
  return conclusion === 'failure' || conclusion === 'cancelled' || conclusion === 'timed_out' || conclusion === 'action_required';
}

/** Returns the last non-context line of a diff hunk — the line a reviewer
 *  is most likely commenting on. Used to derive the "old" line for a
 *  suggestion-block diff. Falls back to the last line overall when the
 *  hunk has no `+`/`-` markers. */
export function lastTouchedLine(hunk: string): string {
  const lines = hunk.split('\n').filter(l => !l.startsWith('@@'));
  for (let i = lines.length - 1; i >= 0; i--) {
    const l = lines[i];
    if (l.startsWith('+') || l.startsWith('-') || l.length === 0) {
      // Strip the leading sign char so the suggestion-diff renders the
      // same indent as the code (we add our own +/- prefix).
      if (l.startsWith('+') || l.startsWith('-')) return l.slice(1);
    }
  }
  // Fall through to the last context line.
  return lines.length > 0 ? lines[lines.length - 1].replace(/^\s/, '') : '';
}

/** Builds an inline-style for a label chip from a GitHub hex color (no '#').
 *  Picks a readable text color from the bg's luminance — same approach
 *  GitHub uses on its own labels — so chips stay legible against light or
 *  dark backgrounds. Returns undefined when no color is provided so the
 *  default chip styling kicks in. */
export function labelChipStyle(color: string | null | undefined): CSSProperties | undefined {
  if (!color || !/^[0-9a-fA-F]{6}$/.test(color)) return undefined;
  const r = parseInt(color.slice(0, 2), 16);
  const g = parseInt(color.slice(2, 4), 16);
  const b = parseInt(color.slice(4, 6), 16);
  // Standard sRGB luminance (0-1). Bright labels get dark text, dark labels
  // get white text — keeps contrast above ~4.5:1 in practice.
  const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const text = luma > 0.6 ? '#1f2937' : '#ffffff';
  return { background: `#${color}`, color: text, borderColor: 'transparent' };
}

export function relativeDayLabel(ts: number): string {
  if (!ts) return 'Unknown date';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const itemDay = new Date(ts);
  itemDay.setHours(0, 0, 0, 0);
  const days = Math.round((today.getTime() - itemDay.getTime()) / 86_400_000);
  if (days === 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days} days ago`;
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function truncatePath(path: string): string {
  if (path.length < 36) return path;
  const segs = path.split('/');
  if (segs.length < 3) return path;
  return `…/${segs.slice(-2).join('/')}`;
}
