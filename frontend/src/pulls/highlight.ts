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
import java from 'highlight.js/lib/languages/java';

// highlight.js leaves Java method calls and constants plain, so a line of calls showed no color where GitHub colors both.
// Code views import highlight.js from here so this grammar is registered before their first highlight.
hljs.registerLanguage('java', api => {
  const language = java(api);
  return {
    ...language,
    contains: [
      ...language.contains ?? [],
      // Last, because the earlier mode wins a tie: declarations, `new Type(` and `return (` keep their own colors.
      { scope: 'title.function.invoke', match: /\b(?!(?:if|for|while|switch|catch|synchronized|try|return|throw|assert|yield|case|this|super)\b)[A-Za-z_$][\w$]*(?=\s*\()/, relevance: 0 },
      { scope: 'variable.constant', match: /\b[A-Z][A-Z\d_]+\b/, relevance: 0 },
    ],
  };
});

export default hljs;
