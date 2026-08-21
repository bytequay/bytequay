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
package com.bytequay.app.cherrypick;

import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;

import java.nio.file.Path;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * What the user supplies to preview a range.
 *
 * <p>The local target branch and the work branch are derived by ByteQuay; the
 * user names neither.
 *
 * @param repository local clone to read
 * @param workspaceId durable workspace that owns the managed clone, when applicable
 * @param sourceRemote remote holding the source range
 * @param sourceBaseRef exclusive start of the range
 * @param sourceTipRef inclusive end of the range
 * @param targetRemote remote holding the target branch
 * @param targetBranch branch the commits are destined for
 */
public record PreviewCherryPickRangeCommand(
        Path repository,
        Optional<String> workspaceId,
        String sourceRemote,
        String sourceBaseRef,
        String sourceTipRef,
        String targetRemote,
        String targetBranch)
{
    private static final int MAX_REF_LENGTH = 255;

    public PreviewCherryPickRangeCommand
    {
        requireNonNull(repository, "repository is null");
        if (!repository.isAbsolute()) {
            throw new CherryPickInvalidRequestException("repository path is not absolute");
        }
        repository = repository.normalize();
        requireNonNull(workspaceId, "workspaceId is null");
        workspaceId.ifPresent(value -> checkRef(value, "workspaceId"));
        checkRef(sourceRemote, "sourceRemote");
        checkRef(sourceBaseRef, "sourceBaseRef");
        checkRef(sourceTipRef, "sourceTipRef");
        checkRef(targetRemote, "targetRemote");
        checkRef(targetBranch, "targetBranch");
    }

    /** Returns the local branch ByteQuay keeps synchronized with the remote target. */
    public String localTargetBranch()
    {
        return targetRemote + "-" + targetBranch.replace('/', '-');
    }

    /** Returns the remote-tracking ref the local target branch must match after a sync. */
    public String remoteTrackingRef()
    {
        return targetRemote + "/" + targetBranch;
    }

    private static void checkRef(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new CherryPickInvalidRequestException(name + " is blank");
        }
        if (value.length() > MAX_REF_LENGTH) {
            throw new CherryPickInvalidRequestException(name + " exceeds " + MAX_REF_LENGTH + " characters");
        }
        // Refs reach Git as argv values, so a shell cannot see them; these
        // characters would still corrupt the record-separated output parsing.
        if (value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new CherryPickInvalidRequestException(name + " contains a control character");
        }
        if (value.startsWith("-")) {
            throw new CherryPickInvalidRequestException(name + " looks like a command-line option");
        }
    }
}
