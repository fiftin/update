package com.vyulabs.update.builder

import java.io.File
import java.net.URI
import java.util.Date

import com.vyulabs.libs.git.GitRepository
import com.vyulabs.update.distribution.{AdminRepository, GitRepositoryUtils}
import com.vyulabs.update.distribution.distribution.DeveloperAdminRepository
import com.vyulabs.update.builder.config.SourcesConfig
import com.vyulabs.update.utils.UpdateUtils
import com.vyulabs.update.common.Common.{ClientName, ServiceName}
import com.vyulabs.update.common.Common
import com.vyulabs.update.config.UpdateConfig
import com.vyulabs.update.info.{DesiredVersions, VersionInfo}
import com.vyulabs.update.distribution.developer.DeveloperDistributionDirectoryClient
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.utils.UpdateUtils.{copyFile, extendMacro}
import com.vyulabs.update.version.BuildVersion
import org.eclipse.jgit.transport.RefSpec
import org.slf4j.Logger

class Builder(directory: DeveloperDistributionDirectoryClient, adminRepositoryUri: URI)(implicit filesLocker: SmartFilesLocker) {
  private val builderLockFile = "builder.lock"

  def makeVersion(author: String, serviceName: ServiceName,
                  clientName: Option[ClientName], comment: Option[String],
                  newVersion: Option[BuildVersion], sourceBranches: Seq[String])
                 (implicit log: Logger): Option[BuildVersion] = {
    val servicesDir = new File("services")
    val serviceDir = new File(servicesDir, serviceName)
    if (!serviceDir.exists() && !serviceDir.mkdirs()) {
      log.error(s"Can't create directory ${serviceDir}")
    }
    UpdateUtils.synchronize[Option[BuildVersion]](new File(serviceDir, builderLockFile), false,
      (attempt, _) => {
        if (attempt == 1) {
          log.info(s"Another builder creates version for ${serviceName} - wait ...")
        }
        Thread.sleep(5000)
        true
      },
      () => {
        val adminRepository = DeveloperAdminRepository(adminRepositoryUri, new File(serviceDir, "admin")).getOrElse {
          sys.error("Init admin repository error")
        }
        val sourcesConfig = SourcesConfig(adminRepository.getDirectory()).getOrElse {
          sys.error("Can't get config of sources")
        }
        val sourceRepositoriesConf = sourcesConfig.Services.get(serviceName).getOrElse {
          sys.error(s"Source repositories of service ${serviceName} is not specified.")
        }

        val gitLock = adminRepository.buildVersionLock(serviceName)
        if (gitLock.lock(DeveloperAdminRepository.makeStartOfBuildMessage(author, serviceName, clientName, comment, newVersion),
          s"Continue build version")) {
          var generatedVersion = Option.empty[BuildVersion]
          try {
            val sourceDir = new File(serviceDir, "source")
            val buildDir = new File(serviceDir, "build")

            if (buildDir.exists() && !UpdateUtils.deleteFileRecursively(buildDir)) {
              log.error(s"Can't delete build directory ${buildDir}")
              return None
            }

            log.info("Get existing versions")
            val version = newVersion match {
              case Some(version) =>
                if (directory.isVersionExists(serviceName, version)) {
                  log.error(s"Version ${version} already exists")
                  return None
                }
                version
              case None =>
                directory.downloadVersionsInfo(clientName, serviceName) match {
                  case Some(versions) if (!versions.info.isEmpty) =>
                    val lastVersion = versions.info.sortBy(_.buildVersion)(BuildVersion.ordering).last
                    log.info(s"Last version is ${lastVersion}")
                    lastVersion.buildVersion.next()
                  case _ =>
                    log.error("No existing versions")
                    BuildVersion(clientName, Seq(1, 0, 0))
                }
            }

            log.info(s"Generate version ${version}")

            log.info(s"Pull source repositories")
            var sourceRepositories = Seq.empty[GitRepository]
            var mainSourceRepository: GitRepository = null
            val sourceBranchIt = sourceBranches.iterator
            for (repositoryConf <- sourceRepositoriesConf) {
              val directory = repositoryConf.directory match {
                case Some(dir) =>
                  new File(sourceDir, dir)
                case None =>
                  sourceDir
              }
              val branch = if (sourceBranchIt.hasNext) {
                sourceBranchIt.next()
              } else {
                "master"
              }
              val sourceRepository =
                GitRepositoryUtils.getGitRepository(repositoryConf.uri, branch, repositoryConf.cloneSubmodules, directory).getOrElse {
                  log.error("Pull source repository error")
                  return None
                }
              sourceRepositories :+= sourceRepository
              if (mainSourceRepository == null) {
                mainSourceRepository = sourceRepository
              }
            }

            log.info("Initialize update config")
            val servicesUpdateConfig = UpdateConfig(mainSourceRepository.getDirectory()).getOrElse {
              return None
            }
            val updateConfig = servicesUpdateConfig.services.getOrElse(serviceName, {
              log.error(s"Can't find update config for service ${serviceName}")
              return None
            })

            log.info("Execute build commands")
            var args = Map.empty[String, String]
            args += ("version" -> version.toString)
            for (command <- updateConfig.BuildConfig.BuildCommands) {
              if (!UpdateUtils.runProcess(command, args, mainSourceRepository.getDirectory(), true)) {
                return None
              }
            }

            log.info(s"Copy files to build directory ${buildDir}")
            for (copyCommand <- updateConfig.BuildConfig.CopyBuildFiles) {
              val sourceFile = extendMacro(copyCommand.SourceFile, args)
              val in = if (sourceFile.startsWith("/")) {
                new File(sourceFile)
              } else {
                new File(mainSourceRepository.getDirectory(), sourceFile)
              }
              val out = new File(buildDir, extendMacro(copyCommand.DestinationFile, args))
              val outDir = out.getParentFile
              if (outDir != null) {
                if (!outDir.exists() && !outDir.mkdirs()) {
                  log.error(s"Can't make directory ${outDir}")
                  return None
                }
              }
              if (!copyFile(in, out, file => !copyCommand.Except.contains(in.toPath.relativize(file.toPath).toString), copyCommand.Settings)) {
                return None
              }
            }

            for (installConfig <- updateConfig.InstallConfig) {
              log.info("Create install configuration file")
              val configFile = new File(buildDir, Common.InstallConfigFileName)
              if (configFile.exists()) {
                log.error(s"Build repository already contains file ${configFile}")
                return None
              }
              if (!UpdateUtils.writeConfigFile(configFile, installConfig.Origin)) {
                return None
              }
            }

            log.info(s"Upload version ${version} to distribution directory")
            val versionInfo = VersionInfo(version, author, sourceBranches, new Date(), comment)
            if (!directory.uploadVersion(serviceName, versionInfo, buildDir)) {
              return None
            }

            log.info(s"Mark source repositories with version ${version}")
            for (repository <- sourceRepositories) {
              val tag = serviceName + "-" + version.toString
              if (!repository.setTag(tag, comment)) {
                return None
              }
              if (!repository.push(Seq(new RefSpec(tag)))) {
                return None
              }
            }

            log.info(s"Version ${version} is created successfully")
            generatedVersion = Some(version)
            generatedVersion
          } finally {
            adminRepository.processLogFile(!generatedVersion.isEmpty)
            if (!gitLock.unlock(DeveloperAdminRepository.makeEndOfBuildMessage(serviceName, generatedVersion))) {
              log.error("Can't unlock version generation")
            }
            adminRepository.tagServices(Seq(serviceName))
          }
        } else {
          log.error(s"Can't lock build new version of service ${serviceName}")
          None
        }
      }).flatten
  }

  def getDesiredVersions(clientName: Option[ClientName])(implicit log: Logger): Option[Map[ServiceName, BuildVersion]] = {
    directory.downloadDesiredVersions(clientName).map(_.Versions)
  }

  def setDesiredVersions(clientName: Option[ClientName], servicesVersions: Map[ServiceName, Option[BuildVersion]])
                         (implicit log: Logger): Boolean = {
    log.info(s"Upload desired versions ${servicesVersions}" + (if (clientName.isDefined) s" for client ${clientName.get}" else ""))
    UpdateUtils.synchronize[Boolean](new File(".", builderLockFile), false,
      (attempt, _) => {
        if (attempt == 1) {
          log.info("Another builder is running - wait ...")
        }
        Thread.sleep(5000)
        true
      },
      () => {
        val adminRepository = DeveloperAdminRepository(adminRepositoryUri, new File("admin")).getOrElse {
          sys.error("Init admin repository error")
        }
        val gitLock = adminRepository.buildDesiredVersionsLock()
        if (gitLock.lock(AdminRepository.makeStartOfSettingDesiredVersionsMessage(servicesVersions),
          s"Continue updating of desired versions")) {
          var newDesiredVersions = Option.empty[DesiredVersions]
          try {
            var desiredVersionsMap = directory.downloadDesiredVersions(clientName).map(_.Versions).getOrElse(Map.empty)
            servicesVersions.foreach {
              case (serviceName, Some(version)) =>
                desiredVersionsMap += (serviceName -> version)
              case (serviceName, None) =>
                desiredVersionsMap -= serviceName
            }
            val desiredVersions = DesiredVersions(desiredVersionsMap)
            if (!directory.uploadDesiredVersions(clientName, desiredVersions)) {
              log.error("Can't update desired versions")
              return false
            }
            log.info(s"Desired versions are successfully uploaded")
            newDesiredVersions = Some(desiredVersions)
            true
          } finally {
            for (desiredVersions <- newDesiredVersions) {
              val desiredVersionsFile = adminRepository.getDesiredVersionsFile()
              if (!UpdateUtils.writeConfigFile(desiredVersionsFile, desiredVersions.toConfig())) {
                return false
              }
              if (!adminRepository.addFileToCommit(desiredVersionsFile)) {
                return false
              }
            }
            adminRepository.processLogFile(!newDesiredVersions.isEmpty)
            if (!gitLock.unlock(AdminRepository.makeEndOfSettingDesiredVersionsMessage(!newDesiredVersions.isEmpty, servicesVersions))) {
              log.error("Can't unlock update of desired versions")
            }
            if (!servicesVersions.isEmpty) {
              adminRepository.tagServices(servicesVersions.map(_._1).toSeq)
            }
          }
        } else {
          log.error(s"Can't lock updating of desired versions")
          false
        }
      }).getOrElse(false)
  }
}
