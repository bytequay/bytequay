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
import { object, text } from '../pulls/wireParse';

export type ArtifactSummaryDto = { id: string; title: string; updatedAt: string };

export type ArtifactTarget = { workspaceId: string; artifactId: string };

export type ArtifactBounds = { x: number; y: number; width: number; height: number };

export type ArtifactBridge = {
  /** Newest first, titles only. */
  listArtifacts: (workspaceId: string) => Promise<ArtifactSummaryDto[]>;
  /** Lays the page over these bounds; null for either hides it. */
  showArtifact: (target: ArtifactTarget | null, bounds: ArtifactBounds | null) => Promise<void>;
};

export function parseArtifactSummaries(value: unknown): ArtifactSummaryDto[] {
  if (!Array.isArray(value)) throw new Error('Malformed artifacts');
  return value.map(entry => {
    const item = object(entry, 'artifact');
    return { id: text(item.id, 'artifact id'), title: text(item.title, 'artifact title'), updatedAt: text(item.updatedAt, 'artifact time') };
  });
}

export function parseArtifactPage(value: unknown): string {
  return text(object(value, 'artifact').html, 'artifact page');
}

export function parseArtifactTarget(value: unknown): ArtifactTarget {
  const target = object(value, 'artifact target');
  return { workspaceId: text(target.workspaceId, 'workspace'), artifactId: text(target.artifactId, 'artifact') };
}
