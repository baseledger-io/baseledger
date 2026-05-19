// scalafix:off
package domain

import java.io.NotSerializableException

import scala.util.{ Failure, Success }

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest

import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }

class ScalaPbSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier: Int = 1001 // Unique ID for this project

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case m: GeneratedMessage => m.toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val companionClassName = manifest + "$"
    system.dynamicAccess.getObjectFor[GeneratedMessageCompanion[_]](companionClassName) match {
      case Success(companion) =>
        companion.parseFrom(bytes).asInstanceOf[AnyRef]
      case Failure(e) =>
        throw new NotSerializableException(
          s"Unable to load companion object for manifest [$manifest]: ${e.getMessage}"
        )
    }
  }
}
