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
import { shiftDay } from '../recent/model';
import type { DoneItemDto, PullRequestDto } from '../types';
import { dayText } from './today';
import type { WorkItem, WorkPr } from './work';

/** The compact reference My work's cards use: "trino#42". */
function reference(repo: string, number: number): string {
  return `${repo.slice(repo.indexOf('/') + 1)}#${number}`;
}

function doneLine(item: DoneItemDto, verb = ''): string {
  const ref = item.repo === null || item.number === null ? null : reference(item.repo, item.number);
  return `* ${[verb, item.title, ref].filter(Boolean).join(' ')}`;
}

/** What a live pull request is busy with, as the line under it; nothing while it simply waits on review. */
function progressNote(row: PullRequestDto | null): string | null {
  if (row === null) return null;
  if (row.ciStatus === 'FAILING') return 'Making CI green';
  if (row.reviewDecision === 'CHANGES_REQUESTED') return 'Addressing requested changes';
  if (row.mergeableState === 'dirty') return 'Resolving merge conflicts';
  if (row.reviewDecision === 'APPROVED') return 'Approved';
  return null;
}

const live = (pr: WorkPr) => pr.state === 'draft' || pr.state === 'open';

/**
 * A piece of My work as a line: Draft or Sent by its pull request, else Developing. Tickets and issues nobody has started, issues you
 * opened for others, and work whose pull requests all ended are not in progress, so they have no line.
 */
function progressLines(item: WorkItem): string[] {
  const pr = item.prs.find(live);
  if (item.authored || pr === undefined && (item.prs.length > 0 || item.task === null && item.source !== 'local')) return [];
  const verb = pr === undefined ? 'Developing' : pr.state === 'draft' ? 'Draft' : 'Sent';
  const refs = [item.label, pr === undefined ? '' : reference(pr.repo, pr.number)].filter((ref, index, all) => ref !== '' && all.indexOf(ref) === index);
  const note = pr === undefined ? null : progressNote(pr.row);
  return [`* ${[verb, item.title, ...refs].join(' ')}`, ...(note === null ? [] : [`   * ${note}`])];
}

/**
 * One day as stand-up notes to paste: what merged, what else finished, what is in progress (on today only, since it is how things stand
 * now), and what you reviewed. Empty sections are left out.
 */
export function standupText(day: string, today: string, items: DoneItemDto[], work: WorkItem[]): string {
  const of = (...kinds: DoneItemDto['kind'][]) => items.filter(item => kinds.includes(item.kind));
  const sections: [string, string[]][] = [
    ['Merged', of('MERGED').map(item => doneLine(item))],
    ['Done', of('ISSUE_CLOSED', 'TASK_DONE', 'WORK_DONE').map(item => doneLine(item))],
    ['Work in progress', day === today ? work.flatMap(progressLines) : []],
    ['Reviewed', items.flatMap(item => item.kind === 'APPROVED' ? [doneLine(item, 'Approved')]
      : item.kind === 'CHANGES_REQUESTED' ? [doneLine(item, 'Requested changes on')]
        : item.kind === 'MERGED_BY_YOU' ? [doneLine(item, 'Merged')]
          : item.kind === 'REVIEWED' ? [doneLine(item)] : [])],
  ];
  const header = day === today ? 'Today:' : day === shiftDay(today, -1) ? 'Yesterday:' : `${dayText(day)}:`;
  const body = sections.filter(([, lines]) => lines.length > 0).map(([name, lines]) => `${name}:\n\n${lines.join('\n')}`);
  return body.length === 0 ? `${header}\nNothing finished` : `${header}\n${body.join('\n\n')}`;
}
