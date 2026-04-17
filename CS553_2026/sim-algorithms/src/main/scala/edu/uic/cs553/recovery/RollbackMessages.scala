package edu.uic.cs553.recovery

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

sealed trait RollbackMsg derives CanEqual

final case class Checkpoint(nodeId: Int, sequenceNo: Int, state: String) extends RollbackMsg
final case class RequestRollback(initiatorId: Int, targetCheckpointSeq: Int) extends RollbackMsg
final case class RollbackAck(nodeId: Int, rolledBackTo: Int) extends RollbackMsg
final case class LogSend(fromId: Int, toId: Int, msgSeqNo: Int, msgKind: String) extends RollbackMsg
final case class LogReceive(fromId: Int, toId: Int, msgSeqNo: Int) extends RollbackMsg

object RollbackMessages:
  given Encoder[Checkpoint] = deriveEncoder
  given Decoder[Checkpoint] = deriveDecoder
  given Encoder[RequestRollback] = deriveEncoder
  given Decoder[RequestRollback] = deriveDecoder
  given Encoder[RollbackAck] = deriveEncoder
  given Decoder[RollbackAck] = deriveDecoder
  given Encoder[LogSend] = deriveEncoder
  given Decoder[LogSend] = deriveDecoder
  given Encoder[LogReceive] = deriveEncoder
  given Decoder[LogReceive] = deriveDecoder

  given Encoder[RollbackMsg] = Encoder.instance {
    case Checkpoint(nodeId, sequenceNo, state) =>
      Json.obj(
        "kind" -> Json.fromString("Checkpoint"),
        "nodeId" -> Json.fromInt(nodeId),
        "sequenceNo" -> Json.fromInt(sequenceNo),
        "state" -> Json.fromString(state)
      )
    case RequestRollback(initiatorId, targetCheckpointSeq) =>
      Json.obj(
        "kind" -> Json.fromString("RequestRollback"),
        "initiatorId" -> Json.fromInt(initiatorId),
        "targetCheckpointSeq" -> Json.fromInt(targetCheckpointSeq)
      )
    case RollbackAck(nodeId, rolledBackTo) =>
      Json.obj(
        "kind" -> Json.fromString("RollbackAck"),
        "nodeId" -> Json.fromInt(nodeId),
        "rolledBackTo" -> Json.fromInt(rolledBackTo)
      )
    case LogSend(fromId, toId, msgSeqNo, msgKind) =>
      Json.obj(
        "kind" -> Json.fromString("LogSend"),
        "fromId" -> Json.fromInt(fromId),
        "toId" -> Json.fromInt(toId),
        "msgSeqNo" -> Json.fromInt(msgSeqNo),
        "msgKind" -> Json.fromString(msgKind)
      )
    case LogReceive(fromId, toId, msgSeqNo) =>
      Json.obj(
        "kind" -> Json.fromString("LogReceive"),
        "fromId" -> Json.fromInt(fromId),
        "toId" -> Json.fromInt(toId),
        "msgSeqNo" -> Json.fromInt(msgSeqNo)
      )
  }

  given Decoder[RollbackMsg] = Decoder.instance { cursor =>
    cursor.get[String]("kind").flatMap {
      case "Checkpoint" => cursor.as[Checkpoint]
      case "RequestRollback" => cursor.as[RequestRollback]
      case "RollbackAck" => cursor.as[RollbackAck]
      case "LogSend" => cursor.as[LogSend]
      case "LogReceive" => cursor.as[LogReceive]
      case other => Left(io.circe.DecodingFailure(s"Unknown rollback message kind: $other", cursor.history))
    }
  }
