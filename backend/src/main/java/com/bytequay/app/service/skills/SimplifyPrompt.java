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
package com.bytequay.app.service.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Body of the cleanup pass a Development Turn triggers when it wrote enough
 * new code to be worth re-reading.
 *
 * <p>Deliberately not a {@link ManagedSkillPolicy} entry: this is the prompt of
 * its own dedicated Turn, not ambient context injected into every coding Turn,
 * so it never consumes a managed-skill slot.
 */
public final class SimplifyPrompt
{
    public static final String NAME = "simplify";

    private static final String RESOURCE = "managed-skills/bytequay/simplify/SKILL.md";
    private static final String BODY = readResource();

    private SimplifyPrompt() {}

    public static String body()
    {
        return BODY;
    }

    private static String readResource()
    {
        try (var in = SimplifyPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing Simplify resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("read Simplify resource " + RESOURCE, e);
        }
    }
}
