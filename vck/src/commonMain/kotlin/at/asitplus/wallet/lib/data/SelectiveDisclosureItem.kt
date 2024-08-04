package at.asitplus.wallet.lib.data

import at.asitplus.KmmResult.Companion.wrap
import at.asitplus.signum.indispensable.io.Base64UrlStrict
import at.asitplus.wallet.lib.iso.sha256
import at.asitplus.wallet.lib.jws.SelectiveDisclosureItemSerializer
import io.matthewnelson.encoding.base64.Base64
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToString
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Selective Disclosure item in SD-JWT format
 */
@Serializable(with = SelectiveDisclosureItemSerializer::class)
data class SelectiveDisclosureItem(
    val salt: ByteArray,
    val claimName: String,
    @Contextual
    val claimValue: Any,
) {

    fun serialize() = vckJsonSerializer.encodeToString(this)

    /**
     * Creates a disclosure, as described in section 5.2 of
     * [draft-ietf-oauth-selective-disclosure-jwt-08](https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/)
     */
    fun toDisclosure() = serialize().encodeToByteArray().encodeToString(Base64UrlStrict)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SelectiveDisclosureItem

        if (!salt.contentEquals(other.salt)) return false
        if (claimName != other.claimName) return false
        if (claimValue != other.claimValue) return false

        return true
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + claimName.hashCode()
        result = 31 * result + claimValue.hashCode()
        return result
    }

    override fun toString(): String {
        return "SelectiveDisclosureItem(" +
                "salt=${salt.encodeToString(Base64())}, " +
                "claimName='$claimName', " +
                "claimValue=$claimValue" +
                ")"
    }


    companion object {
        fun deserialize(it: String) = kotlin.runCatching {
            vckJsonSerializer.decodeFromString<SelectiveDisclosureItem>(it)
        }.wrap()

        /**
         * Hashes a disclosure from [SelectiveDisclosureItem.toDisclosure] according to section 5.2.3 of
         * [draft-ietf-oauth-selective-disclosure-jwt-08](https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/)
         **/
        fun String.hashDisclosure() = encodeToByteArray().sha256().encodeToString(Base64UrlStrict)
    }

}