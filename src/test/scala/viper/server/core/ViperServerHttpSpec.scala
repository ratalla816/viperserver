package viper.server.core

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

import java.io.File
import java.nio.file.Paths

import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model.{StatusCodes, _}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.TimeLimits
import org.scalatest.time.{Seconds, Span}
import viper.server.frontends.http.ViperHttpServer
import viper.server.ViperConfig
import viper.server.vsi.Requests._

import scala.concurrent.Await
import scala.concurrent.duration._

class ViperServerHttpSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with TimeLimits {

  import scala.language.postfixOps
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
  implicit val requestTimeput: RouteTestTimeout = RouteTestTimeout(10.second dilated)

  private val verificationContext: VerificationExecutionContext = new DefaultVerificationExecutionContext()
  private val viperServerHttp = {
    val config = new ViperConfig(IndexedSeq())
    new ViperHttpServer(config)(verificationContext)
    // note that the server is not yet started but is just initialized
    // the first test case will then actually start it
  }

  private val _routesUnderTest = viperServerHttp.routes()

  def printRequestResponsePair(req: String, res: String): Unit = {
    println(s">>> ViperServer test request `$req` response in the following response: $res")
  }

  // FIXME this does not work with SBT for some reason
  def getResourcePath(vpr_file: String): String = {
    val cross_platform_path = new File(vpr_file) getPath
    val resource = getClass.getResource(cross_platform_path)
    val fname = if (resource != null) {
      val file = Paths.get(resource.toURI)
      file.toString
    } else {
      // simulate absent file
      val temp_file = File.createTempFile("ViperServer_testing", ".vpr")
      val absent_fname = temp_file.getPath
      temp_file.delete()
      absent_fname
    }
    "\"" + fname + "\""
  }

  private val verifiableFile = "src/test/resources/viper/let.vpr"
  private val nonExistingFile = "2165e0fbd4b980436557b5a6f1a41f68.vpr"
  private val emptyFile = "src/test/resources/viper/empty.vpr"

  private val tool = "silicon"
  private val testSimpleViperCode_cmd = s"$tool --disableCaching ${verifiableFile}"
  private val testEmptyFile_cmd = s"$tool --disableCaching ${emptyFile}"
  private val testNonExistingFile_cmd = s"$tool --disableCaching ${nonExistingFile}"

  "ViperServer" should {
    "eventually start" in {
      failAfter(Span(10, Seconds)) {
        val started = viperServerHttp.start()
        // wait until server has been started:
        Await.result(started, Duration.Inf)
      }
    }

    s"start a verification process using `$tool` over a small Viper program" in {
      Post("/verify", VerificationRequest(testSimpleViperCode_cmd)) ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"POST, /verify, $testSimpleViperCode_cmd", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should not include ("""File not found""")
      }
    }

    "respond with the result for process #0" in {
      Get("/verify/0") ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"GET, /verify/0", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should include (s""""kind":"overall","status":"success","verifier":"$tool"""")
      }
    }

    s"start another verification process using `$tool` on an empty file" in {
      Post("/verify", VerificationRequest(testEmptyFile_cmd)) ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"POST, /verify, $testEmptyFile_cmd", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should not include ("""File not found""")
      }
    }

    "respond with the result for process #1" in {
      Get("/verify/1") ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"GET, /verify/1", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should include (s""""kind":"overall","status":"success","verifier":"$tool"""")
      }
    }

    s"start another verification process using `$tool` on an non-existent file" in {
      Post("/verify", VerificationRequest(testNonExistingFile_cmd)) ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"POST, /verify, $testEmptyFile_cmd", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should include (s"not found")
      }
    }

    "stop all running executions and terminate self" in {
      Get("/exit") ~> _routesUnderTest ~> check {
        //printRequestResponsePair(s"GET, /exit", responseAs[String])
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
      }
    }

    "should eventually stop" in {
      failAfter(Span(10, Seconds)) {
        Await.ready(viperServerHttp.stopped(), Duration.Inf)
        verificationContext.terminate()
      }
    }
  }
}
