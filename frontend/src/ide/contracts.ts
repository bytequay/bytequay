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
import type { CodeQueryRequest, CodeQueryReply } from './codeQuery';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { ExecutionStart, ExecutionOutput, ExecutionResult } from './executionProtocol';
import type { LocalRunDto, CodeHostRef } from '../pulls/code-tests/contracts';
import type { TerminalBridge } from '../pulls/code-tests/TerminalView';
import type { CodeCommentsOptions } from '../pulls/code-tests/useCodeComments';
import type { AgentTaskDto, PullRequestDetailDto } from '../types';

export type IdeConnection = {
  taskId: string;
  sessionId: string;
  authority: string;
  connectionToken: string;
  root: string;
  bundleRoot: string;
  cacheRoot: string;
  commit: string;
};

export type IdeComments = Omit<CodeCommentsOptions, 'onPendingChange' | 'onDetailChange' | 'onAskAgent' | 'onOpenFindingInReview' | 'threads' | 'localReview'> & {
  askAgent: boolean;
  openFinding: boolean;
  threads: Omit<CodeCommentsOptions['threads'], 'onRefresh' | 'onDetailUpdate'>;
  localReview: Omit<CodeCommentsOptions['localReview'], 'onReply' | 'onResolve' | 'onRetry'>;
};

export type IdeContext = {
  host: CodeHostRef;
  review: AgentReviewDto | null;
  task: AgentTaskDto | null;
  detail: PullRequestDetailDto | null;
  comments?: IdeComments;
  findingBusy?: boolean;
  findingError?: string | null;
  workingCopy?: { blocked: string | null; staleLabel: string | null };
};

export type IdeRequest = { id: string; requestId: string; method: string; args: unknown[] };
export type IdeReply = { requestId: string; value?: unknown; error?: string };
export type IdeEvent = { id: string; dirty: number; busy: boolean; closed?: boolean; error?: string };
export type IdeBounds = { x: number; y: number; width: number; height: number };

/** Only the ByteQuay renderer can own or position an IDE view. */
export type IdeDesktopBridge = {
  open: (host: CodeHostRef) => Promise<string>;
  present: (id: string, bounds: IdeBounds | null) => Promise<void>;
  update: (id: string, context: IdeContext) => Promise<void>;
  close: (id: string) => Promise<void>;
  discardSourceDrafts: (id: string) => Promise<void>;
  reply: (reply: IdeReply) => void;
  onRequest: (listener: (request: IdeRequest) => void) => () => void;
  onEvent: (listener: (event: IdeEvent) => void) => () => void;
};

/** The isolated workbench has no desktop session token and cannot invoke arbitrary app IPC. */
export type IdeWorkbenchBridge = {
  terminal: TerminalBridge;
  onDiscardSource: (handler: () => Promise<void>) => () => void;
  startExecution: (request: ExecutionStart) => Promise<LocalRunDto>;
  outputExecution: (id: string, request: ExecutionOutput) => Promise<void>;
  finishExecution: (id: string, request: ExecutionResult) => Promise<LocalRunDto>;
  stopExecution: (id: string) => Promise<LocalRunDto>;
  onQuery: (listener: (request: CodeQueryRequest & { cancel?: boolean }) => void) => () => void;
  queryReply: (id: string, reply: CodeQueryReply) => void;
  queryReady: () => void;
  save: (path: string, content: string, expectedHash: string) => Promise<void>;
  bootstrap: () => Promise<{ connection: IdeConnection; context: IdeContext | null }>;
  request: (method: string, ...args: unknown[]) => Promise<unknown>;
  report: (event: Pick<IdeEvent, 'dirty' | 'busy' | 'error'>) => void;
  onContext: (listener: (context: IdeContext) => void) => () => void;
};

declare global {
  interface Window {
    ide: IdeDesktopBridge;
    ideWorkbench: IdeWorkbenchBridge;
  }
}

export type CodeBlame = {
  path: string;
  revision: string;
  contentHash: string;
  spans: BlameSpan[];
  truncated: boolean;
  unavailableReason: string | null;
};
export type BlameSpan = {
  line: number;
  count: number;
  originalLine: number;
  commit: string;
  author: string;
  authoredAt: string;
  summary: string;
  originalPath: string;
};

export type HistoryCommit = { sha: string; parents: string[]; author: string; authoredAt: string; message: string };
export type CodeHistory = { revision: string; commits: HistoryCommit[]; nextOffset: number | null };
export type HistoryComparison = { commit: HistoryCommit; parent: string | null; files: HistoryChange[]; truncated: boolean };
export type HistoryChange = { path: string; originalPath: string | null; status: string };
