package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.junit.jupiter.api.Test;

public class BndContainerSourceManagerTest {

	@Test
	public void disabled_source_lookup_does_not_search_repositories() {
		IClasspathEntry binary = mock(IClasspathEntry.class);
		when(binary.getEntryKind()).thenReturn(IClasspathEntry.CPE_LIBRARY);
		when(binary.getPath()).thenReturn(new Path("binary.jar"));

		List<IClasspathEntry> configured = BndContainerSourceManager.configureSourceAttachments(List.of(binary),
			new Properties(), false, (path, properties) -> {
				fail("Repository source lookup must not run");
				return null;
			});

		assertThat(configured).singleElement()
			.isSameAs(binary);
	}

	@Test
	public void explicit_source_attachment_is_preserved_when_lookup_is_disabled() {
		IClasspathEntry binary = mock(IClasspathEntry.class);
		when(binary.getEntryKind()).thenReturn(IClasspathEntry.CPE_LIBRARY);
		when(binary.getSourceAttachmentPath()).thenReturn(new Path("explicit-sources.jar"));

		List<IClasspathEntry> configured = BndContainerSourceManager.configureSourceAttachments(List.of(binary),
			new Properties(), false, (path, properties) -> null);

		assertThat(configured).singleElement()
			.isSameAs(binary);
	}

	@Test
	public void wrapped_file_not_found_is_a_missing_source() {
		Throwable failure = new InvocationTargetException(new FileNotFoundException("sources.jar"));

		assertThat(BndContainerSourceManager.isMissingSource(failure)).isTrue();
	}

	@Test
	public void other_repository_failures_are_not_missing_sources() {
		assertThat(BndContainerSourceManager.isMissingSource(new IOException("repository unavailable"))).isFalse();
	}

	@Test
	public void initial_container_restore_never_searches_repositories() {
		assertThat(BndContainerInitializer.shouldSearchRepositoriesForSources(true, true)).isFalse();
		assertThat(BndContainerInitializer.shouldSearchRepositoriesForSources(false, true)).isFalse();
	}

	@Test
	public void background_update_searches_only_when_enabled() {
		assertThat(BndContainerInitializer.shouldSearchRepositoriesForSources(true, false)).isTrue();
		assertThat(BndContainerInitializer.shouldSearchRepositoriesForSources(false, false)).isFalse();
	}
}
