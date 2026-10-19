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

import type { ComponentProps } from 'react';
import { JiraScreen as IntegrationScreen, JiraTicketLayer as IntegrationTicketLayer } from '@bytequay/jira/renderer';
import { JiraIntegration } from './JiraHost';

export { JiraIssueActions } from './JiraHost';
export { supportedTransitionField, transitionPayload } from '@bytequay/jira/renderer';

export default function JiraScreen(props: ComponentProps<typeof IntegrationScreen>) {
  return <JiraIntegration><IntegrationScreen {...props} /></JiraIntegration>;
}

export function JiraTicketLayer(props: ComponentProps<typeof IntegrationTicketLayer>) {
  return <JiraIntegration><IntegrationTicketLayer {...props} /></JiraIntegration>;
}
