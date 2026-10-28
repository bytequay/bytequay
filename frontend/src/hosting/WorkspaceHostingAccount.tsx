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
import { useEffect, useState } from 'react';
import type { WorkspaceDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

export function WorkspaceHostingAccount({ workspace }: { workspace: WorkspaceDto }) {
  const [accountId, setAccountId] = useState<number>();
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void window.bridge.getWorkspaceHosting(workspace.id).then(binding => { if (active) setAccountId(binding?.accountId); })
      .catch(failure => { if (active) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { active = false; };
  }, [workspace.id]);
  return <div className="setw-page">
    <h1>GitLab account</h1><p>{workspace.monitoredOwner}/{workspace.monitoredRepo}</p>
    <p className="setw-footnote">Choose the account used for this project.</p>
    <GitLabAccounts selected={accountId} server={workspace.hostingServer ?? undefined} onSelect={async account => {
      const binding = await window.bridge.setWorkspaceHosting(workspace.id, account.account.id);
      setAccountId(binding.accountId); setError(null);
    }} />
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}
