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
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';
import { encodingDetector, textmateImports } from './ideBuild';

export default defineConfig({
  plugins: [react(), encodingDetector(), textmateImports()],
  base: './',
  // This pinned release imports cssValue internally but omits its package export.
  resolve: { alias: { '@codingame/monaco-vscode-api/vscode/vs/base/browser/cssValue': resolve(__dirname, 'node_modules/@codingame/monaco-vscode-api/vscode/src/vs/base/browser/cssValue.js') } },
  build: { target: 'esnext', rollupOptions: { input: resolve(__dirname, 'ide/index.html') } },
  server: { host: '127.0.0.1' },
  worker: { format: 'es' },
});
