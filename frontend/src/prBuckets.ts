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
import type { HandledAction } from './types';

export type PrLike = {
  handledAction: HandledAction | null;
  snoozedUntil: string | null;
  reviewedAt: string | null;
  updatedAt: string | null;
};

export const RESURFACE_GRACE_MS = 60 * 60 * 1000;

export type Bucket = 'inbox' | 'snoozed' | 'handled';

export function isHandled(pr: PrLike): boolean {
  return pr.handledAction === 'MERGED'
    || pr.handledAction === 'DISMISSED'
    || pr.handledAction === 'MANUAL';
}

export function isSnoozed(pr: PrLike, now: number = Date.now()): boolean {
  return pr.snoozedUntil !== null && new Date(pr.snoozedUntil).getTime() > now;
}

export function bucketize(pr: PrLike, now: number = Date.now()): Bucket {
  if (isSnoozed(pr, now)) return 'snoozed';
  return isHandled(pr) ? 'handled' : 'inbox';
}

export function isResurfaced(pr: PrLike): boolean {
  if (pr.reviewedAt === null || isHandled(pr) || pr.handledAction === 'APPROVED') return false;
  const reviewedMs = new Date(pr.reviewedAt).getTime();
  const updatedMs = pr.updatedAt === null ? 0 : new Date(pr.updatedAt).getTime();
  return updatedMs > reviewedMs + RESURFACE_GRACE_MS;
}
