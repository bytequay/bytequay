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

import React from 'react';

/**
 * The one disclosure arrow: a right-pointing chevron that turns down when what it guards is open.
 * Folds, rows and menu carets all draw this so the app has a single arrow, not three glyphs.
 */
export function Chevron({ open = false, size = 12, className = '' }: {
  open?: boolean;
  size?: number;
  className?: string;
}): React.JSX.Element {
  return (
    <svg
      className={`chev${open ? ' is-open' : ''}${className === '' ? '' : ` ${className}`}`}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden="true"
    >
      <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
