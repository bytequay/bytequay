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

export type ArtifactState = 'WRITING' | 'READY' | 'FAILED';

export type ArtifactNextDto = { id: string; title: string; ask: string; state: 'SHOWN' | 'WRITTEN' | 'DISMISSED'; artifactId: string | null };

export type ArtifactDto = {
  id: string;
  title: string;
  /** The latest request: what the page was asked for, or the change asked last. */
  ask: string;
  state: ArtifactState;
  failure: string | null;
  /** When the page was last saved; null until the first save. */
  writtenAt: string | null;
  next: ArtifactNextDto[];
  updatedAt: string;
};

/** Which page to show; a new `version` loads it again. */
export type ArtifactTarget = { workspaceId: string; artifactId: string; version: string };

export type ArtifactBounds = { x: number; y: number; width: number; height: number };

export type ArtifactBridge = {
  /** Newest change first, without pages. */
  listArtifacts: (workspaceId: string) => Promise<ArtifactDto[]>;
  askArtifact: (workspaceId: string, ask: string) => Promise<ArtifactDto>;
  changeArtifact: (workspaceId: string, artifactId: string, ask: string) => Promise<ArtifactDto>;
  stopArtifact: (workspaceId: string, artifactId: string) => Promise<void>;
  /** Starts the card's page and returns it. */
  writeNextArtifact: (workspaceId: string, artifactId: string, cardId: string) => Promise<ArtifactDto>;
  dismissNextArtifact: (workspaceId: string, artifactId: string, cardId: string) => Promise<ArtifactDto>;
  /** Lays the page over these bounds; null for either hides it. */
  showArtifact: (target: ArtifactTarget | null, bounds: ArtifactBounds | null) => Promise<void>;
};

export function parseArtifact(value: unknown): ArtifactDto {
  const item = object(value, 'artifact');
  if (!Array.isArray(item.next)) throw new Error('Malformed artifact cards');
  return {
    id: text(item.id, 'artifact id'),
    title: text(item.title, 'artifact title'),
    ask: text(item.ask, 'artifact ask'),
    state: oneOf(item.state, ['WRITING', 'READY', 'FAILED'] as const, 'artifact state'),
    failure: optionalText(item.failure, 'artifact failure'),
    writtenAt: optionalText(item.writtenAt, 'artifact time'),
    next: item.next.map(entry => {
      const card = object(entry, 'artifact card');
      return { id: text(card.id, 'card id'), title: text(card.title, 'card title'), ask: text(card.ask, 'card ask'),
        state: oneOf(card.state, ['SHOWN', 'WRITTEN', 'DISMISSED'] as const, 'card state'), artifactId: optionalText(card.artifactId, 'card page') };
    }),
    updatedAt: text(item.updatedAt, 'artifact time'),
  };
}

export function parseArtifacts(value: unknown): ArtifactDto[] {
  if (!Array.isArray(value)) throw new Error('Malformed artifacts');
  return value.map(parseArtifact);
}

export function parseArtifactPage(value: unknown): string {
  return text(object(value, 'artifact').html, 'artifact page');
}

export function parseArtifactTarget(value: unknown): ArtifactTarget {
  const target = object(value, 'artifact target');
  return { workspaceId: text(target.workspaceId, 'workspace'), artifactId: text(target.artifactId, 'artifact'), version: text(target.version, 'artifact version') };
}
