package com.vyulabs.update.installer

import java.io.File
import java.net.{URI, URL}

import com.vyulabs.update.distribution.distribution.ClientAdminRepository
import com.vyulabs.update.common.Common
import com.vyulabs.update.common.Common.{ClientName, ServiceName}
import com.vyulabs.update.info.DesiredVersions
import com.vyulabs.update.distribution.client.ClientDistributionDirectory
import com.vyulabs.update.distribution.developer.DeveloperDistributionDirectoryClient
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.utils.UpdateUtils
import com.vyulabs.update.version.BuildVersion
import org.slf4j.Logger

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 04.02.19.
  * Copyright FanDate, Inc.
  */
class InitClient()(implicit filesLocker: SmartFilesLocker, log: Logger) {
  private val adminRepositoryDir = new File("src/test", "admin")
  private val distributionDir = new File("src/test", "distrib")

  def initClient(clientName: ClientName,
                 clientDistributionUrl: URL, adminRepositoryUri: URI,
                 developerDistribution: DeveloperDistributionDirectoryClient,
                 distributionServicePort: Int): Boolean = {
    val clientDistribution = new ClientDistributionDirectory(new File(distributionDir, "directory"))
    log.info("Init admin repository")
    if (!initAdminRepository()) {
      log.error("Can't init admin repository")
      return false
    }
    log.info("Init distribution directory")
    if (!initDistribDirectory(clientName, clientDistribution, developerDistribution, distributionServicePort)) {
      log.error("Can't init distribution directory")
      return false
    }
    log.info("Init install directory")
    if (!initInstallDirectory(
        clientDistribution.getVersionImageFile(Common.ScriptsServiceName,
          clientDistribution.getDesiredVersion(Common.ScriptsServiceName).get),
      clientName, clientDistributionUrl, adminRepositoryUri)) {
      log.error("Can't init install repository")
      return false
    }
    log.info("Client is initialized successfully.")
    true
  }

  private def initAdminRepository(): Boolean = {
    if (!adminRepositoryDir.exists()) {
      log.info(s"Create admin repository in directory ${adminRepositoryDir}")
      if (!adminRepositoryDir.mkdir()) {
        log.error(s"Can't make directory ${adminRepositoryDir}")
        return false
      }
      if (!ClientAdminRepository.create(adminRepositoryDir)) {
        log.error("Can't create admin repository")
        return false
      }
    } else {
      log.info(s"Directory ${adminRepositoryDir} exists")
    }
    true
  }

  private def initInstallDirectory(scriptsZip: File, clientName: ClientName,
                                   clientDistributionUrl: URL, adminRepositoryUri: URI): Boolean = {
    log.info("Update installer.sh")
    if (!UpdateUtils.unzip(scriptsZip, new File("."), (name: String) => { name == "installer.sh" })) {
      return false
    }
    val installerFile = new File("installer.sh")
    val originalContent = new String(UpdateUtils.readFileToBytes(installerFile).getOrElse {
      log.error(s"Read file ${installerFile} error")
      return false
    }, "utf8")
    installerFile.renameTo(File.createTempFile("installer", "sh"))
    val content = originalContent.replace("#clientName=", s"clientName=${clientName}")
      .replaceAll("#clientDistributionUrl=", s"clientDistributionUrl=${clientDistributionUrl}")
      .replaceAll("#adminRepositoryUrl=", s"adminRepositoryUrl=${adminRepositoryUri}")
    if (!UpdateUtils.writeFileFromBytes(installerFile, content.getBytes("utf8"))) {
      log.error(s"Write file ${installerFile} error")
      return false
    }
    if (!UpdateUtils.runProcess("chmod", Seq("+x", "installer.sh"), Map.empty, new File("."),
          Some(0), None, false)) {
      log.warn("Can't set execution attribute to installer.sh")
    }
    true
  }

  private def initDistribDirectory(clientName: ClientName,
                                   clientDistribution: ClientDistributionDirectory,
                                   developerDistribution: DeveloperDistributionDirectoryClient,
                                   distributionServicePort: Int): Boolean = {
    if (!distributionDir.exists()) {
      log.info(s"Create directory ${distributionDir}")
      if (!distributionDir.mkdir()) {
        log.error(s"Can't make directory ${distributionDir}")
        return false
      }
    } else {
      log.info(s"Directory ${distributionDir} exists")
    }
    log.info("Download desired versions")
    val desiredVersions = developerDistribution.downloadDesiredVersions(clientName).getOrElse {
      log.error("Can't download desired versions")
      return false
    }.Versions
    if (!downloadUpdateServices(clientDistribution, developerDistribution, desiredVersions)) {
      log.error("Can't download update services")
      return false
    }
    log.info("Write desired versions")
    if (!UpdateUtils.writeConfigFile(clientDistribution.getDesiredVersionsFile(), DesiredVersions(desiredVersions).toConfig())) {
      log.error("Can't write desired versions")
      return false
    }
    log.info("Setup distribution server")
    if (!setupDistributionServer(clientName, clientDistribution, developerDistribution, desiredVersions, distributionServicePort)) {
      log.error("Can't setup distribution server")
      return false
    }
    true
  }

  private def downloadUpdateServices(clientDistribution: ClientDistributionDirectory,
                                     developerDistribution: DeveloperDistributionDirectoryClient,
                                     desiredVersions: Map[ServiceName, BuildVersion]): Boolean = {
    Seq(Common.ScriptsServiceName, Common.DistributionServiceName, Common.InstallerServiceName, Common.UpdaterServiceName).foreach {
      serviceName =>
        if (!downloadVersion(clientDistribution, developerDistribution, serviceName, desiredVersions.get(serviceName).getOrElse {
          log.error(s"Desired version of service ${serviceName} is not defined")
          return false
        })) {
          log.error(s"Can't copy version image of service ${serviceName}")
          return false
        }
    }
    true
  }

  private def downloadVersion(clientDistribution: ClientDistributionDirectory,
                              developerDistribution: DeveloperDistributionDirectoryClient,
                              serviceName: ServiceName, buildVersion: BuildVersion): Boolean = {
    log.info(s"Download version ${buildVersion} of service ${serviceName}")
    developerDistribution.downloadVersionImage(serviceName, buildVersion,
      clientDistribution.getVersionImageFile(serviceName, buildVersion)) &&
    developerDistribution.downloadVersionInfoFile(serviceName, buildVersion,
      clientDistribution.getVersionInfoFile(serviceName, buildVersion))
  }

  private def setupDistributionServer(clientName: ClientName,
                                      clientDistribution: ClientDistributionDirectory,
                                      developerDistribution: DeveloperDistributionDirectoryClient,
                                      desiredVersions: Map[ServiceName, BuildVersion],
                                      distributionServicePort: Int): Boolean = {
    UpdateUtils.unzip(clientDistribution.getVersionImageFile(Common.ScriptsServiceName, desiredVersions.get(Common.ScriptsServiceName).get),
      distributionDir, (name: String) => {
        name == "distribution_setup.sh" || name == "distribution.sh"
      })
    if (!UpdateUtils.runProcess("bash",
        Seq( "distribution_setup.sh", "client", clientName, developerDistribution.url.toString, distributionServicePort.toString),
        Map.empty, distributionDir, Some(0), None, true)) {
      log.error("Can't setup distribution server")
      return false
    }
    new File("distribution_setup.sh").delete()
    true
  }
}
