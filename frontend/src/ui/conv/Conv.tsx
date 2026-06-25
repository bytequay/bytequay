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
import type { ReactNode, Ref } from 'react';

/** How close to the bottom (px) still counts as "following" — within this
 *  the view sticks to new content; past it the user is reading history and
 *  we leave their scroll alone. */
const STICK_THRESHOLD_PX = 80;

/**
 * The scrollable conversation column — the focal point of every surface
 * (trunk, task brain, stage detail). New content auto-scrolls into view so
 * a reply or streamed token never lands below the fold, but only while the
 * user is already at the bottom: scroll up to read history and it stays put
 * until you return to the bottom. Shared by all conversation windows, so
 * the behaviour is consistent everywhere.
 */
export function Conv({ children, scrollRef }: { children: ReactNode; scrollRef?: Ref<HTMLDivElement> }) {
  const elRef = useRef<HTMLDivElement | null>(null);
  const stick = useRef(true);

  // After every content change, pin to the bottom when the user is following.
  useEffect(() => {
    const el = elRef.current;
    if (el !== null && stick.current) {
      el.scrollTop = el.scrollHeight;
    }
  });

  const onScroll = () => {
    const el = elRef.current;
    if (el === null) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD_PX;
  };

  const assignRef = (node: HTMLDivElement | null) => {
    elRef.current = node;
    if (typeof scrollRef === 'function') {
      scrollRef(node);
    }
    else if (scrollRef !== null && scrollRef !== undefined) {
      (scrollRef as { current: HTMLDivElement | null }).current = node;
    }
  };

  return (
    <div className="conv" ref={assignRef} onScroll={onScroll}>
      {children}
    </div>
  );
}
