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
import type { Plugin } from 'vite';
import { join } from 'node:path';

/** Keep VS Code's file decoding but replace its LGPL charset guesser with the pinned MIT implementation. */
export function encodingDetector(): Plugin {
  return {
    name: 'bytequay-encoding-detector',
    enforce: 'pre',
    resolveId(source) {
      if (source === 'jschardet') return join(__dirname, 'src/ide/encodingDetector.ts');
    },
  };
}

/** Vite 5 incorrectly projects named exports before TextMate unwraps its dynamic import's `main` namespace. */
export function textmateImports(): Plugin {
  return {
    name: 'bytequay-textmate-imports',
    enforce: 'pre',
    transform(code, id) {
      if (!id.includes('@codingame/monaco-vscode-textmate-service-override/')) return;
      return code.replaceAll(
        "await import('@codingame/monaco-vscode-api/_virtual/main').then",
        "await Promise.resolve(import('@codingame/monaco-vscode-api/_virtual/main')).then",
      );
    },
  };
}
