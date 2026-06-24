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
 * 28px square icon button used throughout the top bar and panes. Carries
 * an optional `active` (pressed) state. A `title` doubles as the
 * accessible name unless an explicit `ariaLabel` is given.
 */
export function IconBtn({ children, onClick, active = false, title, ariaLabel, disabled = false }: {
  children: ReactNode;
  onClick?: () => void;
  active?: boolean;
  title?: string;
  ariaLabel?: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className={active ? 'v3-iconbtn v3-iconbtn--active' : 'v3-iconbtn'}
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={ariaLabel ?? title}
      aria-pressed={active}
    >
      {children}
    </button>
  );
}
