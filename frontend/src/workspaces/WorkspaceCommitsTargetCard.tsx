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
import { useEffect, useId, useState } from 'react';
import type { WorkspaceCommitsTargetDto, WorkspaceDto } from '../types';

export default function WorkspaceCommitsTargetCard({ workspace, target, onChanged }: {
  workspace: WorkspaceDto;
  target: WorkspaceCommitsTargetDto;
  onChanged: (target: WorkspaceCommitsTargetDto) => void;
}) {
  const [branch, setBranch] = useState(target.branch);
  const [branches, setBranches] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const suggestionsId = useId();
  const automaticBranch = target.remote === 'upstream'
    ? workspace.upstreamDefaultBranch ?? workspace.defaultBranch
    : workspace.defaultBranch;
  const repository = target.remote === 'upstream'
    ? `${workspace.upstreamOwner}/${workspace.upstreamRepo}`
    : `${workspace.selectedOwner}/${workspace.selectedRepo}`;

  useEffect(() => { setBranch(target.branch); }, [target.branch, target.remote]);
  useEffect(() => {
    let cancelled = false;
    setBranches([]);
    window.bridge.gitRemoteBranches(workspace.id, target.remote)
      .then(value => { if (!cancelled) setBranches(value); })
      .catch(() => { /* A branch can be entered before the checkout exists or while offline. */ });
    return () => { cancelled = true; };
  }, [workspace.id, target.remote]);

  const save = async (reset: boolean) => {
    if (busy || (reset ? !target.overridden : !branch.trim() || (target.overridden && branch.trim() === target.branch))) return;
    setBusy(true);
    setError(null);
    try {
      const updated = reset
        ? await window.bridge.clearWorkspaceCommitsTarget(workspace.id)
        : await window.bridge.setWorkspaceCommitsTarget(workspace.id, branch.trim());
      onChanged(updated);
      setBranch(updated.branch);
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">Commits default target branch</span>
        <span className={`sets-badge ${target.overridden ? 'wss-override' : 'is-neutral'}`}>
          {target.overridden ? 'WORKSPACE OVERRIDE' : 'AUTOMATIC'}
        </span>
      </div>
      <div className="sets-card-note wss-commits-description">
        Opening Commits uses this branch. Cherry-picks use it as their default destination.
        The repository follows your pull-request routing: <strong>{repository}</strong>.
        {' '}Enter a branch name such as master or release/479; the remote is selected automatically.
      </div>
      <form className="sets-row wss-commits-target" onSubmit={event => { event.preventDefault(); void save(false); }}>
        <label className="sets-row-main">
          <span className="wss-label">Target branch · <span className="sets-mono">{target.remote}</span></span>
          <input
            className="wss-input sets-mono"
            aria-label="Commits default target branch"
            value={branch}
            list={suggestionsId}
            placeholder={automaticBranch}
            maxLength={512}
            disabled={busy}
            onChange={event => setBranch(event.target.value)}
          />
          <datalist id={suggestionsId}>
            {branches.map(name => <option key={name} value={name} />)}
          </datalist>
        </label>
        <button className="sets-btn-primary" type="submit" disabled={busy || !branch.trim() || (target.overridden && branch.trim() === target.branch)}>
          {busy ? 'Saving…' : 'Save'}
        </button>
        {target.overridden && <button className="sets-btn" type="button" disabled={busy} onClick={() => { void save(true); }}>
          Reset to automatic
        </button>}
      </form>
      <div className="sets-card-note">Automatic target: <span className="sets-mono">{target.remote}/{automaticBranch}</span></div>
      {error && <div className="sets-error" role="alert">{error}</div>}
    </section>
  );
}
