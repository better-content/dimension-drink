package dev.yourname.obelisks.util

/**
 * A simple Result type for error handling without exceptions.
 */
sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val error: String, val cause: Throwable? = null) : Result<Nothing>()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw RuntimeException(error, cause)
    }

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (String, Throwable?) -> Unit): Result<T> {
        if (this is Failure) action(error, cause)
        return this
    }

    companion object {
        fun <T> success(value: T): Result<T> = Success(value)
        fun failure(error: String, cause: Throwable? = null): Result<Nothing> = Failure(error, cause)

        inline fun <T> runCatching(block: () -> T): Result<T> = try {
            Success(block())
        } catch (e: Exception) {
            Failure(e.message ?: "Unknown error", e)
        }
    }
}
