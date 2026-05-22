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

type Props = {
  /** Navigate out of the shell to the full top-level threads list.
   *  Until we render that page inline (polish pass) the shell's
   *  Threads section punches out so the user sees the established
   *  filters / chrome they already know. */
  onLeaveShell?: () => void;
};

function WorkspaceThreadsPage({ onLeaveShell }: Props) {
  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Threads</h1>
          <div className="workspace-pageheader__meta">
            workspace threads — full list lives on the top-level Threads tab
          </div>
        </div>
        {onLeaveShell && (
          <button
            type="button"
            className="workspace-pageheader__action"
            onClick={onLeaveShell}
          >
            Open threads list →
          </button>
        )}
      </header>
      <div className="workspace-placeholder">
        Inlining the threads list inside the workspace shell is a polish
        pass — rendering it here would double up with the chrome that page
        already has. For now, jump to the full top-level Threads tab.
      </div>
    </>
  );
}

export default WorkspaceThreadsPage;
