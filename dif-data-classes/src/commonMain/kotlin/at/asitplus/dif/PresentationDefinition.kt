package at.asitplus.dif

import at.asitplus.KmmResult.Companion.wrap
import com.benasher44.uuid.uuid4
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Data class for
 * [DIF Presentation Exchange v1.0.0](https://identity.foundation/presentation-exchange/spec/v1.0.0/#presentation-definition)
 */
@Serializable
data class PresentationDefinition(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("purpose")
    val purpose: String? = null,
    @SerialName("input_descriptors")
    val inputDescriptors: Collection<InputDescriptor>,
    @Deprecated(message = "Removed in DIF Presentation Exchange 2.0.0", ReplaceWith("inputDescriptors.format"))
    @SerialName("format")
    val formats: FormatHolder? = null,
    @SerialName("submission_requirements")
    val submissionRequirements: Collection<SubmissionRequirement>? = null,
) {
    @Deprecated(message = "Removed in DIF Presentation Exchange 2.0.0")
    constructor(
        inputDescriptors: Collection<InputDescriptor>,
        formats: FormatHolder
    ) : this(id = uuid4().toString(), inputDescriptors = inputDescriptors, formats = formats)

    fun serialize() = ddcJsonSerializer.encodeToString(this)

    companion object {
        fun deserialize(it: String) = kotlin.runCatching {
            ddcJsonSerializer.decodeFromString<PresentationDefinition>(it)
        }.wrap()
    }
}

