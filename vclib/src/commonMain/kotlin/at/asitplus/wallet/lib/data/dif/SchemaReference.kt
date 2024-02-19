package at.asitplus.wallet.lib.data.dif

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SchemaReference(
    @SerialName("uri")
    val uri: String,
    @SerialName("required")
    val required: Boolean? = null,
)