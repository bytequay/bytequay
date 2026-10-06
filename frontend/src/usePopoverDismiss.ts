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
import { useEffect, useRef } from 'react';

/**
 * Closes a popover on an outside press or Escape. Escape listens on document so it runs before the window listener that
 * closes a zoomed or overlaid pull request, which then sees the event handled.
 *
 * @param withTrigger treat the popover's parent, which holds its toggle button, as inside, so the toggle can close it
 */
export function usePopoverDismiss(onClose: () => void, withTrigger = false) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const close = (event: PointerEvent) => {
      const boundary = withTrigger ? host.current?.parentElement : host.current;
      if (!boundary?.contains(event.target as Node)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      onClose();
    };
    window.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      window.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [onClose, withTrigger]);
  return host;
}

