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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Standalone JRE-only entry point, extracted outside the worktree before launching a provider. */
public final class ProcessSessionLauncher
{
    private ProcessSessionLauncher() {}

    /** Creates the owned session, then replaces this JVM with the gated shell at the same PID. */
    public static void main(String[] args)
            throws Throwable
    {
        Linker linker = Linker.nativeLinker();
        var symbols = linker.defaultLookup();
        var setsid = linker.downcallHandle(symbols.find("setsid").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        if ((int) setsid.invokeExact() < 0) {
            throw new IllegalStateException("Unable to create the process session");
        }
        var execv = linker.downcallHandle(symbols.find("execv").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, args.length + 1);
            for (int index = 0; index < args.length; index++) {
                argv.setAtIndex(ValueLayout.ADDRESS, index, arena.allocateFrom(args[index]));
            }
            argv.setAtIndex(ValueLayout.ADDRESS, args.length, MemorySegment.NULL);
            int result = (int) execv.invokeExact(argv.getAtIndex(ValueLayout.ADDRESS, 0), argv);
            throw new IllegalStateException("Unable to execute the gated launcher: " + result);
        }
    }
}
