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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Complete argv, environment, directory, and runtime bounds for one child process. */
public record ProcessSpec(
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        int maximumStdoutBytes,
        int maximumStderrBytes,
        Duration maximumRuntime,
        Duration cancellationGrace)
{
    public ProcessSpec
    {
        requireNonNull(argv, "argv is null");
        argv = ImmutableList.copyOf(argv);
        checkArgument(!argv.isEmpty(), "argv is empty");
        checkArgument(argv.stream().noneMatch(String::isBlank), "argv contains a blank value");
        requireNonNull(workingDirectory, "workingDirectory is null");
        checkArgument(workingDirectory.isAbsolute(), "workingDirectory is not absolute");
        requireNonNull(environment, "environment is null");
        environment = ImmutableMap.copyOf(environment);
        checkArgument(environment.keySet().stream().noneMatch(String::isBlank), "environment contains a blank name");
        checkArgument(maximumStdoutBytes > 0, "maximumStdoutBytes is not positive");
        checkArgument(maximumStderrBytes > 0, "maximumStderrBytes is not positive");
        requireNonNull(maximumRuntime, "maximumRuntime is null");
        checkArgument(!maximumRuntime.isZero() && !maximumRuntime.isNegative(), "maximumRuntime is not positive");
        requireNonNull(cancellationGrace, "cancellationGrace is null");
        checkArgument(!cancellationGrace.isNegative(), "cancellationGrace is negative");
    }
}
