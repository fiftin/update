package com.vyulabs.update.installer

import java.io.File
import java.net.{URI, URL}

import com.vyulabs.update.common.Common
import com.vyulabs.update.distribution.distribution.ClientAdminRepository
import com.vyulabs.update.common.Common.ServiceName
import com.vyulabs.update.common.com.vyulabs.common.utils.Arguments
import com.vyulabs.update.distribution.client.ClientDistributionDirectoryClient
import com.vyulabs.update.distribution.developer.DeveloperDistributionDirectoryClient
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.utils.UpdateUtils
import com.vyulabs.update.version.BuildVersion
import org.slf4j.LoggerFactory

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 01.02.19.
  * Copyright FanDate, Inc.
  */
object InstallerMain extends App {
  implicit val log = LoggerFactory.getLogger(this.getClass)

  def usage() =
    "Arguments: initDeveloper <distributionServicePort=value>\n" +
    "           initClient <clientName=value> <adminRepositoryUrl=value> <clientDistributionUrl=value> <developerDirectoryUrl=value> <distributionServicePort=value>\n" +
    "           installUpdates <clientName=value> <adminRepositoryUrl=value> <clientDistributionUrl=value> <developerDistributionUrl=value>\n" +
    "                          [servicesOnly=<service1>:[-<profile>][,...]] [localConfigOnly=true] [setDesiredVersions=true]\n" +
    "           getDesiredVersions <clientName=value> <adminRepositoryUrl=value> <clientDistributionUrl=value>\n" +
    "           setDesiredVersions <clientName=value> <adminRepositoryUrl=value> <clientDistributionUrl=value> <services=<service[:version]>,[service1[:version1]],...>"

  if (args.size < 1) {
    sys.error(usage())
  }

  val command = args(0)
  val arguments = Arguments.parse(args.drop(1))

  implicit val filesLocker = new SmartFilesLocker()

  UpdateUtils.synchronize(new File(s"installer.lock"), false,
    (attempt, _) => {
      if (attempt == 1) {
        log.info("Another installer is running - wait ...")
      }
      Thread.sleep(5000)
      true
    },
    () => {
      command match {
        case "initDeveloper" =>
          val initDeveloper = new InitDeveloper()
          val distributionServicePort = arguments.getIntValue("distributionServicePort")
          if (!initDeveloper.initDeveloper(distributionServicePort)) {
            sys.error("Init developer error")
          }

        case "initClient" =>
          val initClient = new InitClient()
          val clientName = arguments.getValue("clientName")
          val adminRepositoryUri = new URI(arguments.getValue("adminRepositoryUrl"))
          val clientDistributionUrl = new URL(arguments.getValue("clientDistributionUrl"))
          val developerDirectoryUrl = new URL(arguments.getValue("developerDirectoryUrl"))
          val distributionServicePort = arguments.getIntValue("distributionServicePort")
          val developerDirectory = DeveloperDistributionDirectoryClient(developerDirectoryUrl)
          if (!initClient.initClient(clientName, clientDistributionUrl, adminRepositoryUri, developerDirectory, distributionServicePort)) {
            sys.error("Init client error")
          }

        case "installUpdates" =>
          val updateClient = new UpdateClient()
          val clientName = arguments.getValue("clientName")
          val adminRepositoryUri = new URI(arguments.getValue("adminRepositoryUrl"))
          log.info(s"Initialize admin repository")
          val adminRepository =
            ClientAdminRepository(adminRepositoryUri, new File("admin")).getOrElse {
              sys.error("Admin repository initialize error")
            }

          val clientDistributionUrl = new URL(arguments.getValue("clientDistributionUrl"))
          val clientDistribution = new ClientDistributionDirectoryClient(clientDistributionUrl)

          val developerDirectoryUrl = new URL(arguments.getValue("developerDirectoryUrl"))
          val developerDistribution = DeveloperDistributionDirectoryClient(developerDirectoryUrl)
          val servicesOnly = arguments.getOptionValue("servicesOnly")
            .map(_.split(",").foldLeft(Set.empty[ServiceName])((set, record) => {
              if (record == Common.InstallerServiceName) {
                sys.error("No need to update installer. It updates itself when running.")
              }
              set + record
            }))
          val localConfigOnly = arguments.getOptionBooleanValue("localConfigOnly").getOrElse(false)
          if (localConfigOnly && servicesOnly.isEmpty) {
            sys.error("Use option localConfigOnly with servicesOnly")
          }
          val setDesiredVersions = arguments.getOptionBooleanValue("setDesiredVersions").getOrElse(true)
          if (!updateClient.installUpdates(clientName, adminRepository, clientDistribution, developerDistribution,
               servicesOnly, localConfigOnly, setDesiredVersions)) {
            sys.error("Install update error")
          }

        case "getDesiredVersions" =>
          val updateClient = new UpdateClient()
          val adminRepositoryUri = new URI(arguments.getValue("adminRepositoryUrl"))
          log.info(s"Initialize admin repository")
          val adminRepository =
            ClientAdminRepository(adminRepositoryUri, new File("admin")).getOrElse {
              sys.error("Admin repository initialize error")
            }

          val clientDistributionUrl = new URL(arguments.getValue("clientDistributionUrl"))
          val clientDistribution = new ClientDistributionDirectoryClient(clientDistributionUrl)

          val versions = updateClient.getDesiredVersions(adminRepository, clientDistribution).getOrElse {
            sys.error("Can't get desired versions")
          }
          log.info("Desired versions:")
          versions.foreach { case (serviceName, version) => log.info(s"  ${serviceName} ${version}") }

        case "setDesiredVersions" =>
          val updateClient = new UpdateClient()
          val adminRepositoryUri = new URI(arguments.getValue("adminRepositoryUrl"))
          log.info(s"Initialize admin repository")
          val adminRepository =
            ClientAdminRepository(adminRepositoryUri, new File("admin")).getOrElse {
              sys.error("Admin repository initialize error")
            }

          val clientDistributionUrl = new URL(arguments.getValue("clientDistributionUrl"))
          val clientDistribution = new ClientDistributionDirectoryClient(clientDistributionUrl)

          var servicesVersions = Map.empty[ServiceName, Option[BuildVersion]]
          val servicesRecords: Seq[String] = arguments.getValue("services").split(",")
          for (record <- servicesRecords) {
            val fields = record.split(":")
            if (fields.size == 1) {
              servicesVersions += (fields(0) -> None)
            } else if (fields.size == 2) {
              servicesVersions += (fields(0) -> Some(BuildVersion.parse(fields(1))))
            } else {
              sys.error(s"Invalid service record ${record}")
            }
          }

          log.info(s"Set desired versions ${servicesVersions}")
          if (!updateClient.setDesiredVersions(adminRepository, clientDistribution, servicesVersions)) {
            sys.error("Desired versions assignment error")
          }

        case command =>
          sys.error(s"Invalid command ${command}\n${usage()}")
      }
    })
}