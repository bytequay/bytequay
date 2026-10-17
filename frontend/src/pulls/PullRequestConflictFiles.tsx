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
import type { PullRequestConflictsDto } from '../types';
import { remoteErrorMessage } from '../remoteError';

export default function PullRequestConflictFiles({ repo, number }: { repo: string; number: number }) {
  const [result, setResult] = useState<PullRequestConflictsDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setResult(null);
    setError(null);
    void window.bridge.fetchPrConflicts(repo, number)
      .then(value => { if (!cancelled) setResult(value); })
      .catch(failure => { if (!cancelled) setError(remoteErrorMessage(failure)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [repo, number, attempt]);

  return <section className="pr-conflict-files" aria-label="Conflicted files" aria-busy={loading}>
    <div className="pr-conflict-files__header">
      <h3>Conflicted files{result !== null && ` (${result.files.length})`}</h3>
      <button type="button" disabled={loading} onClick={() => setAttempt(value => value + 1)}>
        {error ? 'Retry' : 'Refresh files'}
      </button>
    </div>
    {loading && <p role="status">Checking conflicted files…</p>}
    {error && <p role="status">{error}</p>}
    {result !== null && <>
      <p>Compared with <code>{result.baseRef}</code>.</p>
      {result.files.length > 0
        ? <ul>{result.files.map(path => <li key={path}><code>{path}</code></li>)}</ul>
        : <p>No conflicted files found. Refresh the pull request to update its merge status.</p>}
    </>}
  </section>;
}
