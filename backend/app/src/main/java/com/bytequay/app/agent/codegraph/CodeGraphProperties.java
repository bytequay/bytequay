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
package com.bytequay.app.agent.codegraph;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@ConfigurationProperties("bytequay.codegraph")
public record CodeGraphProperties(String home)
{
    public CodeGraphProperties
    {
        requireNonNull(home, "home is null");
    }

    /** The vendored bundle directory, or empty when the launcher configured none. */
    public Optional<Path> bundleHome()
    {
        return home.isBlank() ? Optional.empty() : Optional.of(Path.of(home));
    }
}
