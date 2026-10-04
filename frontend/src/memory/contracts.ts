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
import { parseContextBuildView, type ContextBuildViewDto } from '../repository-context/contracts';

export type MemoryKind = 'RULE' | 'RECIPE' | 'SPEC';
export type MemoryPart = 'REVIEW_COMMENT' | 'REVIEW' | 'COMMENT' | 'CLOSE';
export type MemoryWhere = 'TASK_MESSAGE' | 'REVIEW_MESSAGE' | 'RESOLVE' | 'DISMISS';
/** Where a memory came from, with the words it rests on; the user's words and commands have no address. */
export type MemorySourceDto = {
  kind: 'PULL_REQUEST' | 'PAGE' | 'USER' | 'COMMAND'; url: string | null; quote: string; at: string; section: string | null;
  pullRequest: null | { number: number; part: MemoryPart; author: string; maintainer: boolean; merged: boolean; resolved: boolean; outdated: boolean };
  user: null | { where: MemoryWhere; id: string; item: string };
  command: null | { taskId: string; turnId: string; command: string; directory: string; commit: string };
};
export type MemoryReportDto = { reason: string; quote: string; taskId: string; at: string };
/** One rule or spec agents receive, titled with the situation it covers; `missingScope` lists scope paths gone from the monitored head. */
export type MemoryDto = {
  id: string; kind: MemoryKind; title: string; text: string; scope: string[]; sources: MemorySourceDto[];
  origin: { user: boolean; description: string; taskId: string | null };
  version: number; previous: null | { title: string; text: string; scope: string[] }; delivered: number; cited: number; reports: MemoryReportDto[];
  missingScope: string[]; createdAt: string; updatedAt: string;
};
/** Whether reviews and development tasks of the workspace receive memory and the memory tool. */
export type MemorySwitchesDto = { reviews: boolean; development: boolean };
/** A guideline file every agent turn reads, at the monitored branch head. */
export type GuidelineFileDto = { path: string; bytes: number; cut: boolean; url: string };
/** The latest harvest: what it read, and the memories it saved, saw again or replaced. */
export type HarvestLastRunDto = {
  harvestId: string; label: string; startedAt: string; endedAt: string | null; pulls: number; threads: number; saved: string[]; seenAgain: number;
  replaced: number; outcome: 'RUNNING' | 'DONE' | 'FAILED' | 'NOTHING';
};
/** How far Learn from my PRs has read, and the latest harvest. */
export type MemoryHarvestDto = { cursor: { number: number; closedAt: string } | null; last: HarvestLastRunDto | null };
export type MemoryPageDto = { memories: MemoryDto[]; switches: MemorySwitchesDto; guidelines: GuidelineFileDto[]; head: string | null; harvest: MemoryHarvestDto };
/** Learn from my PRs, or Learn from this PR with the pull request and its repository. */
export type StartMemoryHarvestRequest = { requestKey: string; expectedBuildSequence: number; pull: number | null; repo: string | null };
/** Whether a run started, and how many pull requests and new maintainer threads it found. */
export type MemoryHarvestStartedDto = { started: boolean; pulls: number; threads: number; view: ContextBuildViewDto };

export function flag(value: unknown, what: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`Malformed ${what}`);
  return value;
}
export function list<T>(value: unknown, what: string, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value.map(parse);
}
const strings = (value: unknown, what: string): string[] => list(value, what, item => text(item, what));

function source(value: unknown): MemorySourceDto {
  const item = object(value, 'memory source');
  const part = item.pullRequest == null ? null : object(item.pullRequest, 'memory source pull request');
  const user = item.user == null ? null : object(item.user, 'memory source user');
  const command = item.command == null ? null : object(item.command, 'memory source command');
  return {
    kind: oneOf(item.kind, ['PULL_REQUEST', 'PAGE', 'USER', 'COMMAND'], 'memory source kind'), url: optionalText(item.url),
    quote: text(item.quote, 'memory source quote'), at: text(item.at, 'memory source time'), section: optionalText(item.section),
    user: user === null ? null : { where: oneOf(user.where, ['TASK_MESSAGE', 'REVIEW_MESSAGE', 'RESOLVE', 'DISMISS'], 'memory source where'),
      id: text(user.id, 'memory source user id'), item: text(user.item, 'memory source user item') },
    command: command === null ? null : { taskId: text(command.taskId, 'memory source command task'), turnId: text(command.turnId, 'memory source command turn'),
      command: text(command.command, 'memory source command'), directory: text(command.directory, 'memory source command directory'),
      commit: text(command.commit, 'memory source command commit') },
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
    id: text(item.id, 'memory id'), kind: oneOf(item.kind, ['RULE', 'RECIPE', 'SPEC'], 'memory kind'), title: text(item.title, 'memory title'),
    text: text(item.text, 'memory text'),
    scope: strings(item.scope, 'memory scope'), sources: list(item.sources, 'memory sources', source),
    origin: { user: flag(origin.user, 'memory origin'), description: text(origin.description, 'memory origin'), taskId: optionalText(origin.taskId) },
    version: count(item.version, 'memory version'),
    previous: previous === null ? null : { title: text(previous.title, 'memory previous title'), text: text(previous.text, 'memory previous text'),
      scope: strings(previous.scope, 'memory previous scope') },
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
    harvest: harvest(item.harvest),
  };
}

function harvest(value: unknown): MemoryHarvestDto {
  const item = object(value, 'harvest');
  const cursor = item.cursor == null ? null : object(item.cursor, 'harvest cursor');
  const last = item.last == null ? null : object(item.last, 'last harvest');
  return {
    cursor: cursor === null ? null : { number: count(cursor.number, 'harvest cursor'), closedAt: text(cursor.closedAt, 'harvest cursor time') },
    last: last === null ? null : {
      harvestId: text(last.harvestId, 'harvest id'), label: text(last.label, 'harvest label'), startedAt: text(last.startedAt, 'harvest start'),
      endedAt: optionalText(last.endedAt), pulls: count(last.pulls, 'harvest pulls'), threads: count(last.threads, 'harvest threads'),
      saved: strings(last.saved, 'harvest saves'), seenAgain: count(last.seenAgain, 'harvest repeats'), replaced: count(last.replaced, 'harvest replacements'),
      outcome: oneOf(last.outcome, ['RUNNING', 'DONE', 'FAILED', 'NOTHING'], 'harvest outcome'),
    },
  };
}

export function parseMemoryHarvestStarted(value: unknown, workspaceId: string): MemoryHarvestStartedDto {
  const item = object(value, 'harvest start');
  return { started: flag(item.started, 'harvest start'), pulls: count(item.pulls, 'harvest pulls'), threads: count(item.threads, 'harvest threads'),
    view: parseContextBuildView(item.view, workspaceId) };
}

export function parseStartMemoryHarvestRequest(value: unknown): StartMemoryHarvestRequest {
  const item = object(value, 'harvest request');
  for (const key of Object.keys(item)) if (!['requestKey', 'expectedBuildSequence', 'pull', 'repo'].includes(key)) throw new Error(`Malformed harvest request: ${key}`);
  const requestKey = text(item.requestKey, 'harvest request key');
  const sequence = count(item.expectedBuildSequence, 'harvest request sequence');
  const pull = item.pull == null ? null : count(item.pull, 'harvest pull request');
  const repo = item.repo == null ? null : text(item.repo, 'harvest repository');
  if (requestKey.trim() === '' || !Number.isSafeInteger(sequence) || sequence < 0 || (pull !== null && (!Number.isSafeInteger(pull) || pull < 1))
    || (pull === null) !== (repo === null) || (repo !== null && !/^[A-Za-z0-9-]+\/(?!\.\.?$)[\w.-]+$/.test(repo))) {
    throw new Error('Malformed harvest request');
  }
  return { requestKey, expectedBuildSequence: sequence, pull, repo };
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
export function parseMemoryTitle(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '' || value.length > 200) throw new Error('Malformed memory title');
  return value;
}
export function parseMemoryText(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '' || value.length > 2000) throw new Error('Malformed memory text');
  return value;
}
