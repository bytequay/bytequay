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
import { useState } from 'react';
import type { HostingConnectionDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

export default function GitLabSettings() {
  const [account, setAccount] = useState<HostingConnectionDto | null>(null);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState<string | null>(null);
  return <div className="setw-page"><h1>GitLab</h1>
    <p className="setw-footnote">Connect GitLab.com or a private server. Choose an account when creating a workspace.</p>
    <GitLabAccounts key={revision} selected={account?.account.id} onSelect={setAccount} />
    {account?.connected && <button type="button" className="setw-btn is-danger" onClick={() => {
      void window.bridge.disconnectHostingAccount(account.account.id).then(() => { setAccount(null); setRevision(value => value + 1); setError(null); })
        .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
    }}>Disconnect {account.account.login}</button>}
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}
