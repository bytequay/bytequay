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
package com.bytequay.app.service.localpr;

/**
 * Published whenever a task's branch is pushed and/or its PR opened through
 * any path other than {@link PRPublishService#push} itself — a push /
 * open_pr gate (auto-approved or not), or the ship/next tool flow. The
 * listener syncs the task's PR row so its own panel doesn't keep
 * offering "ready to push" for a push that already happened elsewhere.
 *
 * @param taskId the task whose PR row to sync
 * @param remotePrNumber the PR now open on the remote
 * @param remotePrUrl the PR's github.com URL
 */
public record PrPushedEvent(String taskId, int remotePrNumber, String remotePrUrl)
{
}
