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
package com.bytequay.app.domain;

import java.util.List;

/**
 * Reviewers to add or remove from a pull request.
 * Used for both POST (request) and DELETE (remove) at
 * /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers.
 *
 * @param reviewers     logins of individual users to request/remove (may be empty)
 * @param teamReviewers slugs of teams to request/remove (may be empty)
 */
public record RequestReviewersCommand(
        List<String> reviewers,
        List<String> teamReviewers)
{}
