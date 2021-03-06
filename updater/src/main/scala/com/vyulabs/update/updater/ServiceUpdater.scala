package com.vyulabs.update.updater

import com.vyulabs.update.common.Common.InstanceId
import com.vyulabs.update.common.{Common, ServiceInstanceName}
import com.vyulabs.update.config.InstallConfig
import com.vyulabs.update.utils.UpdateUtils
import com.vyulabs.update.distribution.client.ClientDistributionDirectoryClient
import com.vyulabs.update.updater.uploaders.{FaultUploader, LogUploader}
import com.vyulabs.update.version.BuildVersion
import org.slf4j.Logger

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 16.01.19.
  * Copyright FanDate, Inc.
  */
class ServiceUpdater(instanceId: InstanceId,
                     val serviceInstanceName: ServiceInstanceName,
                     state: ServiceStateController,
                     clientDirectory: ClientDistributionDirectoryClient)
                    (implicit log: Logger) {
  private var serviceRunner = Option.empty[ServiceRunner]

  private val faultUploader = new FaultUploader(state.faultsDirectory, clientDirectory)

  faultUploader.start()

  def close(): Unit = {
    for (serviceRunner <- serviceRunner) {
      serviceRunner.stopService()
      this.serviceRunner = None
    }
    faultUploader.close()
  }

  def needUpdate(desiredVersion: Option[BuildVersion]): Option[BuildVersion] = {
    desiredVersion match {
      case Some(newVersion) if (state.getVersion.isEmpty) =>
        state.info(s"Service is not installed")
        Some(newVersion)
      case Some(newVersion) if (!state.getVersion.contains(newVersion)) =>
        state.info(s"Is obsolete. Current version ${state.getVersion()} desired version ${newVersion}")
        Some(newVersion)
      case Some(_) =>
        state.info(s"Up to date")
        None
      case None =>
        state.error(s"No desired version for service")
        None
    }
  }

  def update(newVersion: BuildVersion): Boolean = {
    if (!state.serviceDirectory.exists() && !state.serviceDirectory.mkdir()) {
      state.error(s"Can't make directory ${state.serviceDirectory}")
      return false
    }

    state.setUpdateToVersion(newVersion)

    state.info(s"Download version ${newVersion}")
    if (state.newServiceDirectory.exists() && !UpdateUtils.deleteFileRecursively(state.newServiceDirectory)) {
      state.error(s"Can't remove directory ${state.newServiceDirectory}")
      return false
    }
    if (!state.newServiceDirectory.mkdir()) {
      state.error(s"Can't make directory ${state.newServiceDirectory}")
      return false
    }
    if (!clientDirectory.downloadVersion(serviceInstanceName.serviceName, newVersion, state.newServiceDirectory)) {
      state.error(s"Can't download ${serviceInstanceName.serviceName} version ${newVersion}")
      return false
    }

    state.info(s"Install service")
    var args = Map.empty[String, String]
    args += ("profile" -> serviceInstanceName.serviceProfile)
    args += ("version" -> newVersion.original().toString)

    val installConfig = InstallConfig(state.newServiceDirectory).getOrElse {
      state.error(s"No install config in the build directory")
      return false
    }

    for (command <- installConfig.InstallCommands) {
      if (!UpdateUtils.runProcess(command, args, state.newServiceDirectory, true)) {
        state.error(s"Install error")
        return false
      }
    }

    state.info(s"Update to version ${newVersion}")
    if (state.currentServiceDirectory.exists()) {
      for (serviceRunner <- serviceRunner) {
        state.info(s"Stop old version ${state.getVersion()}")
        if (serviceRunner.stopService()) {
          state.serviceStopped()
        } else {
          state.error(s"Can't stop service")
        }
        serviceRunner.saveLogs(false)
        this.serviceRunner = None
      }

      if (!UpdateUtils.deleteFileRecursively(state.currentServiceDirectory)) {
        state.error(s"Can't delete ${state.currentServiceDirectory}")
        return false
      }
    }

    if (!state.newServiceDirectory.renameTo(state.currentServiceDirectory)) {
      state.error(s"Can't rename ${state.newServiceDirectory} to ${state.currentServiceDirectory}")
      return false
    }

    for (command <- installConfig.PostInstallCommands) {
      if (!UpdateUtils.runProcess(command, args, state.currentServiceDirectory, true)) {
        state.error(s"Install error")
        return false
      }
    }

    UpdateUtils.writeServiceVersion(state.currentServiceDirectory, newVersion)

    state.setVersion(newVersion)

    true
  }

  def isExecuted(): Boolean = {
    serviceRunner.isDefined
  }

  def execute(): Boolean = {
    if (serviceRunner.isEmpty) {
      val newVersion = state.getVersion().getOrElse {
        state.error("Can't start service because it is not installed")
        return false
      }

      val installConfig = InstallConfig(state.currentServiceDirectory).getOrElse {
        state.error(s"No install config in the build directory")
        return false
      }

      for (runService <- installConfig.RunService) {
        state.info(s"Start service of version ${newVersion}")

        var args = Map.empty[String, String]
        args += ("profile" -> serviceInstanceName.serviceProfile)
        args += ("version" -> newVersion.original().toString)

        val runner = new ServiceRunner(instanceId, serviceInstanceName, state, clientDirectory, faultUploader)
        if (!runner.startService(runService, args, state.currentServiceDirectory)) {
          state.error(s"Can't start service")
          return false
        }
        serviceRunner = Some(runner)
      }

      state.serviceStarted()
    } else {
      state.error(s"Service is already started")
    }

    true
  }
}
