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
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { ESLint } = require('eslint');
const { join } = require('node:path');
const resolver = require('./workspace-resolver.cjs');

test('resolves built public workspace exports and refuses internal paths', () => {
  assert.equal(resolver.resolve('@bytequay/ui/settings', __filename).path, require.resolve('@bytequay/ui/settings'));
  assert.deepEqual(resolver.resolve('@bytequay/ui/src/settings', __filename), { found: false });
  assert.deepEqual(resolver.resolve('@bytequay/ui/not-an-export', __filename), { found: false });
  assert.equal(resolver.resolve('./workspace-resolver.cjs', __filename).path, require.resolve('./workspace-resolver.cjs'));
});

test('ESLint rejects workspace implementation imports without falling back to filesystem resolution', async () => {
  const eslint = new ESLint({ cwd: join(__dirname, '..'), overrideConfig: { rules: { 'header/header': 'off' } } });
  for (const [name, valid] of [['@bytequay/ui/settings', true], ['@bytequay/ui/src/settings', false], ['@bytequay/ui/dist/settings.js', false]]) {
    const [result] = await eslint.lintText('import { SettingsCard } from ' + JSON.stringify(name) + '; void SettingsCard;', {
      filePath: join(__dirname, '../src/moduleBoundary.ts'),
    });
    assert.equal(result.messages.some(message => message.ruleId === 'import/no-unresolved'), !valid, name);
  }
});
