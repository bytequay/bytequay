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

/** Phase 6 commit 1 placeholder. The workspace-scoped Settings page
 *  (Repositories / AI defaults / Behavior / Danger zone) lands in
 *  commit 4 — most pieces already exist in the app-level
 *  SettingsShell and will move/restyle rather than build from
 *  scratch. */
function WorkspaceSettingsPage() {
  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Settings</h1>
          <div className="workspace-pageheader__meta">
            workspace-scoped settings · placeholder until commit 4
          </div>
        </div>
      </header>
      <div className="workspace-placeholder">
        Workspace settings land in the fourth Phase-6 commit — Repositories,
        AI defaults, Behavior toggles, and a Danger zone (archive / delete).
        Most surfaces already exist under the app-level Settings; the commit
        moves and restyles them rather than starting from scratch.
      </div>
    </>
  );
}

export default WorkspaceSettingsPage;
