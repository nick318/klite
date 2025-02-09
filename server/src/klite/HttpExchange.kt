package klite

import com.sun.net.httpserver.HttpsExchange
import klite.StatusCode.Companion.OK
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import kotlin.LazyThreadSafetyMode.NONE
import kotlin.reflect.KClass

typealias OriginalHttpExchange = com.sun.net.httpserver.HttpExchange
typealias Headers = com.sun.net.httpserver.Headers

open class HttpExchange(
  private val original: OriginalHttpExchange,
  private val config: RouterConfig,
  private val sessionStore: SessionStore?
): AutoCloseable {
  var route: Route? = null
  val method = RequestMethod.valueOf(original.requestMethod)
  open val remoteAddress: String get() = original.remoteAddress.address.hostAddress
  open val host: String get() = header("Host")!!
  open val isSecure: Boolean get() = original is HttpsExchange

  val path: String get() = original.requestURI.path
  lateinit var pathParams: MatchGroupCollection internal set
  fun path(param: String) = pathParams[param]?.value ?: error("Param $param missing in path")

  val query: String get() = original.requestURI.query?.let { "?$it" } ?: ""
  val queryParams: Params by lazy { original.requestURI.queryParams }
  fun query(param: String) = queryParams[param]

  val fullUrl get() = fullUrl(original.requestURI.toString())
  fun fullUrl(suffix: String): URI = URI("http${if (isSecure) "s" else ""}://$host$suffix")

  inline fun <reified T: Any> body(): T = body(T::class)
  fun <T: Any> body(type: KClass<T>): T {
    val contentType = requestType ?: "text/plain"
    return config.parsers.find { contentType.startsWith(it.contentType) }?.parse(requestStream, type) ?:
      throw UnsupportedMediaTypeException(requestType)
  }

  val bodyParams: Params by lazy { body() }
  fun body(param: String) = bodyParams[param]

  val attrs: MutableMap<Any, Any?> = mutableMapOf()
  @Suppress("UNCHECKED_CAST")
  fun <T> attr(key: Any): T = attrs[key] as T
  fun attr(key: Any, value: Any?) = attrs.put(key, value)

  val headers: Headers get() = original.requestHeaders
  fun header(key: String): String? = headers.getFirst(key)

  val responseHeaders: Headers get() = original.responseHeaders
  fun header(key: String, value: String) { responseHeaders[key] = value }

  val cookies: Params by lazy(NONE) { decodeCookies(header("Cookie")) }
  fun cookie(key: String) = cookies[key]

  fun cookie(key: String, value: String, expires: Instant? = null) { this += Cookie(key, value, expires, secure = isSecure) }
  operator fun plusAssign(cookie: Cookie) = responseHeaders.add("Set-Cookie", cookie.toString())

  val session: Session by lazy(NONE) { sessionStore?.load(this) ?: error("No sessionStore defined") }

  val statusCode: Int get() = original.responseCode
  val isResponseStarted get() = statusCode >= 0

  val requestType get() = header("Content-Type")
  val requestStream: InputStream get() = original.requestBody

  val responseStream: OutputStream get() = original.responseBody
  var responseType: String?
    get() = responseHeaders["Content-Type"]?.firstOrNull()
    set(value) { value?.let { header("Content-Type", it) } }

  val accept get() = Accept(header("Accept"))

  fun render(code: StatusCode, body: Any?) {
    val accept = accept
    val renderer = config.renderers.find { accept(it) } ?:
      if (accept.isRelaxed || code != OK) config.renderers.first() else throw NotAcceptableException(accept.contentTypes)
    startResponse(code, if (body == null) -1 else 0, renderer.contentType)
    if (body != null) renderer.render(responseStream, body)
    // TODO: maybe still render null (vs Unit, when no rendering is needed)
  }

  private fun startResponse(code: StatusCode, length: Long, contentType: String?) {
    sessionStore?.save(this, session)
    responseType = contentType
    original.sendResponseHeaders(code.value, length)
  }

  fun send(code: StatusCode, body: ByteArray? = null, contentType: String? = null) {
    startResponse(code, body?.size?.toLong() ?: -1, contentType)
    body?.let { responseStream.write(it) }
  }

  fun send(code: StatusCode, body: String?, contentType: String? = null) =
    send(code, body?.toByteArray(), "$contentType; charset=UTF-8")

  private val onCompleteHandlers = mutableListOf<Runnable>()
  fun onComplete(handler: Runnable) { onCompleteHandlers += handler }

  override fun close() {
    original.close()
    onCompleteHandlers.forEach { it.run() }
  }

  override fun toString() = "$method ${original.requestURI}"
}

class XForwardedHttpExchange(original: OriginalHttpExchange, config: RouterConfig, sessionStore: SessionStore?):
  HttpExchange(original, config, sessionStore) {
  override val remoteAddress get() = header("X-Forwarded-For") ?: super.remoteAddress
  override val host get() = header("X-Forwarded-Host") ?: super.host
  override val isSecure get() = header("X-Forwarded-Proto") == "https"
}
