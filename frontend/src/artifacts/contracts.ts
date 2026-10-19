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
import { count, flag, object, oneOf, optionalText, text } from '../pulls/wireParse';

/** A conversation; its title becomes its first page's title once there is one. */
export type ArtifactDto = { id: string; title: string; working: boolean; updatedAt: string };

/** A conversation in the list, with how many pages it made (versions not counted). */
export type ArtifactRowDto = { artifact: ArtifactDto; pages: number };

export type ArtifactItemKind = 'USER' | 'REPLY' | 'ACTIVITY' | 'OFFER' | 'PAGE' | 'FAILURE';

export type ArtifactOfferDto = { title: string; covers: string; state: 'SHOWN' | 'ACCEPTED' | 'DISMISSED' };

/** One line of the conversation; an offer carries its card, and a page line the version it shows. */
export type ArtifactItemDto = { id: string; kind: ArtifactItemKind; text: string; offer: ArtifactOfferDto | null; pageId: string | null; at: string };

/** One saved version of a page; `key` names the page across its versions. */
export type ArtifactPageDto = { id: string; key: string; version: number; title: string; commit: string; writtenAt: string };

export type ArtifactViewDto = { artifact: ArtifactDto; items: ArtifactItemDto[]; pages: ArtifactPageDto[] };

/** Which page version to show. */
export type ArtifactTarget = { workspaceId: string; artifactId: string; pageId: string };

export type ArtifactBounds = { x: number; y: number; width: number; height: number };

export type ArtifactBridge = {
  /** Newest first. */
  listArtifacts: (workspaceId: string) => Promise<ArtifactRowDto[]>;
  startArtifact: (workspaceId: string, text: string) => Promise<ArtifactViewDto>;
  viewArtifact: (workspaceId: string, artifactId: string) => Promise<ArtifactViewDto>;
  sendArtifact: (workspaceId: string, artifactId: string, text: string) => Promise<ArtifactViewDto>;
  answerArtifactOffer: (workspaceId: string, artifactId: string, itemId: string, accept: boolean) => Promise<ArtifactViewDto>;
  stopArtifact: (workspaceId: string, artifactId: string) => Promise<void>;
  /** Lays the page over these bounds; null for either hides it. */
  showArtifact: (target: ArtifactTarget | null, bounds: ArtifactBounds | null) => Promise<void>;
};

function parseArtifact(value: unknown): ArtifactDto {
  const item = object(value, 'artifact');
  return { id: text(item.id, 'artifact id'), title: text(item.title, 'artifact title'), working: flag(item.working, 'artifact working'),
    updatedAt: text(item.updatedAt, 'artifact time') };
}

function list(value: unknown, what: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value;
}

export function parseArtifactRows(value: unknown): ArtifactRowDto[] {
  return list(value, 'artifacts').map(entry => {
    const row = object(entry, 'artifact row');
    return { artifact: parseArtifact(row.artifact), pages: count(row.pages, 'artifact pages') };
  });
}

export function parseArtifactView(value: unknown): ArtifactViewDto {
  const view = object(value, 'artifact view');
  return {
    artifact: parseArtifact(view.artifact),
    items: list(view.items, 'artifact items').map(entry => {
      const item = object(entry, 'artifact item');
      const offer = item.offer === null || item.offer === undefined ? null : object(item.offer, 'artifact offer');
      return {
        id: text(item.id, 'item id'),
        kind: oneOf(item.kind, ['USER', 'REPLY', 'ACTIVITY', 'OFFER', 'PAGE', 'FAILURE'] as const, 'item kind'),
        text: typeof item.text === 'string' ? item.text : '',
        offer: offer === null ? null : { title: text(offer.title, 'offer title'), covers: text(offer.covers, 'offer covers'),
          state: oneOf(offer.state, ['SHOWN', 'ACCEPTED', 'DISMISSED'] as const, 'offer state') },
        pageId: optionalText(item.pageId, 'item page'),
        at: text(item.at, 'item time'),
      };
    }),
    pages: list(view.pages, 'artifact pages').map(entry => {
      const page = object(entry, 'artifact page');
      return { id: text(page.id, 'page id'), key: text(page.key, 'page key'), version: count(page.version, 'page version'), title: text(page.title, 'page title'),
        commit: text(page.commit, 'page commit'), writtenAt: text(page.writtenAt, 'page time') };
    }),
  };
}

export function parseArtifactPage(value: unknown): string {
  return text(object(value, 'artifact page').html, 'artifact page');
}

export function parseArtifactTarget(value: unknown): ArtifactTarget {
  const target = object(value, 'artifact target');
  return { workspaceId: text(target.workspaceId, 'workspace'), artifactId: text(target.artifactId, 'artifact'), pageId: text(target.pageId, 'artifact page') };
}
