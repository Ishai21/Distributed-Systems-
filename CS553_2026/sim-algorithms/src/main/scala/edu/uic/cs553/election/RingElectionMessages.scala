package edu.uic.cs553.election

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

sealed trait RingElectionMsg derives CanEqual

final case class ElectionMsg(senderId: Int, randomValue: Double, round: Int) extends RingElectionMsg
final case class LeaderMsg(leaderId: Int, leaderValue: Double) extends RingElectionMsg
final case class AckLeader(from: Int) extends RingElectionMsg

object RingElectionMessages:
  given Encoder[ElectionMsg] = deriveEncoder
  given Decoder[ElectionMsg] = deriveDecoder
  given Encoder[LeaderMsg] = deriveEncoder
  given Decoder[LeaderMsg] = deriveDecoder
  given Encoder[AckLeader] = deriveEncoder
  given Decoder[AckLeader] = deriveDecoder

  given Encoder[RingElectionMsg] = Encoder.instance {
    case ElectionMsg(senderId, randomValue, round) =>
      Json.obj(
        "kind" -> Json.fromString("ElectionMsg"),
        "senderId" -> Json.fromInt(senderId),
        "randomValue" -> Json.fromDoubleOrNull(randomValue),
        "round" -> Json.fromInt(round)
      )
    case LeaderMsg(leaderId, leaderValue) =>
      Json.obj(
        "kind" -> Json.fromString("LeaderMsg"),
        "leaderId" -> Json.fromInt(leaderId),
        "leaderValue" -> Json.fromDoubleOrNull(leaderValue)
      )
    case AckLeader(from) =>
      Json.obj(
        "kind" -> Json.fromString("AckLeader"),
        "from" -> Json.fromInt(from)
      )
  }

  given Decoder[RingElectionMsg] = Decoder.instance { cursor =>
    cursor.get[String]("kind").flatMap {
      case "ElectionMsg" => cursor.as[ElectionMsg]
      case "LeaderMsg" => cursor.as[LeaderMsg]
      case "AckLeader" => cursor.as[AckLeader]
      case other => Left(io.circe.DecodingFailure(s"Unknown ring election message kind: $other", cursor.history))
    }
  }
