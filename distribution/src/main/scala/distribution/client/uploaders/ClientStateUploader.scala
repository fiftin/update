package distribution.client.uploaders

import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FutureDirectives
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.Config
import com.vyulabs.update.common.Common.{ClientName, InstanceId, UpdaterDirectory, UpdaterInstanceId, ProcessId}
import com.vyulabs.update.distribution.Distribution
import com.vyulabs.update.distribution.client.ClientDistributionDirectory
import com.vyulabs.update.distribution.developer.{DeveloperDistributionDirectoryClient, DeveloperDistributionWebPaths}
import com.vyulabs.update.state.{InstanceState, InstancesState}
import com.vyulabs.update.utils.UpdateUtils
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration.FiniteDuration

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 22.05.19.
  * Copyright FanDate, Inc.
  */
class ClientStateUploader(clientName: ClientName, dir: ClientDistributionDirectory, developerDirectoryUrl: URL)
                         (implicit system: ActorSystem, materializer: Materializer)
      extends Thread with DeveloperDistributionWebPaths with FutureDirectives { self =>
  private implicit val log = LoggerFactory.getLogger(this.getClass)

  private val developerDirectory = DeveloperDistributionDirectoryClient(developerDirectoryUrl)

  private var downloadingFiles = Set.empty[File]
  private var statesToUpload = Map.empty[UpdaterInstanceId, InstanceState]

  private val uploadInterval = 10000
  private var lastUploadTime = 0L
  private val expirationPeriod = FiniteDuration.apply(1, TimeUnit.MINUTES).toMillis

  private var stopping = false

  def receiveState(instanceId: InstanceId, updaterProcessId: ProcessId,
                   config: Config, distribution: Distribution): Route = {
    val instanceState = InstanceState(config)
    val file = dir.getInstanceStateFile(instanceId, updaterProcessId)
    self.synchronized {
      val updaterInstanceId = UpdaterInstanceId(instanceState.instanceId, instanceState.directory)
      statesToUpload += (updaterInstanceId -> instanceState)
      downloadingFiles += file
    }
    val promise = Promise[Unit]()
    import ExecutionContext.Implicits.global
    promise.future.onComplete { _ =>
      self.synchronized {
        UpdateUtils.maybeDeleteOldFiles(dir.getStatesDir(), System.currentTimeMillis() - expirationPeriod, downloadingFiles)
        downloadingFiles -= file
      }
    }
    val source = Source.single(
      ByteString(UpdateUtils.renderConfig(config, true).getBytes("utf8")))
    distribution.fileWriteWithLock(source, dir.getInstanceStateFile(instanceId, updaterProcessId), Some(promise))
  }

  def close(): Unit = {
    self.synchronized {
      stopping = true
      notify()
    }
    join()
  }

  override def run(): Unit = {
    log.info("State uploader started")
    try {
      while (true) {
        val pause = lastUploadTime + uploadInterval - System.currentTimeMillis()
        if (pause > 0) {
          self.synchronized {
            if (stopping) {
              return
            }
            wait(pause)
            if (stopping) {
              return
            }
          }
        }
        if (!stopping) {
          try {
            val states = self.synchronized {
              val states = statesToUpload
              statesToUpload = Map.empty
              states
            }
            if (!states.isEmpty) {
              log.debug("Upload instances state to developer distribution server")
              val instancesState = InstancesState(states)
              if (!developerDirectory.uploadInstancesState(clientName, instancesState)) {
                log.error("Can't upload instances state")
              }
            }
          } catch {
            case ex: Exception =>
              log.error("State uploader exception", ex)
          }
          lastUploadTime = System.currentTimeMillis()
        }
      }
    } catch {
      case ex: Exception =>
        log.error(s"State uploader thread is failed", ex)
    }
  }
}
