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
import { TrunkIcon } from '../primitives';

/**
 * The context chip shown once a workspace is open: the dark trunk-glyph
 * hero tile + name + "N repos · M threads" — the same identity mark as
 * the workspace main pane's header. Clicking it returns to the
 * workspace's own surface; switching workspaces happens on the
 * Workspaces landing page.
 */
export function WorkspaceSwitcher({ name, sub, onSwitch }: {
  name: string;
  sub: string;
  onSwitch?: () => void;
}) {
  return (
    <button type="button" className="ws-switcher" title={`Open ${name}`} onClick={onSwitch}>
      <span className="ws-hero-tile" aria-hidden><TrunkIcon size={16} /></span>
      <span className="ws-meta">
        <span className="ws-name">{name}</span>
        <span className="ws-sub">{sub}</span>
      </span>
    </button>
  );
}
