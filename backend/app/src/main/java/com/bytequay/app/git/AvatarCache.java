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
package com.bytequay.app.git;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.regex.Pattern;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;

/** Bounded local file cache for avatars discovered during repository synchronization. */
@Component
public class AvatarCache
{
    private static final Logger log = LoggerFactory.getLogger(AvatarCache.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?");
    private static final Duration REPOSITORY_TTL = Duration.ofDays(1);
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final List<ImageType> IMAGE_TYPES = ImmutableList.of(
            new ImageType(MediaType.IMAGE_PNG, ".png"),
            new ImageType(MediaType.IMAGE_JPEG, ".jpg"),
            new ImageType(MediaType.IMAGE_GIF, ".gif"),
            new ImageType(MediaType.parseMediaType("image/webp"), ".webp"));

    private final Path root;
    private final HttpClient client;
    private final Striped<Lock> refreshLocks = Striped.lock(64);

    @Autowired
    public AvatarCache(@Value("${bytequay.avatar-cache.root:${java.io.tmpdir}/bytequay-avatars}") String root)
    {
        this(Path.of(root), HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    AvatarCache(Path root, HttpClient client)
    {
        this.root = requireNonNull(root, "root is null").toAbsolutePath().normalize();
        this.client = requireNonNull(client, "client is null");
    }

    /** Downloads an uncached HTTPS image and returns its stable local key. */
    public Optional<String> cache(String sourceUrl)
    {
        URI source = source(sourceUrl);
        String key = Hashing.sha256().hashString(source.toString(), UTF_8).toString();
        if (find(key).isPresent()) {
            return Optional.of(key);
        }
        return download(source, key);
    }

    /** Local reads never wait for a refresh; callers request refresh separately in the background. */
    public Optional<CachedAvatar> repository(String owner, boolean refresh)
    {
        checkArgument(owner != null && OWNER.matcher(owner).matches(), "repository owner is invalid");
        URI source = URI.create("https://github.com/" + owner.toLowerCase(Locale.ROOT) + ".png?size=64");
        String key = Hashing.sha256().hashString(source.toString(), UTF_8).toString();
        if (!refresh) {
            return find(key);
        }
        Lock lock = refreshLocks.get(key);
        return withLock(lock, () -> {
            Optional<CachedAvatar> cached = find(key);
            if (cached.isEmpty() || expired(cached.orElseThrow())) {
                download(source, key);
            }
            return find(key);
        });
    }

    private static boolean expired(CachedAvatar avatar)
    {
        try {
            return Files.getLastModifiedTime(avatar.path()).toInstant().plus(REPOSITORY_TTL).isBefore(Instant.now());
        }
        catch (IOException e) {
            return true;
        }
    }

    private Optional<String> download(URI source, String key)
    {
        try {
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(TIMEOUT)
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            Optional<ImageType> imageType = response.headers().firstValue("Content-Type")
                    .flatMap(AvatarCache::imageType);
            byte[] bytes;
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200 || imageType.isEmpty()) {
                    return Optional.empty();
                }
                bytes = body.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                return Optional.empty();
            }
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, key + ".", ".tmp");
            try {
                Files.write(temporary, bytes);
                move(temporary, file(key, imageType.orElseThrow()));
                for (ImageType type : IMAGE_TYPES) {
                    if (!type.equals(imageType.orElseThrow())) {
                        Files.deleteIfExists(file(key, type));
                    }
                }
            }
            finally {
                Files.deleteIfExists(temporary);
            }
            return Optional.of(key);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        catch (IOException | RuntimeException e) {
            log.debug("Avatar cache write failed for {}: {}", source.getHost(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns an existing local image and never downloads on a miss. */
    public Optional<CachedAvatar> find(String key)
    {
        checkArgument(key != null && KEY.matcher(key).matches(), "avatar key is invalid");
        return IMAGE_TYPES.stream()
                .map(type -> new CachedAvatar(file(key, type), type.mediaType()))
                .filter(avatar -> Files.isRegularFile(avatar.path()))
                .findFirst();
    }

    private Path file(String key, ImageType type)
    {
        Path path = root.resolve(key + type.suffix()).normalize();
        checkArgument(path.startsWith(root), "avatar path escapes cache root");
        return path;
    }

    private static URI source(String sourceUrl)
    {
        requireNonNull(sourceUrl, "sourceUrl is null");
        URI source = URI.create(sourceUrl);
        checkArgument("https".equalsIgnoreCase(source.getScheme()), "avatar URL must use HTTPS");
        checkArgument(source.getHost() != null && !source.getHost().isBlank(), "avatar URL host is missing");
        checkArgument(source.getUserInfo() == null, "avatar URL user info is not allowed");
        return source;
    }

    private static Optional<ImageType> imageType(String value)
    {
        try {
            MediaType received = MediaType.parseMediaType(value);
            return IMAGE_TYPES.stream()
                    .filter(type -> type.mediaType().isCompatibleWith(received))
                    .findFirst();
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static void move(Path source, Path target)
            throws IOException
    {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, REPLACE_EXISTING);
        }
    }

    public record CachedAvatar(Path path, MediaType mediaType)
    {
        public CachedAvatar
        {
            requireNonNull(path, "path is null");
            requireNonNull(mediaType, "mediaType is null");
        }
    }

    private record ImageType(MediaType mediaType, String suffix) {}
}
