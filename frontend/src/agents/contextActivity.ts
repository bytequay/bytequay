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

import type { DevelopmentContextActivityDto } from '../types';

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid repository note activity');
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid repository note activity');
  return value;
}

function count(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid repository note activity');
  return value;
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid repository note activity');
  return value;
}

function list(value: unknown, max: number): unknown[] {
  if (!Array.isArray(value) || value.length > max) throw new Error('Invalid repository note activity');
  return value;
}

function strings(value: unknown, max: number): string[] {
  return list(value, max).map(text);
}

/** Whitelists the small receipt projection; saved note bodies never cross the renderer bridge. */
export function parseDevelopmentContextActivity(value: unknown): DevelopmentContextActivityDto {
  const source = object(value);
  return {
    cards: list(source.cards, 100).map(raw => {
      const card = object(raw);
      const kind = text(card.kind);
      const delivery = text(card.delivery);
      if (kind !== 'OFFER' && kind !== 'SEARCH' && kind !== 'READ') throw new Error('Invalid repository note activity');
      if (delivery !== 'PREPARED' && delivery !== 'CONFIRMED' && delivery !== 'UNCERTAIN' && delivery !== 'UNKNOWN') {
        throw new Error('Invalid repository note activity');
      }
      return {
        reference: text(card.reference), turnId: text(card.turnId), kind,
        createdAt: text(card.createdAt), notebookRevision: count(card.notebookRevision), delivery,
        bytes: count(card.bytes), query: text(card.query), paths: strings(card.paths, 20),
        entries: list(card.entries, 100).map(rawEntry => {
          const entry = object(rawEntry);
          return { id: text(entry.id), version: count(entry.version), kind: text(entry.kind),
            scopeId: text(entry.scopeId), title: text(entry.title), matchReasons: strings(entry.matchReasons, 20),
            included: flag(entry.included) };
        }),
        omittedCount: count(card.omittedCount),
        scopeStates: list(card.scopeStates, 10).map(rawScope => {
          const scope = object(rawScope);
          return { scopeId: text(scope.scopeId), preparation: text(scope.preparation), missingKnowledge: flag(scope.missingKnowledge) };
        }),
        warnings: strings(card.warnings, 20),
      };
    }),
    hasMore: flag(source.hasMore), usageTruncated: flag(source.usageTruncated),
  };
}
