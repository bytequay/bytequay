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
package com.bytequay.app.review;

import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.MethodStatus;
import com.bytequay.core.review.LocalRun.TestClass;
import com.bytequay.core.review.LocalRun.TestMethod;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;

/** Reads the Surefire XML reports a run wrote. */
final class SurefireReports
{
    private static final int MAXIMUM_TRACE_CHARACTERS = 16 * 1024;
    // Deep enough for <group>/<module>/target/surefire-reports/<report>; sources and hidden directories are never entered.
    private static final int MAXIMUM_REPORT_DEPTH = 6;
    // The runs list carries every run's rows and is polled while a run is live, so one long assertion message stays small.
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 4 * 1024;

    private SurefireReports() {}

    private static String boundedMessage(String message)
    {
        return message.length() <= MAXIMUM_MESSAGE_CHARACTERS ? message : message.substring(0, MAXIMUM_MESSAGE_CHARACTERS) + "…";
    }

    /**
     * Rows from every Surefire report in the working copy modified within {@code [from, until]}, for an agent's command, whose modules
     * and selector are not known. Symbolic links are not followed.
     *
     * @return classes in report order, empty when there is no fresh report; empty optional when a report cannot be read
     */
    static Optional<List<TestClass>> readWindow(Path root, Instant from, Instant until)
    {
        List<Path> reports = new ArrayList<>();
        ListMultimap<String, TestMethod> methods = LinkedListMultimap.create();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAXIMUM_REPORT_DEPTH, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                {
                    String name = directory.equals(root) ? "" : directory.getFileName().toString();
                    return name.startsWith(".") || name.equals("src") || name.equals("node_modules") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    long modified = attributes.lastModifiedTime().toMillis();
                    if (attributes.isRegularFile() && isReport(file) && modified >= from.toEpochMilli() && modified <= until.toEpochMilli()) {
                        reports.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure)
                {
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path file : reports.stream().sorted(comparing(path -> path.getFileName().toString().replaceFirst("\\.xml$", ""))).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    parse(input, file.getFileName().toString().replaceFirst("^TEST-", "").replaceFirst("\\.xml$", ""), methods);
                }
            }
        }
        catch (NoSuchFileException missing) {
            return Optional.of(ImmutableList.of());
        }
        catch (IOException | XMLStreamException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.of(classes(methods));
    }

    private static boolean isReport(Path file)
    {
        String name = file.getFileName().toString();
        Path directory = file.getParent();
        return name.startsWith("TEST-") && name.endsWith(".xml") && directory != null && directory.getFileName().toString().equals("surefire-reports")
                && directory.getParent() != null && directory.getParent().getFileName().toString().equals("target");
    }

    private static List<TestClass> classes(ListMultimap<String, TestMethod> methods)
    {
        return methods.keySet().stream()
                .map(className -> new TestClass(className, duration(methods.get(className)), methods.get(className)))
                .collect(toImmutableList());
    }

    static LocalRun.Counts counts(List<TestClass> classes)
    {
        List<TestMethod> methods = classes.stream().flatMap(result -> result.methods().stream()).collect(toImmutableList());
        return new LocalRun.Counts(methods.size(), count(methods, MethodStatus.FAILED), count(methods, MethodStatus.ERROR), count(methods, MethodStatus.SKIPPED));
    }

    private static void parse(InputStream input, String testClass, ListMultimap<String, TestMethod> methods)
            throws XMLStreamException
    {
        XMLStreamReader reader = xmlInputFactory().createXMLStreamReader(input);
        try {
            String suite = testClass;
            Optional<Testcase> testcase = Optional.empty();
            boolean capturing = false;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "testsuite" -> suite = Optional.ofNullable(reader.getAttributeValue(null, "name")).filter(name -> !name.isBlank()).orElse(testClass);
                            case "testcase" -> testcase = Optional.of(new Testcase(
                                    Optional.ofNullable(reader.getAttributeValue(null, "classname")).filter(name -> !name.isBlank()).orElse(suite),
                                    Optional.ofNullable(reader.getAttributeValue(null, "name")).orElse(""),
                                    milliseconds(reader.getAttributeValue(null, "time"))));
                            case "failure", "error", "skipped" -> {
                                if (testcase.isPresent() && testcase.orElseThrow().status == MethodStatus.PASSED) {
                                    Testcase current = testcase.orElseThrow();
                                    current.status = switch (reader.getLocalName()) {
                                        case "failure" -> MethodStatus.FAILED;
                                        case "error" -> MethodStatus.ERROR;
                                        default -> MethodStatus.SKIPPED;
                                    };
                                    current.message = Optional.ofNullable(reader.getAttributeValue(null, "message")).map(SurefireReports::boundedMessage);
                                    capturing = true;
                                }
                            }
                            default -> {}
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (capturing) {
                            StringBuilder trace = testcase.orElseThrow().trace;
                            trace.append(reader.getText(), 0, Math.min(reader.getTextLength(), Math.max(0, MAXIMUM_TRACE_CHARACTERS - trace.length())));
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "failure", "error", "skipped" -> capturing = false;
                            case "testcase" -> {
                                testcase.ifPresent(current -> methods.put(current.className, current.toMethod()));
                                testcase = Optional.empty();
                            }
                            default -> {}
                        }
                    }
                    default -> {}
                }
            }
        }
        finally {
            reader.close();
        }
    }

    private static OptionalLong duration(List<TestMethod> methods)
    {
        return methods.stream().anyMatch(method -> method.durationMs().isPresent())
                ? OptionalLong.of(methods.stream().mapToLong(method -> method.durationMs().orElse(0)).sum())
                : OptionalLong.empty();
    }

    private static int count(List<TestMethod> methods, MethodStatus status)
    {
        return (int) methods.stream().filter(method -> method.status() == status).count();
    }

    /** Seconds as Surefire writes them; older versions group thousands with commas. */
    private static OptionalLong milliseconds(String seconds)
    {
        try {
            return seconds == null ? OptionalLong.empty() : OptionalLong.of(new BigDecimal(seconds.replace(",", "")).movePointRight(3).longValue());
        }
        catch (NumberFormatException malformed) {
            return OptionalLong.empty();
        }
    }

    private static XMLInputFactory xmlInputFactory()
    {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // Reports come from pull-request code, so they must not reach other files or the network through entities.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static final class Testcase
    {
        private final String className;
        private final String name;
        private final OptionalLong duration;
        private final StringBuilder trace = new StringBuilder();
        private MethodStatus status = MethodStatus.PASSED;
        private Optional<String> message = Optional.empty();

        private Testcase(String className, String name, OptionalLong duration)
        {
            this.className = className;
            this.name = name;
            this.duration = duration;
        }

        private TestMethod toMethod()
        {
            String text = trace.toString().strip();
            return new TestMethod(name, status, duration, message, text.isEmpty() ? Optional.empty() : Optional.of(text));
        }
    }
}
