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
import { GooglePage as Settings, GoogleScreen as Screen, type GoogleHost } from '@bytequay/google/renderer';
import MarkdownBody from '../pulls/MarkdownBody';
import BetterWordsButton from '../pulls/BetterWordsButton';
import { openWatchedGitHubLink } from '../githubReference';
import { settingsRoute } from '../settings/routes';
import '../css/pulls.css';
import '../css/agents.css';

const Markdown: GoogleHost['Markdown'] = ({ body }) => <MarkdownBody body={body} repository="" />;
const BetterWords: GoogleHost['BetterWords'] = props => <BetterWordsButton {...props} repository="" />;
const host = (): GoogleHost => ({ bridge: window.bridge, settingsUrl: settingsRoute('google'), openGitHubLink: openWatchedGitHubLink, Markdown, BetterWords });

export function GoogleScreen() {
  return <Screen host={host()} />;
}

export function GooglePage() {
  return <Settings host={host()} />;
}
