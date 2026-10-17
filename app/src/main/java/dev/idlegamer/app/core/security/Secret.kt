package dev.idlegamer.app.core.security

/**
 * Wrapper for values that must never reach logs, crash reports or UI state.
 *
 * JVM strings are immutable, so the value cannot be wiped from memory; the wrapper only guarantees
 * that accidental `toString()`, string templates and data-class printing never reveal it.
 */
class Secret(private val value: String) {

    init {
        require(value.isNotEmpty()) { "Secret must not be empty" }
    }

    /** Access is explicit and greppable: every call site is a place that handles raw credentials. */
    fun reveal(): String = value

    override fun toString(): String = "Secret(***)"

    override fun equals(other: Any?): Boolean =
        other is Secret && java.security.MessageDigest.isEqual(value.toByteArray(), other.value.toByteArray())

    override fun hashCode(): Int = value.length
}
