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
import type { IconKind } from './trailLayout';

/**
 * Minimal inline-SVG outline icons for the footprint pins. Inline (not an
 * icon font) so we add no dependency and so the glyph inherits the pin's
 * surface colour via {@code currentColor}.
 */
export function FootprintIcon({ kind, size = 14 }: { kind: IconKind; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 2,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  };
  switch (kind) {
    case 'kanban':
      return (
        <svg {...common}>
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <line x1="9" y1="4" x2="9" y2="20" />
          <line x1="15" y1="4" x2="15" y2="20" />
        </svg>
      );
    case 'pull-request':
      return (
        <svg {...common}>
          <circle cx="6" cy="6" r="2" />
          <circle cx="6" cy="18" r="2" />
          <line x1="6" y1="8" x2="6" y2="16" />
          <circle cx="18" cy="18" r="2" />
          <path d="M18 16v-5a3 3 0 0 0-3-3h-4" />
          <path d="M13 5l-2 3 2 3" />
        </svg>
      );
    case 'robot':
      return (
        <svg {...common}>
          <rect x="4" y="8" width="16" height="12" rx="2" />
          <line x1="12" y1="4" x2="12" y2="8" />
          <circle cx="9" cy="14" r="1" />
          <circle cx="15" cy="14" r="1" />
        </svg>
      );
    case 'message':
      return (
        <svg {...common}>
          <path d="M21 12a8 8 0 0 1-11.5 7.2L4 20l1.2-4.5A8 8 0 1 1 21 12z" />
        </svg>
      );
    case 'flag':
      return (
        <svg {...common}>
          <line x1="5" y1="21" x2="5" y2="3" />
          <path d="M5 4h12l-2.5 4 2.5 4H5" />
        </svg>
      );
    case 'map-pin':
      return (
        <svg {...common}>
          <path d="M12 21s7-6.2 7-11a7 7 0 0 0-14 0c0 4.8 7 11 7 11z" />
          <circle cx="12" cy="10" r="2.5" />
        </svg>
      );
  }
}
