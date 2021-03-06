package com.vyulabs.update.distribution

import java.io.File

import com.vyulabs.libs.git.{GitRepository}
import com.vyulabs.update.common.Common.ServiceName
import com.vyulabs.update.version.BuildVersion
import org.eclipse.jgit.transport.RefSpec
import org.slf4j.Logger

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 23.01.20.
  * Copyright FanDate, Inc.
  */
class AdminRepository(repository: GitRepository)(implicit log: Logger) {
  private val desiredVersionsFile = new File(repository.getDirectory(), "desired-versions.json")

  def getDesiredVersionsFile(): File = {
    desiredVersionsFile
  }

  def tagServices(serviceNames: Seq[ServiceName]): Boolean = {
    serviceNames.foreach(serviceName =>
      if (!repository.setTag(serviceName, None, true)) {
        return false
      })
    repository.push(serviceNames.map(new RefSpec(_)), false, true)
  }

  def addFileToCommit(file: File): Boolean = {
    repository.add(file)
  }

  def removeFile(file: File): Boolean = {
    repository.remove(file)
  }

  def pull(): Boolean = {
    repository.pull()
  }
}

object AdminRepository {
  def makeStartOfSettingDesiredVersionsMessage(versions: Map[ServiceName, Option[BuildVersion]]): String = {
    if (versions.size == 1) {
      s"Set desired version " + makeDesiredVersionsStr(versions)
    } else {
      s"Set desired versions " + makeDesiredVersionsStr(versions)
    }
  }

  def makeEndOfSettingDesiredVersionsMessage(completed: Boolean, versions: Map[ServiceName, Option[BuildVersion]]): String = {
    if (completed) {
      if (versions.size == 1) {
        s"Desired version " + makeDesiredVersionsStr(versions) + " is successfully assigned"
      } else {
        s"Desired versions " + makeDesiredVersionsStr(versions) + " are successfully assigned"
      }
    } else {
      "Desired versions assign is failed"
    }
  }

  private def makeDesiredVersionsStr(versions: Map[ServiceName, Option[BuildVersion]]): String = {
    versions.foldLeft("") {
      case (versions, record) =>
        val str = record match {
          case (service, version) =>
            service + "->" + version.getOrElse("-")
        }
        versions + (if (versions.isEmpty) str else ", " + str)
    }
  }
}