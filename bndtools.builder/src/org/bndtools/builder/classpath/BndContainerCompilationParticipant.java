package org.bndtools.builder.classpath;

import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.compiler.CompilationParticipant;

import aQute.bnd.build.Project;
import bndtools.central.Central;

public class BndContainerCompilationParticipant extends CompilationParticipant {
	private static final ILogger logger = Logger.getLogger(BndContainerCompilationParticipant.class);

	public BndContainerCompilationParticipant() {
		super();
	}

	@Override
	public int aboutToBuild(IJavaProject javaProject) {
		try {
			Project model = Central.getProject(javaProject.getProject());
			if (model != null) {
				refreshProjectProperties(model);
			}
			return BndContainerInitializer.requestClasspathContainerUpdate(javaProject) ? NEEDS_FULL_BUILD
				: READY_FOR_BUILD;
		} catch (Exception e) {
			logger.logWarning(
				String.format("Failed to update classpath container for project %s", javaProject.getProject()
					.getName()),
				e);
		}
		return READY_FOR_BUILD;
	}

	static void refreshProjectProperties(Project model) throws Exception {
		model.getWorkspace()
			.writeLocked(() -> {
				model.refresh();
				return null;
			});
	}

	@Override
	public boolean isActive(IJavaProject javaProject) {
		return BndContainerInitializer.getClasspathContainer(javaProject) != null;
	}
}
