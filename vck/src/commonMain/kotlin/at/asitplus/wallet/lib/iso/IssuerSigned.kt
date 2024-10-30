package at.asitplus.wallet.lib.iso

import at.asitplus.KmmResult.Companion.wrap
import at.asitplus.catching
import at.asitplus.signum.indispensable.cosef.CoseSigned
import at.asitplus.signum.indispensable.cosef.io.ByteStringWrapper
import at.asitplus.wallet.lib.iso.IssuerSignedItem.Companion.PROP_ELEMENT_ID
import kotlinx.serialization.*
import net.orandja.obor.codec.Cbor
import net.orandja.obor.data.*

/**
 * Part of the ISO/IEC 18013-5:2021 standard: Data structure for mdoc request (8.3.2.1.2.1)
 */
@Serializable
data class IssuerSigned private constructor(
    @SerialName("nameSpaces")
    @Serializable(with = NamespacedIssuerSignedListSerializer::class)
    val namespaces: Map<String, @Contextual IssuerSignedList>? = null,
    @SerialName("issuerAuth")
    val issuerAuth: CoseSigned,
) {
    fun getIssuerAuthPayloadAsMso() = catching {
        MobileSecurityObject.deserializeFromIssuerAuth(issuerAuth.payload!!).getOrThrow()
    }

    fun serialize() = vckCborSerializer.encodeToByteArray(this)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IssuerSigned) return false

        if (issuerAuth != other.issuerAuth) return false
        if (namespaces != other.namespaces) return false

        return true
    }

    override fun hashCode(): Int {
        var result = issuerAuth.hashCode()
        result = 31 * result + (namespaces?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun deserialize(it: ByteArray) = kotlin.runCatching {
            vckCborSerializer.decodeFromByteArray<IssuerSigned>(it)
        }.wrap()


        // Note: Can't be a secondary constructor, because it would have the same JVM signature as the primary one.
        /**
         * Ensures the serialization of this structure in [Document.issuerSigned]:
         * ```
         * IssuerNameSpaces = { ; Returned data elements for each namespace
         *     + NameSpace => [ + IssuerSignedItemBytes ]
         * }
         * IssuerSignedItemBytes = #6.24(bstr .cbor IssuerSignedItem)
         * ```
         *
         * See ISO/IEC 18013-5:2021, 8.3.2.1.2.2 Device retrieval mdoc response
         */
        fun fromIssuerSignedItems(
            namespacedItems: Map<String, List<IssuerSignedItem>>,
            issuerAuth: CoseSigned,
        ): IssuerSigned = IssuerSigned(
            namespaces = namespacedItems.map { (namespace, value) ->
                namespace to IssuerSignedList.fromIssuerSignedItems(value, namespace)
            }.toMap(),
            issuerAuth = issuerAuth,
        )

    }
}