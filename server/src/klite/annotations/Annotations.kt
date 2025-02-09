package klite.annotations

import klite.*
import java.lang.reflect.InvocationTargetException
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter.Kind.INSTANCE
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

@Target(CLASS) annotation class Path(val value: String)
@Target(FUNCTION) annotation class GET(val value: String = "")
@Target(FUNCTION) annotation class POST(val value: String = "")
@Target(FUNCTION) annotation class PUT(val value: String = "")
@Target(FUNCTION) annotation class DELETE(val value: String = "")
@Target(FUNCTION) annotation class OPTIONS(val value: String = "")

@Target(VALUE_PARAMETER) annotation class PathParam
@Target(VALUE_PARAMETER) annotation class QueryParam
@Target(VALUE_PARAMETER) annotation class BodyParam
@Target(VALUE_PARAMETER) annotation class HeaderParam
@Target(VALUE_PARAMETER) annotation class CookieParam
@Target(VALUE_PARAMETER) annotation class AttrParam

fun Router.annotated(routes: Any) {
  val cls = routes::class
  val path = cls.annotation<Path>()?.value ?: ""
  cls.functions.forEach { f ->
    val a = f.annotations.firstOrNull() ?: return@forEach
    val method = RequestMethod.valueOf(a.annotationClass.simpleName!!)
    val subPath = a.annotationClass.members.first().call(a) as String
    add(Route(method, pathParamRegexer.from(path + subPath), toHandler(routes, f), cls.annotations + f.annotations))
  }
}

inline fun <reified T: Any> Router.annotated() = annotated(require<T>())

internal fun toHandler(instance: Any, f: KFunction<*>): Handler {
  val params = f.parameters
  return {
    try {
      val args = Array(params.size) { i ->
        val p = params[i]
        if (p.kind == INSTANCE) instance
        else if (p.type.classifier == HttpExchange::class) this
        else {
          val name = p.name!!
          val type = p.type.classifier as KClass<*>
          fun String.toType() = Converter.fromString(this, type)
          when (p.annotations.firstOrNull()) {
            is PathParam -> path(name).toType()
            is QueryParam -> query(name)?.toType()
            is BodyParam -> body(name)?.toType()
            is HeaderParam -> header(name)?.toType()
            is CookieParam -> cookie(name)?.toType()
            is AttrParam -> attr(name)
            else -> body(type)
          }
        }
      }
      f.callSuspend(*args)
    } catch (e: InvocationTargetException) {
      throw e.targetException
    }
  }
}

inline fun <reified T: Annotation> KClass<*>.annotation(): T? = java.getAnnotation(T::class.java)
inline fun <reified T: Annotation> KFunction<*>.annotation(): T? = javaMethod!!.getAnnotation(T::class.java)
