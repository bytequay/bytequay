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

export type LocalReviewMessageDto = {
  id: string;
  author: 'USER' | 'AGENT';
  body: string;
  createdAt: string;
  turnId: string | null;
  queuedInputId: string | null;
  messageEventId: string | null;
  deliveryState: string | null;
  deliveryError: string | null;
};

export type LocalReviewThreadDto = {
  id: string;
  taskId: string;
  workspaceId: string;
  repo: string;
  number: number;
  path: string;
  side: 'LEFT' | 'RIGHT';
  startLine: number | null;
  line: number;
  baseSha: string | null;
  headSha: string | null;
  code: string;
  resolved: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  messages: LocalReviewMessageDto[];
};

export type LocalReviewThreadActions = {
  onReply: (threadId: string, body: string) => Promise<void>;
  onResolve: (threadId: string, resolved: boolean) => Promise<void>;
  onRetry: (threadId: string, messageId: string) => Promise<void>;
  disabledReason?: string | null;
};

export type LocalReviewThreads = LocalReviewThreadActions & {
  threads: LocalReviewThreadDto[];
};
