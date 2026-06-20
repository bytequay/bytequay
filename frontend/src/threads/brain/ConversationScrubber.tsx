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
import type { ScrubberDash } from '../../types/brainView';

type Props = {
  /** `left` indexes stage summary events (amber active dash); `right`
   *  indexes the user's own messages (teal active dash). The side also
   *  decides which way the hover tooltip pops. */
  position: 'left' | 'right';
  dashes: ScrubberDash[];
  /** Jump the feed to a row by its anchor id. */
  onJumpTo: (rowId: string) => void;
};

/**
 * A floating minimal pill scrubber on one edge of the brain feed. Each
 * dash anchors a row; hovering reveals its label, clicking scrolls the
 * feed to it. Absolutely positioned by the CSS (`.conv-scrub-wrap`).
 */
export function ConversationScrubber({ position, dashes, onJumpTo }: Props) {
  const variant = position === 'left' ? 'stages' : 'you-msgs';
  const groupLabel = position === 'left'
    ? 'Stage summary events · click a dash to jump'
    : 'Your messages · click a dash to jump';
  return (
    <div className={`conv-scrub-wrap ${position}`}>
      <div className={`conv-scrub ${variant}`} role="group" aria-label={groupLabel}>
        {dashes.map(dash => (
          <button
            key={dash.id}
            type="button"
            className={`dash${dash.active ? ' active' : ''}`}
            data-label={dash.label}
            aria-label={dash.label}
            title={dash.label}
            onClick={() => onJumpTo(dash.id)}
          />
        ))}
      </div>
    </div>
  );
}
