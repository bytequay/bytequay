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

import com.google.common.collect.ImmutableList;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongFunction;

/** Reads session membership even after a child changes process group and is reparented. */
final class ProcessSessions
{
    private static final Optional<MethodHandle> GET_SESSION = sessionReader();
    private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final int NO_SUCH_PROCESS = 3;

    private ProcessSessions() {}

    static OptionalLong sessionOf(long pid)
    {
        if (GET_SESSION.isEmpty() || pid <= 0 || pid > Integer.MAX_VALUE) {
            return OptionalLong.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            int session = (int) GET_SESSION.orElseThrow().invokeExact(state, (int) pid);
            if (session > 0) {
                return OptionalLong.of(session);
            }
            // macOS retains zombies in ProcessHandle.allProcesses/isAlive, but getsid correctly
            // reports ESRCH: they can no longer execute or belong to a live session.
            return state.get(ValueLayout.JAVA_INT, ERRNO_OFFSET) == NO_SUCH_PROCESS ? OptionalLong.of(0) : OptionalLong.empty();
        }
        catch (Throwable failure) {
            return OptionalLong.empty();
        }
    }

    static Optional<List<Long>> members(long session)
    {
        return members(session, ProcessSessions::sessionOf);
    }

    static Optional<List<Long>> members(long session, LongFunction<OptionalLong> reader)
    {
        try (var processes = ProcessHandle.allProcesses()) {
            ImmutableList.Builder<Long> members = ImmutableList.builder();
            for (ProcessHandle process : processes.toList()) {
                if (process.pid() == 0) {
                    continue;
                }
                OptionalLong observed = reader.apply(process.pid());
                if (observed.isEmpty()) {
                    // A vanished process needs no cleanup; an unreadable live process is not proof.
                    if (process.isAlive()) {
                        return Optional.empty();
                    }
                }
                else if (observed.getAsLong() == session) {
                    members.add(process.pid());
                }
            }
            return Optional.of(members.build());
        }
        catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Optional<MethodHandle> sessionReader()
    {
        try {
            Linker linker = Linker.nativeLinker();
            return linker.defaultLookup().find("getsid")
                    .map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno")));
        }
        catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }
}
