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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
        Optional<Map<Long, String>> descendantStartTokens,
        boolean descendantTrackingIncomplete,
        boolean workFinished,
        Optional<Integer> exitCode,
        Optional<ProcessExit.Reason> exitReason,
        Optional<ProcessExit.TreeTermination> treeTermination,
        Optional<String> recoveryBootSessionId)
{
    /** Only this exact format proves what a marker's absent fields mean. */
    static final int CURRENT_FORMAT = 4;

    LaunchMarker
    {
        checkArgument(format >= 1 && format <= CURRENT_FORMAT, "unsupported launch marker format");
        checkArgument(!workFinished || format >= 3, "legacy marker cannot prove provider work finished");
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
        requireNonNull(descendantStartTokens, "descendantStartTokens is null");
        descendantStartTokens = descendantStartTokens.map(ImmutableMap::copyOf);
        requireNonNull(exitCode, "exitCode is null");
        requireNonNull(exitReason, "exitReason is null");
        requireNonNull(treeTermination, "treeTermination is null");
        requireNonNull(recoveryBootSessionId, "recoveryBootSessionId is null");
        checkArgument(recoveryBootSessionId.isEmpty() || format >= 4, "legacy marker cannot identify a recovery boot session");
        recoveryBootSessionId = recoveryBootSessionId.map(value -> {
            String canonical = UUID.fromString(value).toString();
            checkArgument(canonical.equalsIgnoreCase(value), "invalid recovery boot session UUID");
            return canonical;
        });
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
                Optional.of(ImmutableMap.of()),
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    LaunchMarker gated(ProcessIdentity identity, String launcherCommand, long processGroup)
    {
        return copy(format, LaunchPhase.GATED, Optional.of(identity.pid()), Optional.of(identity.startToken().toString()), Optional.of(launcherCommand),
                Optional.of(processGroup), Optional.of(ImmutableMap.of()), false, false, Optional.empty(), Optional.empty(), Optional.empty(), recoveryBootSessionId);
    }

    LaunchMarker withPhase(LaunchPhase next)
    {
        return copy(format, next, pid, startToken, launcherCommand, processGroup, descendantStartTokens, descendantTrackingIncomplete(),
                workFinished, exitCode, exitReason, treeTermination, recoveryBootSessionId);
    }

    LaunchMarker withDescendant(ProcessIdentity identity)
    {
        ImmutableMap.Builder<Long, String> descendants = ImmutableMap.builder();
        descendantStartTokens.ifPresent(descendants::putAll);
        descendants.put(identity.pid(), identity.startToken().toString());
        return copy(format, phase, pid, startToken, launcherCommand, processGroup, Optional.of(descendants.buildKeepingLast()), descendantTrackingIncomplete(),
                workFinished, exitCode, exitReason, treeTermination, recoveryBootSessionId);
    }

    LaunchMarker withIncompleteDescendantTracking()
    {
        return copy(format, phase, pid, startToken, launcherCommand, processGroup, descendantStartTokens, true,
                workFinished, exitCode, exitReason, treeTermination, recoveryBootSessionId);
    }

    LaunchMarker withWorkFinished()
    {
        return copy(format, phase, pid, startToken, launcherCommand, processGroup, descendantStartTokens, descendantTrackingIncomplete(),
                true, exitCode, exitReason, treeTermination, recoveryBootSessionId);
    }

    LaunchMarker withRecoveryBootSessionId(String bootSessionId)
    {
        checkArgument(recoveryBootSessionId.isEmpty() || recoveryBootSessionId.orElseThrow().equalsIgnoreCase(bootSessionId), "recovery boot session is already recorded");
        return copy(CURRENT_FORMAT, phase, pid, startToken, launcherCommand, processGroup, descendantStartTokens, descendantTrackingIncomplete(),
                workFinished, exitCode, exitReason, treeTermination, Optional.of(bootSessionId));
    }

    LaunchMarker terminal(ProcessExit exit)
    {
        return copy(format, LaunchPhase.TERMINAL, pid, startToken, launcherCommand, processGroup, descendantStartTokens, descendantTrackingIncomplete(),
                workFinished, Optional.of(exit.exitCode()), Optional.of(exit.reason()), Optional.of(exit.treeTermination()), recoveryBootSessionId);
    }

    /** Legacy markers used an absent identity map to record an observation gap. */
    @Override
    public boolean descendantTrackingIncomplete()
    {
        return descendantTrackingIncomplete || descendantStartTokens.isEmpty();
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

    Optional<List<ProcessIdentity>> descendants()
    {
        if (descendantStartTokens.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(descendantStartTokens.orElseThrow().entrySet().stream()
                    .map(entry -> new ProcessIdentity(entry.getKey(), Instant.parse(entry.getValue())))
                    .collect(ImmutableList.toImmutableList()));
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private LaunchMarker copy(int format, LaunchPhase phase, Optional<Long> pid, Optional<String> startToken, Optional<String> launcherCommand,
            Optional<Long> processGroup, Optional<Map<Long, String>> descendantStartTokens, boolean descendantTrackingIncomplete, boolean workFinished,
            Optional<Integer> exitCode, Optional<ProcessExit.Reason> exitReason, Optional<ProcessExit.TreeTermination> treeTermination,
            Optional<String> recoveryBootSessionId)
    {
        return new LaunchMarker(format, launchId, backendId, ownerKind, ownerId, profile, executable, argvDigest, phase, pid, startToken, launcherCommand,
                processGroup, descendantStartTokens, descendantTrackingIncomplete, workFinished, exitCode, exitReason, treeTermination, recoveryBootSessionId);
    }
}
