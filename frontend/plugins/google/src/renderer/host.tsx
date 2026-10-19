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
import type { GoogleBridge } from '../contracts';

export type GoogleHost = {
  bridge: GoogleBridge & { openExternal: (url: string) => Promise<void> };
  settingsUrl: string;
  openGitHubLink: (url: string) => Promise<void>;
  Markdown: ComponentType<{ body: string }>;
  BetterWords: ComponentType<{
    text: string;
    textareaRef: RefObject<HTMLTextAreaElement | null>;
    disabled?: boolean;
    onError?: (failure: unknown | null) => void;
  }>;
};

const Host = createContext<GoogleHost | null>(null);

export function GoogleHostProvider({ host, children }: { host: GoogleHost; children: ReactNode }) {
  return <Host.Provider value={host}>{children}</Host.Provider>;
}

export function useGoogleHost(): GoogleHost {
  const host = useContext(Host);
  if (host === null) throw new Error('Google Apps requires its desktop host.');
  return host;
}
