package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SourceLookupCacheTest {
	private static final Duration MISS_TTL = Duration.ofHours(24);

	@TempDir
	File temporaryDirectory;

	@Test
	public void persists_missing_source_between_cache_instances() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		AtomicLong now = new AtomicLong(1_000L);
		SourceLookupCache first = new SourceLookupCache(cacheFile, MISS_TTL, now::get);

		assertThat(first.get("dependency", () -> null)).isNull();

		SourceLookupCache second = new SourceLookupCache(cacheFile, MISS_TTL, now::get);
		assertThat(second.get("dependency", () -> {
			fail("A persisted miss must prevent another lookup");
			return null;
		})).isNull();
	}

	@Test
	public void retries_missing_source_after_expiry() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		AtomicLong now = new AtomicLong(1_000L);
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, now::get);
		AtomicInteger lookups = new AtomicInteger();

		assertThat(cache.get("dependency", () -> {
			lookups.incrementAndGet();
			return null;
		})).isNull();

		now.addAndGet(MISS_TTL.toMillis());
		assertThat(cache.get("dependency", () -> {
			lookups.incrementAndGet();
			return null;
		})).isNull();
		assertThat(lookups).hasValue(2);
	}

	@Test
	public void reuses_existing_source_until_it_is_removed() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		File source = new File(temporaryDirectory, "dependency-sources.jar");
		assertThat(source.createNewFile()).isTrue();
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, () -> 1_000L);
		AtomicInteger lookups = new AtomicInteger();
		Callable<File> lookup = () -> {
			lookups.incrementAndGet();
			return source;
		};

		assertThat(cache.get("dependency", lookup)).isEqualTo(source);
		assertThat(cache.get("dependency", lookup)).isEqualTo(source);
		assertThat(lookups).hasValue(1);

		assertThat(source.delete()).isTrue();
		assertThat(cache.get("dependency", lookup)).isEqualTo(source);
		assertThat(lookups).hasValue(2);
	}

	@Test
	public void clear_forces_an_immediate_retry() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, () -> 1_000L);
		AtomicInteger lookups = new AtomicInteger();
		Callable<File> lookup = () -> {
			lookups.incrementAndGet();
			return null;
		};

		assertThat(cache.get("dependency", lookup)).isNull();
		cache.clear();
		assertThat(cache.get("dependency", lookup)).isNull();
		assertThat(lookups).hasValue(2);
	}

	@Test
	public void lookup_failures_are_not_cached() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, () -> 1_000L);
		AtomicInteger lookups = new AtomicInteger();

		assertThatThrownBy(() -> cache.get("dependency", () -> {
			lookups.incrementAndGet();
			throw new IOException("repository unavailable");
		})).isInstanceOf(IOException.class);

		assertThat(cache.get("dependency", () -> {
			lookups.incrementAndGet();
			return null;
		})).isNull();
		assertThat(lookups).hasValue(2);
	}

	@Test
	public void concurrent_requests_share_one_lookup() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, () -> 1_000L);
		AtomicInteger lookups = new AtomicInteger();
		CountDownLatch lookupStarted = new CountDownLatch(1);
		CountDownLatch finishLookup = new CountDownLatch(1);
		Callable<File> lookup = () -> {
			lookups.incrementAndGet();
			lookupStarted.countDown();
			assertThat(finishLookup.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		};

		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<File>> results = List.of(executor.submit(() -> cache.get("dependency", lookup)),
				executor.submit(() -> cache.get("dependency", lookup)),
				executor.submit(() -> cache.get("dependency", lookup)),
				executor.submit(() -> cache.get("dependency", lookup)));
			assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
			finishLookup.countDown();
			for (Future<File> result : results) {
				assertThat(result.get(5, TimeUnit.SECONDS)).isNull();
			}
			assertThat(lookups).hasValue(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void clear_does_not_store_an_in_progress_lookup() throws Exception {
		File cacheFile = new File(temporaryDirectory, "sources.properties");
		SourceLookupCache cache = new SourceLookupCache(cacheFile, MISS_TTL, () -> 1_000L);
		AtomicInteger lookups = new AtomicInteger();
		CountDownLatch lookupStarted = new CountDownLatch(1);
		CountDownLatch finishLookup = new CountDownLatch(1);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<File> first = executor.submit(() -> cache.get("dependency", () -> {
				lookups.incrementAndGet();
				lookupStarted.countDown();
				assertThat(finishLookup.await(5, TimeUnit.SECONDS)).isTrue();
				return null;
			}));
			assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
			cache.clear();
			finishLookup.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS)).isNull();

			assertThat(cache.get("dependency", () -> {
				lookups.incrementAndGet();
				return null;
			})).isNull();
			assertThat(lookups).hasValue(2);
		} finally {
			executor.shutdownNow();
		}
	}
}
