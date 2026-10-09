package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Jar;
import aQute.lib.io.IO;

class BndContainerCompilationParticipantTest {
	@TempDir
	File tmp;

	private File	projectDir;
	private File	first;
	private File	second;

	@BeforeEach
	void createWorkspace() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		projectDir = IO.mkdirs(new File(tmp, "project"));
		File lib = IO.mkdirs(new File(projectDir, "lib"));
		first = new File(lib, "first.jar");
		second = new File(lib, "second.jar");
		try (Jar jar = new Jar("dependency")) {
			jar.write(first);
			jar.write(second);
		}
	}

	@Test
	void changedProjectPropertiesAreReadBeforeCalculatingBuildpath() throws Exception {
		File properties = new File(projectDir, Project.BNDFILE);
		IO.store("-buildpath: lib/first.jar;version=file\n-testpath: lib/first.jar;version=file\n", properties);

		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);
			assertThat(project.getTestpath()).extracting(Container::getFile)
				.containsExactly(first);

			writeChanged(properties, "-buildpath: lib/second.jar;version=file\n-testpath: lib/second.jar;version=file\n",
				project.lastModified());
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(workspace.readLocked(project::getBuildpath)).extracting(Container::getFile)
				.containsExactly(second);
			assertThat(project.getTestpath()).extracting(Container::getFile)
				.containsExactly(second);
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void changedIncludedPropertiesAreReadBeforeCalculatingBuildpath() throws Exception {
		File included = new File(projectDir, "dependencies.bnd");
		IO.store("-include: dependencies.bnd\n", new File(projectDir, Project.BNDFILE));
		IO.store("-buildpath: lib/first.jar;version=file\n", included);

		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);

			writeChanged(included, "-buildpath: lib/second.jar;version=file\n", project.lastModified());
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(workspace.readLocked(project::getBuildpath)).extracting(Container::getFile)
				.containsExactly(second);
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void unchangedPropertiesPreserveBuildpath() throws Exception {
		IO.store("-buildpath: lib/first.jar;version=file\n", new File(projectDir, Project.BNDFILE));

		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			Collection<Container> buildpath = project.getBuildpath();
			assertThat(buildpath).extracting(Container::getFile)
				.containsExactly(first);

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(project.getBuildpath()).isSameAs(buildpath)
				.extracting(Container::getFile)
				.containsExactly(first);
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void legitimatelyEmptyBuildpathRemainsValid() throws Exception {
		IO.store("", new File(projectDir, Project.BNDFILE));
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(project.getBuildpath()).isEmpty();
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void refreshingPropertiesCannotUpgradeWorkspaceReadLock() throws Exception {
		File properties = new File(projectDir, Project.BNDFILE);
		IO.store("-buildpath: lib/first.jar;version=file\n", properties);

		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);
			writeChanged(properties, "-buildpath: lib/second.jar;version=file\n", project.lastModified());

			assertThatThrownBy(() -> workspace.readLocked(() -> {
				BndContainerCompilationParticipant.refreshProjectProperties(project);
				return null;
			})).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already holds a read lock");
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(second);
		}
	}

	@Test
	void refreshingPropertiesExcludesWorkspaceReadersAndReleasesLock() throws Exception {
		IO.store("", new File(projectDir, Project.BNDFILE));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = spy(workspace.getProject("project"));
			doAnswer(invocation -> {
				assertThatThrownBy(() -> executor.submit(() -> workspace.readLocked(() -> true, 100))
					.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(TimeoutException.class);
				return invocation.callRealMethod();
			}).when(project)
				.refresh();

			BndContainerCompilationParticipant.refreshProjectProperties(project);

			assertThat(executor.submit(() -> workspace.readLocked(() -> true, 1_000))
				.get(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void refreshFailurePropagatesAndReleasesWriteLock() throws Exception {
		IO.store("", new File(projectDir, Project.BNDFILE));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = spy(workspace.getProject("project"));
			IllegalStateException failure = new IllegalStateException("project properties refresh failed");
			doThrow(failure).when(project)
				.refresh();

			assertThatThrownBy(() -> BndContainerCompilationParticipant.refreshProjectProperties(project))
				.isSameAs(failure);
			assertThat(executor.submit(() -> workspace.writeLocked(() -> true, 1_000))
				.get(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void writeChanged(File file, String content, long lastModified) throws Exception {
		IO.store(content, file);
		Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(lastModified + 2_000));
	}
}
