package org.bndtools.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.JarLifecycleListener;
import aQute.bnd.service.RepositoryPlugin;
import aQute.bnd.version.Version;
import aQute.lib.io.IO;
import bndtools.central.RebuildTriggerPolicy;
import bndtools.central.RebuildTriggerPolicyPlugin;

class CnfWatcherTest {
	@TempDir
	File tmp;

	@Test
	void repositoryOnlyRefreshMustInvalidatePathsBeforeJavaCompilation() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		File index = new File(tmp, "cnf/dependencies.maven");
		IO.store("dependency:1.0.0", index);
		File projectDir = IO.mkdirs(new File(tmp, "project"));
		IO.store("-buildpath: dependency;version=latest\n", new File(projectDir, Project.BNDFILE));
		File otherDir = IO.mkdirs(new File(tmp, "other"));
		IO.store("-buildpath: dependency;version=latest\n", new File(otherDir, Project.BNDFILE));
		Version firstVersion = new Version("1.0.0");
		Version secondVersion = new Version("2.0.0");
		File first = dependency(firstVersion);
		File second = dependency(secondVersion);
		RepositoryPlugin repository = mock(RepositoryPlugin.class);
		when(repository.getName()).thenReturn("test");
		when(repository.versions("dependency")).thenReturn(new TreeSet<>(List.of(firstVersion)));
		when(repository.get(eq("dependency"), any(Version.class), anyMap(), any(RepositoryPlugin.DownloadListener.class)))
			.thenAnswer(invocation -> {
				File file = firstVersion.equals(invocation.getArgument(1)) ? first : second;
				invocation.getArgument(3, RepositoryPlugin.DownloadListener.class).success(file);
				return file;
			});

		try (Workspace workspace = new Workspace(tmp)) {
			workspace.addBasicPlugin(repository);
			Project project = workspace.getProject("project");
			Project other = workspace.getProject("other");
			assertThat(project.getClasspathSnapshot().buildpath()).extracting(Container::getFile)
				.containsExactly(first);
			assertThat(other.getClasspathSnapshot().buildpath()).extracting(Container::getFile)
				.containsExactly(first);
			int revision = project.getChanged();

			IO.store("dependency:2.0.0", index);
			when(repository.versions("dependency")).thenReturn(new TreeSet<>(List.of(secondVersion)));
			boolean propertiesChanged = workspace.refresh();

			assertThat(propertiesChanged).as("repository input is not an included bnd properties file")
				.isFalse();
			assertThat(workspace.writeLocked(() -> {
				assertThat(project.refresh()).isFalse();
				return project.getClasspathSnapshot().buildpath();
			})).as("refreshing only project properties does not invalidate cached repository paths")
				.extracting(Container::getFile)
				.containsExactly(first);

			RebuildTriggerPolicyPlugin policy = new RebuildTriggerPolicyPlugin(
				RebuildTriggerPolicy.REBUILDTRIGGERPOLICY_API);
			Collection<Project> refreshed = CnfWatcher.refreshWorkspace(workspace, ws -> ws.addBasicPlugin(policy));

			assertThat(refreshed).contains(project, other);
			assertThat(project.getPlugins(JarLifecycleListener.class)).contains(policy);
			assertThat(workspace.writeLocked(() -> {
				project.refresh();
				return project.getClasspathSnapshot().buildpath();
			})).as("Java compilation must see the new repository before the bnd builder runs")
				.extracting(Container::getFile)
				.containsExactly(second);
			assertThat(project.getChanged()).isGreaterThan(revision);
			assertThat(other.getClasspathSnapshot().buildpath()).extracting(Container::getFile)
				.containsExactly(second);
			assertThat(project.check()).isTrue();
			assertThat(other.check()).isTrue();
		}
	}

	@Test
	void configurationFailurePropagatesWithoutReportingASuccessfulRefresh() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		try (Workspace workspace = new Workspace(tmp)) {
			IOException failure = new IOException("cannot configure rebuilt repositories");
			assertThatThrownBy(() -> CnfWatcher.refreshWorkspace(workspace, ws -> {
				throw failure;
			})).isSameAs(failure);
			assertThat(workspace.readLocked(() -> "lock released")).isEqualTo("lock released");
		}
	}

	private File dependency(Version version) throws Exception {
		File file = new File(tmp, "dependency-" + version + ".jar");
		try (Jar jar = new Jar("dependency")) {
			Manifest manifest = new Manifest();
			Attributes attributes = manifest.getMainAttributes();
			attributes.putValue("Manifest-Version", "1.0");
			attributes.putValue("Bundle-ManifestVersion", "2");
			attributes.putValue("Bundle-SymbolicName", "dependency");
			attributes.putValue("Bundle-Version", version.toString());
			jar.setManifest(manifest);
			jar.write(file);
		}
		return file;
	}
}
