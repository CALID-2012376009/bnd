package org.bndtools.builder;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bndtools.api.BndtoolsConstants;
import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.utils.workspace.WorkspaceUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.exceptions.ConsumerWithException;
import bndtools.central.Central;

public class CnfWatcher implements IResourceChangeListener {
	private static final ILogger	logger		= Logger.getLogger(CnfWatcher.class);
	private static final CnfWatcher	INSTANCE	= new CnfWatcher();
	private final AtomicBoolean		refreshScheduled	= new AtomicBoolean();

	static CnfWatcher install() {
		ResourcesPlugin.getWorkspace()
			.addResourceChangeListener(INSTANCE, IResourceChangeEvent.POST_CHANGE);
		return INSTANCE;
	}

	private CnfWatcher() {}

	static boolean isRefreshPending() {
		return INSTANCE.refreshScheduled.get();
	}

	static Collection<Project> refreshWorkspace(Workspace workspace, ConsumerWithException<Workspace> configure)
		throws Exception {
		return workspace.writeLocked(() -> {
			workspace.refresh();
			configure.accept(workspace);
			Collection<Project> projects = workspace.getAllProjects();
			// Repository indexes can change without changing bnd properties.
			// Invalidate paths before JDT's pre-compilation callback uses them.
			projects.forEach(Project::setChanged);
			return projects;
		});
	}

	@Override
	public void resourceChanged(final IResourceChangeEvent event) {
		if (Central.hasCnfWorkspace()) {
			processEvent(event);
		} else {
			Central.onCnfWorkspace(workspace -> processEvent(event));
		}
	}

	private void processEvent(IResourceChangeEvent event) {
		try {
			final Workspace workspace = Central.getWorkspaceIfPresent();

			if (workspace == null) {
				// this can happen during first project creation in an empty
				// workspace
				logger.logInfo("Unable to get workspace", null);
				return;
			}

			final IProject cnfProject = WorkspaceUtils.findCnfProject(ResourcesPlugin.getWorkspace()
				.getRoot(), workspace);
			if (cnfProject == null)
				return;

			IResourceDelta delta = event.getDelta();
			if (delta.findMember(cnfProject.getFullPath()) == null)
				return;

			Collection<Project> allProjects = workspace.getAllProjects();
			if (allProjects.isEmpty())
				return;

			Project p = allProjects.iterator()
				.next();
			DeltaWrapper dw = new DeltaWrapper(p, delta, new BuildLogger(BuildLogger.LOG_NONE, "", 0));
			if (dw.hasCnfChanged()) {
				if (!refreshScheduled.compareAndSet(false, true))
					return;

				WorkspaceJob j = new WorkspaceJob("Refreshing workspace for cnf change") {
					@Override
					public IStatus runInWorkspace(IProgressMonitor arg0) throws CoreException {
						try {
							Collection<Project> refreshed = refreshWorkspace(workspace,
								Central::applyRebuildTriggerPolicy);
							BndtoolsBuilder.dirty.addAll(refreshed);
							MarkerSupport ms = new MarkerSupport(cnfProject);
							ms.deleteMarkers("*");
							ms.setMarkers(workspace, BndtoolsConstants.MARKER_BND_WORKSPACE_PROBLEM);
							// clear errors/warnings, to avoid re-adding
							workspace.clear();
						} catch (Exception e) {
							return new Status(IStatus.ERROR, BndtoolsBuilder.PLUGIN_ID,
								"error during workspace refresh",
								e);
						} finally {
							refreshScheduled.set(false);
						}
						return Status.OK_STATUS;
					}
				};
				j.schedule(100);
			}
		} catch (Exception e) {
			logger.logError("Detecting changes in cnf failed, ignoring", e);
		}
	}
}
