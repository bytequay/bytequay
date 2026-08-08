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

/** Builds GitHub's "Quote reply" body — every line of {@code quoted}
 *  prefixed with "> ", a blank line, then whatever the composer already
 *  holds. Shared by the top-level comment box and the inline review-
 *  thread reply so both quote identically. */
export function buildQuotedReply(quoted: string, existing: string): string {
  const quote = quoted.split('\n').map(l => `> ${l}`).join('\n');
  const sep = existing.trim().length > 0 ? '\n\n' : '';
  return `${quote}\n\n${sep}${existing}`;
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
