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
import hljs from 'highlight.js/lib/common';

/**
 * Syntax highlighting via highlight.js, GitHub-flavoured (colours live in
 * css/highlight.css). One shared entry point so the comment renderer, the
 * blob-permalink card, and the diff viewer all colour code the same way.
 *
 * We bundle highlight.js's "common" language set (java, the JS/TS family,
 * python, go, rust, c/c++/c#, sql, json, yaml, xml, bash, markdown, …) —
 * enough for the repos this app reviews without the full ~200-language
 * payload. Anything outside it falls back to auto-detection.
 */

/** File extension → highlight.js language id, for the languages we map
 *  explicitly. Unmapped extensions fall through to auto-detection. */
const EXT_TO_LANG: Record<string, string> = {
  java: 'java',
  kt: 'kotlin', kts: 'kotlin',
  ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
  py: 'python', rb: 'ruby', go: 'go', rs: 'rust',
  c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', hh: 'cpp',
  cs: 'csharp', php: 'php', swift: 'swift', m: 'objectivec', mm: 'objectivec',
  scala: 'scala', sh: 'bash', bash: 'bash', zsh: 'bash',
  sql: 'sql', json: 'json', yml: 'yaml', yaml: 'yaml',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml',
  css: 'css', scss: 'scss', less: 'less',
  md: 'markdown', markdown: 'markdown',
  pl: 'perl', lua: 'lua', r: 'r', ini: 'ini', toml: 'ini',
};

/** Resolve a highlight.js language for a file path, or undefined when we
 *  have no mapping registered in the bundle (caller should auto-detect). */
export function languageForPath(path: string): string | undefined {
  const name = (path.split('/').pop() ?? path).toLowerCase();
  if (name === 'dockerfile' || name.endsWith('.dockerfile')) {
    return hljs.getLanguage('dockerfile') ? 'dockerfile' : undefined;
  }
  const dot = name.lastIndexOf('.');
  const ext = dot >= 0 ? name.slice(dot + 1) : '';
  const lang = EXT_TO_LANG[ext];
  return lang && hljs.getLanguage(lang) ? lang : undefined;
}

/**
 * Highlight {@code code} to an HTML string of {@code <span class="hljs-…">}
 * tokens. With a known {@code language} we highlight against it (illegal
 * sequences ignored so a single diff line never throws); otherwise we
 * auto-detect. A failure falls back to escaped plain text, so the call is
 * always safe to feed into {@code dangerouslySetInnerHTML}.
 */
export function highlightToHtml(code: string, language?: string): string {
  try {
    const html = language !== undefined && hljs.getLanguage(language)
      ? hljs.highlight(code, { language, ignoreIllegals: true }).value
      : hljs.highlightAuto(code).value;
    return colorizeTypes(html);
  }
  catch {
    return escapeHtml(code);
  }
}

/**
 * Colour bare type references — highlight.js tags keywords, strings, and
 * declaration titles, but leaves plain references to classes/collections
 * (`List`, `Schema`, `ImmutableSet`) as uncoloured text. Wrap any remaining
 * PascalCase / SCREAMING_CASE identifier in an {@code hljs-type} span so
 * types and constants read distinctly from methods and locals.
 *
 * Operates only on the text between hljs's own tags (the odd-indexed split
 * segments are tags), so it never touches an already-highlighted token,
 * string body, or comment — those are inside spans.
 */
function colorizeTypes(html: string): string {
  return html.replace(/(<[^>]*>)|([^<]+)/g, (_m, tag: string | undefined, text: string | undefined) =>
    tag !== undefined
      ? tag
      : (text ?? '').replace(/\b[A-Z][A-Za-z0-9_]*\b/g, m => `<span class="hljs-type">${m}</span>`));
}

function escapeHtml(s: string): string {
  return s
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
}
