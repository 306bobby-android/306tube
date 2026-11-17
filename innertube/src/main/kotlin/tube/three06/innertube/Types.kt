package tube.three06.innertube

open class InnerTubeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ParseException(message: String, cause: Throwable? = null) : InnerTubeException(message, cause)

class LoginRequiredException(message: String = "Sign-in required") : InnerTubeException(message)

class NotFoundException(message: String) : InnerTubeException(message)

class ApiException(val httpCode: Int, val status: String?, message: String) : InnerTubeException(message)

enum class ClientKind { WEB, WEB_EMBEDDED, MWEB, ANDROID, ANDROID_VR, IOS, TVHTML5, TVHTML5_EMBEDDED }

enum class PlaylistPrivacy { PUBLIC, UNLISTED, PRIVATE }
