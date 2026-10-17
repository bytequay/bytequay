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
import { pullRequestFromRoute, type ZoomedReference } from '../githubReference';
import type { AgendaTargetDto } from '../types';
import { workspaceLocationFromRoute } from '../workspaces/model';

/**
 * The ref of what the user is looking at, so "this one" in a message can resolve: the top layer first, then the page's route. Pages whose
 * open item lives outside the route send none.
 */
export function screenRef(layer: ZoomedReference | undefined, hash: string, workspaceRepo: string | null): string | null {
  if (layer !== undefined) {
    switch (layer.kind) {
      case 'pull': return `pr:${layer.repo.toLowerCase()}#${layer.number}`;
      case 'issue': return `issue:${layer.repo.toLowerCase()}#${layer.number}`;
      case 'jira': return `jira:${layer.cloudId}:${layer.issueId}`;
      case 'work': return `work:${layer.itemId}`;
      case 'task': return `task:${layer.taskId}`;
    }
  }
  const pull = pullRequestFromRoute(hash);
  if (pull !== null) return `pr:${pull.repo.toLowerCase()}#${pull.number}`;
  const location = workspaceLocationFromRoute(hash);
  if (location === null) return null;
  if (location.reviewId !== undefined) return `review:${location.reviewId}`;
  if (location.agentTaskId !== null) return `task:${location.agentTaskId}`;
  if (workspaceRepo === null) return null;
  if (location.pullRequestNumber !== null) return `pr:${workspaceRepo.toLowerCase()}#${location.pullRequestNumber}`;
  if (location.issueNumber !== null) return `issue:${workspaceRepo.toLowerCase()}#${location.issueNumber}`;
  return null;
}

const KIND_NAMES: Record<string, string> = { jira: 'Jira ticket', work: 'work item', epic: 'Epic', task: 'task', review: 'review' };

/** A ref's short name, as the On screen chip and an unlabelled reply link show it. */
export function refLabel(ref: string): string {
  const number = /^(?:pr|issue):[^#]+#(\d+)$/.exec(ref);
  return number !== null ? `#${number[1]}` : KIND_NAMES[ref.slice(0, ref.indexOf(':'))] ?? ref;
}

// The agent renderer opens only web links, so a ref travels as one that the panel catches before anything opens it.
const REF_LINK = 'https://bytequay.invalid/ref?r=';

export function refFromHref(href: string): string | null {
  return href.startsWith(REF_LINK) ? decodeURIComponent(href.slice(REF_LINK.length)) : null;
}

const escape = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Turns the refs a reply names into links where it has a target, written as `[label](ref)` or bare; a ref link without one keeps
 * only its label, since nothing could open it.
 */
export function linkRefs(text: string, targets: Record<string, AgendaTargetDto>): string {
  const known = Object.keys(targets).sort((a, b) => b.length - a.length).map(escape);
  const bare = known.length === 0 ? '' : `|(?<![\\w:/#\`])(${known.join('|')})(?![\\w-])`;
  const pattern = new RegExp(`\\[([^\\]\\n]*)\\]\\(((?:pr|issue|jira|task|review|work|epic):[^)\\s]+)\\)${bare}`, 'g');
  return text.replace(pattern, (whole: string, label: string | undefined, linked: string | undefined, named: string | undefined) => {
    const ref = linked ?? named;
    if (ref === undefined) return whole;
    if (!(ref in targets)) return label ?? whole;
    return `[${label ?? refLabel(ref)}](${REF_LINK}${encodeURIComponent(ref)})`;
  });
}
