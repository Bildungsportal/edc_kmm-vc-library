package at.asitplus.wallet.lib.iso

import at.asitplus.KmmResult.Companion.wrap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * Part of the ISO/IEC 18013-5:2021 standard: Data structure for MSO (9.1.2.4)
 */
@Serializable
data class KeyAuthorization(
    @SerialName("nameSpaces")
    val namespaces: Array<String>? = null,
    @SerialName("dataElements")
    val dataElements: Map<String, Array<String>>? = null,
) {

    fun serialize() = vckCborSerializer.encodeToByteArray(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as KeyAuthorization

        if (namespaces != null) {
            if (other.namespaces == null) return false
            if (!namespaces.contentEquals(other.namespaces)) return false
        } else if (other.namespaces != null) return false
        return dataElements == other.dataElements
    }

    override fun hashCode(): Int {
        var result = namespaces?.contentHashCode() ?: 0
        result = 31 * result + (dataElements?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun deserialize(it: ByteArray) = kotlin.runCatching {
            vckCborSerializer.decodeFromByteArray<KeyAuthorization>(it)
        }.wrap()
    }
}