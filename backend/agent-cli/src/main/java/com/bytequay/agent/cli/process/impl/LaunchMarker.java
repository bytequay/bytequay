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
package com.bytequay.agent.cli.process.impl;

import com.bytequay.agent.cli.process.ExecutionProfile;
import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Durable on-disk launch fact for one supervised process.
 *
 * <p>The marker carries no prompt text, credential, raw argv, or core-domain object. It is written
 * and fsynced by the supervisor and read back by process recovery in a later backend instance.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record LaunchMarker(
        int format,
        String launchId,
        String backendId,
        ProcessOwner.Kind ownerKind,
        String ownerId,
        ExecutionProfile profile,
        String executable,
        String argvDigest,
        LaunchPhase phase,
        Optional<Long> pid,
        Optional<String> startToken,
        Optional<String> launcherCommand,
        Optional<Long> processGroup,
        Optional<Integer> exitCode,
        Optional<ProcessExit.Reason> exitReason,
        Optional<ProcessExit.TreeTermination> treeTermination)
{
    /** Only this exact format proves what a marker's absent fields mean. */
    static final int CURRENT_FORMAT = 1;

    LaunchMarker
    {
        checkArgument(format == CURRENT_FORMAT, "unsupported launch marker format");
        requireNonNull(launchId, "launchId is null");
        requireNonNull(backendId, "backendId is null");
        requireNonNull(ownerKind, "ownerKind is null");
        requireNonNull(ownerId, "ownerId is null");
        requireNonNull(profile, "profile is null");
        requireNonNull(executable, "executable is null");
        requireNonNull(argvDigest, "argvDigest is null");
        requireNonNull(phase, "phase is null");
        requireNonNull(pid, "pid is null");
        requireNonNull(startToken, "startToken is null");
        requireNonNull(launcherCommand, "launcherCommand is null");
        requireNonNull(processGroup, "processGroup is null");
        requireNonNull(exitCode, "exitCode is null");
        requireNonNull(exitReason, "exitReason is null");
        requireNonNull(treeTermination, "treeTermination is null");
        checkArgument(profile == ownerKind.profile(), "profile does not match the owner kind");
        checkArgument(phase == LaunchPhase.PREPARED || pid.isPresent(), "launched marker has no pid");
    }

    static LaunchMarker prepared(
            String launchId,
            String backendId,
            ProcessOwner owner,
            String executable,
            String argvDigest)
    {
        return new LaunchMarker(
                CURRENT_FORMAT,
                launchId,
                backendId,
                owner.kind(),
                owner.id(),
                owner.profile(),
                executable,
                argvDigest,
                LaunchPhase.PREPARED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    LaunchMarker gated(ProcessIdentity identity, String launcherCommand, long processGroup)
    {
        return new LaunchMarker(
                format,
                launchId,
                backendId,
                ownerKind,
                ownerId,
                profile,
                executable,
                argvDigest,
                LaunchPhase.GATED,
                Optional.of(identity.pid()),
                Optional.of(identity.startToken().toString()),
                Optional.of(launcherCommand),
                Optional.of(processGroup),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    LaunchMarker withPhase(LaunchPhase next)
    {
        return new LaunchMarker(
                format,
                launchId,
                backendId,
                ownerKind,
                ownerId,
                profile,
                executable,
                argvDigest,
                next,
                pid,
                startToken,
                launcherCommand,
                processGroup,
                exitCode,
                exitReason,
                treeTermination);
    }

    LaunchMarker terminal(ProcessExit exit)
    {
        return new LaunchMarker(
                format,
                launchId,
                backendId,
                ownerKind,
                ownerId,
                profile,
                executable,
                argvDigest,
                LaunchPhase.TERMINAL,
                pid,
                startToken,
                launcherCommand,
                processGroup,
                Optional.of(exit.exitCode()),
                Optional.of(exit.reason()),
                Optional.of(exit.treeTermination()));
    }

    ProcessOwner owner()
    {
        return new ProcessOwner(ownerKind, ownerId);
    }

    /**
     * Returns the recorded operating-system identity of the gated launcher and its payload.
     *
     * @return recorded identity, or empty when the marker has no usable identity
     */
    Optional<ProcessIdentity> identity()
    {
        if (pid.isEmpty() || startToken.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProcessIdentity(pid.orElseThrow(), Instant.parse(startToken.orElseThrow())));
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
