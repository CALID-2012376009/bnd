package org.bndtools.builder.classpath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;

final class SourceLookupCache {
	private static final String						SEPARATOR	= "|";
	private static final ILogger						logger		= Logger.getLogger(SourceLookupCache.class);

	private final File								cacheFile;
	private final long								missTtlMillis;
	private final LongSupplier						currentTimeMillis;
	private final Map<String, CacheEntry>				entries		= new HashMap<>();
	private final Map<String, CompletableFuture<File>>	inFlight	= new HashMap<>();
	private boolean									loaded;
	private long									generation;

	SourceLookupCache(File cacheFile, Duration missTtl, LongSupplier currentTimeMillis) {
		this.cacheFile = cacheFile;
		this.missTtlMillis = missTtl.toMillis();
		this.currentTimeMillis = currentTimeMillis;
	}

	File get(String key, Callable<File> lookup) throws Exception {
		CompletableFuture<File> future;
		boolean performLookup = false;
		long lookupGeneration = 0L;
		synchronized (this) {
			CacheEntry cached = getCached(key);
			if (cached != null) {
				return cached.source;
			}
			future = inFlight.get(key);
			if (future == null) {
				future = new CompletableFuture<>();
				inFlight.put(key, future);
				performLookup = true;
				lookupGeneration = generation;
			}
		}

		if (!performLookup) {
			return await(future);
		}

		try {
			File source = lookup.call();
			synchronized (this) {
				if (lookupGeneration == generation) {
					entries.put(key, new CacheEntry(currentTimeMillis.getAsLong(), source));
					saveSafely();
				}
			}
			future.complete(source);
			return source;
		} catch (Throwable t) {
			future.completeExceptionally(t);
			if (t instanceof Exception) {
				throw (Exception) t;
			}
			if (t instanceof Error) {
				throw (Error) t;
			}
			throw new RuntimeException(t);
		} finally {
			synchronized (this) {
				inFlight.remove(key, future);
			}
		}
	}

	synchronized void clear() throws IOException {
		entries.clear();
		inFlight.clear();
		generation++;
		loaded = true;
		Files.deleteIfExists(cacheFile.toPath());
	}

	private CacheEntry getCached(String key) {
		loadSafely();
		CacheEntry cached = entries.get(key);
		if (cached == null) {
			return null;
		}
		if (cached.source != null) {
			if (cached.source.isFile()) {
				return cached;
			}
		} else if (currentTimeMillis.getAsLong() - cached.checkedAt < missTtlMillis) {
			return cached;
		}
		entries.remove(key);
		saveSafely();
		return null;
	}

	private void loadSafely() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!cacheFile.isFile()) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(cacheFile.toPath())) {
			properties.load(input);
		} catch (IOException e) {
			logger.logWarning("Unable to read source lookup cache " + cacheFile, e);
			return;
		}
		for (String key : properties.stringPropertyNames()) {
			String value = properties.getProperty(key);
			int separator = value.indexOf(SEPARATOR);
			if (separator < 0) {
				continue;
			}
			try {
				long checkedAt = Long.parseLong(value.substring(0, separator));
				String sourcePath = value.substring(separator + SEPARATOR.length());
				File source = sourcePath.isEmpty() ? null : new File(sourcePath);
				entries.put(key, new CacheEntry(checkedAt, source));
			} catch (NumberFormatException e) {
				logger.logWarning("Ignoring malformed entry in source lookup cache " + cacheFile, e);
			}
		}
	}

	private void saveSafely() {
		try {
			save();
		} catch (IOException e) {
			logger.logWarning("Unable to write source lookup cache " + cacheFile, e);
		}
	}

	private void save() throws IOException {
		Properties properties = new Properties();
		entries.forEach((key, entry) -> properties.setProperty(key,
			entry.checkedAt + SEPARATOR + ((entry.source == null) ? "" : entry.source.getAbsolutePath())));

		File parent = cacheFile.getParentFile();
		Files.createDirectories(parent.toPath());
		File temporary = new File(parent, cacheFile.getName() + ".tmp");
		try (OutputStream output = Files.newOutputStream(temporary.toPath())) {
			properties.store(output, null);
		}
		try {
			Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static File await(CompletableFuture<File> future) throws Exception {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread()
				.interrupt();
			throw e;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new RuntimeException(cause);
		}
	}

	private static final class CacheEntry {
		final long	checkedAt;
		final File	source;

		CacheEntry(long checkedAt, File source) {
			this.checkedAt = checkedAt;
			this.source = source;
		}
	}
}
