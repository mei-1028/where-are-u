package com.whereareu.app.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** One exception type for every API/network failure, with a Chinese user message. */
class ApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = httpStatus == 401 || code == "UNAUTHORIZED"
}

object ApiErrorMapper {

    private val json = Json { ignoreUnknownKeys = true }

    private val messages = mapOf(
        "UNAUTHORIZED" to "登录已过期，请重新登录",
        "FORBIDDEN" to "没有权限执行该操作",
        "NOT_FOUND" to "未找到相关数据",
        "VALIDATION" to "提交的信息有误",
        "CONFLICT" to "操作冲突，请刷新后重试",
        "ALREADY_IN_FAMILY" to "你已经加入了一个家庭",
        "INVITE_INVALID" to "邀请码无效",
        "INVITE_EXPIRED" to "邀请码已过期，请向家人要新的邀请码",
        "DEVICE_OFFLINE" to "对方设备当前离线",
        "RATE_LIMITED" to "操作太频繁，请稍后再试",
        "NETWORK" to "网络不可达，请检查服务器地址或网络连接",
        "TIMEOUT" to "请求超时，请稍后重试",
        "PARSE" to "服务器返回数据格式异常",
        "UNKNOWN" to "发生未知错误，请稍后重试",
    )

    fun messageFor(code: String, serverMessage: String? = null): String {
        if (code == "VALIDATION" && !serverMessage.isNullOrBlank()) return serverMessage
        return messages[code] ?: serverMessage?.takeIf { it.isNotBlank() } ?: messages.getValue("UNKNOWN")
    }

    fun fromHttp(status: Int, errorBody: String?): ApiException {
        val envelope = errorBody?.let {
            runCatching {
                json.decodeFromString(ApiErrorEnvelope.serializer(), it)
            }.getOrNull()
        }
        val code = envelope?.error?.code ?: when (status) {
            401 -> "UNAUTHORIZED"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            409 -> "CONFLICT"
            429 -> "RATE_LIMITED"
            else -> if (status >= 500) "UNKNOWN" else "VALIDATION"
        }
        val message = when {
            status >= 500 && envelope == null -> "服务器暂时不可用，请稍后重试"
            else -> messageFor(code, envelope?.error?.message)
        }
        return ApiException(code = code, message = message, httpStatus = status)
    }

    fun map(throwable: Throwable): ApiException = when (throwable) {
        is ApiException -> throwable
        is HttpException -> fromHttp(throwable.code(), runCatching { throwable.response()?.errorBody()?.string() }.getOrNull())
        is SocketTimeoutException -> ApiException("TIMEOUT", messageFor("TIMEOUT"), cause = throwable)
        is UnknownHostException, is ConnectException -> ApiException("NETWORK", messageFor("NETWORK"), cause = throwable)
        is IOException -> ApiException("NETWORK", messageFor("NETWORK"), cause = throwable)
        is SerializationException -> ApiException("PARSE", messageFor("PARSE"), cause = throwable)
        else -> ApiException("UNKNOWN", messageFor("UNKNOWN"), cause = throwable)
    }
}

/** Runs a suspending API call and maps every failure into [ApiException]. */
suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(ApiErrorMapper.map(throwable))
}

/** Converts Retrofit's Response<Unit> (204/202) into a Result. */
fun Response<Unit>.toResult(): Result<Unit> =
    if (isSuccessful) {
        Result.success(Unit)
    } else {
        val raw = runCatching { errorBody()?.string() }.getOrNull()
        Result.failure(ApiErrorMapper.fromHttp(code(), raw))
    }

fun Throwable.toUserMessage(): String =
    (this as? ApiException)?.message ?: ApiErrorMapper.map(this).message
