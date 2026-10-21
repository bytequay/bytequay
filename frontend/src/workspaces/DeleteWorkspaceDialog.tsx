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
import { useAsyncAction } from '../useAsyncAction';
import { useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import type { WorkspaceDto } from '../types';

export default function DeleteWorkspaceDialog({ workspace, onDeleted, onCancel }: {
  workspace: WorkspaceDto;
  onDeleted: () => void;
  onCancel: () => void;
}) {
  const [finalConfirmation, setFinalConfirmation] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const { busy, error, run } = useAsyncAction();
  const repositoryName = `${workspace.selectedOwner}/${workspace.selectedRepo}`;

  const remove = async () => {
    if (busy || !finalConfirmation || confirmation !== repositoryName) return;
    return run(async () => {
      await window.bridge.deleteWorkspace(workspace.id, true, confirmation);
      try {
        window.localStorage.removeItem(`bq.home.hiddenTasks.${workspace.id}`);
      }
      catch { /* Storage access must not turn a successful deletion into a reported failure. */ }
      onDeleted();
    });
  };

  return <DeleteWorkspaceConfirmation
    repositoryName={repositoryName}
    finalConfirmation={finalConfirmation}
    confirmation={confirmation}
    busy={busy}
    error={error}
    onContinue={() => setFinalConfirmation(true)}
    onConfirmationChange={setConfirmation}
    onDelete={() => { void remove(); }}
    onCancel={onCancel}
  />;
}

export function DeleteWorkspaceConfirmation({
  repositoryName, finalConfirmation, confirmation, busy, error, onContinue, onConfirmationChange, onDelete, onCancel,
}: {
  repositoryName: string;
  finalConfirmation: boolean;
  confirmation: string;
  busy: boolean;
  error: string | null;
  onContinue: () => void;
  onConfirmationChange: (value: string) => void;
  onDelete: () => void;
  onCancel: () => void;
}) {
  return (
    <ConfirmDialog
      key={finalConfirmation ? 'final' : 'disclosure'}
      title={finalConfirmation ? `Permanently delete ${repositoryName}?` : `Delete ${repositoryName} from ByteQuay?`}
      confirmLabel={busy ? 'Deleting…' : finalConfirmation ? 'Delete local data' : 'Continue'}
      confirmDisabled={finalConfirmation && confirmation !== repositoryName}
      busy={busy}
      destructive={finalConfirmation}
      onConfirm={() => {
        if (busy) return;
        if (!finalConfirmation) onContinue();
        else if (confirmation === repositoryName) onDelete();
      }}
      onCancel={onCancel}
    >
      {finalConfirmation ? (
        <>
          <p>This permanently deletes the local checkout, worktrees, task history, and workspace settings. Unsaved and unpushed work will be lost.</p>
          <label className="wss-delete-confirmation">
            <span>Type <strong>{repositoryName}</strong> to confirm.</span>
            <input
              type="text"
              value={confirmation}
              disabled={busy}
              autoComplete="off"
              spellCheck={false}
              onChange={event => onConfirmationChange(event.target.value)}
            />
          </label>
        </>
      ) : (
        <>
          <p>Delete all ByteQuay local data for this workspace, including its checkout, worktrees, task history, and settings.</p>
          <p>To hide it and keep your local data, choose Unwatch instead.</p>
        </>
      )}
      <p>Your GitHub repository and fork will be kept.</p>
      {error && <p className="ws-error" role="alert">{error}</p>}
    </ConfirmDialog>
  );
}
