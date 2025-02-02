// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.server.frontends.http

import akka.{Done, NotUsed}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.{A4Options, TranslateAlloyToKodkod}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import viper.server.ViperConfig
import viper.server.core.{AstConstructionFailureException, VerificationExecutionContext, ViperBackendConfig, ViperCache, ViperCoreServer}
import viper.server.frontends.http.jsonWriters.ViperIDEProtocol.{AlloyGenerationRequestComplete, AlloyGenerationRequestReject, CacheFlushAccept, CacheFlushReject, JobDiscardAccept, JobDiscardReject, ServerStopConfirmed, VerificationRequestAccept, VerificationRequestReject}
import viper.server.utility.Helpers.{getArgListFromArgString, validateViperFile}
import viper.server.vsi.Requests.CacheResetRequest
import viper.server.vsi._
import viper.silver.logger.ViperLogger
import viper.silver.reporter.Message

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ViperHttpServer(config: ViperConfig)(executor: VerificationExecutionContext)
  extends ViperCoreServer(config)(executor) with VerificationServerHttp {

  override def start(): Future[Done] = {
    _logger = ViperLogger("ViperServerLogger", config.getLogFileWithGuarantee, config.logLevel())
    println(s"Writing [level:${config.logLevel()}] logs into ${if (!config.logFile.isSupplied) "(default) " else ""}journal: ${logger.file.get}")

    ViperCache.initialize(logger.get, config.backendSpecificCache(), config.cacheFile.toOption)

    port = config.port.toOption.getOrElse(0) // 0 causes HTTP server to automatically select a free port
    super.start(config.maximumActiveJobs()).map({ _ =>
      println(s"ViperServer online at http://localhost:$port")
      Done
    })(executor)
  }

  def setRoutes(): Route = {
    addRoute(routes(), AdditionalViperServerRoute())
  }

  override def serverStopConfirmation(interrupt: Try[List[String]]): ToResponseMarshallable = {
    interrupt match {
      case Success(_) =>
        println("shutting down...")
        ServerStopConfirmed("shutting down...")
      case Failure(err_msg) =>
        logger.get.error(s"Interrupting one of the verification threads timed out: $err_msg")
        println("forcibly shutting down...")
        ServerStopConfirmed("forcibly shutting down...")
    }
  }

  override def onVerifyPost(vr: Requests.VerificationRequest): ToResponseMarshallable = {
    val arg_list = getArgListFromArgString(vr.arg)
    val file: String = arg_list.last
    val arg_list_partial: List[String] = arg_list.dropRight(1)

    if (!validateViperFile(file)) {
      return VerificationRequestReject("File not found")
    }

    val ast_id = requestAst(arg_list)

    val backend = try {
      ViperBackendConfig(arg_list_partial)
    } catch {
      case _: IllegalArgumentException =>
        logger.get.error(s"Invalid arguments: ${vr.arg} " +
          s"You need to specify the verification backend, e.g., `silicon [args]`")
        return VerificationRequestReject("Invalid arguments for backend.")
    }

    val ver_id = verify(file, ast_id, backend)

    VerificationRequestAccept(ast_id, ver_id)
  }

  override def unpackMessages(s: Source[Envelope, NotUsed]): ToResponseMarshallable = {
    import viper.server.frontends.http.jsonWriters.ViperIDEProtocol._
    val src_message: Source[Message, NotUsed] = s.map(e => unpack(e))
    src_message
  }

  override def verificationRequestRejection(jid: Int, e: Throwable): ToResponseMarshallable = {
    e match {
      case JobNotFoundException =>
        logger.get.error(s"The verification job #$jid does not exist.")
        VerificationRequestReject(s"The verification job #$jid does not exist.")
      case AstConstructionFailureException =>
        logger.get.error(s"The verification job #$jid could not be created since the AST is inconsistent.")
        VerificationRequestReject(s"The verification job #$jid could not be created since the AST is inconsistent.")
      case _ =>
        logger.get.error(s"The verification job #$jid resulted in a terrible error: $e")
        VerificationRequestReject(s"The verification job #$jid resulted in a terrible error: $e")
    }
  }

  override def discardJobConfirmation(jid: Int, msg: String): ToResponseMarshallable = {
    _logger.get.info(s"The verification job #$jid was successfully stopped.")
    JobDiscardAccept(msg)
  }

  override def discardJobRejection(jid: Int): ToResponseMarshallable = {
    _logger.get.error(s"The verification job #$jid does not exist.")
    JobDiscardReject(s"The verification job #$jid does not exist.")
  }

  def AdditionalViperServerRoute(): Route = path("cache" /  "flush") {
    /**
      * Send GET request to "/cache/flush".
      *
      * This will invalidate the entire cache.
      *
      *  Use case:
      *  - Client decided to re-verify several files from scratch.
      */
    get {
      flushCache()
      complete( CacheFlushAccept(s"The cache has been flushed successfully.") )
    }~ path("cache" /  "flush") {
      /**
        * Send POST request to "/cache/flush".
        *
        * This will invalidate the cache for the tool and file specified.
        *
        *  Use case:
        *  - Client decided to re-verify the entire file from scratch.
        */
      post {
        entity(as[CacheResetRequest]) {
          r =>
            ViperCache.forgetFile(r.backend, r.file) match {
              case Some(_) =>
                _logger.get.info(s"The cache for tool (${r.backend}) for file (${r.file}) has been flushed successfully.")
                complete( CacheFlushAccept(s"The cache for tool (${r.backend}) for file (${r.file}) has been flushed successfully.") )
              case None =>
                _logger.get.error(s"The cache does not exist for tool (${r.backend}) for file (${r.file}).")
                complete( CacheFlushReject(s"The cache does not exist for tool (${r.backend}) for file (${r.file}).") )
            }
        }
      }
    } ~ path("alloy") {
      /**
        * Send POST request to "/alloy".
        *
        * This will generate an instance of the given model.
        */
      post {
        object AlloyRequest extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport with DefaultJsonProtocol {
          case class AlloyGenerationRequest(arg: String, solver: String)
          implicit val generateStuff: RootJsonFormat[AlloyRequest.AlloyGenerationRequest] = jsonFormat2(AlloyGenerationRequest.apply)
        }

        entity(as[AlloyRequest.AlloyGenerationRequest]) { r =>
          try {
            val reporter: A4Reporter = new A4Reporter()

            val world = CompUtil.parseEverything_fromString(reporter, r.arg)

            val options: A4Options = new A4Options()
            options.solver = A4Options.SatSolver.parse(r.solver)
            options.skolemDepth = 1
            options.noOverflow = true
            options.unrolls = -1

            val commands = world.getAllCommands
            if (commands.size() != 1) {
              _logger.get.error(s"Expected only one command, but got ${commands.size()}")
              complete( AlloyGenerationRequestReject(s"Expected only one command, but got ${commands.size()}") )
            }
            val command = commands.get(0)
            val solution = TranslateAlloyToKodkod.execute_command(reporter, world.getAllReachableSigs, command, options)
            if (solution.satisfiable()) {
              _logger.get.info("Model is satisfiable")
              complete( AlloyGenerationRequestComplete(solution) )
            } else {
              _logger.get.info(s"Model could not be satisfied.")
              complete( AlloyGenerationRequestReject(s"Model could not be satisfied.") )
            }
          } catch {
            case e: Throwable =>
              _logger.get.error(s"An exception occurred during model-generation:\n${e.toString}")
              complete( AlloyGenerationRequestReject(s"An exception occurred during model-generation:\n${e.toString}") )
          }
        }
      }
    }
  }
}
