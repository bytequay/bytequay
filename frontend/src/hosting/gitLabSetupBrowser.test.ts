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
import { testBrowserScenarios } from '../fixtures/browserScenarios';

testBrowserScenarios([{
  name: 'manages GitLab accounts, watches projects and selects workspace accounts in Chromium',
  expected: 13, timeout: 60_000,
  fixture: { prefix: 'bytequay-gitlab-setup-', entry: 'src/hosting/fixtures/gitLabSetupBrowser.tsx', global: 'GitLabSetupTests', styles: true },
}]);
