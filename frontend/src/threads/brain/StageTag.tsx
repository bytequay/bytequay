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
import type { StageType } from '../../types/brainView';
import { personaForStageType } from './stageMeta';

type Props = {
  /** Display label, e.g. "DevelopmentStage" or "ReviewStage #1". */
  label: string;
  /** Stage type drives the persona color; null renders the neutral chip. */
  stageType: StageType | null;
  /** Drill into the stage detail surface for this tag's stage. */
  onOpen?: () => void;
};

/**
 * Clickable persona-colored chip linking to a stage's detail surface.
 * The `↗` suffix is drawn by CSS (`.stage-tag::after`). Rendered as a
 * button with `role="link"` so it's keyboard-focusable and announced as
 * a navigation affordance.
 */
export function StageTag({ label, stageType, onOpen }: Props) {
  const persona = personaForStageType(stageType);
  return (
    <button
      type="button"
      role="link"
      className={`stage-tag ${persona}`}
      onClick={onOpen}
      title={`Open ${label}`}
    >
      {label}
    </button>
  );
}
