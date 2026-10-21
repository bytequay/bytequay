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
import { renderToStaticMarkup } from 'react-dom/server';
import { AgentTaskBody } from '../AgentTaskScreen';
import { initialAgentTaskState, type AgentTaskState } from '../model';
import { TASK_BODY_NOOPS } from './previewBridge';

export type TaskBodyProps = Partial<Omit<ComponentProps<typeof AgentTaskBody>, 'state'>>;

export function renderTaskBody(state: Partial<AgentTaskState>, props: TaskBodyProps = {}): string {
  return renderToStaticMarkup(<AgentTaskBody state={{ ...initialAgentTaskState(), ...state }} models={[]} {...TASK_BODY_NOOPS} {...props} />);
}
