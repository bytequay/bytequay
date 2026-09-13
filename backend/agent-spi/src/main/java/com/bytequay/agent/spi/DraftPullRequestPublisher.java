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
package com.bytequay.agent.spi;

/**
 * Publishes one task's already-verified development revision as a draft pull request.
 *
 * <p>ByteQuay owns the push and the GitHub write: the credential never reaches the provider
 * process, so a session needs no {@code git push} and no GitHub CLI. Publication is open only
 * while ByteQuay is running the task's draft-pull-request follow-up; every other call is
 * refused, and the accepted destination and revision come from that follow-up rather than from
 * the caller.
 */
@FunctionalInterface
public interface DraftPullRequestPublisher
{
    DraftPullRequestPublisher NONE = (taskId, title, body) -> {
        throw new IllegalStateException("Draft pull request publication is unavailable");
    };

    /**
     * Publishes the task's verified revision.
     *
     * @param taskId task whose publication window must be open
     * @param title pull request title chosen by the root agent
     * @param body pull request body chosen by the root agent
     * @return a short human-readable result naming the created or reused pull request
     * @throws RuntimeException when publication is not open or the remote write did not verify
     */
    String publish(String taskId, String title, String body);
}
