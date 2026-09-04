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

import type { AgentCapabilitiesDto } from '../types';

/** An absent or malformed capability never becomes permission to expose a control. */
export const NO_AGENT_CAPABILITIES: AgentCapabilitiesDto = {
  sessionResume: false,
  partialAssistantText: false,
  structuredPlans: false,
  toolLifecycle: false,
  incrementalCommandOutput: false,
  hookLifecycle: false,
  nestedAgentActivity: false,
  interactiveApprovals: false,
  tokenUsage: false,
  steering: false,
  costProvenance: 'UNAVAILABLE',
  permissionPolicies: ['AUTO'],
  attachmentMediaTypes: [],
};
