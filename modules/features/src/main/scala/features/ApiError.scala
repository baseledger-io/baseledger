package features

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class ApiError(code: String, message: Seq[String])

object ApiError:
  given JsonValueCodec[ApiError] = JsonCodecMaker.make
