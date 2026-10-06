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
import type { IdeEvent } from '../contracts';

/** Parent-renderer interaction fixture; the separate native runtime checks cover editor contents. */
export function installDesktopFixture() {
  const previous = window.ide;
  let listener: (event: IdeEvent) => void = () => {};
  let opens = 0;
  let visible = false;
  window.ide = {
    open: async () => { opens++; return 'fixture-editor'; },
    present: async (_id, bounds) => { visible = bounds !== null; }, update: async () => {}, reply: () => {},
    close: async () => listener({ id: 'fixture-editor', dirty: 0, busy: false, closed: true }), discardSourceDrafts: async () => {},
    onRequest: () => () => {}, onEvent: receive => { listener = receive; return () => { listener = () => {}; }; },
  };
  return { get opens() { return opens; }, get visible() { return visible; },
    report: (dirty: number) => listener({ id: 'fixture-editor', dirty, busy: false }), dispose: () => { window.ide = previous; } };
}
