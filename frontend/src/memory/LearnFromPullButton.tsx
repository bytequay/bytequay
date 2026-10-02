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
import Toast from '../Toast';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { Icon } from '../repository-context/icons';
import { plural } from '../repository-context/model';
import { MEMORY_CHANGED } from './model';
import '../css/memory.css';

/** Learn from this PR: one harvest run over a closed pull request's maintainer threads, saving rules to the workspace's Memory. */
export default function LearnFromPullButton({ workspaceId, repo, number }: { workspaceId: string; repo: string; number: number }) {
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const learn = async (): Promise<void> => {
    setBusy(true);
    try {
      const build = await window.bridge.getRepositoryContextBuild(workspaceId);
      const started = await window.bridge.startMemoryHarvest(workspaceId, { requestKey: crypto.randomUUID(), expectedBuildSequence: build.buildRequestSequence,
        pull: number, repo });
      window.dispatchEvent(new CustomEvent(MEMORY_CHANGED, { detail: workspaceId }));
      setToast({ id: Date.now(), text: started.started ? `Learning from #${number} · ${plural(started.threads, 'maintainer thread')} · follow it in Memory`
        : `#${number} has no new maintainer threads to learn from` });
    }
    catch (cause) {
      setToast({ id: Date.now(), text: remoteErrorMessage(cause) });
    }
    finally {
      setBusy(false);
    }
  };
  return <>
    <button type="button" className="pr-learn" disabled={busy} onClick={() => void learn()}
      title={`Starts 1 run · reads the maintainer threads of #${number} and saves rules to Memory`}>
      <Icon name="bolt" size={12} />Learn from this PR</button>
    {toast !== null && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
  </>;
}
