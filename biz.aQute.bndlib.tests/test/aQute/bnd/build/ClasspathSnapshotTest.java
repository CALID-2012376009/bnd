package aQute.bnd.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.Strategy;
import aQute.bnd.test.jupiter.InjectTemporaryDirectory;
import aQute.lib.io.IO;

class ClasspathSnapshotTest {
	@InjectTemporaryDirectory
	File tmp;

	private File first;
	private File second;

	@BeforeEach
	void createWorkspace() throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		File project = IO.mkdirs(new File(tmp, "project"));
		File lib = IO.mkdirs(new File(project, "lib"));
		first = new File(lib, "first.jar");
		second = new File(lib, "second.jar");
		try (Jar jar = new Jar("dependency")) {
			jar.write(first);
			jar.write(second);
		}
		IO.store("-buildpath: lib/first.jar;version=file,lib/second.jar;version=file\n"
			+ "-testpath: lib/second.jar;version=file,lib/first.jar;version=file;boot=true\n",
			new File(project, Project.BNDFILE));
	}

	@Test
	void liveBuildpathIteratorIsInvalidatedByAnotherWorkspaceReader() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch iterating = new CountDownLatch(1);
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			Future<Void> preparing = executor.submit(() -> {
				assertThat(iterating.await(10, TimeUnit.SECONDS)).isTrue();
				return workspace.readLocked(() -> {
					project.setChanged();
					project.prepare();
					return null;
				});
			});
			workspace.readLocked(() -> {
				Iterator<Container> iterator = project.getBuildpath().iterator();
				assertThat(iterator.next().getFile()).isEqualTo(first);
				iterating.countDown();
				preparing.get(10, TimeUnit.SECONDS);
				assertThatThrownBy(iterator::next).isInstanceOf(ConcurrentModificationException.class);
				return null;
			});
			assertThat(project.check()).isTrue();
		} finally {
			iterating.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

	}

	@Test
	void snapshotRemainsCoherentWhenAnotherWorkspaceReaderPreparesAgain() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch iterating = new CountDownLatch(1);
		try (Workspace workspace = new Workspace(tmp)) {
			Project project = workspace.getProject("project");
			Future<Void> preparing = executor.submit(() -> {
				assertThat(iterating.await(10, TimeUnit.SECONDS)).isTrue();
				return workspace.readLocked(() -> {
					changePaths(project);
					project.prepare();
					return null;
				});
			});
			workspace.readLocked(() -> {
				Project.ClasspathSnapshot paths = project.getClasspathSnapshot();
				Iterator<Container> iterator = paths.buildpath().iterator();
				assertThat(iterator.next().getFile()).isEqualTo(first);
				iterating.countDown();
				preparing.get(10, TimeUnit.SECONDS);
				assertThat(iterator.next().getFile()).isEqualTo(second);
				assertThat(paths.buildpath()).extracting(Container::getFile)
					.containsExactly(first, second);
				assertThat(paths.testpath()).extracting(Container::getFile)
					.containsExactly(second);
				assertThat(paths.bootclasspath()).extracting(Container::getFile)
					.containsExactly(first);
				assertThatThrownBy(() -> paths.buildpath().clear()).isInstanceOf(UnsupportedOperationException.class);
				assertThatThrownBy(() -> paths.testpath().clear()).isInstanceOf(UnsupportedOperationException.class);
				assertThatThrownBy(() -> paths.bootclasspath().clear()).isInstanceOf(UnsupportedOperationException.class);
				assertChangedPaths(project.getClasspathSnapshot());
				return null;
			});
			assertThat(project.check()).isTrue();
		} finally {
			iterating.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void snapshotWaitsForAnInProgressPreparationInsteadOfCopyingPartialPaths() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch partialPaths = new CountDownLatch(1);
		CountDownLatch finishPreparing = new CountDownLatch(1);
		CountDownLatch copying = new CountDownLatch(1);
		AtomicBoolean blockPreparation = new AtomicBoolean();
		try (Workspace workspace = new Workspace(tmp);
			Project project = new Project(workspace, new File(tmp, "project")) {
				@Override
				public List<Container> getBundles(Strategy strategy, String spec, String source) throws Exception {
					if (Constants.TESTPATH.equals(source) && blockPreparation.get()) {
						partialPaths.countDown();
						assertThat(finishPreparing.await(10, TimeUnit.SECONDS)).isTrue();
					}
					return super.getBundles(strategy, spec, source);
				}
			}) {
			project.getClasspathSnapshot();
			changePaths(project);
			blockPreparation.set(true);
			Future<Void> preparing = executor.submit(() -> workspace.readLocked(() -> {
				project.prepare();
				return null;
			}));
			assertThat(partialPaths.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Project.ClasspathSnapshot> snapshot = executor.submit(() -> workspace.readLocked(() -> {
				copying.countDown();
				return project.getClasspathSnapshot();
			}));
			try {
				assertThat(copying.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> snapshot.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally {
				finishPreparing.countDown();
			}
			preparing.get(10, TimeUnit.SECONDS);
			assertChangedPaths(snapshot.get(10, TimeUnit.SECONDS));
			assertThat(project.check()).isTrue();
		} finally {
			finishPreparing.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void snapshotDoesNotLockUnrelatedProjects() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch preparing = new CountDownLatch(1);
		CountDownLatch finishPreparing = new CountDownLatch(1);
		try (Workspace workspace = new Workspace(tmp);
			Project blocked = new Project(workspace, new File(tmp, "project")) {
				@Override
				public List<Container> getBundles(Strategy strategy, String spec, String source) throws Exception {
					preparing.countDown();
					assertThat(finishPreparing.await(10, TimeUnit.SECONDS)).isTrue();
					return super.getBundles(strategy, spec, source);
				}
			}) {
			File other = IO.mkdirs(new File(tmp, "other"));
			IO.store("", new File(other, Project.BNDFILE));
			Future<Project.ClasspathSnapshot> snapshot = executor
				.submit(() -> workspace.readLocked(blocked::getClasspathSnapshot));
			try {
				assertThat(preparing.await(10, TimeUnit.SECONDS)).isTrue();
				Project.ClasspathSnapshot independent = workspace
					.readLocked(() -> workspace.getProject("other").getClasspathSnapshot());
				assertThat(independent.buildpath()).isEmpty();
				assertThat(independent.testpath()).isEmpty();
				assertThat(independent.bootclasspath()).isEmpty();
			} finally {
				finishPreparing.countDown();
			}
			assertThat(snapshot.get(10, TimeUnit.SECONDS).buildpath()).extracting(Container::getFile)
				.containsExactly(first, second);
		} finally {
			finishPreparing.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void changePaths(Project project) {
		project.setProperty(Constants.BUILDPATH, "lib/second.jar;version=file");
		project.setProperty(Constants.TESTPATH, "lib/first.jar;version=file,lib/second.jar;version=file;boot=true");
		project.setChanged();
	}

	private void assertChangedPaths(Project.ClasspathSnapshot paths) {
		assertThat(paths.buildpath()).extracting(Container::getFile)
			.containsExactly(second);
		assertThat(paths.testpath()).extracting(Container::getFile)
			.containsExactly(first);
		assertThat(paths.bootclasspath()).extracting(Container::getFile)
			.containsExactly(second);
	}
}
