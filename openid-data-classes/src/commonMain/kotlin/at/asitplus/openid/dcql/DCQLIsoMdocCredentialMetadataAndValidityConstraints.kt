package at.asitplus.openid.dcql

import at.asitplus.KmmResult
import at.asitplus.catching
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DCQLIsoMdocCredentialMetadataAndValidityConstraints(
    /**
     * OID4VP draft 23: doctype_value: OPTIONAL. String that specifies an allowed value for the
     * doctype of the requested Verifiable Credential. It MUST be a valid doctype identifier as
     * defined in [ISO.18013-5].
     */
    @SerialName(SerialNames.DOCTYPE_VALUE)
    val doctypeValue: String?
) : DCQLCredentialMetadataAndValidityConstraints {
    object SerialNames {
        const val DOCTYPE_VALUE = "doctype_value"
    }

    fun validate(actualDoctypeValue: String?): KmmResult<Unit> = catching {
        if (doctypeValue != null && actualDoctypeValue != doctypeValue) {
            throw IllegalArgumentException("Incompatible MDOC document type.")
        }
    }
}