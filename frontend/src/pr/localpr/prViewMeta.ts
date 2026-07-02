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
import type {
  LocalPRStatus,
  LocalPRTimelineEventType,
} from '../../types/localPr';

/** Status-badge presentation — CSS class (matches `.pr-status-badge.<cls>`),
 *  the label copy, and whether the 🔒 lock glyph prefixes it. Mirrors the
 *  locked mockup's badge color map (local-drafted purple, local-open amber,
 *  remote-drafted gray, remote-open green, merged purple). */
export interface StatusBadgeMeta {
  cls: string;
  label: string;
  lock: boolean;
  /** local-drafted animates a pulsing dot rather than showing the lock. */
  pulsing: boolean;
}

const STATUS_BADGES: Record<LocalPRStatus, StatusBadgeMeta> = {
  'local-drafted': { cls: 'local-drafted', label: 'local · drafting', lock: false, pulsing: true },
  'local-open': { cls: 'local-open', label: 'local · open · awaiting your review', lock: true, pulsing: false },
  'remote-drafted': { cls: 'remote-drafted', label: 'remote · draft', lock: false, pulsing: false },
  'remote-open': { cls: 'remote-open', label: 'remote · open', lock: false, pulsing: false },
  merged: { cls: 'merged', label: 'merged', lock: false, pulsing: false },
  closed: { cls: 'remote-drafted', label: 'closed', lock: false, pulsing: false },
};

export function statusBadgeMeta(status: LocalPRStatus): StatusBadgeMeta {
  return STATUS_BADGES[status];
}

/** Timeline-icon presentation — CSS type class (`.tl-icon.<cls>`) + glyph. */
export interface TimelineIconMeta {
  cls: string;
  glyph: string;
}

const TIMELINE_ICONS: Record<LocalPRTimelineEventType, TimelineIconMeta> = {
  commit: { cls: 'commit', glyph: '◆' },
  ci: { cls: 'ci', glyph: '✓' },
  amend: { cls: 'amend', glyph: '↻' },
  branch: { cls: 'branch', glyph: '⎇' },
  status: { cls: 'status', glyph: '◐' },
  review: { cls: 'review', glyph: '✎' },
  comment: { cls: 'comment', glyph: '💬' },
  'follow-up': { cls: 'review', glyph: '⚠' },
};

export function timelineIconMeta(eventType: LocalPRTimelineEventType): TimelineIconMeta {
  return TIMELINE_ICONS[eventType];
}

/** A `ci` event whose payload status is a failure paints the icon red. */
export function isFailedCiPayload(payload: Record<string, unknown> | null): boolean {
  return payload !== null && payload['status'] === 'failed';
}

/** Compact relative label from an epoch-ms timestamp (the timeline `.ts`). */
export function agoLabel(ms: number, now: number = Date.now()): string {
  const deltaSec = Math.round((now - ms) / 1000);
  if (deltaSec < 60) return 'just now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`;
  return `${Math.round(deltaSec / 86400)}d ago`;
}
