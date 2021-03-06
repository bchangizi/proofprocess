package org.ai4fm.proofprocess.project.core

import java.io.IOException

import scala.collection.JavaConverters._

import org.ai4fm.filehistory.{FileHistoryFactory, FileHistoryProject, FileVersion}
import org.ai4fm.filehistory.core.{FileHistoryUtil, IFileHistoryManager, XmlFileHistoryManager}
import org.ai4fm.proofprocess.cdo.PProcessCDO
import org.ai4fm.proofprocess.project.core.internal.ProjectPProcessCorePlugin.{error, log, plugin}

import org.eclipse.core.resources.{IFile, IProject, IResource}
import org.eclipse.core.runtime.{CoreException, IPath, IProgressMonitor, NullProgressMonitor, Path, QualifiedName, SubProgressMonitor}
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.emf.common.command.BasicCommandStack
import org.eclipse.emf.edit.domain.AdapterFactoryEditingDomain
import org.eclipse.emf.edit.provider.ComposedAdapterFactory

import ProofManager.projectPProcessRepositoryInfo


/**
  * @author Andrius Velykis
  */
object ProofHistoryManager {

  private def historyFileDir = "files"

  /** Key for the loaded project reference on resource */
  lazy private val PROP_FILE_HISTORY =
    new QualifiedName(plugin.pluginId, "fileHistory") //$NON-NLS-1$

  @throws(classOf[CoreException])
  private def historyManager(projectResource: IProject, monitor: IProgressMonitor): ProjectHistoryManager = {

    // retrieve the stored manager, or load one otherwise
    storedManager(projectResource) getOrElse {

      // create/load manager
      val submonitor = subMonitor(monitor)

      try {
        // start a rule on the resource to block when loading the manager
        Job.getJobManager.beginRule(projectResource, monitor)

        // check maybe the manager has already been loaded (double-checked locking)
        storedManager(projectResource) getOrElse {

          monitor.beginTask("Initialising manager", IProgressMonitor.UNKNOWN)

          // TODO what if the project gets moved?
          val proofProcessRoot = projectResource.getLocation.append(ProofManager.proofFolder)
          val historyDir = proofProcessRoot.append(historyFileDir)

          val historyManager = FileHistoryUtil.historyManager(proofProcessRoot.toFile, historyDir.toFile)
          val historyProject = loadHistory(projectResource, monitor)

          val projectHistoryMan = ProjectHistoryManager(historyManager, historyProject)
          projectResource.setSessionProperty(PROP_FILE_HISTORY, projectHistoryMan)

          projectHistoryMan
        }

      } finally {
        Job.getJobManager.endRule(projectResource)
        submonitor.done()
      }
    }
  }

  private def storedManager(projectResource: IProject): Option[ProjectHistoryManager] =
    projectResource.getSessionProperty(PROP_FILE_HISTORY) match {
      case manager: ProjectHistoryManager => Some(manager)
      case _ => None
    }

  private def subMonitor(monitor: IProgressMonitor): IProgressMonitor = {
    Option(monitor) match {
      case Some(monitor) => new SubProgressMonitor(monitor, 10)
      case None => new NullProgressMonitor
    }
  }

  private def loadHistory(projectResource: IProject, monitor: IProgressMonitor) = {

    // Create a ComposedAdapterFactory for all registered models
    val adapterFactory = new ComposedAdapterFactory(ComposedAdapterFactory.Descriptor.Registry.INSTANCE)
    val editingDomain = new AdapterFactoryEditingDomain(adapterFactory, new BasicCommandStack)

    val (databaseLocUri, repositoryName) = projectPProcessRepositoryInfo(projectResource)

    val session = PProcessCDO.session(databaseLocUri, repositoryName)
    val transaction = session.openTransaction(editingDomain.getResourceSet)

    // resolve the "filehistory" resource in the CDO repository
    val emfResource = transaction.getOrCreateResource("filehistory")

    try {
      // load EMF resource
      emfResource.load(null)
    } catch {
      case e: IOException => log(error(Some(e)))
    }

    val resourceContents = emfResource.getContents()

    // look for the history project as the root element
    resourceContents.asScala.headOption match {
      case Some(e: FileHistoryProject) => e
      case _ => {

        // the history project is not available - allocate a new one
        // create a new project, add to the resource and commit
        
        // before creating a new one, try migrating existing XML-based history
        val migrateProject = migrateXmlFileHistory(projectResource, monitor)
        
        val newProject = migrateProject getOrElse FileHistoryFactory.eINSTANCE.createFileHistoryProject
        
        resourceContents.clear
        resourceContents.add(newProject)
        ProofManager.commitTransaction(transaction, monitor)

        newProject
      }
    }
  }

  @throws(classOf[CoreException])
  def syncFileVersion(project: IProject, filePath: IPath, text: Option[String], syncPoint: Option[Int],
    monitor: IProgressMonitor): FileVersion = {

    // if the file path is absolute to workspace, make it relative to project
    val relativePath = if (filePath.isAbsolute()) {
      filePath.makeRelativeTo(project.getLocation)
    } else {
      filePath
    }

    syncFileVersion(project, relativePath.toPortableString, text, syncPoint, monitor)
  }

  @throws(classOf[CoreException])
  def syncFileVersion(projectResource: IProject, path: String, text: Option[String], syncPoint: Option[Int],
    monitor: IProgressMonitor): FileVersion = {

    val historyMan = historyManager(projectResource, monitor)

    val (syncedVersion, changed) = historyMan.manager.syncFileVersion(historyMan.history,
      projectResource.getLocation().toOSString, path, text, syncPoint, monitor)

    if (changed) {
      // refresh the created file
      val file = versionFile(historyMan.manager, projectResource, syncedVersion)
      file.refreshLocal(IResource.DEPTH_ZERO, null)
    }

    // commit transaction after each sync
    ProofManager.commitTransaction(syncedVersion, monitor)

    syncedVersion
  }

  private def versionFile(historyManager: IFileHistoryManager, projectResource: IProject,
    version: FileVersion): IFile = {

    val versionPath = Path.fromOSString(historyManager.historyFile(version).getPath)
    val relativePath = versionPath.makeRelativeTo(projectResource.getLocation)

    projectResource.getFile(relativePath)
  }
  
  private case class ProjectHistoryManager(val manager: IFileHistoryManager, val history: FileHistoryProject)

  /** Load file history from XML to migrate */
  private def migrateXmlFileHistory(projectResource: IProject,
      monitor: IProgressMonitor): Option[FileHistoryProject] = {
    
    val historyPath = projectResource.getLocation.append(ProofManager.proofFolder).toOSString
    Option(XmlFileHistoryManager.getHistoryProject(historyPath, monitor))
  }
  
}
