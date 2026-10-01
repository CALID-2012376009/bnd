package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
