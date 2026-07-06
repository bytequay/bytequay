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
import type { LocalPR } from '../../types/localPr';

const STALE_MS = 5 * 60 * 1000;

function agoShort(ageMs: number): string {
  const s = Math.round(ageMs / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  return `${Math.round(m / 60)}h ago`;
}

/**
 * The shared sync-status chip every PR surface's header renders (U6):
 * `live` for a pure-local-phase PR (nothing to sync yet), a spinner while a
 * sync is in flight, `synced Xs ago`, or amber `stale · Nm` past 5 minutes
 * (e.g. the laptop slept). Click always forces an immediate refresh.
 */
export function SyncChip({
  pr, syncedAt, syncing, onRefresh,
}: {
  pr: LocalPR;
  syncedAt: number | null;
  syncing: boolean;
  onRefresh: () => void;
}) {
  const pureLocalPhase = pr.origin === 'task' && (pr.status === 'local-drafted' || pr.status === 'local-open');
  if (pureLocalPhase) {
    return <span className="pr-sync-chip live">● live — local phase</span>;
  }
  if (syncing) {
    return <span className="pr-sync-chip"><span className="spin" aria-hidden>↻</span> syncing…</span>;
  }
  if (syncedAt === null) {
    return <button type="button" className="pr-sync-chip" onClick={onRefresh}>↻ sync</button>;
  }
  const ageMs = Date.now() - syncedAt;
  const stale = ageMs > STALE_MS;
  return (
    <button type="button" className={stale ? 'pr-sync-chip stale' : 'pr-sync-chip'} onClick={onRefresh}>
      ↻ {stale ? `stale · ${Math.round(ageMs / 60000)}m` : `synced ${agoShort(ageMs)}`}
    </button>
  );
}
