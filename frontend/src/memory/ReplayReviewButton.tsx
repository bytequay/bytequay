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
import { useAgentAvailability } from '../agents/availability';
import { useState } from 'react';
import { History } from 'lucide-react';
import Toast from '../Toast';
import { remoteErrorMessage } from '../remoteError';
import '../css/memory.css';

/** Replay review: Agent Review twice on a merged pull request at the head its maintainers first reviewed, with memory and without. Nothing is posted. */
export default function ReplayReviewButton({ workspaceId, repo, number }: { workspaceId: string; repo: string; number: number }) {
  const availability = useAgentAvailability(workspaceId);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const replay = async (): Promise<void> => {
    setBusy(true);
    try {
      const started = await window.bridge.startReviewReplay(workspaceId, { repo, number });
      setToast({ id: Date.now(), text: `Replaying #${number} at ${started.head.slice(0, 7)} · 2 reviews · compare them in Memory` });
    }
    catch (cause) {
      setToast({ id: Date.now(), text: remoteErrorMessage(cause) });
    }
    finally {
      setBusy(false);
    }
  };
  if (!availability.review) return null;
  return <>
    <button type="button" className="pr-learn" disabled={busy} onClick={() => void replay()}
      title={`Starts 2 reviews of #${number} at the head its maintainers first reviewed, with memory and without · nothing is posted`}>
      <History size={12} aria-hidden="true" />Replay review</button>
    {toast !== null && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
  </>;
}
