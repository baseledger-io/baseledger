// scalafix:off
package domain

import java.io.NotSerializableException

import org.apache.pekko.serialization.SerializerWithStringManifest

import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }

class ScalaPbSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 1001 // Unique ID for this project

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case m: GeneratedMessage => m.toByteArray
    case _ => throw new IllegalArgumentException(s"Cannot serialize ${o.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    try {
      val companion = Class.forName(manifest + "$").getField("MODULE$").get(null).asInstanceOf[GeneratedMessageCompanion[_]]
      companion.parseFrom(bytes).asInstanceOf[AnyRef]
    } catch {
      case e: Exception => throw new NotSerializableException(
          s"Unable to load companion object for manifest [$manifest]: ${e.getMessage}"
        )
    }
  }
}
