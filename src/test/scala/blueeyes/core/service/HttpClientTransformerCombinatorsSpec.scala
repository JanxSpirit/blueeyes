package blueeyes.core.service

import org.spex.Specification
import blueeyes.core.http._
import blueeyes.core.http.HttpHeaders._
import java.net.InetAddress
import blueeyes.concurrent.{FutureDeliveryStrategySequential, Future, FutureImplicits}

class HttpClientTransformerCombinatorsSpec extends Specification with HttpClientTransformerCombinators with HttpClientTransformerImplicits with FutureImplicits with FutureDeliveryStrategySequential{
  private val initialRequest = HttpRequest[String](HttpMethods.GET, "")
  private val responseFuture = Future[String]("")
  private val mockClient = new HttpClient[String]{
    var request: Option[HttpRequest[String]] = None

    def apply(r: HttpRequest[String]) = {
      request = Some(r)
      Future[HttpResponse[String]](HttpResponse[String]())
    }
  }
  
  "Join operator (~)" should {
    "return a future of a tuple" in {
      val response = mockClient {
        path$("/foo"){
          get$ { response: HttpResponse[String] =>
            1
          } ~
          get$ { response: HttpResponse[String] =>
            2
          }
        }
      }
      
      response.value must eventually (beSome((1, 2)))
    }
  }

  "sets protocol, host, port and path to request" in{
    val h = protocol$("https"){
      host$("localhost"){
        port$(8080){
          path$("/foo"){
            path$[String, String]("/bar"){
              get$[String, String]{ clientHandler }
            }
          }
        }
      }
    }

    h(mockClient)
    
    mockClient.request.get mustEqual(HttpRequest[String](HttpMethods.GET, "https://localhost:8080/foo/bar"))
  }

  "sets parameters request" in{
    val h = parameters$('foo -> "bar"){
      get$[String, String]{ clientHandler }
    }

    h(mockClient)

    mockClient.request.get mustEqual(initialRequest.copy(parameters = Map[Symbol, String]('foo -> "bar")))
  }
  "sets headers request" in{
    val h = headers$("content-length" -> "1"){
      get$[String, String]{ clientHandler }
    }

    h(mockClient)

    mockClient.request.get mustEqual(initialRequest.copy(headers = Map[String, String]("content-length" -> "1")))
  }
  "sets remote host header request" in{
    val h = remoteHost$(InetAddress.getLocalHost){
      get$[String, String]{ clientHandler }
    }

    h(mockClient)

    mockClient.request.get mustEqual(initialRequest.copy(headers = Map[String, String]("X-Forwarded-For" -> InetAddress.getLocalHost.getHostAddress()), 
      remoteHost = Some(InetAddress.getLocalHost)))
  }
  "sets http version" in{
    val h = version$(HttpVersions.`HTTP/1.0`){
      get$[String, String]{ clientHandler }
    }

    h(mockClient)

    mockClient.request.get mustEqual(initialRequest.copy(version = HttpVersions.`HTTP/1.0`))
  }
  "HttpClientTransformerImplicits ~: creates composite transformer" in{

    val h = get$[String, String]{ response => Future[String]("foo")} ~ get$[String, String]{ response => Future[String]("bar")}

    h(mockClient).value must beSome(("foo", "bar"))
  }

  private def clientHandler = (response: HttpResponse[String]) => responseFuture
}
