package blueeyes.core.service

import java.lang.reflect.{Method}
import java.util.concurrent.CountDownLatch

import blueeyes.core.http._
import blueeyes.util.CommandLineArguments
import net.lag.configgy.{Config, ConfigMap, Configgy}
import net.lag.logging.Logger
import blueeyes.util.RichThrowableImplicits._
import blueeyes.util.logging.LoggingHelper
import java.net.InetAddress
import blueeyes.concurrent.{FutureDeliveryStrategySequential, Future}

/** A trait that grabs services reflectively from the fields of the class it is
 * mixed into.
 */
trait HttpReflectiveServiceList[T] { self =>
  lazy val services: List[HttpService[T]] = {
    val c = self.getClass
    
    val allMethods: List[Method] = c.getDeclaredMethods.toList
    
    val serviceMethods: List[Method] = allMethods.reverse.filter { method =>
      classOf[HttpService[T]].isAssignableFrom(method.getReturnType) && method.getParameterTypes.length == 0
    }
    
    serviceMethods.map { method =>
      method.invoke(self).asInstanceOf[HttpService[T]]
    }
  }
}

/** An http server acts as a container for services. A server can be stopped
 * and started, and has a main function so it can be mixed into objects.
 */
trait HttpServer[T] extends HttpRequestHandler[T] with FutureDeliveryStrategySequential{ self =>

  private lazy val NotFound            = HttpResponse[T](HttpStatus(HttpStatusCodes.NotFound))
  private lazy val InternalServerError = HttpResponse[T](HttpStatus(HttpStatusCodes.InternalServerError))

  /** The root configuration. This is simply Configgy's root configuration 
   * object, so this should not be used until Configgy has been configured.
   */
  def rootConfig: Config = Configgy.config
  
  /** The list of services that this server is supposed to run.
   */
  def services: List[HttpService[T]]
  
  def isDefinedAt(r: HttpRequest[T]): Boolean = true
  
  def apply(r: HttpRequest[T]): Future[HttpResponse[T]] = {
    def convertErrorToResponse(th: Throwable): HttpResponse[T] = th match {
      case e: HttpException => HttpResponse[T](HttpStatus(e.failure, e.reason))
      case _ => {
        val reason = th.fullStackTrace
        log.error(th, "Error handling request")
        HttpResponse[T](HttpStatus(HttpStatusCodes.InternalServerError, if (reason.length > 3500) reason.substring(0, 3500) else reason))
      }
    }
    
    // The raw future may die due to error:
    val rawFuture = try {
      if (_handler.isDefinedAt(r)) _handler(r)
      else Future[HttpResponse[T]](NotFound)
    }
    catch {
      case why: Throwable =>
        // An error during invocation of the request handler, convert to
        // proper response:
        Future[HttpResponse[T]](convertErrorToResponse(why))
    }

    // Convert the raw future into one that cannot die:
    rawFuture.orElse { why =>
      why match {
        case Some(throwable) =>
          convertErrorToResponse(throwable)

        case None =>
          // Future was canceled for no cause.
          InternalServerError
      }
    }
  }
  
  /** Starts the server.
   */
  def start: Future[Unit] = {
    log.info("Starting server")
    
    _status = RunningStatus.Starting
    
    // Start all the services:
    descriptors.foreach { descriptor =>
      log.info("Starting service " + descriptor.service.toString)
      
      descriptor.startup().deliverTo { _ =>
        log.info("Successfully started service " + descriptor.service.toString)
      }.ifCanceled { why =>
        log.error("Failed to start service " + descriptor.service.toString + ": " + why)
      }
    }
    
    // As each handler becomes available, incorporate it into the master handler:
    val handlerFutures = descriptors.map(_.request)
    
    handlerFutures.foreach { future =>
      future.deliverTo { handler =>

        handlerLock.writeLock.lock()
        
        try {
          _handler = _handler.orElse(handler)
        }
        finally {
          handlerLock.writeLock.unlock()
        }
      }
    }
    
    // Combine all futures into a master one that will be delivered when and if
    // all futures are delivered:
    val unitFutures: List[Future[Unit]] = handlerFutures.map(_.toUnit)
    
    Future(unitFutures: _*).toUnit.deliverTo { _ =>
      log.info("Server started")
      
      _status = RunningStatus.Started
    }.ifCanceled { why =>
      _status = RunningStatus.Errored
      
      log.error("Unable to start server: " + why)
    }
  }
  
  /** Stops the server.
   */
  def stop: Future[Unit] = {
    log.info("Stopping server")
    
    _status = RunningStatus.Stopping
    
    // Turn off the request handler so we don't handle any more requests for any service:
    handlerLock.writeLock.lock()
    
    try {
      _handler = Map[HttpRequest[T], Future[HttpResponse[T]]]()
    }
    finally {
      handlerLock.writeLock.unlock()
    }
    
    val shutdownFutures = descriptors.map { descriptor =>
      log.info("Shutting down service " + descriptor.service.toString)
      
      descriptor.shutdown().deliverTo { _ =>
        log.info("Successfully shut down service " + descriptor.service.toString)
      }.ifCanceled { why =>
        log.info("Failed to shut down service " + descriptor.service.toString + ": " + why)
      }
    }
    
    Future(shutdownFutures: _*).toUnit.deliverTo { _ => 
      log.info("Server stopped")
      
      _status = RunningStatus.Stopped
    }.ifCanceled { e =>
      _status = RunningStatus.Errored
      
      log.info("Unable to stop server: " + e)
    }
  }
  
  /** Retrieves the server configuration, which is always located in the 
   * "server" block of the root configuration object.
   */
  lazy val config: ConfigMap = rootConfig.configMap("server")
  
  /** Retrieves the logger for the server, which is configured directly from
   * the server's "log" configuration block.
   */
  lazy val log: Logger = LoggingHelper.initializeLogging(config, "blueeyes.server")

  /** Retrieves the port the server should be running at, which defaults to
   * 8888.
   */
  lazy val port: Int    = config.getInt("port", 8888)

  /** Retrieves the ssl port the server should be running at, which defaults to
   * 8889.
   */
  lazy val sslPort: Int = config.getInt("sslPort", 8889)

  /** Retrieves the host the server should be running at.
   */
  lazy val host = config.getString("address").getOrElse(InetAddress.getLocalHost().getHostName())

  /** Retrieves if the ssl should be running, which defaults to
   * true.
   */
  lazy val sslEnable: Boolean = config.getBool("sslEnable", true)

  /** The status of the server.
   */
  def status: RunningStatus = _status
  
  /** A default main function, which accepts the configuration file from the
   * command line, with flag "--configFile".
   */
  def main(args: Array[String]) {
    val arguments = CommandLineArguments(args: _*)
    
    if (arguments.size == 0 || !arguments.parameters.get("help").isEmpty) {
      println("Usage: --configFile [config file]")
      
      System.exit(-1)
    }
    else {    
      Configgy.configure(arguments.parameters.get("configFile").getOrElse(error("Expected --configFile option")))
      
      start.deliverTo { _ =>
        Runtime.getRuntime.addShutdownHook { new Thread {
          override def start() {
            val doneSignal = new CountDownLatch(1)
            
            self.stop.deliverTo { _ => 
              doneSignal.countDown()
            }.ifCanceled { e =>
              doneSignal.countDown()
            }
            
            doneSignal.await()
          }
        }}
      }
    }
  }

  private var _status: RunningStatus = RunningStatus.Stopped
  
  private val handlerLock = new java.util.concurrent.locks.ReentrantReadWriteLock
  
  private var _handler: HttpRequestHandler[T] = new PartialFunction[HttpRequest[T], Future[HttpResponse[T]]] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = false
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[T]] = error("Function not defined here")
  }
  
  private lazy val descriptors: List[BoundStateDescriptor[T, _]] = services.map { service =>
    val config = rootConfig.configMap("services." + service.name + ".v" + service.version.majorVersion)

    val context = HttpServiceContext(config, service.name, service.version, host, port, sslPort)

    BoundStateDescriptor(context, service)
  }

  private case class BoundStateDescriptor[T, S](context: HttpServiceContext, service: HttpService[T]) {
    val descriptor: HttpServiceDescriptor[T, S] = service.descriptorFactory(context).asInstanceOf[HttpServiceDescriptor[T, S]]
    
    val state: Future[S] = new Future[S]
    
    def startup(): Future[S] = descriptor.startup().deliverTo { result =>
      state.deliver(result)
    }

    lazy val request: Future[HttpRequestHandler[T]] = state.map(state => descriptor.request(state))
    
    def shutdown(): Future[Unit] = state.flatMap(state => descriptor.shutdown(state))
  }
  
  /*
  private case class SafeRequestHandler(underlying: PartialFunction[HttpRequest[T], Future[HttpResponse[T]]]) extends PartialFunction[HttpRequest[T], Future[HttpResponse[T]]] {
    def 
  }
  */
}
