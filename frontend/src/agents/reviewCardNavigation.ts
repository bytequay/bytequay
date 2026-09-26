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

import { useEffect, type RefObject } from 'react';
import type { ReviewCardFocus, ReviewRequestTarget } from './ReviewRequestCard';

/** Search only the destination pane: the transcript may contain another copy of this discussion. */
export function focusReviewCard(container: HTMLElement, target: ReviewRequestTarget): boolean {
  const element = Array.from(container.querySelectorAll<HTMLElement>('[data-review-card], [data-local-review-thread], [data-finding-id]'))
    .find(item => target.kind === 'card' ? item.dataset.reviewCard === target.cardId
      : target.kind === 'local' ? item.dataset.localReviewThread === target.threadId
        : item.dataset.findingId === target.findingId);
  if (element === undefined) return false;
  for (let parent: HTMLElement | null = element; parent !== null && container.contains(parent); parent = parent.parentElement) {
    if (parent instanceof HTMLDetailsElement) parent.open = true;
  }
  const details = element.querySelector('details');
  if (details !== null) details.open = true;
  element.tabIndex = -1;
  element.focus({ preventScroll: true });
  // Center the heading of long cards immediately, including when reopening a kept pane mid-scroll.
  const heading = element.querySelector('summary') ?? element;
  heading.scrollIntoView({ block: 'center', behavior: 'instant' });
  return true;
}

export function useReviewCardFocus(containerRef: RefObject<HTMLElement | null>, focus: ReviewCardFocus | null | undefined, enabled = true): void {
  useEffect(() => {
    const container = containerRef.current;
    if (!enabled || focus == null || container === null) return undefined;
    let focused: Element | null = null;
    const apply = () => {
      if (focused !== null && container.contains(focused)) return;
      if (focusReviewCard(container, focus.target)) focused = document.activeElement;
    };
    apply();
    // A loading report can mount the target later, or move it out of Earlier discussions.
    const observer = new MutationObserver(apply);
    observer.observe(container, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [containerRef, focus, enabled]);
}
