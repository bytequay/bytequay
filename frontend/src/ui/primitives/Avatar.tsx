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

/** Gradient hue for the avatar fill. */
export type AvatarHue = 'purple' | 'teal' | 'amber';

/**
 * Round initials avatar. Size is one of the three mockup footprints
 * (18 / 22 / 26 px); the font scales with it. Decorative by default
 * (`aria-hidden`) — pass `label` to expose it to assistive tech.
 */
export function Avatar({ initials, size = 22, hue = 'purple', label }: {
  initials: string;
  size?: 18 | 22 | 26;
  hue?: AvatarHue;
  label?: string;
}) {
  const fontSize = size <= 18 ? 9 : size <= 22 ? 10 : 11;
  return (
    <span
      className={`v3-avatar v3-avatar--${hue}`}
      style={{ width: size, height: size, fontSize }}
      aria-hidden={label === undefined}
      aria-label={label}
      role={label !== undefined ? 'img' : undefined}
    >
      {initials}
    </span>
  );
}
