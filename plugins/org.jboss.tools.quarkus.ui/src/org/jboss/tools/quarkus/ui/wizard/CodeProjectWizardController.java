/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.quarkus.ui.wizard;

import static org.jboss.tools.quarkus.ui.QuarkusUIPlugin.PLUGIN_ID;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.buildship.core.BuildConfiguration;
import org.eclipse.buildship.core.GradleCore;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.m2e.core.embedder.MavenModelManager;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;
import org.eclipse.m2e.core.project.ProjectImportConfiguration;
import org.jboss.tools.quarkus.core.code.model.QuarkusModelRegistry;
import org.jboss.tools.quarkus.core.project.ProjectUtils;

public class CodeProjectWizardController {

    private CodeProjectModel model;

    public CodeProjectWizardController(CodeProjectModel model) {
        this.model = model;
    }
    
    public IStatus run(IProgressMonitor monitor) {
        IStatus status = getZip(monitor);
        if (!monitor.isCanceled() 
                && status.isOK()) {
            status = createProject(monitor);
        }
        return status;
    }

	private IStatus getZip(IProgressMonitor monitor) {
		QuarkusModelRegistry manager = QuarkusModelRegistry.getDefault();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		IStatus status = null;
		status = manager.zip(model.getEndpoint(), model.getTool(), model.getGroupId(), model.getArtifactId(),
		        model.getVersion(), model.getClassName(), model.getPath(), model.getExtensionsModel(),
		        model.getSelectedExtensions(), model.isUseCodeStarters(), output, monitor);
		if (!monitor.isCanceled() && status.isOK()) {
			status = QuarkusModelRegistry.unzip(output.toByteArray(), model.getLocation());
		}
		return status;
	}

    private IStatus createProject(IProgressMonitor monitor) {
        switch (model.getTool()) {
        case MAVEN:
            return createMavenProject(monitor);
        case GRADLE:
            return createGradleProject(monitor);
        default:
            return null;
        }
    }


    private IProject createGeneralProject(IProgressMonitor monitor) throws CoreException {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProjectDescription description = workspace.newProjectDescription(model.getProjectName());
            description.setLocation(model.getLocation());
            IProject project = workspace.getRoot().getProject(model.getProjectName());
            project.create(description, monitor);
            project.open(IResource.BACKGROUND_REFRESH, monitor);
            return project;
    }

    private IStatus createGradleProject(IProgressMonitor monitor) {
        IStatus status = Status.OK_STATUS;
        try {
            IProject project = createGeneralProject(monitor);
            BuildConfiguration buildConfig = BuildConfiguration.forRootProjectDirectory(project.getLocation().toFile()).build();
            GradleCore.getWorkspace().createBuild(buildConfig).synchronize(monitor);
            makeExecutable();
        } catch (IOException e) {
        	status = new Status(IStatus.ERROR, PLUGIN_ID, e.getLocalizedMessage(), e);   
        } catch (CoreException e) {
            status = e.getStatus();
        }
        return status;
    }

    private IStatus createMavenProject(IProgressMonitor monitor) {
        IStatus status = Status.OK_STATUS;
        MavenPluginActivator mavenPlugin = MavenPluginActivator.getDefault();
        IProjectConfigurationManager configurationManager = mavenPlugin.getProjectConfigurationManager();
        MavenModelManager modelManager = mavenPlugin.getMavenModelManager();
        LocalProjectScanner scanner = new LocalProjectScanner(
                ResourcesPlugin.getWorkspace().getRoot().getRawLocation().toFile(), 
                model.getLocation().toOSString(), 
                false,
                modelManager);
        try {
            scanner.run(monitor);
            ProjectImportConfiguration projectImportConfiguration = new ProjectImportConfiguration();
            configurationManager.importProjects(collect(scanner.getProjects()), projectImportConfiguration, monitor);
            makeExecutable();
        } catch (IOException e) {
        	status = new Status(IStatus.ERROR, PLUGIN_ID, e.getLocalizedMessage(), e);   
        } catch (InterruptedException e) {
            status = new Status(IStatus.ERROR, PLUGIN_ID, e.getLocalizedMessage(), e);
            Thread.currentThread().interrupt();
        } catch (CoreException e) {
            status = e.getStatus();
        }
        return status;
    }
    
    private void makeExecutable() throws IOException {
			if (!Platform.OS_WIN32.equals(Platform.getOS())) {
				IPath toolPath = ProjectUtils
				        .getToolSupport(ResourcesPlugin.getWorkspace().getRoot().getProject(model.getProjectName())).getScriptPath();
				Files.setPosixFilePermissions(toolPath.toFile().toPath(),
				        PosixFilePermissions.fromString("rwxr--r--"));
			}
    }
    
    private Set<MavenProjectInfo> collect(Collection<MavenProjectInfo> projects) {
        return new LinkedHashSet<MavenProjectInfo>() {
            private static final long serialVersionUID = 1L;

            public Set<MavenProjectInfo> collect(Collection<MavenProjectInfo> projects) {
                for (MavenProjectInfo projectInfo : projects) {
                    add(projectInfo);
                    collect(projectInfo.getProjects());
                }
                return this;
            }
        }.collect(projects);
    }

}
