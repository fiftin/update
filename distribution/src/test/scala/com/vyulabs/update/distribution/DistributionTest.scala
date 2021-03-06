package com.vyulabs.update.distribution

import java.io.File
import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Paths}

import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.FileIO

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 14.01.16.
  * Copyright FanDate, Inc.
  */
class DistributionTest extends FlatSpecLike with ScalatestRouteTest with Matchers with Directives {

  behavior of "Distribution"

  val route: Route =
    extractRequestContext { ctx =>
      get {
        pathPrefix("download" / ".*".r) { path =>
          val file = File.createTempFile("aaa", "txt")
          file.deleteOnExit()
          Files.write(file.toPath, "qwe123".getBytes)
          getFromFile(s"${file.getPath}")
        }
      } ~
      post {
        path("upload") {
          implicit val materializer = ctx.materializer

          mapRouteResult {
            case r =>
              r
          } {
            fileUpload("instances-state") {
              case (fileInfo, byteSource) =>
                val sink = FileIO.toPath(Paths.get(s"/tmp/${fileInfo.fileName}"))
                val future = byteSource.runWith(sink)
                onSuccess(future) { _ =>
                  complete("Success")
                }
            }
          }
        }
      } ~
      head {
        path("update") {
          complete(StatusCodes.OK)
        } ~
        path("ping") {
          complete(StatusCodes.OK)
        }
      }
    }

  it should "download files" in {
    Get("/download/aaa/bbb") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "qwe123"
    }
  }

  it should "upload files" in {
    val multipartForm =
      Multipart.FormData(Multipart.FormData.BodyPart.Strict(
        "instances-state",
        HttpEntity(ContentTypes.`application/octet-stream`, "2,3,5\n7,11,13,17,23\n29,31,37\n".getBytes),
        Map("filename" -> "primes.csv")))

    Post("/upload", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Success"
    }

    Head("/ping") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
}
