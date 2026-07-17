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
    <div
      className="ws-switcher"
      role="button"
      tabIndex={0}
      title={`Open ${name}`}
      onClick={onSwitch}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSwitch?.();
        }
      }}
    >
      <span className="ws-switcher-avatar" aria-hidden>
        {(name.split('/').at(-1)?.[0] ?? name[0] ?? '?').toUpperCase()}
      </span>
      <span className="ws-meta">
        <span className="ws-name">{name.split('/').at(-1) ?? name}</span>
        <span className="ws-sub">{sub}</span>
      </span>
      <svg className="ws-switcher-chevron" width="13" height="13" viewBox="0 0 24 24"
        fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
        strokeLinejoin="round" aria-hidden>
        <path d="m7 9 5-5 5 5M7 15l5 5 5-5" />
      </svg>
    </div>
  );
}
