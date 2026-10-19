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

import { createContext, useContext, type ComponentType, type ReactNode, type RefObject } from 'react';
import type { JiraBridge, JiraIssue, JiraIssueDetail, JiraLocation } from '../contracts';

export type JiraIssueActionsProps = {
  issue: JiraIssue; busy?: boolean; labelled?: boolean; onEpic: () => void; onStart: () => void;
  onAssigned?: (detail: JiraIssueDetail) => void;
};
export type JiraWorkspaceDialogProps = { issue: JiraIssue; onClose: () => void; onAssigned: (detail: JiraIssueDetail) => void };
export type JiraLaunchProps = { issue: JiraIssue; draft?: string; replaces?: string; send?: boolean; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void };
export type JiraHost = {
  bridge: JiraBridge;
  openExternal: (url: string) => Promise<void>;
  openInAppBrowser: (url: string) => Promise<void>;
  settingsUrl: string;
  generalSettingsUrl: string;
  route: (location?: JiraLocation, workspaceId?: string) => string;
  location: (hash: string) => JiraLocation | null;
  openTicket: (location: JiraLocation) => void;
  openTask: (workspaceId: string, taskId: string) => void;
  createLocalChild: (location: JiraLocation, id: string, title: string, description: string) => Promise<{ workspaceId: string; id: string }>;
  openWorkItem: (workspaceId: string, id: string) => void;
  useCovered: () => boolean;
  useTasks: (issues: JiraIssue[], version: string | null) => Record<string, ReactNode>;
  useTicketActivity: (issue: JiraIssue | null, location: JiraLocation, covered: boolean) => {
    complete: () => void;
    onAction: (action: 'comment' | 'update', count: number, issue: JiraIssue) => void;
  };
  useEpic: (issue: JiraIssue | null, source: ReactNode, showWork: boolean, onWork: (show: boolean) => void) => {
    available: boolean; tabs: ReactNode; content: ReactNode;
  };
  IssueActions: ComponentType<JiraIssueActionsProps>;
  WorkspaceDialog: ComponentType<JiraWorkspaceDialogProps>;
  Launch: ComponentType<JiraLaunchProps>;
  Pin: ComponentType<{ issue: JiraIssue }>;
  Markdown: ComponentType<{ body: string }>;
  BetterWords: ComponentType<{
    text: string; textareaRef: RefObject<HTMLTextAreaElement | null>; workspaceId?: string;
    disabled?: boolean; onError?: (failure: unknown | null) => void;
  }>;
};

const Host = createContext<JiraHost | null>(null);

export function JiraHostProvider({ host, children }: { host: JiraHost; children: ReactNode }) {
  return <Host.Provider value={host}>{children}</Host.Provider>;
}

export function useJiraHost(): JiraHost {
  const host = useContext(Host);
  if (host === null) throw new Error('Jira requires its desktop host.');
  return host;
}
