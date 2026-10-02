package org.bndtools.builder;

import java.io.File;

import org.bndtools.build.api.IProjectDecorator;
import org.bndtools.builder.decorator.ui.ProjectDecoratorImpl;
import org.bndtools.builder.classpath.BndContainerSourceManager;
import org.osgi.framework.BundleContext;

import aQute.bnd.build.WorkspaceRepository;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.RepositoryListenerPlugin;
import aQute.bnd.service.RepositoryPlugin;

public class BuilderPlugin extends org.eclipse.core.runtime.Plugin {

	private static BuilderPlugin instance = null;

	public static BuilderPlugin getInstance() {
		synchronized (BuilderPlugin.class) {
			return instance;
		}
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		synchronized (BuilderPlugin.class) {
			instance = this;
		}
		context.registerService(IProjectDecorator.class, new ProjectDecoratorImpl(), null);
		context.registerService(RepositoryListenerPlugin.class, new RepositoryListenerPlugin() {
			@Override
			public void bundleAdded(RepositoryPlugin repository, Jar jar, File file) {
				repositoryChanged(repository);
			}

			@Override
			public void bundleRemoved(RepositoryPlugin repository, Jar jar, File file) {
				repositoryChanged(repository);
			}

			@Override
			public void repositoryRefreshed(RepositoryPlugin repository) {
				repositoryChanged(repository);
			}

			@Override
			public void repositoriesRefreshed() {
				BndContainerSourceManager.clearSourceLookupCache();
			}

			private void repositoryChanged(RepositoryPlugin repository) {
				if (!(repository instanceof WorkspaceRepository)) {
					BndContainerSourceManager.clearSourceLookupCache();
				}
			}
		}, null);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		synchronized (BuilderPlugin.class) {
			instance = null;
		}
		super.stop(context);
	}

}
