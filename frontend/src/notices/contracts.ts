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
export const NOTICE_KINDS = ['TURN_FINISHED', 'QUESTION', 'APPROVAL', 'FAILURE', 'RECOVERY', 'QUEUE_PAUSED', 'MAIL'] as const;
export type NoticeKind = typeof NOTICE_KINDS[number];
export type TaskNotice = {
  id: string; kind: Exclude<NoticeKind, 'MAIL'>; taskId: string; workspaceId: string; turnId: string | null;
  title: string; message: string; repository: string; laneId: string; occurredAt: string;
};
export type MailNotice = {
  id: string; kind: 'MAIL'; profile: string; messageId: string; threadId: string;
  title: string; message: string; sender: string; email: string; occurredAt: string;
};
export type AppNotice = TaskNotice | MailNotice;
export type NoticeSnapshot = { notices: AppNotice[]; popupIds: string[]; active: boolean; maxHeight: number };
export type AppNoticeBounds = { x: number; y: number; width: number; height: number } | null;
export type NoticeBridge = {
  read: () => Promise<NoticeSnapshot>;
  subscribe: (listener: (state: NoticeSnapshot) => void) => () => void;
  dismiss: (id: string) => Promise<void>;
  open: (id: string) => Promise<void>;
  resize: (height: number, width: number) => void;
};
export const needsAction = (notice: AppNotice): boolean => notice.kind !== 'TURN_FINISHED';
export const NOTICE_LABELS: Record<NoticeKind, string> = {
  TURN_FINISHED: 'Turn finished', QUESTION: 'Question for you', APPROVAL: 'Approval needed',
  FAILURE: 'Turn failed', RECOVERY: 'Task is blocked', QUEUE_PAUSED: 'Queued messages paused', MAIL: 'New email',
};
export function parseNotices(value: unknown): AppNotice[] {
  if (!Array.isArray(value)) throw new Error('Invalid notices');
  const ids = new Set<string>();
  return value.map(raw => {
    if (raw === null || typeof raw !== 'object') throw new Error('Invalid notice');
    const item = raw as Record<string, unknown>;
    const sourceFields = item.kind === 'MAIL' ? ['profile', 'messageId', 'sender', 'email'] : ['taskId', 'workspaceId', 'repository', 'laneId'];
    for (const key of ['id', 'kind', 'title', 'message', 'occurredAt', ...sourceFields]) {
      if (typeof item[key] !== 'string' || !(item[key] as string).trim() || (item[key] as string).length > 1000) throw new Error('Invalid notice field');
    }
    if (!NOTICE_KINDS.includes(item.kind as NoticeKind) || !Number.isFinite(Date.parse(item.occurredAt as string))
      || ids.has(item.id as string)) throw new Error('Invalid notice identity');
    ids.add(item.id as string);
    if (item.kind === 'MAIL') {
      if (typeof item.threadId !== 'string' || item.threadId.length > 200 || (item.messageId as string).length > 200
        || (item.profile as string).length > 200) throw new Error('Invalid mail identity');
      return { id: item.id, kind: 'MAIL', profile: item.profile, messageId: item.messageId, threadId: item.threadId,
        title: item.title, message: item.message, sender: item.sender, email: item.email, occurredAt: item.occurredAt } as MailNotice;
    }
    if (item.turnId !== null && (typeof item.turnId !== 'string' || !item.turnId || item.turnId.length > 200)) throw new Error('Invalid notice identity');
    return { id: item.id, kind: item.kind, taskId: item.taskId, workspaceId: item.workspaceId, turnId: item.turnId,
      title: item.title, message: item.message, repository: item.repository, laneId: item.laneId, occurredAt: item.occurredAt } as AppNotice;
  });
}

declare global { interface Window { noticeBridge: NoticeBridge } }
