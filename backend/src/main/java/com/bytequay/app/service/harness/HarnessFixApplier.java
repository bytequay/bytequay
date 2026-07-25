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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Edit;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies validated unique-anchor edits without giving the agent write tools. */
@Component
public class HarnessFixApplier
{
    private static final int MAX_EDITS = 20;
    private static final int MAX_FILE_CHARS = 1_000_000;

    public FixResult apply(Path root, Diagnosis diagnosis)
    {
        if (diagnosis.edits() == null || diagnosis.edits().isEmpty()
                || diagnosis.edits().size() > MAX_EDITS) {
            throw new IllegalArgumentException("diagnosis must contain 1-" + MAX_EDITS + " edits");
        }
        Path realRoot = real(root);
        Map<Path, String> originals = new LinkedHashMap<>();
        Map<Path, String> planned = new LinkedHashMap<>();
        for (Edit edit : diagnosis.edits()) {
            validateEdit(edit);
            Path target = safeExistingFile(realRoot, edit.path());
            String current = planned.computeIfAbsent(target, ignored -> read(target));
            originals.computeIfAbsent(target, ignored -> read(target));
            if (count(current, edit.find()) != 1) {
                throw new IllegalArgumentException(
                        "edit anchor must occur exactly once: " + edit.path());
            }
            String next = current.replace(edit.find(), edit.replace());
            if (next.length() > MAX_FILE_CHARS) {
                throw new IllegalArgumentException("edited file exceeds size cap: " + edit.path());
            }
            planned.put(target, next);
        }

        List<Path> written = new ArrayList<>();
        try {
            for (Map.Entry<Path, String> entry : planned.entrySet()) {
                replaceAtomically(entry.getKey(), entry.getValue());
                written.add(entry.getKey());
            }
        }
        catch (RuntimeException e) {
            for (Path target : written.reversed()) {
                try {
                    replaceAtomically(target, originals.get(target));
                }
                catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }
        List<String> changed = planned.keySet().stream()
                .map(realRoot::relativize)
                .map(Path::toString)
                .sorted()
                .toList();
        return new FixResult(changed, diagnosis.targetSubject(),
                diagnosis.verifyHint() == null ? List.of() : List.copyOf(diagnosis.verifyHint()),
                "agent");
    }

    public FixResult applyRecipe(Path root, Diagnosis recipe)
    {
        if (recipe.binding() == null
                || !recipe.binding().matches("recipe:[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("recipe application requires recipe:<id> binding");
        }
        List<String> verify = recipe.verifyHint() == null
                ? List.of() : List.copyOf(recipe.verifyHint());
        verify.forEach(HarnessModels::verifyVerb);
        if (recipe.edits() == null || recipe.edits().isEmpty()) {
            if (verify.stream().map(HarnessModels::verifyVerb).noneMatch("regen"::equals)) {
                throw new IllegalArgumentException(
                        "recipe without edits requires a regen verification hint");
            }
            return new FixResult(
                    List.of(), recipe.targetSubject(), verify, recipe.binding());
        }
        FixResult applied = apply(root, recipe);
        return new FixResult(
                applied.filesChanged(), applied.targetSubject(),
                verify, recipe.binding());
    }

    private static void validateEdit(Edit edit)
    {
        if (edit == null || edit.path() == null || edit.path().isBlank()
                || edit.find() == null || edit.find().isEmpty() || edit.replace() == null) {
            throw new IllegalArgumentException("every edit needs path, non-empty find, and replace");
        }
        if (Path.of(edit.path()).isAbsolute()) {
            throw new IllegalArgumentException("edit path must be repository-relative");
        }
    }

    private static Path safeExistingFile(Path realRoot, String relative)
    {
        try {
            Path target = realRoot.resolve(relative).normalize();
            if (!target.startsWith(realRoot) || !Files.isRegularFile(target)) {
                throw new IllegalArgumentException("edit path is not an existing repository file: " + relative);
            }
            Path real = target.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("edit path escapes the repository: " + relative);
            }
            return real;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("cannot read edit path: " + relative, e);
        }
    }

    private static Path real(Path root)
    {
        try {
            return root.toRealPath();
        }
        catch (IOException e) {
            throw new IllegalArgumentException("repository path is unavailable: " + root, e);
        }
    }

    private static String read(Path target)
    {
        try {
            String value = Files.readString(target, StandardCharsets.UTF_8);
            if (value.length() > MAX_FILE_CHARS) {
                throw new IllegalArgumentException("edit target exceeds size cap: " + target);
            }
            return value;
        }
        catch (IOException e) {
            throw new IllegalStateException("unable to read " + target, e);
        }
    }

    private static int count(String value, String needle)
    {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static void replaceAtomically(Path target, String content)
    {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".ci-harness-", ".tmp");
            preservePosixPermissions(target, temporary);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("unable to apply edit to " + target, e);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // Best-effort cleanup; target state is already decided.
                }
            }
        }
    }

    private static void preservePosixPermissions(Path source, Path target)
            throws IOException
    {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        }
        catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems do not expose executable bits.
        }
    }
}
