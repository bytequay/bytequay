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
package com.bytequay.app.service.goal;

/**
 * Where a goal item stands. Finished: a merged or closed pull request, a closed issue, a done
 * ticket, an ended task. Working: an open pull request, an issue with a development task, a ticket
 * in progress, a running task. Idle: everything else.
 */
public enum GoalItemState
{
    IDLE,
    WORKING,
    FINISHED
}
