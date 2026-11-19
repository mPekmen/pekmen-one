package net.pekmen.one.core.model

import java.util.UUID

/** The UUID returned by the configured identity provider. */
@JvmInline
value class AccountId private constructor(val value: String) {
    companion object {
        fun parse(value: String): AccountId {
            val canonical = UUID.fromString(value).toString()
            require(value.equals(canonical, ignoreCase = true)) { "Invalid account identifier" }
            return AccountId(canonical)
        }
    }
}
