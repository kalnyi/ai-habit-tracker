package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitCompletionCodecsSpec extends AnyWordSpec with Matchers {

  "LocalDate codec" should {

    "encode to YYYY-MM-DD string" in {
      val date = LocalDate.of(2026, 4, 17)
      val json = date.asJson
      json.noSpaces shouldBe "\"2026-04-17\""
    }

    "decode a valid YYYY-MM-DD string" in {
      val result = decode[LocalDate]("\"2026-04-17\"")
      result shouldBe Right(LocalDate.of(2026, 4, 17))
    }

    "round-trip through encode/decode" in {
      val date = LocalDate.of(2026, 4, 17)
      decode[LocalDate](date.asJson.noSpaces) shouldBe Right(date)
    }

    "return Left with a non-empty message on invalid input" in {
      val result = decode[LocalDate]("\"not-a-date\"")
      result.isLeft shouldBe true
      result.swap.toOption.get.getMessage should not be empty
    }
  }

  "CreateHabitCompletionRequest codec" should {

    "decode a request with both completedOn and note" in {
      val json = """{"completedOn":"2026-04-17","note":"Felt energised"}"""
      val result = decode[CreateHabitCompletionRequest](json)
      result shouldBe Right(
        CreateHabitCompletionRequest(
          completedOn = LocalDate.of(2026, 4, 17),
          note = Some("Felt energised"),
          completedAt = None
        )
      )
    }

    "decode a request without note (treated as None)" in {
      val json = """{"completedOn":"2026-04-17"}"""
      val result = decode[CreateHabitCompletionRequest](json)
      result shouldBe Right(
        CreateHabitCompletionRequest(
          completedOn = LocalDate.of(2026, 4, 17),
          note = None,
          completedAt = None
        )
      )
    }

    "fail to decode when completedOn is missing" in {
      val json = """{"note":"something"}"""
      decode[CreateHabitCompletionRequest](json).isLeft shouldBe true
    }

    "fail to decode when completedOn is not a valid date" in {
      val json = """{"completedOn":"not-a-date"}"""
      decode[CreateHabitCompletionRequest](json).isLeft shouldBe true
    }

    "round-trip through encode/decode" in {
      val req = CreateHabitCompletionRequest(LocalDate.of(2026, 4, 17), Some("note"), None)
      decode[CreateHabitCompletionRequest](req.asJson.noSpaces) shouldBe Right(req)
    }

    "round-trip without note" in {
      val req = CreateHabitCompletionRequest(LocalDate.of(2026, 4, 17), None, None)
      decode[CreateHabitCompletionRequest](req.asJson.noSpaces) shouldBe Right(req)
    }

    "decode a request with completedAt populated" in {
      val json = """{"completedOn":"2026-04-17","completedAt":"2026-04-17T09:30:00Z"}"""
      val result = decode[CreateHabitCompletionRequest](json)
      result shouldBe Right(
        CreateHabitCompletionRequest(
          completedOn = LocalDate.of(2026, 4, 17),
          note = None,
          completedAt = Some(Instant.parse("2026-04-17T09:30:00Z"))
        )
      )
    }

    "decode a request without completedAt (treated as None)" in {
      val json = """{"completedOn":"2026-04-17"}"""
      decode[CreateHabitCompletionRequest](json) shouldBe Right(
        CreateHabitCompletionRequest(
          completedOn = LocalDate.of(2026, 4, 17),
          note = None,
          completedAt = None
        )
      )
    }
  }

  "HabitCompletionResponse codec" should {

    "round-trip through encode/decode with note" in {
      val resp = HabitCompletionResponse(
        id = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff"),
        habitId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
        completedOn = LocalDate.of(2026, 4, 17),
        note = Some("Felt energised"),
        createdAt = Instant.parse("2026-04-17T10:30:00Z"),
        completedAt = None
      )
      decode[HabitCompletionResponse](resp.asJson.noSpaces) shouldBe Right(resp)
    }

    "round-trip through encode/decode without note" in {
      val resp = HabitCompletionResponse(
        id = UUID.randomUUID(),
        habitId = UUID.randomUUID(),
        completedOn = LocalDate.of(2026, 4, 17),
        note = None,
        createdAt = Instant.parse("2026-04-17T10:30:00Z"),
        completedAt = None
      )
      decode[HabitCompletionResponse](resp.asJson.noSpaces) shouldBe Right(resp)
    }

    "encode note as null when None" in {
      val resp = HabitCompletionResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 4, 17),
        None,
        Instant.parse("2026-04-17T10:30:00Z"),
        None
      )
      val json = resp.asJson
      json.hcursor.downField("note").focus.map(_.isNull) shouldBe Some(true)
    }
  }
}
