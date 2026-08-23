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
package com.bytequay.app.web.cherrypick;

import static java.util.Objects.requireNonNull;

/**
 * Preview request body.
 *
 * <p>The repository path, local target branch, and work branch are derived by
 * the backend. The optional source remote must name one of the workspace's
 * configured remotes; when absent the linked upstream is used.
 */
public record CherryPickPreviewRequest(
        String sourceRemote,
        String sourceBaseRef,
        String sourceTipRef,
        String targetBranch)
{
    public CherryPickPreviewRequest
    {
        requireNonNull(sourceBaseRef, "sourceBaseRef is null");
        requireNonNull(sourceTipRef, "sourceTipRef is null");
        requireNonNull(targetBranch, "targetBranch is null");
    }
}
