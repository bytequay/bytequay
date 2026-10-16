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
import { count, flag, object, oneOf, optionalCount, optionalText, text } from '../pulls/wireParse';
import { list } from './contracts';

/** A finding of one side of a replay; a finding about the whole change has no path. */
export type ReplayFindingDto = { id: string; label: string; title: string; severity: string | null; path: string | null; side: string | null;
  startLine: number | null; endLine: number | null };
export const REPLAY_STATES = ['RUNNING', 'DONE', 'INCOMPLETE', 'STOPPED', 'FAILED', 'GONE'] as const;
/** One review of a replay, with memory or without. */
export type ReplaySideDto = { memory: boolean; reviewId: string; state: typeof REPLAY_STATES[number]; outcome: string | null; waiting: boolean;
  findings: ReplayFindingDto[] };
/** Whether one side raised a comment: its findings near the comment's lines, and the user's call when it disagrees with them. */
export type ReplayMarkDto = { memory: boolean; findings: string[]; raised: boolean; corrected: boolean };
/** A maintainer's first comment in a review thread, at the lines of the commit it was written on. */
export type ReplayCommentDto = { id: string; author: string; path: string; side: string; line: number | null; startLine: number | null; onReplayedHead: boolean;
  body: string; url: string; at: string; marks: ReplayMarkDto[] };
/** Agent Review run twice on a merged pull request at the head its maintainers first reviewed, next to what they said. */
export type ReplayDto = { id: string; number: number; title: string; url: string; head: string; fallback: string | null; cutoff: string; reviewer: string;
  excludedMemories: number; createdAt: string; sides: ReplaySideDto[]; comments: ReplayCommentDto[] };
export type StartReplayRequest = { repo: string; number: number };
export type MarkReplayRequest = { commentId: string; memory: boolean; raised: boolean };

const line = (value: unknown, what: string): number | null => value == null ? null : count(value, what);

function finding(value: unknown): ReplayFindingDto {
  const item = object(value, 'replay finding');
  return { id: text(item.id, 'replay finding id'), label: text(item.label, 'replay finding label'), title: text(item.title, 'replay finding title'),
    severity: optionalText(item.severity), path: optionalText(item.path), side: optionalText(item.side), startLine: optionalCount(item.startLine),
    endLine: optionalCount(item.endLine) };
}

function side(value: unknown): ReplaySideDto {
  const item = object(value, 'replay side');
  return { memory: flag(item.memory, 'replay side memory'), reviewId: text(item.reviewId, 'replay review'), state: oneOf(item.state, REPLAY_STATES, 'replay state'),
    outcome: optionalText(item.outcome), waiting: flag(item.waiting, 'replay waiting'), findings: list(item.findings, 'replay findings', finding) };
}

function comment(value: unknown): ReplayCommentDto {
  const item = object(value, 'replay comment');
  return { id: text(item.id, 'replay comment id'), author: text(item.author, 'replay comment author'), path: text(item.path, 'replay comment path'),
    side: text(item.side, 'replay comment side'), line: line(item.line, 'replay comment line'), startLine: line(item.startLine, 'replay comment start line'),
    onReplayedHead: flag(item.onReplayedHead, 'replay comment head'), body: text(item.body, 'replay comment body'), url: text(item.url, 'replay comment url'),
    at: text(item.at, 'replay comment time'), marks: list(item.marks, 'replay marks', mark => {
      const entry = object(mark, 'replay mark');
      return { memory: flag(entry.memory, 'replay mark memory'), findings: list(entry.findings, 'replay mark findings', id => text(id, 'replay mark finding')),
        raised: flag(entry.raised, 'replay mark raised'), corrected: flag(entry.corrected, 'replay mark corrected') };
    }) };
}

export function parseReplay(value: unknown): ReplayDto {
  const item = object(value, 'replay');
  return { id: text(item.id, 'replay id'), number: count(item.number, 'replay number'), title: text(item.title, 'replay title'), url: text(item.url, 'replay url'),
    head: text(item.head, 'replay head'), fallback: optionalText(item.fallback), cutoff: text(item.cutoff, 'replay cutoff'), reviewer: text(item.reviewer, 'replay reviewer'),
    excludedMemories: count(item.excludedMemories, 'replay excluded memories'), createdAt: text(item.createdAt, 'replay time'),
    sides: list(item.sides, 'replay sides', side), comments: list(item.comments, 'replay comments', comment) };
}

export function parseReplays(value: unknown): ReplayDto[] {
  return list(object(value, 'replays').replays, 'replays', parseReplay);
}

export function parseStartReplayRequest(value: unknown): StartReplayRequest {
  const item = object(value, 'replay request');
  const repo = text(item.repo, 'replay repository');
  const number = count(item.number, 'replay pull request');
  if (!/^[A-Za-z0-9-]+\/(?!\.\.?$)[\w.-]+$/.test(repo) || !Number.isSafeInteger(number) || number < 1) throw new Error('Malformed replay request');
  return { repo, number };
}

export function parseMarkReplayRequest(value: unknown): MarkReplayRequest {
  const item = object(value, 'replay mark');
  const commentId = text(item.commentId, 'replay mark comment');
  if (commentId.trim() === '') throw new Error('Malformed replay mark');
  return { commentId, memory: flag(item.memory, 'replay mark memory'), raised: flag(item.raised, 'replay mark raised') };
}

export function parseReplayId(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f-]{36}$/.test(value)) throw new Error('Malformed replay id');
  return value;
}

export const replayRunning = (replay: ReplayDto): boolean => replay.sides.some(item => item.state === 'RUNNING');

/** How many maintainer comments one side raised, after the user's calls. */
export function raised(replay: ReplayDto, memory: boolean): number {
  return replay.comments.filter(item => item.marks.some(mark => mark.memory === memory && mark.raised)).length;
}

/** The finished replays' comments and what each side raised, summed. */
export function totals(replays: ReplayDto[]): { replays: number; comments: number; withMemory: number; withoutMemory: number } {
  const done = replays.filter(replay => !replayRunning(replay) && replay.sides.every(item => item.state === 'DONE' || item.state === 'INCOMPLETE'));
  return { replays: done.length, comments: done.reduce((sum, replay) => sum + replay.comments.length, 0),
    withMemory: done.reduce((sum, replay) => sum + raised(replay, true), 0), withoutMemory: done.reduce((sum, replay) => sum + raised(replay, false), 0) };
}

/** The replay file by file: the maintainers' comments on each file, and each side's findings there that no comment matched. */
export function replayFiles(replay: ReplayDto): { path: string; comments: ReplayCommentDto[]; others: { memory: boolean; finding: ReplayFindingDto }[] }[] {
  const matched = new Set(replay.comments.flatMap(item => item.marks.flatMap(mark => mark.findings)));
  const others = replay.sides.flatMap(item => item.findings.filter(found => !matched.has(found.id)).map(found => ({ memory: item.memory, finding: found })));
  const paths = [...new Set([...replay.comments.map(item => item.path), ...others.map(other => other.finding.path ?? '')])].sort((left, right) =>
    left === '' ? 1 : right === '' ? -1 : left.localeCompare(right));
  return paths.map(path => ({ path, comments: replay.comments.filter(item => item.path === path),
    others: others.filter(other => (other.finding.path ?? '') === path) }));
}
