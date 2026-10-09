package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Constants;
import aQute.lib.io.IO;

class BndContainerInitializerTest {
	@TempDir
	File tmp;

	private File projectDir;
	private File first;
	private File second;

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

			assertThat(BndContainerInitializer.getRefreshedBuildpath(project)).extracting(Container::getFile)
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

			assertThat(BndContainerInitializer.getRefreshedBuildpath(project)).extracting(Container::getFile)
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

			assertThat(BndContainerInitializer.getRefreshedBuildpath(project)).containsExactlyElementsOf(buildpath)
				.isNotSameAs(buildpath);
			assertThat(project.getBuildpath()).isSameAs(buildpath);
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void legitimatelyEmptyBuildpathRemainsValid() throws Exception {
		IO.store("", new File(projectDir, Project.BNDFILE));
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			assertThat(BndContainerInitializer.getRefreshedBuildpath(project)).isEmpty();
			assertThat(project.check()).isTrue();
		}
	}

	@Test
	void refreshingBuildpathCannotMutateUnderWorkspaceReadLock() throws Exception {
		IO.store("-buildpath: lib/first.jar;version=file\n", new File(projectDir, Project.BNDFILE));

		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			File properties = new File(projectDir, Project.BNDFILE);
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);
			writeChanged(properties, "-buildpath: lib/second.jar;version=file\n", project.lastModified());

			assertThatThrownBy(() -> workspace.readLocked(() -> BndContainerInitializer.getRefreshedBuildpath(project)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already holds a read lock");
			assertThat(project.getBuildpath()).extracting(Container::getFile)
				.containsExactly(first);
			assertThat(BndContainerInitializer.getRefreshedBuildpath(project)).extracting(Container::getFile)
				.containsExactly(second);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {
		true, false
	})
	void failedCalculationDoesNotPublishOrPersistAnEmptyClasspath(boolean initializing) throws Exception {
		IO.store("-buildpath: lib/first.jar;version=file\n", new File(projectDir, Project.BNDFILE));

		try (Workspace workspace = new Workspace(tmp)) {
			Project model = spy(workspace.getProject("project"));
			ConcurrentModificationException failure = new ConcurrentModificationException("classpath changed");
			doThrow(failure).when(model)
				.getClasspathSnapshot();
			IProject project = mock(IProject.class);
			IWorkspace eclipseWorkspace = mock(IWorkspace.class);
			when(project.getName()).thenReturn("project");
			when(project.isOpen()).thenReturn(true);
			when(project.getWorkspace()).thenReturn(eclipseWorkspace);
			when(eclipseWorkspace.getRoot()).thenReturn(mock(IWorkspaceRoot.class));
			IJavaProject javaProject = mock(IJavaProject.class);
			File savedContainer = new File(projectDir, ".container");
			IO.store("previous container", savedContainer);

			BndContainerInitializer.Updater updater = new BndContainerInitializer.Updater(project, javaProject, model,
				new File(tmp, "empty.index"));
			assertThatThrownBy(() -> updater.updateClasspathContainer(initializing)).isInstanceOf(CoreException.class)
				.hasCause(failure)
				.hasMessageContaining("Unable to calculate classpath for project project");
			verifyNoInteractions(javaProject);
			assertThat(IO.collect(savedContainer)).isEqualTo("previous container");
			assertThat(model.getErrors()).anyMatch(message -> message.contains("Unable to calculate classpath"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {
		true, false
	})
	void entryConversionCanPrepareAgainWithoutInvalidatingAnyOfThePaths(boolean reentrant) throws Exception {
		IO.store("-buildpath: lib/first.jar;version=file,lib/second.jar;version=file\n"
			+ "-testpath: lib/first.jar;version=file,lib/second.jar;version=file;boot=true\n",
			new File(projectDir, Project.BNDFILE));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Workspace workspace = new Workspace(tmp)) {
			Project model = workspace.getProject("project");
			IProject project = mock(IProject.class);
			IWorkspace eclipseWorkspace = mock(IWorkspace.class);
			when(project.isOpen()).thenReturn(true);
			when(project.getWorkspace()).thenReturn(eclipseWorkspace);
			when(eclipseWorkspace.getRoot()).thenReturn(mock(IWorkspaceRoot.class));
			IJavaProject javaProject = mock(IJavaProject.class);
			IClasspathEntry testSource = mock(IClasspathEntry.class);
			when(testSource.getEntryKind()).thenReturn(IClasspathEntry.CPE_SOURCE);
			when(testSource.getExtraAttributes()).thenReturn(new IClasspathAttribute[] {
				BndContainer.TEST
			});
			when(javaProject.getRawClasspath()).thenReturn(new IClasspathEntry[] {
				testSource
			});
			AtomicBoolean converted = new AtomicBoolean();
			BndContainerInitializer.Updater updater = new BndContainerInitializer.Updater(project, javaProject, model,
				new File(tmp, "empty.index")) {
				@Override
				IPath fileToPath(File file) throws Exception {
					if (converted.compareAndSet(false, true)) {
						if (reentrant) {
							prepareChangedPaths(model);
						} else {
							executor.submit(() -> workspace.readLocked(() -> {
								prepareChangedPaths(model);
								return null;
							})).get(10, TimeUnit.SECONDS);
						}
					}
					return Path.fromOSString(file.getAbsolutePath());
				}

				@Override
				IResource fileToResource(File file) {
					return null;
				}

				@Override
				IClasspathEntry createLibraryEntry(IPath path, IPath sourceAttachmentPath,
					IPath sourceAttachmentRootPath, IAccessRule[] accessRules, IClasspathAttribute[] extraAttrs) {
					IClasspathEntry entry = mock(IClasspathEntry.class);
					when(entry.getPath()).thenReturn(path);
					when(entry.getExtraAttributes()).thenReturn(extraAttrs);
					return entry;
				}
			};
			BndContainer container = workspace.readLocked(() -> workspace.readLocked(updater::calculateProjectClasspath));

			assertThat(converted).isTrue();
			assertThat(container.getClasspathEntries()).extracting(IClasspathEntry::getPath)
				.containsExactly(Path.fromOSString(first.getAbsolutePath()), Path.fromOSString(second.getAbsolutePath()),
					Path.fromOSString(first.getAbsolutePath()), Path.fromOSString(second.getAbsolutePath()));
			assertThat(container.getClasspathEntries()).extracting(entry -> BndContainer.hasAttribute(entry, BndContainer.TEST))
				.containsExactly(false, false, true, false);
			assertThat(container.lastModified()).isEqualTo(Math.max(first.lastModified(), second.lastModified()));
			assertThat(model.getBuildpath()).extracting(Container::getFile).containsExactly(second);
			assertThat(model.getTestpath()).extracting(Container::getFile).containsExactly(second);
			assertThat(model.check()).isTrue();
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void prepareChangedPaths(Project model) throws Exception {
		model.setProperty(Constants.BUILDPATH, "lib/second.jar;version=file");
		model.setProperty(Constants.TESTPATH, "lib/second.jar;version=file");
		model.setChanged();
		model.prepare();
	}

	private void writeChanged(File file, String content, long lastModified) throws Exception {
		IO.store(content, file);
		Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(lastModified + 2_000));
	}
}
