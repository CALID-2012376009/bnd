package org.bndtools.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Collections;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.Path;
import org.junit.jupiter.api.Test;

import aQute.bnd.build.Project;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Processor;

class DeltaWrapperTest {

	private final Project		model	= mock(Project.class);
	private final BuildLogger	log		= new BuildLogger(BuildLogger.LOG_NONE, "test", 0);

	@Test
	void nullDeltaDoesNotReportPropertiesOrSubbundlesChanged() throws Exception {
		Processor processor = mock(Processor.class);
		when(processor.getIncluded()).thenReturn(Collections.emptyList());

		DeltaWrapper delta = new DeltaWrapper(model, null, log);

		assertThat(delta.havePropertiesChanged(processor)).isFalse();
		assertThat(delta.hasChangedSubbundles()).isFalse();
	}

	@Test
	void changedPropertiesFileIsReported() throws Exception {
		File propertiesFile = new File("project", Project.BNDFILE);
		Processor processor = mock(Processor.class);
		when(processor.getPropertiesFile()).thenReturn(propertiesFile);
		when(processor.getIncluded()).thenReturn(Collections.emptyList());

		IResourceDelta root = mock(IResourceDelta.class);
		IResourceDelta properties = mock(IResourceDelta.class);
		when(root.getFullPath()).thenReturn(new Path("/project"));
		when(root.findMember(new Path(Project.BNDFILE))).thenReturn(properties);
		when(properties.getKind()).thenReturn(IResourceDelta.CHANGED);

		DeltaWrapper delta = new DeltaWrapper(model, root, log,
			file -> new Path("/project").append(file.getName()));

		assertThat(delta.havePropertiesChanged(processor)).isTrue();
	}

	@Test
	void changedSubbundleInputIsReported() throws Exception {
		when(model.getProperty(Constants.SUB)).thenReturn("*.bnd");

		IResourceDelta root = mock(IResourceDelta.class);
		IResourceDelta subbundle = mock(IResourceDelta.class);
		IResource resource = mock(IResource.class);
		when(subbundle.getResource()).thenReturn(resource);
		when(resource.getType()).thenReturn(IResource.FILE);
		when(subbundle.getProjectRelativePath()).thenReturn(new Path("bundle.bnd"));
		doAnswer(invocation -> {
			invocation.getArgument(0, IResourceDeltaVisitor.class)
				.visit(subbundle);
			return null;
		}).when(root)
			.accept(any());

		DeltaWrapper delta = new DeltaWrapper(model, root, log);

		assertThat(delta.hasChangedSubbundles()).isTrue();
	}
}
