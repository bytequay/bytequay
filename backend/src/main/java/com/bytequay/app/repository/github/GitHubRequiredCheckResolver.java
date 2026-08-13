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
package com.bytequay.app.repository.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/** Resolves exact required checks from active rulesets and branch protection. */
@Component
public final class GitHubRequiredCheckResolver
{
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    public record Snapshot(
            String sourceRef, String sourceDigest, List<String> selectors)
    {
        public Snapshot
        {
            requireText(sourceRef, "sourceRef");
            requireText(sourceDigest, "sourceDigest");
            selectors = List.copyOf(requireNonNull(
                    selectors, "selectors is null"));
        }
    }

    public static final class UnresolvedRequiredCheckException
            extends IllegalStateException
    {
        UnresolvedRequiredCheckException(String message)
        {
            super(message);
        }
    }

    private final RestClient github;
    private final ObjectMapper json;

    public GitHubRequiredCheckResolver(
            @Qualifier("gitHubRestClient") RestClient gitHubRestClient,
            ObjectMapper json)
    {
        this.github = requireNonNull(
                gitHubRestClient, "gitHubRestClient is null");
        this.json = requireNonNull(json, "json is null");
    }

    public Snapshot resolve(
            String token, String owner, String repository, String branch)
    {
        requireText(token, "token");
        requireText(owner, "owner");
        requireText(repository, "repository");
        requireText(branch, "branch");
        ObjectNode evidence = json.createObjectNode();
        ArrayNode ruleEvidence = evidence.putArray("rulesets");
        TreeSet<String> selectors = new TreeSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int currentPage = page;
            ResponseEntity<JsonNode> response = github.get()
                    .uri(builder -> builder
                            .path("/repos/{owner}/{repository}/rules/branches/{branch}")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", currentPage)
                            .build(owner, repository, branch))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode rules = response.getBody();
            if (rules == null || !rules.isArray()) {
                throw unresolved("GitHub returned malformed branch rules");
            }
            ruleEvidence.add(rules.deepCopy());
            for (JsonNode rule : rules) {
                collectRequiredChecks(rule, selectors);
            }
            if (rules.size() < PAGE_SIZE) {
                break;
            }
            if (page == MAX_PAGES) {
                throw unresolved(
                        "GitHub branch rules exceeded the supported page limit");
            }
        }
        collectClassicProtection(
                token, owner, repository, branch, evidence, selectors);
        return new Snapshot(
                "github:required-checks/branches/" + branch,
                "sha256:" + sha256(evidence),
                new ArrayList<>(selectors));
    }

    private void collectClassicProtection(
            String token,
            String owner,
            String repository,
            String branch,
            ObjectNode evidence,
            TreeSet<String> selectors)
    {
        JsonNode protection;
        try {
            protection = github.get()
                    .uri("/repos/{owner}/{repository}/branches/{branch}"
                                    + "/protection/required_status_checks",
                            owner, repository, branch)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JsonNode.class);
        }
        catch (RestClientResponseException response) {
            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                evidence.putNull("classicProtection");
                return;
            }
            throw response;
        }
        if (protection == null || !protection.isObject()) {
            throw unresolved(
                    "GitHub returned malformed classic branch protection");
        }
        evidence.set("classicProtection", protection.deepCopy());
        JsonNode contexts = protection.get("contexts");
        JsonNode checks = protection.get("checks");
        if (contexts == null || !contexts.isArray()
                || checks == null || !checks.isArray()) {
            throw unresolved(
                    "GitHub returned malformed classic required checks");
        }
        Set<String> contextNames = new HashSet<>();
        for (JsonNode context : contexts) {
            if (!context.isTextual() || context.textValue().isBlank()) {
                throw unresolved(
                        "GitHub returned malformed classic required checks");
            }
            contextNames.add(context.textValue().strip());
        }
        Set<String> exactNames = new HashSet<>();
        for (JsonNode check : checks) {
            String name = check.path("context").isTextual()
                    ? check.path("context").textValue().strip() : "";
            JsonNode appId = check.get("app_id");
            if (name.isEmpty() || appId == null
                    || !appId.canConvertToLong()
                    || appId.longValue() < 1) {
                throw unresolved(
                        "a required check has no exact GitHub App identity");
            }
            exactNames.add(name);
            selectors.add("GITHUB_CHECK:" + appId.longValue() + ":" + name);
        }
        if (!contextNames.equals(exactNames)) {
            throw unresolved(
                    "classic required contexts do not match exact checks");
        }
    }

    private static void collectRequiredChecks(
            JsonNode rule, TreeSet<String> selectors)
    {
        if (rule == null || !rule.isObject()
                || !rule.path("type").isTextual()) {
            throw unresolved("GitHub returned malformed branch rules");
        }
        if (!"required_status_checks".equals(rule.path("type").textValue())) {
            return;
        }
        JsonNode checks = rule.path("parameters")
                .path("required_status_checks");
        if (!checks.isArray()) {
            throw unresolved("GitHub returned malformed required checks");
        }
        for (JsonNode check : checks) {
            String name = check.path("context").isTextual()
                    ? check.path("context").textValue().strip() : "";
            JsonNode integration = check.get("integration_id");
            if (name.isEmpty() || integration == null
                    || !integration.canConvertToLong()
                    || integration.longValue() < 1) {
                throw unresolved(
                        "a required check has no exact GitHub App identity");
            }
            selectors.add("GITHUB_CHECK:" + integration.longValue()
                    + ":" + name);
        }
    }

    private static String sha256(JsonNode value)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static UnresolvedRequiredCheckException unresolved(String message)
    {
        return new UnresolvedRequiredCheckException(message);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
