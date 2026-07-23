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
import type { LocalPRTimelineEvent } from '../../types/localPr';

function stringList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((entry): entry is string => typeof entry === 'string')
    : [];
}

/** Threads whose latest local-review transition is a submission. Reopening a
 * root or adding a new user clarification makes it pending again until an
 * explicit later submission. */
export function localReviewSubmissionTransitions(
  events: LocalPRTimelineEvent[],
): Map<string, { submitted: boolean; eventId: string }> {
  const transitions = new Map<string, { submitted: boolean; eventId: string }>();
  events
    .map((event, index) => ({ event, index }))
    .sort((left, right) => left.event.createdAt - right.event.createdAt || left.index - right.index)
    .forEach(({ event }) => {
      if (event.eventType !== 'review') return;
      const reviewEvent = event.payload?.['reviewEvent'];
      if (reviewEvent === 'submitted') {
        for (const id of stringList(event.payload?.['commentIds'])) {
          transitions.set(id, { submitted: true, eventId: event.id });
        }
        const bodyCommentId = event.payload?.['bodyCommentId'];
        if (typeof bodyCommentId === 'string') {
          transitions.set(bodyCommentId, { submitted: true, eventId: event.id });
        }
      }
      else if (reviewEvent === 'reopened' || reviewEvent === 'updated') {
        const commentId = event.payload?.['commentId'];
        if (typeof commentId === 'string') {
          transitions.set(commentId, { submitted: false, eventId: event.id });
        }
      }
    });
  return transitions;
}

export function activelySubmittedCommentIds(events: LocalPRTimelineEvent[]): Set<string> {
  return new Set([...localReviewSubmissionTransitions(events)]
    .filter(([, state]) => state.submitted)
    .map(([id]) => id));
}
