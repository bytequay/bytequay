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
export const NOTICE_KINDS = ['TURN_FINISHED', 'QUESTION', 'APPROVAL', 'FAILURE', 'RECOVERY', 'QUEUE_PAUSED'] as const;
export const NOTICE_WIDTH = 340;
export type NoticeKind = typeof NOTICE_KINDS[number];
export type AppNotice = {
  id: string; kind: NoticeKind; taskId: string; workspaceId: string; turnId: string | null; reviewId: string | null;
  title: string; pullRequest: string | null; pullRequestTitle: string | null; changes: NoticeChanges | null;
  message: string; repository: string; laneId: string; occurredAt: string;
};
/** Latest review run counts; `update` marks a run that covers only changes since the prior review. */
export type NoticeChanges = { update: boolean; commits: number; files: number; additions: number; deletions: number };
export type NoticeSnapshot = { notices: AppNotice[]; popupIds: string[]; viewedIds: string[]; active: boolean; maxHeight: number };
export type AppNoticeBounds = { x: number; y: number; width: number; height: number } | null;
export type NoticeBridge = {
  read: () => Promise<NoticeSnapshot>;
  subscribe: (listener: (state: NoticeSnapshot) => void) => () => void;
  dismiss: (id: string) => Promise<void>;
  open: (id: string) => Promise<void>;
  resize: (height: number) => void;
};
/** Approvals and AskUserQuestion cards; an alert's Answer opens the Assistant panel at the card, which has the task's own buttons. */
export const APPROVAL_NOTICE = 'approval:';
export const needsAction = (notice: AppNotice): boolean => notice.kind !== 'TURN_FINISHED';
export const NOTICE_LABELS: Record<NoticeKind, string> = {
  TURN_FINISHED: 'Turn finished', QUESTION: 'Waiting for you', APPROVAL: 'Approval needed',
  FAILURE: 'Turn failed', RECOVERY: 'Task is blocked', QUEUE_PAUSED: 'Queued messages paused',
};
/** What opening a notice shows, as its button says, on its alert and in the Assistant panel alike. */
export const NOTICE_ACTIONS: Record<NoticeKind, string> = {
  TURN_FINISHED: 'View response', QUESTION: 'Reply', APPROVAL: 'Review request', FAILURE: 'View log', RECOVERY: 'Inspect task', QUEUE_PAUSED: 'View queue',
};
export const noticeLabel = (notice: AppNotice): string => notice.reviewId === null ? NOTICE_LABELS[notice.kind]
  : notice.kind === 'TURN_FINISHED' ? 'Review finished' : notice.kind === 'FAILURE' ? 'Review failed' : NOTICE_LABELS[notice.kind];
export function parseNotices(value: unknown): AppNotice[] {
  if (!Array.isArray(value)) throw new Error('Invalid notices');
  const ids = new Set<string>();
  return value.map(raw => {
    if (raw === null || typeof raw !== 'object') throw new Error('Invalid notice');
    const item = raw as Record<string, unknown>;
    for (const key of ['id', 'kind', 'title', 'message', 'occurredAt', 'taskId', 'workspaceId', 'repository', 'laneId']) {
      if (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 1000) throw new Error('Invalid notice field');
    }
    if (!NOTICE_KINDS.includes(item.kind as NoticeKind) || !Number.isFinite(Date.parse(item.occurredAt as string))
      || ids.has(item.id as string)) throw new Error('Invalid notice identity');
    ids.add(item.id as string);
    for (const key of ['turnId', 'reviewId']) {
      if (item[key] !== null && (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 200)) throw new Error('Invalid notice identity');
    }
    for (const key of ['pullRequest', 'pullRequestTitle']) {
      if (item[key] !== null && (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 1000)) throw new Error('Invalid notice context');
    }
    return { id: item.id, kind: item.kind, taskId: item.taskId, workspaceId: item.workspaceId, turnId: item.turnId, reviewId: item.reviewId,
      title: item.title, pullRequest: item.pullRequest, pullRequestTitle: item.pullRequestTitle, changes: parseChanges(item.changes),
      message: item.message, repository: item.repository, laneId: item.laneId, occurredAt: item.occurredAt } as AppNotice;
  });
}
function parseChanges(raw: unknown): NoticeChanges | null {
  if (raw === null) return null;
  if (typeof raw !== 'object') throw new Error('Invalid notice context');
  const { update, commits, files, additions, deletions } = raw as Record<string, unknown>;
  const counts = [commits, files, additions, deletions];
  if (typeof update !== 'boolean' || counts.some(count => !Number.isSafeInteger(count) || (count as number) < 0)) throw new Error('Invalid notice context');
  return { update, commits, files, additions, deletions } as NoticeChanges;
}

declare global { interface Window { noticeBridge: NoticeBridge } }
