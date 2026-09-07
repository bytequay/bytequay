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
import { ConfirmDialog } from '../ConfirmDialog';
import type { WorkspaceDto } from '../types';

export default function UnwatchWorkspaceDialog({ workspace, busy, error, onConfirm, onCancel }: {
  workspace: WorkspaceDto;
  busy: boolean;
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <ConfirmDialog
      title={`Unwatch ${workspace.name}?`}
      confirmLabel={busy ? 'Unwatching…' : 'Unwatch'}
      busy={busy}
      onConfirm={() => { if (!busy) onConfirm(); }}
      onCancel={onCancel}
    >
      <p>This moves the workspace to Retired. Your local checkout, tasks, settings, and history will be kept.</p>
      <p>Open it from Retired to access saved tasks and files, or watch it again to rerun setup.</p>
      {error && <p className="ws-error" role="alert">{error}</p>}
    </ConfirmDialog>
  );
}
