package blueeyes.core.service

import blueeyes.concurrent.Future
import blueeyes.core.http._

trait HttpClient[T] extends HttpRequestHandler[T] {
  def isDefinedAt(request: HttpRequest[T]): Boolean = true
  
  def apply(request: HttpRequest[T]): Future[HttpResponse[T]]

  def apply[R](f: HttpClient[T] => Future[R]) = f(this)
}

class HttpClientProxy[T](r: HttpRequestHandler[T]) {
  def apply(request: HttpRequest[T]): Future[HttpResponse[T]] = r.apply(request)
}