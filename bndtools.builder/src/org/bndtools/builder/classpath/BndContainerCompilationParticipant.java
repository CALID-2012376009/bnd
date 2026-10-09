package org.bndtools.builder.classpath;

import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.builder.BuildLogger;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.compiler.CompilationParticipant;

import bndtools.central.Central;
import bndtools.preferences.BndPreferences;

import aQute.bnd.build.Project;

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
				BndContainerInitializer.getRefreshedBuildpath(model);
			}
			boolean updated = BndContainerInitializer.requestClasspathContainerUpdate(javaProject);
			if (updated && new BndPreferences().getBuildLogging() >= BuildLogger.LOG_BASIC) {
				logger.logInfo("JAVA FULL BUILD " + javaProject.getProject()
					.getName() + ": requested after Bnd classpath container update", null);
			}
			return updated ? NEEDS_FULL_BUILD : READY_FOR_BUILD;
		} catch (Exception e) {
			logger.logWarning(
				String.format("Failed to update classpath container for project %s", javaProject.getProject()
					.getName()),
				e);
		}
		return READY_FOR_BUILD;
	}

	@Override
	public boolean isActive(IJavaProject javaProject) {
		return BndContainerInitializer.getClasspathContainer(javaProject) != null;
	}
}
