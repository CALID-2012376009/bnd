package org.bndtools.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.RepositoryPlugin;
import aQute.bnd.version.Version;
import aQute.lib.io.IO;

class CnfWatcherTest {
	@TempDir
	File tmp;

	@Test
	void repositoryChangesInvalidateAllBuildpathsWithoutPropertiesChanges() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		for (String name : List.of("producer", "consumer")) {
			File directory = IO.mkdirs(new File(tmp, name));
			IO.store("-buildpath: external;version=latest,stable;version=latest\n",
				new File(directory, Project.BNDFILE));
		}
		File first = new File(tmp, "first.jar");
		File second = new File(tmp, "second.jar");
		File stable = new File(tmp, "stable.jar");
		try (Jar jar = new Jar("external")) {
			jar.write(first);
			jar.write(second);
			jar.write(stable);
		}
		Version oldVersion = new Version("1.0.0");
		Version newVersion = new Version("2.0.0");
		RepositoryPlugin repository = mock(RepositoryPlugin.class);
		when(repository.getName()).thenReturn("test");
		when(repository.versions("external")).thenReturn(new TreeSet<>(List.of(oldVersion)));
		when(repository.versions("stable")).thenReturn(new TreeSet<>(List.of(oldVersion)));
		when(repository.get(any(String.class), any(Version.class), anyMap(),
			any(RepositoryPlugin.DownloadListener.class))).thenAnswer(invocation -> {
				File file = "stable".equals(invocation.getArgument(0)) ? stable
					: oldVersion.equals(invocation.getArgument(1)) ? first : second;
				invocation.getArgument(3, RepositoryPlugin.DownloadListener.class)
					.success(file);
				return file;
			});
		try (Workspace workspace = new Workspace(tmp)) {
			workspace.addBasicPlugin(repository);
			Project producer = workspace.getProject("producer");
			Project consumer = workspace.getProject("consumer");
			for (Project project : List.of(producer, consumer)) {
				assertThat(project.getBuildpath()).extracting(Container::getFile)
					.containsExactly(first, stable);
			}
			when(repository.versions(eq("external"))).thenReturn(new TreeSet<>(List.of(newVersion)));
			assertThat(workspace.refresh()).isFalse();
			for (Project project : List.of(producer, consumer)) {
				assertThat(project.refresh()).isFalse();
				assertThat(project.getBuildpath()).extracting(Container::getFile)
					.containsExactly(first, stable);
			}
			AtomicInteger policyUpdates = new AtomicInteger();
			assertThat(CnfWatcher.refreshWorkspace(workspace, ws -> {
				assertThat(ws).isSameAs(workspace);
				policyUpdates.incrementAndGet();
			})).containsExactlyInAnyOrder(producer, consumer);
			assertThat(policyUpdates).hasValue(1);
			// No snapshot API or per-project refresh after invalidation.
			for (Project project : List.of(producer, consumer)) {
				assertThat(project.getBuildpath()).extracting(Container::getFile)
					.containsExactly(second, stable);
				assertThat(project.check()).isTrue();
			}
		}
	}

	@Test
	void refreshFailurePropagatesAndReleasesWriteLock() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Workspace workspace = new Workspace(tmp)) {
			IllegalStateException failure = new IllegalStateException("policy update failed");
			assertThatThrownBy(() -> CnfWatcher.refreshWorkspace(workspace, ws -> {
				assertThatThrownBy(() -> executor.submit(() -> ws.readLocked(() -> true, 100))
					.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(TimeoutException.class);
				throw failure;
			})).isSameAs(failure);
			assertThat(executor.submit(() -> workspace.writeLocked(() -> true, 1_000))
				.get(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
		}
	}
}
