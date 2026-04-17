package com.habittracker.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.circe.Json
import scala.jdk.CollectionConverters._


final class DocsRoutes {

  // ---------------------------------------------------------------------------
  // Cached resources — loaded eagerly to surface packaging errors at startup
  // ---------------------------------------------------------------------------

  private val specYamlBytes: Array[Byte] = {
    val stream = getClass.getClassLoader.getResourceAsStream("openapi/openapi.yaml")
    if (stream == null) throw new IllegalStateException("openapi/openapi.yaml not found on classpath")
    try stream.readAllBytes()
    finally stream.close()
  }

  private val specJsonString: String = {
    import org.yaml.snakeyaml.Yaml
    val yaml   = new Yaml()
    val parsed = yaml.load[Any](new String(specYamlBytes, "UTF-8"))
    snakeYamlToCirce(parsed).noSpaces
  }

  private val indexHtml: String = {
    val stream = getClass.getClassLoader.getResourceAsStream("openapi/index.html")
    if (stream == null) throw new IllegalStateException("openapi/index.html not found on classpath")
    try new String(stream.readAllBytes(), "UTF-8")
    finally stream.close()
  }

  // ---------------------------------------------------------------------------
  // SnakeYAML → Circe JSON conversion
  // ---------------------------------------------------------------------------

  private def snakeYamlToCirce(obj: Any): Json = obj match {
    case null                        => Json.Null
    case m: java.util.Map[_, _]      =>
      Json.obj(m.asScala.map { case (k, v) => k.toString -> snakeYamlToCirce(v) }.toSeq: _*)
    case l: java.util.List[_]        =>
      Json.arr(l.asScala.map(snakeYamlToCirce).toSeq: _*)
    case s: String                   => Json.fromString(s)
    case n: java.lang.Integer        => Json.fromLong(n.longValue())
    case n: java.lang.Long           => Json.fromLong(n.longValue())
    case n: java.lang.Number         => Json.fromDoubleOrNull(n.doubleValue())
    case b: java.lang.Boolean        => Json.fromBoolean(b)
    case other                       => Json.fromString(other.toString)
  }

  // ---------------------------------------------------------------------------
  // Content-Types
  // ---------------------------------------------------------------------------

  private val yamlContentType: ContentType =
    ContentType(
      MediaType.customWithFixedCharset("application", "x-yaml", HttpCharsets.`UTF-8`)
    )

  // ---------------------------------------------------------------------------
  // WebJar version constant — must match build.gradle
  // ---------------------------------------------------------------------------

  private val swaggerUiVersion = "5.17.14"

  // ---------------------------------------------------------------------------
  // Route tree
  // ---------------------------------------------------------------------------

  val route: Route =
    pathPrefix("docs") {
      concat(
        // GET /docs — Swagger UI index page
        pathEndOrSingleSlash {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, indexHtml))
          }
        },
        // GET /docs/openapi.yaml
        path("openapi.yaml") {
          get {
            complete(HttpEntity(yamlContentType, specYamlBytes))
          }
        },
        // GET /docs/openapi.json
        path("openapi.json") {
          get {
            complete(HttpEntity(ContentTypes.`application/json`, specJsonString))
          }
        },
        // GET /docs/ui/{file} — served from swagger-ui WebJar
        pathPrefix("ui") {
          path(Remaining) { file =>
            get {
              getFromResource(
                s"META-INF/resources/webjars/swagger-ui/$swaggerUiVersion/$file"
              )
            }
          }
        }
      )
    }
}
