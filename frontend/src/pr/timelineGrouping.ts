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
import type { ActivityItemDto, ReviewThreadDto } from '../types';

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
