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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexProtocolReferenceBindings
{
    private static final String REFERENCE_BINDINGS =
            "/com/bytequay/agent/cli/codex/app-server-v0.151.0-bindings.json";
    private static final ImmutableSet<String> UNBOUND_RECORDS = ImmutableSet.of(
            "UnknownNotification",
            "UnknownParams",
            "UnknownThreadItem",
            "UnknownServerRequest",
            "EmptyParams",
            "EmptyResponse");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CodexProtocolCodec codec = new CodexProtocolCodec(objectMapper);

    @Test
    void matchesGeneratedRecordBindings()
            throws Exception
    {
        JsonNode reference = referenceBindings();
        assertThat(reference.path("formatVersion").asInt()).isOne();
        assertThat(reference.path("codexVersion").asText()).isEqualTo("0.151.0");

        ImmutableSet<String> protocolRecords = Arrays.stream(CodexProtocol.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .map(Class::getSimpleName)
                .collect(toImmutableSet());
        assertThat(protocolRecords).containsAll(UNBOUND_RECORDS);
        ImmutableList<String> boundRecords = ImmutableList.copyOf(reference.path("records")).stream()
                .map(binding -> codexType(binding.path("javaType").asText()).getSimpleName())
                .collect(toImmutableList());
        assertThat(boundRecords).doesNotHaveDuplicates();
        assertThat(ImmutableSet.copyOf(boundRecords))
                .containsExactlyInAnyOrderElementsOf(protocolRecords.stream()
                        .filter(record -> !UNBOUND_RECORDS.contains(record))
                        .collect(toImmutableSet()));

        for (JsonNode binding : reference.path("records")) {
            String description = description(binding);
            Class<?> type = codexType(binding.path("javaType").asText());
            assertThat(type.isRecord()).as(description).isTrue();

            RecordComponent[] actualComponents = type.getRecordComponents();
            ImmutableList<String> actualJavaNames = Arrays.stream(actualComponents)
                    .map(RecordComponent::getName)
                    .collect(toImmutableList());
            ImmutableList.Builder<String> expectedJavaNames = ImmutableList.builder();
            ImmutableList.Builder<String> expectedWireNames = ImmutableList.builder();
            for (JsonNode component : binding.path("components")) {
                expectedJavaNames.add(component.path("javaName").asText());
                expectedWireNames.add(component.path("wireName").asText());
            }
            assertThat(actualJavaNames).as(description).containsExactlyElementsOf(expectedJavaNames.build());
            assertThat(Arrays.stream(actualComponents)
                    .map(TestCodexProtocolReferenceBindings::wireName)
                    .collect(toImmutableList()))
                    .as(description)
                    .containsExactlyElementsOf(expectedWireNames.build());

            String mode = binding.path("mode").asText();
            switch (mode) {
                case "decode" -> verifyDecodeBinding(binding, type, actualComponents);
                case "encode" -> verifyEncodeBinding(binding, type);
                default -> throw new AssertionError("Unknown reference binding mode: " + mode);
            }
        }
    }

    @Test
    void matchesGeneratedEnumBindings()
            throws Exception
    {
        JsonNode reference = referenceBindings();
        ImmutableSet<String> protocolEnums = Arrays.stream(CodexProtocol.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .map(Class::getSimpleName)
                .collect(toImmutableSet());
        ImmutableList<String> boundEnums = ImmutableList.copyOf(reference.path("enums")).stream()
                .map(binding -> codexType(binding.path("javaType").asText()).getSimpleName())
                .collect(toImmutableList());
        assertThat(boundEnums).doesNotHaveDuplicates();
        assertThat(ImmutableSet.copyOf(boundEnums)).containsExactlyInAnyOrderElementsOf(protocolEnums);

        for (JsonNode binding : reference.path("enums")) {
            String javaType = binding.path("javaType").asText();
            Class<?> type = codexType(javaType);
            assertThat(type.isEnum()).as(javaType).isTrue();

            ImmutableList<String> expectedWireValues = ImmutableList.copyOf(binding.path("wireValues"))
                    .stream()
                    .map(JsonNode::asText)
                    .collect(toImmutableList());
            ImmutableList.Builder<String> actualWireValues = ImmutableList.builder();
            ImmutableList.Builder<String> defaultValues = ImmutableList.builder();
            for (Object value : type.getEnumConstants()) {
                Field field = type.getDeclaredField(((Enum<?>) value).name());
                if (field.isAnnotationPresent(JsonEnumDefaultValue.class)) {
                    defaultValues.add(field.getName());
                    continue;
                }
                JsonProperty property = requireNonNull(
                        field.getAnnotation(JsonProperty.class),
                        () -> javaType + "." + field.getName() + " has no wire name");
                actualWireValues.add(property.value());
            }
            assertThat(actualWireValues.build()).as(javaType).containsExactlyInAnyOrderElementsOf(expectedWireValues);
            assertThat(defaultValues.build()).as(javaType).containsExactly("UNKNOWN");

            for (String wireValue : expectedWireValues) {
                Enum<?> decoded = (Enum<?>) codec.decodeResult(TextNode.valueOf(wireValue), type);
                assertThat(decoded.name()).as(javaType + " " + wireValue).isNotEqualTo("UNKNOWN");
            }
            Enum<?> future = (Enum<?>) codec.decodeResult(TextNode.valueOf("bytequayFutureValue"), type);
            assertThat(future.name()).as(javaType).isEqualTo("UNKNOWN");
        }
    }

    @Test
    void enforcesApprovalResponseNullAndOmissionRules()
            throws Exception
    {
        assertThatThrownBy(() -> new CodexProtocol.CommandApprovalResponse(NullNode.instance))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodexProtocol.FileChangeApprovalResponse(NullNode.instance))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodexProtocol.PermissionsApprovalResponse(
                NullNode.instance,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode encoded = objectMapper.readTree(codec.encode(new CodexProtocol.PermissionsApprovalResponse(
                objectMapper.createObjectNode(),
                Optional.empty(),
                Optional.empty())));
        assertThat(encoded.has("scope")).isFalse();
    }

    @Test
    void omitsAbsentThreadReadOption()
            throws Exception
    {
        JsonNode encoded = objectMapper.readTree(codec.encode(new CodexProtocol.ThreadReadParams(
                "thread-1",
                Optional.empty())));
        assertThat(encoded.has("includeTurns")).isFalse();
    }

    private void verifyDecodeBinding(JsonNode binding, Class<?> type, RecordComponent[] actualComponents)
    {
        String description = description(binding);
        ObjectNode sample = sample(binding);
        codec.decodeResult(sample, type);
        if (binding.has("decodeAs")) {
            Class<?> decodeType = codexType(binding.path("decodeAs").asText());
            assertThat(decodeType.isAssignableFrom(type)).as(description + " decodeAs").isTrue();
            assertThat(codec.decodeResult(sample, decodeType)).as(description).isExactlyInstanceOf(type);
        }

        ObjectNode additive = sample.deepCopy();
        additive.put("bytequayFutureField", true);
        codec.decodeResult(additive, type);

        for (int index = 0; index < actualComponents.length; index++) {
            RecordComponent actual = actualComponents[index];
            JsonNode component = binding.path("components").get(index);
            boolean required = component.path("required").asBoolean();
            boolean nullable = component.path("nullable").asBoolean();
            boolean optional = actual.getType().equals(Optional.class);
            assertThat(optional)
                    .as(description + " " + actual.getName() + " optionality")
                    .isEqualTo(!required || nullable);
            if (required && !nullable) {
                assertThat(actual.getType().isPrimitive())
                        .as(description + " " + actual.getName() + " required presence")
                        .isFalse();
            }

            ObjectNode missing = sample.deepCopy();
            missing.remove(component.path("wireName").asText());
            if (required) {
                assertDecodeFailure(missing, type, description + " missing " + actual.getName());
            }
            else {
                codec.decodeResult(missing, type);
            }

            ObjectNode explicitNull = sample.deepCopy();
            explicitNull.putNull(component.path("wireName").asText());
            if (nullable || !required) {
                codec.decodeResult(explicitNull, type);
            }
            else {
                assertDecodeFailure(explicitNull, type, description + " null " + actual.getName());
            }

            JsonNode alternatives = component.get("samples");
            if (alternatives != null) {
                assertThat(alternatives.isArray()).as(description + " " + actual.getName() + " samples").isTrue();
                assertThat(alternatives).as(description + " " + actual.getName() + " samples").isNotEmpty();
                for (JsonNode alternative : alternatives) {
                    ObjectNode alternativeSample = sample.deepCopy();
                    alternativeSample.set(component.path("wireName").asText(), alternative.deepCopy());
                    codec.decodeResult(alternativeSample, type);
                }
            }
        }
    }

    private void verifyEncodeBinding(JsonNode binding, Class<?> type)
            throws IOException
    {
        String description = description(binding);
        ObjectNode sample = sample(binding);
        verifyEncodedSample(binding, type, sample, description);

        for (JsonNode component : binding.path("components")) {
            JsonNode alternatives = component.get("samples");
            if (alternatives == null) {
                continue;
            }
            assertThat(alternatives.isArray()).as(description + " " + component.path("javaName").asText() + " samples").isTrue();
            assertThat(alternatives).as(description + " " + component.path("javaName").asText() + " samples").isNotEmpty();
            for (JsonNode alternative : alternatives) {
                ObjectNode alternativeSample = sample.deepCopy();
                alternativeSample.set(component.path("wireName").asText(), alternative.deepCopy());
                verifyEncodedSample(binding, type, alternativeSample, description);
            }
        }
    }

    private void verifyEncodedSample(
            JsonNode binding,
            Class<?> type,
            ObjectNode sample,
            String description)
            throws IOException
    {
        Object value = codec.decodeResult(sample, type);
        JsonNode encoded = objectMapper.readTree(codec.encode(value));

        ImmutableList.Builder<String> expectedWireNames = ImmutableList.builder();
        for (JsonNode component : binding.path("components")) {
            String wireName = component.path("wireName").asText();
            expectedWireNames.add(wireName);
            assertThat(encoded.get(wireName)).as(description + " " + wireName).isEqualTo(sample.get(wireName));
        }
        assertThat(ImmutableList.copyOf(encoded.fieldNames()))
                .as(description)
                .containsExactlyElementsOf(expectedWireNames.build());
    }

    private void assertDecodeFailure(JsonNode value, Class<?> type, String description)
    {
        assertThatThrownBy(() -> codec.decodeResult(value, type))
                .as(description)
                .isInstanceOf(AgentUnavailableException.class);
    }

    private ObjectNode sample(JsonNode binding)
    {
        ObjectNode sample = objectMapper.createObjectNode();
        JsonNode context = binding.get("context");
        if (context != null && context.isObject()) {
            sample.setAll((ObjectNode) context.deepCopy());
        }
        for (JsonNode component : binding.path("components")) {
            JsonNode value = component.get("sample");
            assertThat(value).as(description(binding) + " sample " + component.path("javaName").asText()).isNotNull();
            sample.set(component.path("wireName").asText(), value.deepCopy());
        }
        return sample;
    }

    private static String wireName(RecordComponent component)
    {
        JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
        return property == null || property.value().isBlank() ? component.getName() : property.value();
    }

    private static Class<?> codexType(String name)
    {
        int separator = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        String simpleName = name.substring(separator + 1);
        return Arrays.stream(CodexProtocol.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unknown Codex protocol type: " + name));
    }

    private static String description(JsonNode binding)
    {
        String javaType = binding.path("javaType").asText();
        JsonNode context = binding.get("context");
        return context == null ? javaType : javaType + " (" + context + ")";
    }

    private JsonNode referenceBindings()
            throws IOException
    {
        try (InputStream input = requireNonNull(
                getClass().getResourceAsStream(REFERENCE_BINDINGS),
                REFERENCE_BINDINGS + " is missing")) {
            return objectMapper.readTree(input);
        }
    }
}
