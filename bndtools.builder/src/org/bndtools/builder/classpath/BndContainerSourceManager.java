package org.bndtools.builder.classpath;

import static aQute.bnd.osgi.Constants.BSN_SOURCE_SUFFIX;
import static aQute.bnd.osgi.Constants.VERSION_ATTR_LATEST;
import static aQute.bnd.service.Strategy.EXACT;
import static aQute.bnd.service.Strategy.HIGHEST;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.SortedSet;
import java.util.function.BiFunction;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.builder.BndtoolsBuilder;
import org.bndtools.builder.BuilderPlugin;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;

import aQute.bnd.build.WorkspaceRepository;
import aQute.bnd.header.Attrs;
import aQute.bnd.osgi.Domain;
import aQute.bnd.service.RepositoryPlugin;
import aQute.bnd.service.Strategy;
import aQute.bnd.version.Version;
import aQute.lib.io.IO;
import bndtools.central.Central;
import bndtools.central.RepositoryUtils;

public class BndContainerSourceManager {

	private static final String				PROPERTY_SRC_ROOT			= ".srcRoot";	//$NON-NLS-1$

	private static final String				PROPERTY_SRC_PATH			= ".srcPath";	//$NON-NLS-1$

	private static final String				SOURCE_LOOKUP_CACHE_FILE		= "source-lookup-cache.properties";

	private static final Duration			SOURCE_LOOKUP_MISS_TTL		= Duration.ofHours(24);

	private static final ILogger				logger						= Logger
		.getLogger(BndContainerSourceManager.class);

	private static volatile SourceLookupCache	sourceLookupCache;

	/**
	 * Persist the attached sources for given {@link IClasspathEntry} instances.
	 */
	public static void saveAttachedSources(final IProject project, final IClasspathEntry[] classpathEntries)
		throws CoreException {
		final Properties props = new Properties();

		// Construct the Properties that represent the source attachment(s)
		for (final IClasspathEntry entry : classpathEntries) {
			if (IClasspathEntry.CPE_LIBRARY != entry.getEntryKind()) {
				continue;
			}
			final String path = entry.getPath()
				.toPortableString();
			if (entry.getSourceAttachmentPath() != null) {
				props.put(path + PROPERTY_SRC_PATH, entry.getSourceAttachmentPath()
					.toPortableString());
			}
			if (entry.getSourceAttachmentRootPath() != null) {
				props.put(path + PROPERTY_SRC_ROOT, entry.getSourceAttachmentRootPath()
					.toPortableString());
			}
		}

		// Write the properties to a persistent storage area
		final File propertiesFile = getSourceAttachmentPropertiesFile(project);
		if (props.isEmpty()) {
			IO.delete(propertiesFile);
		} else {
			try (OutputStream out = IO.outputStream(propertiesFile)) {
				props.store(out, new Date().toString());
			} catch (final IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, BndtoolsBuilder.PLUGIN_ID,
					"Failure to write container source attachments", e));
			}
		}
	}

	/**
	 * Return (a potentially modified) list of {@link IClasspathEntry} instances
	 * that will have any previously persisted attached sources added.
	 */
	public static List<IClasspathEntry> loadAttachedSources(final IProject project,
		final List<IClasspathEntry> classPathEntries) throws CoreException {
		return loadAttachedSources(project, classPathEntries, true);
	}

	/**
	 * Return (a potentially modified) list of {@link IClasspathEntry} instances
	 * that will have previously persisted attached sources added and, when
	 * enabled, missing sources looked up in repositories.
	 */
	public static List<IClasspathEntry> loadAttachedSources(final IProject project,
		final List<IClasspathEntry> classPathEntries, boolean searchRepositories) throws CoreException {
		if (classPathEntries.isEmpty()) {
			return classPathEntries;
		}

		final List<RepositoryPlugin> repositories = searchRepositories ? RepositoryUtils.listRepositories(true)
			: List.of();
		final Properties props = loadSourceAttachmentProperties(project);
		return configureSourceAttachments(classPathEntries, props, searchRepositories,
			(path, extraProps) -> getSourceBundle(path, extraProps, repositories));
	}

	static List<IClasspathEntry> configureSourceAttachments(final List<IClasspathEntry> classPathEntries,
		final Properties props, boolean searchRepositories,
		BiFunction<IPath, Map<String, String>, File> sourceBundleFinder) {
		final List<IClasspathEntry> configuredClassPathEntries = new ArrayList<>(classPathEntries.size());
		for (final IClasspathEntry entry : classPathEntries) {
			if (entry.getEntryKind() != IClasspathEntry.CPE_LIBRARY || entry.getSourceAttachmentPath() != null) {
				configuredClassPathEntries.add(entry);
				continue;
			}

			final String key = entry.getPath()
				.toPortableString();

			IPath srcPath = null;
			IPath srcRoot = null;

			// Retrieve the saved source attachment information
			if (props != null && props.containsKey(key + PROPERTY_SRC_PATH)) {
				srcPath = Path.fromPortableString((String) props.get(key + PROPERTY_SRC_PATH));
				if (props.containsKey(key + PROPERTY_SRC_ROOT)) {
					srcRoot = Path.fromPortableString((String) props.get(key + PROPERTY_SRC_ROOT));
				}
			} else if (searchRepositories) {
				// If there is no saved source attachment, then try and find a
				// source bundle
				Map<String, String> extraProps = new HashMap<>();

				for (IClasspathAttribute attr : entry.getExtraAttributes()) {
					extraProps.put(attr.getName(), attr.getValue());
				}

				File sourceBundle = sourceBundleFinder.apply(entry.getPath(), extraProps);
				if (sourceBundle != null) {
					srcPath = new Path(sourceBundle.getAbsolutePath());
				}
			}

			if (srcPath != null || srcRoot != null) {
				configuredClassPathEntries.add(JavaCore.newLibraryEntry(entry.getPath(), srcPath, srcRoot,
					entry.getAccessRules(), entry.getExtraAttributes(), entry.isExported()));
			} else {
				configuredClassPathEntries.add(entry);
			}
		}

		return configuredClassPathEntries;
	}

	private static File getSourceBundle(IPath path, Map<String, String> props, List<RepositoryPlugin> repositories) {
		if (Central.getWorkspaceIfPresent() == null) {
			return null;
		}

		IPath bundlePath = path;
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		IResource resource = root.findMember(path);
		if (resource != null) {
			bundlePath = resource.getLocation();
		}

		try (JarInputStream jarStream = new JarInputStream(IO.stream(bundlePath.toFile()), false)) {
			Manifest manifest = jarStream.getManifest();
			if (manifest == null) {
				return null;
			}

			String bsn = null;
			String version = null;

			Domain domain = Domain.domain(manifest);
			Entry<String, Attrs> bsnAttrs = domain.getBundleSymbolicName();

			if (bsnAttrs != null) {
				bsn = bsnAttrs.getKey();
				version = domain.getBundleVersion();
			}

			if (bsn == null) {
				bsn = props.get("bsn");
			}

			if (bsn == null) {
				return null;
			}

			if (version == null) {
				version = props.get("version");
			}

			if ("file".equals(version)) {
				// this is a jar in the project folder and we cannot look that
				// up in a repo
				// we could only hope that this jar contains sources
				// under OSGI-OPT/src and will be picked up by
				// org.bndtools.builder.classpath.BndContainerInitializer.Updater.calculateSourceAttachmentPath(IPath,
				// File)
				return null;
			}

			String resolvedBsn = bsn;
			String resolvedVersion = version;
			String sourceLookupKey = sourceLookupKey(resolvedBsn, resolvedVersion, repositories);
			return getSourceLookupCache().get(sourceLookupKey,
				() -> findSourceBundle(resolvedBsn, resolvedVersion, props, repositories));
		} catch (final Exception e) {
			logger.logWarning("Unable to find source bundle for " + path, e);
			return null;
		}
	}

	private static File findSourceBundle(String bsn, String version, Map<String, String> props,
		List<RepositoryPlugin> repositories) throws Exception {
		String bsnSource = bsn + BSN_SOURCE_SUFFIX;
		Strategy strategy = (version == null || VERSION_ATTR_LATEST.equals(version)) ? HIGHEST : EXACT;
		Version v = null;

		for (RepositoryPlugin repo : repositories) {

			if (repo == null || repo instanceof WorkspaceRepository) {
				continue;
			}

			if (HIGHEST == strategy) {
				SortedSet<Version> vs = repo.versions(bsn);

				if (vs != null && !vs.isEmpty()) {
					Version latest = vs.last();
					File sourceBundle = repo.get(bsnSource, latest, props);

					if (sourceBundle != null) {
						return sourceBundle;
					}
				}
			} else {

				if (v == null) {
					v = new Version(version); // just parse once
				}
				File sourceBundle = repo.get(bsnSource, v, props);

				if (sourceBundle != null) {
					return sourceBundle;
				}
			}
		}

		return null;
	}

	private static String sourceLookupKey(String bsn, String version, List<RepositoryPlugin> repositories) {
		String repositoryKey = repositories.stream()
			.filter(repo -> repo != null && !(repo instanceof WorkspaceRepository))
			.map(repo -> repo.getClass()
				.getName() + ":" + repo.getName() + ":" + repo.getLocation())
			.collect(Collectors.joining(","));
		return bsn + "|" + ((version == null) ? VERSION_ATTR_LATEST : version) + "|" + repositoryKey;
	}

	private static SourceLookupCache getSourceLookupCache() {
		SourceLookupCache cache = sourceLookupCache;
		if (cache == null) {
			synchronized (BndContainerSourceManager.class) {
				cache = sourceLookupCache;
				if (cache == null) {
					File cacheFile = new File(BuilderPlugin.getInstance()
						.getStateLocation()
						.toFile(), SOURCE_LOOKUP_CACHE_FILE);
					sourceLookupCache = cache = new SourceLookupCache(cacheFile, SOURCE_LOOKUP_MISS_TTL,
						System::currentTimeMillis);
				}
			}
		}
		return cache;
	}

	public static void clearSourceLookupCache() {
		try {
			getSourceLookupCache().clear();
		} catch (IOException e) {
			logger.logWarning("Unable to clear the source lookup cache", e);
		}
	}

	private static Properties loadSourceAttachmentProperties(final IProject project) throws CoreException {
		final Properties props = new Properties();

		final File propertiesFile = getSourceAttachmentPropertiesFile(project);
		if (propertiesFile.exists()) {
			try (InputStream in = IO.stream(propertiesFile)) {
				props.load(in);
			} catch (final IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, BndtoolsBuilder.PLUGIN_ID,
					"Failure to read container source attachments", e));
			}
		}

		return props;
	}

	private static File getSourceAttachmentPropertiesFile(final IProject project) {
		return new File(BuilderPlugin.getInstance()
			.getStateLocation()
			.toFile(), project.getName() + ".sources"); //$NON-NLS-1$
	}

}
