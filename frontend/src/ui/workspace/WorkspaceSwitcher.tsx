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
import { Logo } from '../primitives';
import type { LogoColor } from '../primitives';

/**
 * The drill-in context chip shown once a workspace is open: its logo +
 * name + "N repos · M threads", with a ▾ for lateral quick-switching to
 * another workspace.
 */
export function WorkspaceSwitcher({ initials, color, name, sub, onSwitch }: {
  initials: string;
  color: LogoColor;
  name: string;
  sub: string;
  onSwitch?: () => void;
}) {
  return (
    <button type="button" className="ws-switcher" title="Switch workspace" onClick={onSwitch}>
      <Logo initials={initials} color={color} />
      <span className="ws-meta">
        <span className="ws-name">{name}</span>
        <span className="ws-sub">{sub}</span>
      </span>
      <span className="chev" aria-hidden>▾</span>
    </button>
  );
}
