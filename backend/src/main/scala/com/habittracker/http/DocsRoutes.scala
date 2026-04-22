package com.habittracker.http

import cats.effect.IO
import io.circe.Json
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`

import scala.jdk.CollectionConverters._

/** http4s routes for API documentation.
  *
  * Serves:
  *   GET /docs            — Swagger UI index page (HTML)
  *   GET /docs/openapi.yaml — raw OpenAPI spec (YAML)
  *   GET /docs/openapi.json — OpenAPI spec converted to JSON
  *   GET /docs/ui/{file}  — swagger-ui WebJar static assets
  */
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

  private val specYamlString: String = new String(specYamlBytes, "UTF-8")

  private val specJsonString: String = {
    import org.yaml.snakeyaml.Yaml
    val yaml   = new Yaml()
    val parsed = yaml.load[Any](specYamlString)
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
    case null                   => Json.Null
    case m: java.util.Map[_, _] =>
      Json.obj(m.asScala.map { case (k, v) => k.toString -> snakeYamlToCirce(v) }.toSeq: _*)
    case l: java.util.List[_]   =>
      Json.arr(l.asScala.map(snakeYamlToCirce).toSeq: _*)
    case s: String              => Json.fromString(s)
    case n: java.lang.Integer   => Json.fromLong(n.longValue())
    case n: java.lang.Long      => Json.fromLong(n.longValue())
    case n: java.lang.Number    => Json.fromDoubleOrNull(n.doubleValue())
    case b: java.lang.Boolean   => Json.fromBoolean(b)
    case other                  => Json.fromString(other.toString)
  }

  // ---------------------------------------------------------------------------
  // Media type constants
  // ---------------------------------------------------------------------------

  private val yamlMediaType: MediaType = MediaType.unsafeParse("application/x-yaml")

  // ---------------------------------------------------------------------------
  // WebJar version constant — must match build.gradle
  // ---------------------------------------------------------------------------

  private val swaggerUiVersion = "5.17.14"

  // ---------------------------------------------------------------------------
  // Route tree
  // ---------------------------------------------------------------------------

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "docs" =>
      Ok(indexHtml, `Content-Type`(MediaType.text.html, Charset.`UTF-8`))

    case GET -> Root / "docs" / "openapi.yaml" =>
      Ok(specYamlString, `Content-Type`(yamlMediaType))

    case GET -> Root / "docs" / "openapi.json" =>
      Ok(specJsonString, `Content-Type`(MediaType.application.json))

    case GET -> Root / "docs" / "ui" / file =>
      IO {
        Option(
          getClass.getClassLoader.getResourceAsStream(
            s"META-INF/resources/webjars/swagger-ui/$swaggerUiVersion/$file"
          )
        ).map { s => try s.readAllBytes() finally s.close() }
      }.flatMap {
        case Some(bytes) => Ok(bytes)
        case None        => NotFound()
      }
  }
}
