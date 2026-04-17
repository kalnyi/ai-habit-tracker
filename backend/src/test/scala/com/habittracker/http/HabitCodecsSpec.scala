package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitCodecsSpec extends AnyWordSpec with Matchers {

  "HabitResponse" should {

    "encode to JSON and back (round-trip)" in {
      val now = Instant.parse("2026-04-16T10:30:00Z")
      val id  = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")
      val response = HabitResponse(
        id = id,
        name = "Read 20 pages",
        description = Some("Non-fiction preferred"),
        frequency = "daily",
        createdAt = now,
        updatedAt = now
      )

      val json     = response.asJson
      val decoded  = decode[HabitResponse](json.noSpaces)

      decoded shouldBe Right(response)
    }

    "encode description as null when None" in {
      val now = Instant.now()
      val response = HabitResponse(
        id = UUID.randomUUID(),
        name = "Exercise",
        description = None,
        frequency = "weekly",
        createdAt = now,
        updatedAt = now
      )

      val json = response.asJson
      json.hcursor.downField("description").focus.map(_.isNull) shouldBe Some(true)
    }
  }

  "CreateHabitRequest" should {

    "decode a valid JSON body" in {
      val json = """{"name":"Read","description":"Non-fiction","frequency":"daily"}"""
      val result = decode[CreateHabitRequest](json)
      result shouldBe Right(CreateHabitRequest("Read", Some("Non-fiction"), "daily"))
    }

    "decode when description is absent (treated as None)" in {
      val json   = """{"name":"Exercise","frequency":"weekly"}"""
      val result = decode[CreateHabitRequest](json)
      result shouldBe Right(CreateHabitRequest("Exercise", None, "weekly"))
    }

    "fail to decode when name is missing" in {
      val json   = """{"description":"something","frequency":"daily"}"""
      val result = decode[CreateHabitRequest](json)
      result.isLeft shouldBe true
    }

    "fail to decode when frequency is missing" in {
      val json   = """{"name":"Read"}"""
      val result = decode[CreateHabitRequest](json)
      result.isLeft shouldBe true
    }
  }

  "UpdateHabitRequest" should {

    "decode a valid JSON body" in {
      val json = """{"name":"Updated","description":null,"frequency":"weekly"}"""
      val result = decode[UpdateHabitRequest](json)
      result shouldBe Right(UpdateHabitRequest("Updated", None, "weekly"))
    }

    "decode when description is absent" in {
      val json   = """{"name":"Run","frequency":"daily"}"""
      val result = decode[UpdateHabitRequest](json)
      result shouldBe Right(UpdateHabitRequest("Run", None, "daily"))
    }
  }

  "Frequency string codecs" should {

    "preserve 'daily' through a HabitResponse round-trip" in {
      val resp = HabitResponse(UUID.randomUUID(), "h", None, "daily", Instant.now(), Instant.now())
      val json = resp.asJson
      val back = decode[HabitResponse](json.noSpaces)
      back.map(_.frequency) shouldBe Right("daily")
    }

    "preserve 'weekly' through a HabitResponse round-trip" in {
      val resp = HabitResponse(UUID.randomUUID(), "h", None, "weekly", Instant.now(), Instant.now())
      val json = resp.asJson
      val back = decode[HabitResponse](json.noSpaces)
      back.map(_.frequency) shouldBe Right("weekly")
    }
  }
}
