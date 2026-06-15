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
import { describe, expect, it } from 'vitest';
import { tokenizeShell } from './shellHighlight';

const join = (src: string) => tokenizeShell(src).map(t => t.text).join('');

describe('tokenizeShell', () => {
  it('classifies strings, variables, operators, and comments', () => {
    const toks = tokenizeShell('echo "hi $USER" | grep x # note');
    expect(toks.some(t => t.kind === 'string' && t.text === '"hi $USER"')).toBe(true);
    expect(toks.some(t => t.kind === 'op' && t.text === '|')).toBe(true);
    expect(toks.some(t => t.kind === 'comment' && t.text === '# note')).toBe(true);
  });

  it('does not treat # or | inside single quotes as comment/op', () => {
    const toks = tokenizeShell("sed 's#a#b#' x");
    expect(toks.find(t => t.kind === 'string')?.text).toBe("'s#a#b#'");
    expect(toks.some(t => t.kind === 'comment')).toBe(false);
    expect(toks.some(t => t.kind === 'op')).toBe(false);
  });

  it('highlights $name and ${name} variables', () => {
    expect(tokenizeShell('$HOME ${PATH}').filter(t => t.kind === 'var').map(t => t.text))
      .toEqual(['$HOME', '${PATH}']);
  });

  it('preserves newlines so the command keeps its real lines', () => {
    expect(join('a\nb')).toBe('a\nb');
  });

  it('round-trips a realistic command exactly (lossless)', () => {
    const src = 'TOKEN=$(git remote get-url origin | sed -E \'s#x#y#\') && curl -sS "$URL"';
    expect(join(src)).toBe(src);
  });

  it('round-trips a multi-line heredoc exactly', () => {
    const src = 'cat > /tmp/b.json <<\'EOF\'\n{ "title": "x" }\nEOF';
    expect(join(src)).toBe(src);
  });
});
