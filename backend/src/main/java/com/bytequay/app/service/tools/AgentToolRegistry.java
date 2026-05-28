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
package com.bytequay.app.service.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Scans every Spring bean for {@link AgentTool}-annotated methods on
 * startup, generates a {@link ToolSpec} per method, and exposes the
 * sorted catalog. The list is the source of truth for both the MCP
 * server ({@code tools/list} / {@code tools/call}) and the future
 * in-JVM lane.
 *
 * <h3>Schema generation</h3>
 *
 * Each handler's first parameter is its <em>args record</em>. Every
 * record component becomes one property in the JSON inputSchema,
 * typed from the component's Java class:
 *
 * <ul>
 *   <li>{@code String} → {@code "string"}</li>
 *   <li>{@code int / long / Integer / Long} → {@code "integer"}</li>
 *   <li>{@code boolean / Boolean} → {@code "boolean"}</li>
 *   <li>{@code JsonNode} → {@code "object"}</li>
 * </ul>
 *
 * {@link ToolParam#description()} on the component becomes the
 * property's {@code description}; {@link ToolParam#required()} adds
 * the field to the schema's {@code required} array.
 *
 * <p>Tools without arguments use the marker {@link Void} args type;
 * the schema is then an empty object.
 */
@Component
public class AgentToolRegistry
{
    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final ApplicationContext context;
    private final ObjectMapper mapper;

    private List<ToolSpec> specs = List.of();

    @Autowired
    public AgentToolRegistry(ApplicationContext context, ObjectMapper mapper)
    {
        this.context = requireNonNull(context, "context is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Scan beans only once the whole context has refreshed.
     *
     *  <p>{@code @PostConstruct} would run before every other bean
     *  is ready, so {@code context.getBean} on a not-yet-initialized
     *  peer throws BeanCurrentlyInCreation and the bean gets silently
     *  skipped. Listening for ContextRefreshedEvent guarantees the
     *  full bean graph is wired. */
    @EventListener(ContextRefreshedEvent.class)
    void scan()
    {
        // Idempotent — ContextRefreshedEvent can fire more than once
        // in some test setups; only rescan when we haven't yet.
        if (!specs.isEmpty()) {
            return;
        }
        List<ToolSpec> built = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            }
            catch (RuntimeException e) {
                // Some Spring infra beans throw when resolved
                // eagerly; skip them. They never own @AgentTool
                // methods anyway.
                continue;
            }
            // AopUtils.getTargetClass unwraps CGLIB / JDK proxies so
            // the scan finds @AgentTool methods on beans that happen
            // to be wrapped by @Transactional / @Async / etc.
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            for (Method method : beanClass.getDeclaredMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation == null) {
                    continue;
                }
                built.add(buildSpec(bean, method, annotation));
            }
        }
        built.sort(Comparator.comparing(ToolSpec::name));
        // Fail-fast on duplicate names — wires that overlap silently
        // are a class of bug the registry shouldn't allow.
        for (int i = 1; i < built.size(); i++) {
            if (built.get(i).name().equals(built.get(i - 1).name())) {
                throw new IllegalStateException(
                        "duplicate @AgentTool name: " + built.get(i).name()
                                + " on " + built.get(i).handlerMethod()
                                + " and " + built.get(i - 1).handlerMethod());
            }
        }
        this.specs = ImmutableList.copyOf(built);
        log.info("AgentToolRegistry: registered {} tool(s): {}", specs.size(),
                specs.stream().map(ToolSpec::name).toList());
    }

    /** Every tool, sorted by name. Stable across calls — the
     *  caller can rely on byte-identical serialisations for prefix
     *  cache stability. */
    public List<ToolSpec> all()
    {
        return specs;
    }

    /** Subset visible to {@code role}. Same stable ordering. */
    public List<ToolSpec> visibleTo(AgentRole role)
    {
        return specs.stream().filter(s -> s.availableTo(role)).toList();
    }

    /** Lookup by name. Empty when the tool isn't registered (the
     *  dispatcher then surfaces a "method not found" error). */
    public Optional<ToolSpec> byName(String name)
    {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /**
     * Dispatch a tool call to its handler. The handler's typed args
     * record is bound from {@code call.arguments()} (wire names from
     * {@link ToolParam#wireName()} are translated back to record
     * component names first), then the handler runs and returns a
     * {@link ToolOutcome} the calling lane adapts to its transport.
     *
     * <p>Returns {@link Optional#empty()} when the tool isn't on the
     * registry-dispatch path yet — either it isn't registered at all,
     * or its {@link AgentTool} method is still a declaration-only stub
     * (return type {@code void}) whose behaviour lives in a lane's
     * hand-coded dispatch. The caller falls back to that path on an
     * empty result, so tools migrate one at a time without a flag day.
     *
     * <p>Permission and role gating is <em>not</em> done here — the
     * lane enforces it before calling, because the gate (approval
     * prompt, budget, park-guard) is lane-specific. This method
     * assumes the call is already authorised.
     */
    public Optional<ToolOutcome> invoke(String toolName, ToolCall call)
    {
        requireNonNull(call, "call is null");
        ToolSpec spec = byName(toolName).orElse(null);
        if (spec == null || spec.handlerMethod().getReturnType() != ToolOutcome.class) {
            return Optional.empty();
        }
        Object boundArgs = bindArgs(spec, call.arguments());
        Method method = spec.handlerMethod();
        if (!method.canAccess(spec.handlerBean())) {
            method.setAccessible(true);
        }
        try {
            return Optional.of((ToolOutcome) method.invoke(spec.handlerBean(), boundArgs, call));
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot invoke tool handler " + toolName, e);
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("tool handler " + toolName + " failed", cause);
        }
    }

    /** Bind the call's raw JSON arguments into the handler's args
     *  record. Void args types bind to {@code null}; otherwise the
     *  wire keys are remapped onto record component names so the
     *  {@link ToolParam#wireName()} convention round-trips. */
    private Object bindArgs(ToolSpec spec, JsonNode rawArgs)
    {
        Class<?> argsType = spec.argsType();
        if (argsType == Void.class || argsType == void.class) {
            return null;
        }
        JsonNode node = (rawArgs == null || rawArgs.isMissingNode() || rawArgs.isNull())
                ? mapper.createObjectNode()
                : remapWireNames(argsType, rawArgs);
        try {
            return mapper.treeToValue(node, argsType);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "could not bind arguments for tool " + spec.name() + ": " + e.getMessage(), e);
        }
    }

    /** Project the raw arguments onto the record's component-name keys,
     *  pulling each value from its wire name first (then the component
     *  name as a fallback). Extra keys the record doesn't declare are
     *  dropped, so binding never trips on unknown properties. */
    private ObjectNode remapWireNames(Class<?> argsType, JsonNode rawArgs)
    {
        ObjectNode remapped = mapper.createObjectNode();
        if (!argsType.isRecord() || !rawArgs.isObject()) {
            return remapped;
        }
        for (RecordComponent component : argsType.getRecordComponents()) {
            String wire = wireNameOf(argsType, component);
            JsonNode value = rawArgs.has(wire)
                    ? rawArgs.get(wire)
                    : rawArgs.get(component.getName());
            if (value != null) {
                remapped.set(component.getName(), value);
            }
        }
        return remapped;
    }

    private static ToolSpec buildSpec(Object bean, Method method, AgentTool annotation)
    {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            throw new IllegalStateException(
                    "@AgentTool method must take an args record as its first parameter: " + method);
        }
        Class<?> argsType = paramTypes[0];
        String inputSchema = generateSchema(argsType);
        Set<AgentRole> roleSet = annotation.roles().length == 0
                ? ImmutableSet.of(AgentRole.ANY)
                : ImmutableSet.copyOf(annotation.roles());
        return new ToolSpec(
                annotation.name(),
                annotation.description(),
                annotation.whenToUse(),
                annotation.security(),
                annotation.gating(),
                roleSet,
                inputSchema,
                argsType,
                bean,
                method);
    }

    /** Generate a JSON Schema document for an args record. Returns
     *  the {@code {"type":"object", …}} block as a string with
     *  deterministic key order — the same call returns byte-identical
     *  output every time so the model's prefix cache stays valid. */
    static String generateSchema(Class<?> argsType)
    {
        if (argsType == Void.class || argsType == void.class) {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }
        if (!argsType.isRecord()) {
            throw new IllegalStateException(
                    "args type must be a record: " + argsType.getName());
        }
        // TreeMap so the property order is alphabetical and stable.
        TreeMap<String, String> properties = new TreeMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : argsType.getRecordComponents()) {
            ToolParam paramAnnotation = readToolParam(argsType, component);
            String wireName = wireNameOf(argsType, component);
            String type = jsonTypeFor(component.getType());
            String description = paramAnnotation == null ? "" : paramAnnotation.description();
            StringBuilder prop = new StringBuilder();
            prop.append('"').append(escape(wireName)).append("\":{");
            prop.append("\"type\":\"").append(type).append('"');
            if (!description.isEmpty()) {
                prop.append(",\"description\":\"").append(escape(description)).append('"');
            }
            prop.append('}');
            properties.put(wireName, prop.toString());
            if (paramAnnotation != null && paramAnnotation.required()) {
                required.add(wireName);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (String prop : properties.values()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(prop);
        }
        out.append("},\"required\":[");
        first = true;
        // Sort required to keep ordering stable.
        required.sort(Comparator.naturalOrder());
        for (String r : required) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(r)).append('"');
        }
        out.append("]}");
        return out.toString();
    }

    /** The on-the-wire property name for a record component — the
     *  {@link ToolParam#wireName()} when set, otherwise the component
     *  name. Shared by schema generation and argument binding so the
     *  two never drift. */
    private static String wireNameOf(Class<?> argsType, RecordComponent component)
    {
        ToolParam param = readToolParam(argsType, component);
        return param == null || param.wireName().isEmpty()
                ? component.getName()
                : param.wireName();
    }

    /** Reads the {@link ToolParam} annotation off a record component.
     *  The runtime exposes record-component annotations via the
     *  component itself only when retention covers {@code
     *  RECORD_COMPONENT}; otherwise it gets forwarded onto the
     *  accessor method. Try both paths so callers can put the
     *  annotation on either site. */
    private static ToolParam readToolParam(Class<?> argsType, RecordComponent component)
    {
        ToolParam direct = component.getAnnotation(ToolParam.class);
        if (direct != null) {
            return direct;
        }
        try {
            Method accessor = argsType.getDeclaredMethod(component.getName());
            return accessor.getAnnotation(ToolParam.class);
        }
        catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String jsonTypeFor(Class<?> type)
    {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == long.class
                || type == Integer.class || type == Long.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (JsonNode.class.isAssignableFrom(type)) {
            return "object";
        }
        // Fallback — anything else is an object the caller can shape.
        return "object";
    }

    private static String escape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Suppresses an unused-warning on Arrays — kept here so future
     *  scalar-array handling can wire in without re-introducing the
     *  import. */
    @SuppressWarnings("unused")
    private static final Class<?> ARRAY_MARKER = Arrays.class;
}
