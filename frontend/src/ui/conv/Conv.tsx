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
import type { ReactNode, Ref } from 'react';

/**
 * The scrollable conversation column — the focal point of every surface.
 * Holds {@link EventRow}s and friends. The host owns scroll-to-bottom via
 * the forwarded `scrollRef`.
 */
export function Conv({ children, scrollRef }: { children: ReactNode; scrollRef?: Ref<HTMLDivElement> }) {
  return (
    <div className="conv" ref={scrollRef}>
      {children}
    </div>
  );
}
