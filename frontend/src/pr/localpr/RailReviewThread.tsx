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
import type { ReactNode } from 'react';

/**
 * Gives a `ReviewThreadCard` the same rail-context treatment
 * `pr-bubble-row`/`pr-person-event` already get inside `.pr-tl-rail`
 * (`v3-conv.css`) — `position: relative; z-index: 1` so it paints over
 * `.pr-tl-rail::before`'s vertical line, plus a left offset past the
 * line's `left: 15px` so the card starts clear of it. `ReviewThreadCard`
 * itself is shared with the diff view's own `.prc-timeline__entries` rail
 * (`pr-detail.css`, a wider 80px gutter) — that file's rules only apply
 * when it's a direct child of *that* container, so a rendering inside
 * `.pr-tl-rail` needs this wrapper instead of relying on either file alone.
 */
export function RailReviewThread({ children }: { children: ReactNode }) {
  return <div className="rail-thread">{children}</div>;
}
