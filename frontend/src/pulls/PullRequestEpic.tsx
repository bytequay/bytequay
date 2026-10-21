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
import { ChevronRight, Flag } from 'lucide-react';
import { useEffect, useState } from 'react';
import { epicLink, findWorkItem } from '../agents/WorkItemPrompt';
import { zoomReference } from '../githubReference';
import type { UnifiedWorkItem, WorkItemDto } from '../workitems/contracts';
import { progressWidths, summarize, viewOf } from '../workitems/model';
import { epicWork } from '../workspaces/epics';
import '../css/epic-view.css';

export type PullRequestEpic = { workspaceId: string; epic: UnifiedWorkItem; label: string; work: WorkItemDto[] };

/** The Epic whose work this pull request delivers; null when it has none. */
export function usePullRequestEpic(workspaceId: string | undefined, repo: string, number: number): PullRequestEpic | null {
  const key = `${workspaceId ?? ''}:${repo}#${number}`;
  const [found, setFound] = useState<{ key: string; value: PullRequestEpic } | null>(null);
  useEffect(() => {
    if (workspaceId === undefined) return;
    let active = true;
    void Promise.resolve().then(async () => {
      const items = await window.bridge.listUnifiedWorkItems(workspaceId);
      const pull = `${repo.toLowerCase()}#${number}`;
      // Epic work names its pull request once ByteQuay follows it; before that, and only then, the task that opened it tells.
      const delivering = items.find(item => item.work?.epicId && item.work.links.some(link => link.kind === 'PULL_REQUEST' && link.key === pull));
      const task = delivering === undefined ? await window.bridge.findPullRequestTask(workspaceId, repo, number) : null;
      const source = delivering !== undefined ? findWorkItem(items, { itemId: delivering.id }) : task === null ? null : findWorkItem(items, { taskId: task.taskId });
      const epicId = source?.item.work?.epicId;
      if (!active || !source?.epic || !epicId) return;
      setFound({ key, value: { workspaceId, epic: source.epic, label: [source.epic.reference?.label, source.epic.title].filter(Boolean).join(' · '),
        work: epicWork(items.flatMap(item => item.work === null ? [] : [item.work]), epicId) } });
    }).catch(() => {});
    return () => { active = false; };
  }, [key, workspaceId, repo, number]);
  return found?.key === key ? found.value : null;
}

function openEpic(value: PullRequestEpic): void {
  const link = epicLink(value.workspaceId, value.epic);
  if (link !== null) zoomReference(link.target);
}

/** The Epic named in the branch row, where a narrow page has no room for the card. */
export function PullRequestEpicLink({ value }: { value: PullRequestEpic }) {
  return <button type="button" className="pr-epic-link" aria-label={`Epic ${value.label}`} title={`Epic: ${value.label}`} onClick={() => openEpic(value)}>
    <Flag size={16} aria-hidden="true" /><span>{value.label}</span>
  </button>;
}

/** The Epic beside a wide page: its progress, and the work ready to start next folded below. */
export function PullRequestEpicCard({ value }: { value: PullRequestEpic }) {
  const summary = summarize({ items: value.work }, new Set());
  const widths = progressWidths(summary);
  const next = value.work.filter(item => viewOf(item, value.work) === 'next');
  return <aside className="pr-epic-card" aria-label="Epic">
    <div className="pr-epic-card__kicker"><Flag size={13} aria-hidden="true" />Epic</div>
    <button type="button" className="pr-epic-card__title" title={`Open Epic: ${value.label}`} onClick={() => openEpic(value)}>{value.label}</button>
    {summary.total > 0 && <>
      <div className="epic-progress" aria-hidden="true"><span className="is-done" style={{ width: widths.finished }} /><span className="is-working" style={{ width: widths.working }} /></div>
      <div className="pr-epic-card__counts">{summary.finished} of {summary.total} done{summary.working > 0 ? ` · ${summary.working} in progress` : ''}</div>
    </>}
    {next.length > 0 && <details className="pr-epic-card__next">
      <summary><ChevronRight size={13} aria-hidden="true" />Next tasks<span>{next.length}</span></summary>
      <ul>{next.map(item => <li key={item.id}>
        <button type="button" title={item.title} onClick={() => zoomReference({ kind: 'work', workspaceId: value.workspaceId, itemId: item.id })}>{item.title}</button>
      </li>)}</ul>
    </details>}
  </aside>;
}
