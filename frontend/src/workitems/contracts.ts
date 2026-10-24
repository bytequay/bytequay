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

import { object, oneOf, optionalText, text } from '../pulls/wireParse';


export type WorkSource = 'LOCAL' | 'GITHUB' | 'JIRA';
export type UnifiedWorkItem = {
  id: string; source: WorkSource; title: string; description: string; status: string; closed: boolean; updatedAt: string;
  reference: { kind: GoalItemKind; key: string; label: string; title: string; addedAt: string } | null;
  work: WorkItemDto | null;
};
export type Publication = {
  source: 'GITHUB' | 'JIRA'; expectedRevision: number; connectionId?: string; cloudId?: string; project?: string; issueTypeId?: string;
};
export type WorkItemContextLink = {
  targetId: string; id: string; source: WorkSource; title: string; status: string; available: boolean;
  reference: { kind: 'ISSUE' | 'JIRA'; key: string; label: string } | null;
};
export type WorkItemBridge = {
  listUnifiedWorkItems: (workspaceId: string, localOnly?: boolean) => Promise<UnifiedWorkItem[]>;
  listWorkItemContextLinks: (workspaceId: string, itemId: string) => Promise<WorkItemContextLink[]>;
  linkWorkItemContext: (workspaceId: string, itemId: string, expectedRevision: number, targetId: string) => Promise<WorkItemDto>;
  unlinkWorkItemContext: (workspaceId: string, itemId: string, expectedRevision: number, targetId: string) => Promise<WorkItemDto>;
  publishWorkItem: (workspaceId: string, id: string, request: Publication) => Promise<WorkItemDto>;
  reconcileWorkItem: (workspaceId: string, id: string, destination: Publication, key: string) => Promise<WorkItemDto>;
  workItemJiraTypes: (workspaceId: string, connectionId: string, cloudId: string, project: string) => Promise<{ id: string; name: string }[]>;
  specWorkItem: (workspaceId: string, checkId: string, candidateId: string) => Promise<WorkItemDto>;
};
export function parseWorkItemContextLink(value: unknown): WorkItemContextLink {
  if (value === null || typeof value !== 'object') throw new Error('Malformed context link');
  const link = value as Record<string, unknown>;
  if (typeof link.targetId !== 'string' || typeof link.id !== 'string' || typeof link.title !== 'string' || typeof link.status !== 'string'
      || typeof link.available !== 'boolean' || !['LOCAL', 'GITHUB', 'JIRA'].includes(String(link.source))) throw new Error('Malformed context link');
  let reference: WorkItemContextLink['reference'] = null;
  if (link.reference != null) {
    if (typeof link.reference !== 'object') throw new Error('Malformed context link source');
    const ref = link.reference as Record<string, unknown>;
    if ((ref.kind !== 'ISSUE' && ref.kind !== 'JIRA') || typeof ref.key !== 'string' || typeof ref.label !== 'string') throw new Error('Malformed context link source');
    reference = { kind: ref.kind, key: ref.key, label: ref.label };
  }
  return { targetId: link.targetId, id: link.id, source: link.source as WorkSource, title: link.title, status: link.status, available: link.available, reference };
}
export function parseUnifiedWorkItem(value: unknown): UnifiedWorkItem {
  if (value === null || typeof value !== 'object') throw new Error('Malformed work item');
  const v = value as Record<string, unknown>;
  if (typeof v.id !== 'string' || typeof v.title !== 'string' || typeof v.description !== 'string' || typeof v.status !== 'string'
      || typeof v.closed !== 'boolean' || typeof v.updatedAt !== 'string' || !['LOCAL', 'GITHUB', 'JIRA'].includes(String(v.source))) throw new Error('Malformed work item');
  let reference: UnifiedWorkItem['reference'] = null;
  if (v.reference != null) {
    const r = v.reference as Record<string, unknown>;
    if (!['ISSUE', 'JIRA'].includes(String(r.kind)) || typeof r.key !== 'string' || typeof r.label !== 'string' || typeof r.title !== 'string' || typeof r.addedAt !== 'string') throw new Error('Malformed work item source');
    reference = { kind: r.kind as GoalItemKind, key: r.key, label: r.label, title: r.title, addedAt: r.addedAt };
  }
  return { id: v.id, source: v.source as WorkSource, title: v.title, description: v.description, status: v.status, closed: v.closed,
    updatedAt: v.updatedAt, reference, work: v.work == null ? null : parseWorkItem(v.work) };
}
export function workItemRoute(workspaceId: string, id?: string): string {
  return `#/workspaces/${encodeURIComponent(workspaceId)}/issues?source=local${id ? `&work=${encodeURIComponent(id)}` : ''}`;
}

export const GOAL_ITEM_KINDS = ['PULL_REQUEST', 'ISSUE', 'JIRA', 'TASK'] as const;

export type GoalItemKind = typeof GOAL_ITEM_KINDS[number];

export type GoalItemState = 'IDLE' | 'WORKING' | 'MERGED' | 'ENDED' | 'UNKNOWN';

/** A goal item, or a candidate for one, as the backend read it from local stores. */
export type GoalItemDto = {
  kind: GoalItemKind;
  /** owner/repo#number, connection:cloud:issue for a Jira ticket, or the task id. */
  key: string;
  /** #123, ENG-412 or Task. */
  label: string;
  title: string;
  state: GoalItemState;
  /** The item's own state in words, such as Merged, In Progress or Running. */
  status: string;
  blocked: boolean;
  url: string | null;
  activity: { text: string; at: string } | null;
  /** When it was added; null for a candidate. */
  addedAt: string | null;
  /** The source's own priority, such as a Jira ticket's, shown as it is and never mapped to the work's. */
  priority?: string | null;
};

export function parseGoalItem(value: unknown): GoalItemDto {
  const source = object(value, 'goal item');
  const activity = source.activity === null || source.activity === undefined ? null : object(source.activity, 'goal item activity');
  if (typeof source.blocked !== 'boolean') throw new Error('Malformed goal item blocked');
  return {
    kind: oneOf(source.kind, GOAL_ITEM_KINDS, 'goal item kind'),
    key: text(source.key, 'goal item key'),
    label: text(source.label, 'goal item label'),
    title: text(source.title, 'goal item title'),
    state: oneOf(source.state, ['IDLE', 'WORKING', 'MERGED', 'ENDED', 'UNKNOWN'] as const, 'goal item state'),
    status: text(source.status, 'goal item status'),
    blocked: source.blocked,
    url: optionalText(source.url),
    activity: activity === null ? null : { text: text(activity.text, 'goal activity text'), at: text(activity.at, 'goal activity time') },
    addedAt: optionalText(source.addedAt),
    ...(typeof source.priority === 'string' ? { priority: source.priority } : {}),
  };
}

export function parseList<T>(value: unknown, parse: (entry: unknown) => T, what: string): T[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value.map(parse);
}

export type WorkItemState = 'PLANNED' | 'WORKING' | 'DONE' | 'DROPPED';
export type WorkItemKind = 'LEARN' | 'INVESTIGATE' | 'DEVELOP';
export type CompletionRule = 'MERGED_DELIVERY' | 'ACCEPTED_RESULT';
export type WorkItemPriority = 'HIGH' | 'NORMAL' | 'LOW';
export type WorkItemDto = {
  id: string;
  isEpic: boolean;
  workspaceId: string;
  title: string;
  kind: WorkItemKind;
  acceptanceCriteria: string;
  completionRule: CompletionRule;
  state: WorkItemState;
  revision: number;
  links: GoalItemDto[];
  /** Work items used as reference context, without changing source or development ownership. */
  contextLinks: { targetId: string; title: string }[];
  history: { operationId: string; action: string; detail: string; at: string }[];
  createdAt: string;
  updatedAt: string;
  priority: WorkItemPriority;
  /** The same Epic's work it waits on. */
  blockedBy: string[];
  /** The Epic the work belongs to: a local item, GitHub issue, or Jira ticket identity. */
  epicId?: string | null;
  /** Its place in its Epic's order, lowest first; equal places keep creation order. */
  epicRank?: number;
};
export type CreateWorkItem = Pick<WorkItemDto, 'id' | 'title' | 'kind' | 'acceptanceCriteria' | 'completionRule' | 'epicId'>;
/** Work an Epic took in, and the other Epic it left, if any. */
export type EpicJoinDto = { item: WorkItemDto; movedFrom: string | null };
/** A GitHub sub-issue or Jira child ticket an Epic's source reports, as ByteQuay last read it. */
export type EpicChildDto = { identity: string; kind: 'ISSUE' | 'JIRA'; key: string; label: string; title: string; status: string; closed: boolean; url: string | null };
/** Work the Development model proposed for an Epic, waiting until the user accepts or dismisses it. */
export type EpicProposalDto = {
  id: string;
  title: string;
  /** The one check that tells the work is done. */
  doneWhen: string;
  /** What the work is and the context its agent starts from; empty on a proposal saved before proposals carried one. */
  description: string;
  background: string | null;
  evidence: string | null;
  reproduceSteps: string | null;
};
/** An Epic the Development model drafted from a conversation; only the user's approval creates it. */
export type EpicDraftDto = { title: string; description: string; context: string };
/** One turn of the Create Epic conversation; an agent turn keeps the draft it showed, so the model knows what the user answers. */
export type EpicDraftMessage = { role: 'USER' | 'AGENT'; text: string; draft?: EpicDraftDto };
/** `create`: the user's latest message approved the draft shown before this reply. */
export type EpicDraftReply = { message: string; draft: EpicDraftDto | null; create: boolean };
/** An Epic's children: which were synced into work, which the source dropped since, and whether to offer syncing. */
export type EpicRecordDto = {
  id: string;
  children: EpicChildDto[];
  /** The identities of the children synced into work. */
  synced: string[];
  /** Synced children the source no longer reports. */
  former: EpicChildDto[];
  childrenReadAt: string | null;
  childrenTruncated: boolean;
  offered: boolean;
  /** When the user concluded the Epic with all its work done; a concluded Epic leaves the drawer. */
  concludedAt: string | null;
  /** What the user accepted the Epic delivered. */
  outcome: string;
  proposals: EpicProposalDto[];
  /** Whether the Development model is proposing work now. */
  breakingDown: boolean;
  /** Why the last breakdown failed, until the next one starts. */
  breakdownError: string | null;
  /** What the last ask said when it found no more work worth suggesting, until the next one starts. */
  breakdownNote?: string | null;
};
export type WorkItemDecision = {
  operationId: string;
  expectedRevision: number;
  action: 'EDIT' | 'BEGIN' | 'ACCEPT' | 'FINISH' | 'DROP' | 'REOPEN' | 'PRIORITIZE' | 'BLOCK' | 'UNBLOCK';
  title?: string;
  acceptanceCriteria?: string;
  completionRule?: CompletionRule;
  detail: string;
  priority?: WorkItemPriority;
  /** The work BLOCK makes this item wait on, or UNBLOCK stops it waiting on. */
  blocker?: string;
  /** Dropping work others wait on also stops them waiting on it. */
  releaseDependents?: boolean;
};

export function parseWorkItem(value: unknown): WorkItemDto {
  const item = object(value, 'work item');
  if (!Number.isSafeInteger(item.revision) || (item.revision as number) < 0) throw new Error('Malformed work item revision');
  return {
    id: text(item.id, 'work item id'),
    isEpic: item.isEpic === true,
    workspaceId: text(item.workspaceId, 'work item workspace'),
    title: text(item.title, 'work item title'),
    kind: oneOf(item.kind, ['LEARN', 'INVESTIGATE', 'DEVELOP'] as const, 'work item kind'),
    acceptanceCriteria: text(item.acceptanceCriteria, 'work item criteria'),
    completionRule: oneOf(item.completionRule, ['MERGED_DELIVERY', 'ACCEPTED_RESULT'] as const, 'completion rule'),
    state: oneOf(item.state, ['PLANNED', 'WORKING', 'DONE', 'DROPPED'] as const, 'work item state'),
    revision: item.revision as number,
    links: parseList(item.links, parseGoalItem, 'work item links'),
    contextLinks: item.contextLinks == null ? [] : parseList(item.contextLinks, value => {
      const link = object(value, 'work item context link');
      return { targetId: text(link.targetId, 'context link target'), title: text(link.title, 'context link title') };
    }, 'work item context links'),
    history: parseList(item.history, (value) => {
      const event = object(value, 'work item history');
      return { operationId: text(event.operationId, 'operation'), action: text(event.action, 'action'), detail: text(event.detail, 'detail'), at: text(event.at, 'time') };
    }, 'work item history'),
    createdAt: text(item.createdAt, 'work item created'),
    updatedAt: text(item.updatedAt, 'work item updated'),
    priority: oneOf(item.priority, ['HIGH', 'NORMAL', 'LOW'] as const, 'work item priority'),
    blockedBy: parseList(item.blockedBy, (value) => text(value, 'work item blocker'), 'work item blockers'),
    ...(item.epicId == null ? {} : { epicId: text(item.epicId, 'work item Epic'), epicRank: Number.isSafeInteger(item.epicRank) ? item.epicRank as number : 0 }),
  };
}

function parseEpicChild(value: unknown): EpicChildDto {
  const child = object(value, 'Epic child');
  return {
    identity: text(child.identity, 'Epic child identity'),
    kind: oneOf(child.kind, ['ISSUE', 'JIRA'] as const, 'Epic child kind'),
    key: text(child.key, 'Epic child key'),
    label: text(child.label, 'Epic child label'),
    title: typeof child.title === 'string' ? child.title : '',
    status: typeof child.status === 'string' ? child.status : '',
    closed: child.closed === true,
    url: optionalText(child.url),
  };
}

export function parseEpicRecord(value: unknown): EpicRecordDto {
  const record = object(value, 'Epic');
  return {
    id: text(record.id, 'Epic id'),
    children: parseList(record.children, parseEpicChild, 'Epic children'),
    synced: parseList(record.synced, (identity) => text(identity, 'synced child'), 'synced children'),
    former: parseList(record.former, parseEpicChild, 'former Epic children'),
    childrenReadAt: optionalText(record.childrenReadAt),
    childrenTruncated: record.childrenTruncated === true,
    offered: record.offered === true,
    concludedAt: optionalText(record.concludedAt),
    outcome: typeof record.outcome === 'string' ? record.outcome : '',
    proposals: parseList(record.proposals, (value) => {
      const proposal = object(value, 'Epic proposal');
      return {
        id: text(proposal.id, 'Epic proposal id'), title: text(proposal.title, 'Epic proposal title'), doneWhen: text(proposal.doneWhen, 'Epic proposal done-when'),
        description: typeof proposal.description === 'string' ? proposal.description : '', background: optionalText(proposal.background),
        evidence: optionalText(proposal.evidence), reproduceSteps: optionalText(proposal.reproduceSteps),
      };
    }, 'Epic proposals'),
    breakingDown: record.breakingDown === true,
    breakdownError: optionalText(record.breakdownError),
    breakdownNote: optionalText(record.breakdownNote),
  };
}

function parseEpicDraft(value: unknown): EpicDraftDto {
  const draft = object(value, 'Epic draft');
  return { title: text(draft.title, 'Epic draft title'), description: typeof draft.description === 'string' ? draft.description : '',
    context: typeof draft.context === 'string' ? draft.context : '' };
}

export function parseEpicDraftReply(value: unknown): EpicDraftReply {
  const reply = object(value, 'Epic draft reply');
  return { message: text(reply.message, 'Epic draft message'), draft: reply.draft == null ? null : parseEpicDraft(reply.draft), create: reply.create === true };
}

export function parseConcludedEpics(value: unknown): string[] {
  return parseList(value, (id) => text(id, 'concluded Epic'), 'concluded Epics');
}

export function parseEpicJoin(value: unknown): EpicJoinDto {
  const join = object(value, 'Epic join');
  return { item: parseWorkItem(join.item), movedFrom: optionalText(join.movedFrom) };
}
