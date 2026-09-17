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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import MarkdownBody from './MarkdownBody';
import { applyMarkdownCommand, insertSuggestion, MarkdownToolbar } from './MarkdownEditor';
import { ReviewLineComposer } from './PullChanges';
import ReviewComposer from './agent-review/ReviewComposer';

describe('applyMarkdownCommand', () => {
  it('wraps the selection and keeps it selected', () => {
    expect(applyMarkdownCommand('a bold word', 2, 6, 'bold'))
      .toEqual({ value: 'a **bold** word', start: 4, end: 8 });
  });

  it('parks the caret between the markers when nothing is selected', () => {
    expect(applyMarkdownCommand('', 0, 0, 'italic')).toEqual({ value: '__', start: 1, end: 1 });
  });

  it('fences a multi-line code selection', () => {
    expect(applyMarkdownCommand('one\ntwo', 0, 7, 'code'))
      .toEqual({ value: '```\none\ntwo\n```', start: 4, end: 11 });
  });

  it('selects the placeholder of an inserted link', () => {
    expect(applyMarkdownCommand('see docs', 4, 8, 'link'))
      .toEqual({ value: 'see [docs](url)', start: 11, end: 14 });
  });

  it('prefixes every line the selection touches', () => {
    expect(applyMarkdownCommand('one\ntwo\nthree', 5, 5, 'quote'))
      .toEqual({ value: 'one\n> two\nthree', start: 4, end: 9 });
    expect(applyMarkdownCommand('one\ntwo', 0, 7, 'number'))
      .toEqual({ value: '1. one\n2. two', start: 0, end: 13 });
  });
});

describe('suggestions', () => {
  it('offers a leading suggestion button only when a suggestion is possible', () => {
    const plain = renderToStaticMarkup(<MarkdownToolbar onCommand={() => {}} />);
    const suggesting = renderToStaticMarkup(<MarkdownToolbar onCommand={() => {}} onSuggest={() => {}} />);

    expect(plain).not.toContain('Add a suggestion');
    expect(suggesting).toContain('<div class="pull-diff-review__toolbar"><button type="button" title="Add a suggestion" aria-label="Add a suggestion">');
    expect(suggesting.replace(/<button type="button" title="Add a suggestion"[^]*?<\/button><span class="pull-diff-review__divider" aria-hidden="true"><\/span>/, ''))
      .toBe(plain);
  });

  it('fences the lines into an empty draft and parks the caret after the last one', () => {
    expect(insertSuggestion('', 0, 0, ['int a = 1;', 'int b = 2;'])).toEqual({
      value: '```suggestion\nint a = 1;\nint b = 2;\n```',
      start: 35,
      end: 35,
    });
  });

  it('keeps the fence on its own lines around surrounding text', () => {
    expect(insertSuggestion('Consider', 8, 8, ['x'])).toEqual({ value: 'Consider\n```suggestion\nx\n```', start: 24, end: 24 });
    expect(insertSuggestion('before\nafter', 7, 7, ['x'])).toEqual({ value: 'before\n```suggestion\nx\n```\nafter', start: 22, end: 22 });
    expect(insertSuggestion('keep DROP tail', 5, 9, ['x'])).toEqual({ value: 'keep \n```suggestion\nx\n```\n tail', start: 21, end: 21 });
  });

  it('grows the fence past backtick runs in the lines so the block still renders', () => {
    const line = 'String fence = "```";';
    const inserted = insertSuggestion('', 0, 0, [line]);

    expect(inserted.value).toBe(`\`\`\`\`suggestion\n${line}\n\`\`\`\``);
    expect(inserted.start).toBe(15 + line.length);
    expect(renderToStaticMarkup(<MarkdownBody body={inserted.value} suggestion={{ startLine: 4, lines: ['String fence = null;'] }} />))
      .toContain('<span>+</span>String fence = <mark>&quot;```&quot;</mark>;</td>');
  });
});

describe('the Private label', () => {
  const PILL = '<button type="button" class="pull-diff-review__private" disabled="">';
  const lineComposer = (canReview: boolean, askAgent: boolean) => renderToStaticMarkup(<ReviewLineComposer heading="Add a comment on line R1"
    canReview={canReview} reviewDisabledReason="Outside this diff" body="" onBodyChange={() => {}} preview={false} onPreviewChange={() => {}}
    error={null} onError={() => {}} bodyRef={{ current: null }} submitting={false} posting={false}
    askAgent={askAgent ? { sending: false, disabledReason: 'The agent is reconnecting.', onSend: () => {} } : undefined}
    onCancel={() => {}} onAddPending={() => {}} onPostNow={() => {}} />);
  const reviewComposer = (outgoing: string | null) => renderToStaticMarkup(<ReviewComposer repository="owner/repo" value=""
    placeholder="Ask" ariaLabel="Question" primaryLabel="Ask agent" outgoing={outgoing} onChange={() => {}} onSubmit={() => {}} onCancel={() => {}} />);

  it('shows only when every action in the editor is private', () => {
    expect(lineComposer(false, true)).toContain(PILL);
    expect(lineComposer(true, true)).not.toContain('pull-diff-review__private');
    expect(lineComposer(true, false)).not.toContain('pull-diff-review__private');
    expect(reviewComposer(null)).toContain(PILL);
    expect(reviewComposer('Posts to GitHub')).not.toContain('pull-diff-review__private');
  });

  it('replaces the help text, and disabled reasons become titles plus off-screen status', () => {
    const markup = lineComposer(true, true);

    expect(markup).not.toContain('Private —');
    const statusId = markup.match(/<p id="([^"]+)" class="sr-only" role="status">The agent is reconnecting\. Outside this diff<\/p>/)?.[1];
    expect(statusId).toBeDefined();
    expect(markup.match(/role="status"/g)).toHaveLength(1);
    expect(markup).toContain('title="The agent is reconnecting.">Send to agent</button>');
    expect(markup).toContain('title="Outside this diff">Add to review</button>');
    expect(markup).toContain(`<textarea autofocus="" aria-label="Add a comment on line R1" aria-describedby="${statusId}"`);
    expect(reviewComposer(null)).toMatch(/<p id="[^"]+" class="sr-only" role="status"><\/p>/);
  });

  it('loads its stylesheet before pulls.css so the pending-comment editor can restyle it', () => {
    const index = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    const css = readFileSync(resolve(process.cwd(), 'src/css/markdown-editor.css'), 'utf8');

    expect(index.indexOf("@import './css/markdown-editor.css';")).toBeGreaterThan(-1);
    expect(index.indexOf("@import './css/markdown-editor.css';")).toBeLessThan(index.indexOf("@import './css/pulls.css';"));
    expect(css).toContain('.sr-only { position: absolute;');
  });
});
