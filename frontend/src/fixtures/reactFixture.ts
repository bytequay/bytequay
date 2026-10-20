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

import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';

/** Each scenario owns its host and root; render timing remains under the caller's control. */
export function createFixtureRoot(tag: 'div' | 'main' = 'div') {
  const host = document.createElement(tag);
  document.body.append(host);
  const root = createRoot(host);
  return {
    host,
    root,
    dispose: () => {
      flushSync(() => root.unmount());
      host.remove();
    },
  };
}
