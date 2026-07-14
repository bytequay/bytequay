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

/** Pinned Caveman prompt plus ByteQuay's output-safety constraints. */
public final class CavemanPrompt
{
    public static final String NAME = "caveman";
    static final String VERSION = "0d95a81d35a9f2d123a5e9430d1cfc43d55f1bb0";

    private static final String RESOURCE = "managed-skills/caveman/" + VERSION + "/SKILL.md";
    private static final String BODY = readResource();
    private static final String ACTIVATION = """
            # ByteQuay activation

            Caveman is mandatory for this turn. Use its lite intensity: concise, professional full sentences.
            Higher-priority instructions, structured tool/JSON contracts, safety warnings, and this repository's
            commit and external-comment requirements win over style. Keep external review comments and replies
            clear and complete; never compress them into ambiguity. Keep commit subjects concise and
            self-explanatory.
            """;

    private CavemanPrompt() {}

    public static String body()
    {
        return BODY + "\n\n" + ACTIVATION;
    }

    /** Adds an always-on, ByteQuay-safe Caveman directive before a system prompt. */
    public static String wrap(String systemPrompt)
    {
        return body() + "\n\n" + systemPrompt;
    }

    private static String readResource()
    {
        try (var in = CavemanPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing Caveman resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("read Caveman resource " + RESOURCE, e);
        }
    }
}
