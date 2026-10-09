package org.bndtools.builder.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.eclipse.jdt.core.IClasspathEntry;
import org.junit.jupiter.api.Test;

public class BndContainerTest {

	@Test
	void timestampOnlyUpdateIsReportedWithoutEntryDump() {
		IClasspathEntry entry = mock(IClasspathEntry.class);
		BndContainer previous = new BndContainer.Builder().entry(entry)
			.updateLastModified(100)
			.build();
		BndContainer updated = new BndContainer.Builder().entry(entry)
			.updateLastModified(200)
			.build();

		assertThat(BndContainerInitializer.describeClasspathChange(previous, updated))
			.isEqualTo("entriesChanged=false, timestampAdvanced=true, entries=1->1, timestamp=100->200");
	}

	@Test
	void entryChangeIsReportedEvenWithoutNewerTimestamp() {
		IClasspathEntry first = mock(IClasspathEntry.class, "first");
		IClasspathEntry second = mock(IClasspathEntry.class, "second");
		BndContainer previous = new BndContainer.Builder().entry(first)
			.updateLastModified(100)
			.build();
		BndContainer updated = new BndContainer.Builder().entry(second)
			.updateLastModified(100)
			.build();

		assertThat(BndContainerInitializer.describeClasspathChange(previous, updated)).isEqualTo(
			"entriesChanged=true, timestampAdvanced=false, entries=1->1, timestamp=100->100, firstDifference=0, oldEntry=first, newEntry=second");
	}

	@Test
	void addedAndRemovedEntriesAreReported() {
		IClasspathEntry entry = mock(IClasspathEntry.class, "entry");
		BndContainer empty = new BndContainer.Builder().build();
		BndContainer populated = new BndContainer.Builder().entry(entry)
			.build();

		assertThat(BndContainerInitializer.describeClasspathChange(empty, populated))
			.contains("entries=0->1", "firstDifference=0", "oldEntry=<absent>", "newEntry=entry");
		assertThat(BndContainerInitializer.describeClasspathChange(populated, empty))
			.contains("entries=1->0", "firstDifference=0", "oldEntry=entry", "newEntry=<absent>");
	}

	@Test
	void initialContainerIsReported() {
		BndContainer container = new BndContainer.Builder().updateLastModified(100)
			.build();

		assertThat(BndContainerInitializer.describeClasspathChange(null, container))
			.isEqualTo("initial container, entries=0, timestamp=100");
	}

	@Test
	public void testBndContainerSerialization() throws Exception {
		ClasspathContainerSerializationHelper<BndContainer> serializationHelper = new ClasspathContainerSerializationHelper<>();

		BndContainer c = new BndContainer.Builder().updateLastModified(System.currentTimeMillis())
			.build();
		assertThat(c).isNotNull();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		serializationHelper.writeClasspathContainer(c, baos);

		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		BndContainer c2 = serializationHelper.readClasspathContainer(bais);

		assertThat(c2).isNotSameAs(c);
		assertThat(c2.getClasspathEntries()).isEqualTo(c.getClasspathEntries());
		assertThat(c2.lastModified()).isEqualTo(c.lastModified());

	}

}
