package at.asitplus.openid

import at.asitplus.KmmResult
import at.asitplus.KmmResult.Companion.wrap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Holds a deserialized [OidcUserInfo] as well as a [JsonObject] with other properties,
 * that could not been parsed.
 */
data class OidcUserInfoExtended(
    val userInfo: OidcUserInfo,
    val jsonObject: JsonObject,
) {
    constructor(userInfo: OidcUserInfo) : this(
        userInfo,
        odcJsonSerializer.encodeToJsonElement(userInfo) as JsonObject
    )

    companion object {
        fun deserialize(it: String): KmmResult<OidcUserInfoExtended> =
            runCatching {
                val jsonObject = odcJsonSerializer.decodeFromString<JsonObject>(it)
                val userInfo = odcJsonSerializer.decodeFromJsonElement<OidcUserInfo>(jsonObject)
                OidcUserInfoExtended(userInfo, jsonObject)
            }.wrap()

        fun fromJsonObject(it: JsonObject): KmmResult<OidcUserInfoExtended> =
            runCatching {
                val userInfo = odcJsonSerializer.decodeFromJsonElement<OidcUserInfo>(it)
                OidcUserInfoExtended(userInfo, it)
            }.wrap()

        fun fromOidcUserInfo(userInfo: OidcUserInfo): KmmResult<OidcUserInfoExtended> =
            runCatching {
                OidcUserInfoExtended(userInfo, odcJsonSerializer.encodeToJsonElement(userInfo) as JsonObject)
            }.wrap()

    }
}