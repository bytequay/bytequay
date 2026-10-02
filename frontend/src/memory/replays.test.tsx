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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ReplayDrawer, ReplaysSection } from './ReplaysSection';
import { parseMarkReplayRequest, parseReplay, parseReplays, parseStartReplayRequest, raised, replayFiles, totals, type ReplayDto } from './replays';

const side = (memory: boolean, state: string, findings: Record<string, unknown>[]) => ({ memory, reviewId: memory ? 'review-with' : 'review-without', state,
  outcome: null as string | null, waiting: false, findings });
const finding = (id: string, label: string, title: string, path: string | null, startLine: number | null, endLine: number | null) => ({ id, label, title,
  severity: null as string | null, path, side: path === null ? null : 'RIGHT', startLine, endLine });
const payload: Record<string, unknown> = {
  id: '6f1c2c1e-0000-4000-8000-000000000001', number: 31201, title: 'Add a property', url: 'https://github.com/trinodb/trino/pull/31201', head: 'a'.repeat(40),
  fallback: null, cutoff: '2026-09-19T10:00:00Z', reviewer: 'bo', excludedMemories: 2, createdAt: '2026-10-02T09:00:00Z',
  sides: [
    side(true, 'DONE', [finding('f1', 'F-1', 'Document the property', 'plugin/x/A.java', 15, 16), finding('f2', 'F-2', 'Name the behavior', null, null, null)]),
    side(false, 'DONE', [finding('f3', 'F-1', 'Unused import', 'plugin/x/B.java', 3, 3)])],
  comments: [
    { id: 'c1', author: 'bo', path: 'plugin/x/A.java', side: 'RIGHT', line: 14, startLine: null, onReplayedHead: true, body: 'Please document it.',
      url: 'https://github.com/trinodb/trino/pull/31201#discussion_r1', at: '2026-09-19T10:00:00Z',
      marks: [{ memory: true, findings: ['f1'], raised: true, corrected: false }, { memory: false, findings: [], raised: true, corrected: true }] },
    { id: 'c2', author: 'cy', path: 'plugin/x/C.java', side: 'LEFT', line: 9, startLine: 7, onReplayedHead: false, body: 'Why remove this?',
      url: 'https://github.com/trinodb/trino/pull/31201#discussion_r2', at: '2026-09-20T10:00:00Z',
      marks: [{ memory: true, findings: [], raised: false, corrected: false }, { memory: false, findings: [], raised: false, corrected: false }] }],
};
const done = parseReplay(payload);
const running: ReplayDto = { ...done, id: 'running', sides: done.sides.map(item => item.memory ? { ...item, state: 'RUNNING', waiting: true } : item) };
const NOW = Date.parse('2026-10-02T10:00:00Z');

describe('replay contracts', () => {
  it('parses replays and refuses malformed ones and requests', () => {
    expect(parseReplays({ replays: [payload] })).toEqual([done]);
    expect(done.sides[0].findings[1].path).toBeNull();
    expect(() => parseReplay({ ...payload, sides: [{ ...side(true, 'LOST', []) }] })).toThrow('Malformed replay state');
    expect(parseStartReplayRequest({ repo: 'trinodb/trino', number: 31201 })).toEqual({ repo: 'trinodb/trino', number: 31201 });
    expect(() => parseStartReplayRequest({ repo: 'trinodb/..', number: 31201 })).toThrow('Malformed replay request');
    expect(() => parseStartReplayRequest({ repo: 'trinodb/trino', number: 0 })).toThrow('Malformed replay request');
    expect(() => parseMarkReplayRequest({ commentId: ' ', memory: true, raised: true })).toThrow('Malformed replay mark');
  });

  it('counts what each side raised after the user’s calls, over finished replays only', () => {
    expect(raised(done, true)).toBe(1);
    expect(raised(done, false)).toBe(1);
    expect(totals([done, running])).toEqual({ replays: 1, comments: 2, withMemory: 1, withoutMemory: 1 });
  });

  it('lays the replay out file by file, with the findings no comment matched and the whole change last', () => {
    const files = replayFiles(done);
    expect(files.map(file => file.path)).toEqual(['plugin/x/A.java', 'plugin/x/B.java', 'plugin/x/C.java', '']);
    expect(files[0].others).toEqual([]);
    expect(files[1].others.map(other => [other.memory, other.finding.id])).toEqual([[false, 'f3']]);
    expect(files[3].others.map(other => other.finding.id)).toEqual(['f2']);
  });
});

describe('replays on the Memory page', () => {
  it('shows each replay’s scores, or that it runs, and the sum over finished replays', () => {
    const markup = renderToStaticMarkup(<ReplaysSection replays={[running, done]} now={NOW} onOpen={() => {}} />);
    expect(markup).toContain('Replays · 2');
    expect(markup).toContain('title="Over 1 finished replay"');
    expect(markup).toContain('title="With memory: raised 1 of 2 maintainer comments"');
    expect(markup).toContain('Waiting for approval');
    expect(renderToStaticMarkup(<ReplaysSection replays={[]} now={NOW} onOpen={() => {}} />)).toContain('No replay yet.');
  });

  it('sets each maintainer comment beside both sides’ calls, file by file', () => {
    const markup = renderToStaticMarkup(<ReplayDrawer replay={done} busy={false} now={NOW} onClose={() => {}} onMark={() => {}} onDelete={() => {}}
      onOpenReview={() => {}} onOpenUrl={() => {}} />);
    expect(markup).toContain('the head when @bo first reviewed');
    expect(markup).toContain('did not get 2 memories from this pull');
    expect(markup).toContain('title="With memory: raised by F-1"');
    expect(markup).toContain('title="Without memory: raised, your call"');
    expect(markup).toContain('L7–L9');
    expect(markup).toContain('later head');
    expect(markup).toContain('Unused import');
    expect(markup).toContain('Whole change');
    expect(markup).toContain('>Delete replay</button>');
    expect(renderToStaticMarkup(<ReplayDrawer replay={running} busy={false} now={NOW} onClose={() => {}} onMark={() => {}} onDelete={() => {}}
      onOpenReview={() => {}} onOpenUrl={() => {}} />)).toContain('disabled="">Delete replay</button>');
  });
});
