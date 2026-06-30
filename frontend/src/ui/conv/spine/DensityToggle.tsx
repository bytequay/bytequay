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

/** Conversation density: Focused = your turns + headlines (work folded);
 *  Full = every round's internals expanded. */
export type Density = 'focused' | 'full';

/**
 * Layer-4 control: the density toggle — one segmented control spanning the
 * scannable skeleton (Focused, default) and the complete transcript (Full).
 */
export function DensityToggle({ value, onChange }: {
  value: Density;
  onChange: (next: Density) => void;
}) {
  return (
    <div className="sp-density" role="group" aria-label="Conversation density">
      <span className="sp-density__lbl">Density</span>
      <div className="sp-density__seg">
        <button type="button" className={value === 'focused' ? 'on' : ''} onClick={() => onChange('focused')}>Focused</button>
        <button type="button" className={value === 'full' ? 'on' : ''} onClick={() => onChange('full')}>Full</button>
      </div>
    </div>
  );
}
