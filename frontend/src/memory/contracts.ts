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
import { count, object, oneOf, optionalText, text } from '../pulls/wireParse';

export type MemoryKind = 'RULE' | 'SPEC';
export type MemoryPart = 'REVIEW_COMMENT' | 'REVIEW' | 'COMMENT' | 'CLOSE';
/** Where a memory came from, with the words it rests on. */
export type MemorySourceDto = {
  kind: 'PULL_REQUEST' | 'PAGE'; url: string; quote: string; at: string; section: string | null;
  pullRequest: null | { number: number; part: MemoryPart; author: string; maintainer: boolean; merged: boolean; resolved: boolean; outdated: boolean };
};
export type MemoryReportDto = { reason: string; quote: string; taskId: string; at: string };
/** One rule or spec agents receive; `missingScope` lists scope paths gone from the monitored head. */
export type MemoryDto = {
  id: string; kind: MemoryKind; text: string; scope: string[]; sources: MemorySourceDto[];
  origin: { user: boolean; description: string; taskId: string | null };
  version: number; previous: null | { text: string; scope: string[] }; delivered: number; cited: number; reports: MemoryReportDto[];
  missingScope: string[]; createdAt: string; updatedAt: string;
};
/** Whether reviews and development tasks of the workspace receive memory and the memory tool. */
export type MemorySwitchesDto = { reviews: boolean; development: boolean };
/** A guideline file every agent turn reads, at the monitored branch head. */
export type GuidelineFileDto = { path: string; bytes: number; cut: boolean; url: string };
export type MemoryPageDto = { memories: MemoryDto[]; switches: MemorySwitchesDto; guidelines: GuidelineFileDto[]; head: string | null };

function flag(value: unknown, what: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`Malformed ${what}`);
  return value;
}
function list<T>(value: unknown, what: string, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value.map(parse);
}
const strings = (value: unknown, what: string): string[] => list(value, what, item => text(item, what));

function source(value: unknown): MemorySourceDto {
  const item = object(value, 'memory source');
  const part = item.pullRequest == null ? null : object(item.pullRequest, 'memory source pull request');
  return {
    kind: oneOf(item.kind, ['PULL_REQUEST', 'PAGE'], 'memory source kind'), url: text(item.url, 'memory source url'), quote: text(item.quote, 'memory source quote'),
    at: text(item.at, 'memory source time'), section: optionalText(item.section),
    pullRequest: part === null ? null : {
      number: count(part.number, 'memory source pull request number'), part: oneOf(part.part, ['REVIEW_COMMENT', 'REVIEW', 'COMMENT', 'CLOSE'], 'memory source part'),
      author: text(part.author, 'memory source author'), maintainer: flag(part.maintainer, 'memory source maintainer'), merged: flag(part.merged, 'memory source merged'),
      resolved: flag(part.resolved, 'memory source resolved'), outdated: flag(part.outdated, 'memory source outdated'),
    },
  };
}

export function parseMemory(value: unknown): MemoryDto {
  const entry = object(value, 'memory');
  const item = object(entry.memory, 'memory');
  const origin = object(item.origin, 'memory origin');
  const previous = item.previous == null ? null : object(item.previous, 'memory previous version');
  return {
    id: text(item.id, 'memory id'), kind: oneOf(item.kind, ['RULE', 'SPEC'], 'memory kind'), text: text(item.text, 'memory text'),
    scope: strings(item.scope, 'memory scope'), sources: list(item.sources, 'memory sources', source),
    origin: { user: flag(origin.user, 'memory origin'), description: text(origin.description, 'memory origin'), taskId: optionalText(origin.taskId) },
    version: count(item.version, 'memory version'),
    previous: previous === null ? null : { text: text(previous.text, 'memory previous text'), scope: strings(previous.scope, 'memory previous scope') },
    delivered: count(item.delivered, 'memory deliveries'), cited: count(item.cited, 'memory citations'),
    reports: list(item.reports, 'memory reports', report => {
      const found = object(report, 'memory report');
      return { reason: text(found.reason, 'memory report reason'), quote: text(found.quote, 'memory report quote'), taskId: text(found.taskId, 'memory report task'),
        at: text(found.at, 'memory report time') };
    }),
    missingScope: strings(entry.missingScope, 'memory missing scope'), createdAt: text(item.createdAt, 'memory time'), updatedAt: text(item.updatedAt, 'memory time'),
  };
}

export function parseMemorySwitches(value: unknown): MemorySwitchesDto {
  const item = object(value, 'memory switches');
  return { reviews: flag(item.reviews, 'memory switch for reviews'), development: flag(item.development, 'memory switch for development') };
}

export function parseMemoryPage(value: unknown): MemoryPageDto {
  const item = object(value, 'memory page');
  return {
    memories: list(item.memories, 'memories', parseMemory), switches: parseMemorySwitches(item.switches),
    guidelines: list(item.guidelines, 'guideline files', file => {
      const found = object(file, 'guideline file');
      return { path: text(found.path, 'guideline path'), bytes: count(found.bytes, 'guideline size'), cut: flag(found.cut, 'guideline cut'),
        url: text(found.url, 'guideline address') };
    }),
    head: optionalText(item.head),
  };
}

/** Request values the main process checks before calling the backend. */
export function parseMemoryId(value: unknown): string {
  if (typeof value !== 'string' || !/^m[1-9][0-9]{0,17}$/.test(value)) throw new Error('Malformed memory id');
  return value;
}
export function parseMemoryVersion(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) throw new Error('Malformed memory version');
  return value;
}
export function parseMemoryText(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '' || value.length > 2000) throw new Error('Malformed memory text');
  return value;
}
