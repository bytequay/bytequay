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
import type { LocalPR } from '../../types/localPr';

/** Who an actor is, relative to this PR — drives bubble tinting + badges
 *  (U15): `agent` (claude-code / brain) gets the purple tint + Agent badge,
 *  `author` (matches `pr.author` on an external PR) gets the blue tint +
 *  Author badge, `you` is the local user (no tint), anyone else is a
 *  third-party reviewer/commenter (no tint, no badge — we don't have their
 *  real GitHub role association synced). */
export type ActorRole = 'agent' | 'author' | 'you' | 'other';

const AGENT_ACTORS = new Set(['claude-code', 'brain']);

export function actorRole(actor: string, pr: LocalPR): ActorRole {
  if (AGENT_ACTORS.has(actor)) return 'agent';
  if (actor === 'you') return 'you';
  if (pr.origin === 'external' && pr.author !== null && actor === pr.author) return 'author';
  return 'other';
}

/** Short avatar-glyph label for an actor (2 letters max, matching the
 *  mockup's circular avatar chips). */
export function avatarLabel(actor: string): string {
  if (actor === 'claude-code') return 'CC';
  if (actor === 'brain') return 'B';
  if (actor === 'you') return 'Y';
  const handle = actor.startsWith('@') ? actor.slice(1) : actor;
  return handle.slice(0, 2).toUpperCase();
}

/** Display name for an actor row — "you" capitalizes, a synced GitHub
 *  `@handle` drops the `@` (the badge/tint already says who they are). */
export function displayName(actor: string): string {
  if (actor === 'you') return 'You';
  return actor.startsWith('@') ? actor.slice(1) : actor;
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
