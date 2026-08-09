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
package com.bytequay.app.service.checks;

import java.nio.file.Path;
import java.util.List;

/**
 * One pluggable validation check run during the VALIDATING phase. The
 * concrete checks (unit tests, checkstyle, repo-rule checker) are
 * registered as Spring beans and run by the durable validation handlers;
 * an empty registry means validation passes trivially. Each returns the
 * failures it found (empty = clean).
 *
 * <p>This is the SPI seam the spec's {@code testRunner / checkstyleRunner
 * / ruleChecker} plug into — they land as {@code ValidationCheck} beans
 * without touching the service or the bounded loop.
 */
public interface ValidationCheck
{
    /** Run against {@code worktree} (may be null if the task's worktree
     *  was reaped) for {@code taskId}; return the failures found. */
    List<ValidationFailure> run(String taskId, Path worktree);
}
