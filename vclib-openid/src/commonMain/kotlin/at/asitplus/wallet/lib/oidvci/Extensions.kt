package at.asitplus.wallet.lib.oidvci

import at.asitplus.crypto.datatypes.CryptoAlgorithm
import at.asitplus.crypto.datatypes.io.Base64UrlStrict
import at.asitplus.crypto.datatypes.jws.toJwsAlgorithm
import at.asitplus.wallet.lib.agent.Issuer
import at.asitplus.wallet.lib.data.ConstantIndex
import at.asitplus.wallet.lib.data.ConstantIndex.supportsIso
import at.asitplus.wallet.lib.data.ConstantIndex.supportsSdJwt
import at.asitplus.wallet.lib.data.ConstantIndex.supportsVcJwt
import at.asitplus.wallet.lib.data.VcDataModelConstants
import at.asitplus.wallet.lib.oidc.OpenIdConstants
import at.asitplus.wallet.lib.oidvci.mdl.RequestedCredentialClaimSpecification
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToString

fun ConstantIndex.CredentialScheme.toSupportedCredentialFormat(cryptoAlgorithms: Set<CryptoAlgorithm>): Map<String, SupportedCredentialFormat> {
    val iso = if (supportsIso) {
        isoNamespace!! to SupportedCredentialFormat.forIsoMdoc(
            format = CredentialFormatEnum.MSO_MDOC,
            scope = isoNamespace!!,
            docType = isoDocType!!,
            supportedBindingMethods = setOf(OpenIdConstants.BINDING_METHOD_COSE_KEY),
            supportedSigningAlgorithms = cryptoAlgorithms.map { it.toJwsAlgorithm().identifier }.toSet(),
            isoClaims = mapOf(
                isoNamespace!! to claimNames.associateWith { RequestedCredentialClaimSpecification() }
            )
        )
    } else null
    val jwtVc = if (supportsVcJwt) {
        encodeToCredentialIdentifier(vcType!!, CredentialFormatEnum.JWT_VC) to SupportedCredentialFormat.forVcJwt(
            format = CredentialFormatEnum.JWT_VC,
            scope = vcType!!,
            credentialDefinition = SupportedCredentialFormatDefinition(
                types = listOf(VcDataModelConstants.VERIFIABLE_CREDENTIAL, vcType!!),
                credentialSubject = claimNames.associateWith { CredentialSubjectMetadataSingle() }
            ),
            supportedBindingMethods = setOf(OpenIdConstants.PREFIX_DID_KEY, OpenIdConstants.URN_TYPE_JWK_THUMBPRINT),
            supportedSigningAlgorithms = cryptoAlgorithms.map { it.toJwsAlgorithm().identifier }.toSet(),
        )
    } else null
    val sdJwt = if (supportsSdJwt) {
        encodeToCredentialIdentifier(sdJwtType!!, CredentialFormatEnum.VC_SD_JWT) to SupportedCredentialFormat.forSdJwt(
            format = CredentialFormatEnum.VC_SD_JWT,
            scope = sdJwtType!!,
            sdJwtVcType = sdJwtType!!,
            supportedBindingMethods = setOf(OpenIdConstants.PREFIX_DID_KEY, OpenIdConstants.URN_TYPE_JWK_THUMBPRINT),
            supportedSigningAlgorithms = cryptoAlgorithms.map { it.toJwsAlgorithm().identifier }.toSet(),
            sdJwtClaims = claimNames.associateWith { RequestedCredentialClaimSpecification() }
        )
    } else null
    return listOfNotNull(iso, jwtVc, sdJwt).toMap()
}

/**
 * Reverse functionality of [decodeFromCredentialIdentifier]
 */
private fun encodeToCredentialIdentifier(type: String, format: CredentialFormatEnum) =
    "$type#${format.text}"

/**
 * Reverse functionality of [encodeToCredentialIdentifier]
 */
fun decodeFromCredentialIdentifier(input: String): Pair<String, CredentialFormatEnum> {
    val typeOrSdJwtType = input.substringBeforeLast("#")
    val format = CredentialFormatEnum.parse(input.substringAfterLast("#")) ?: CredentialFormatEnum.MSO_MDOC
    return Pair(typeOrSdJwtType, format)
}

fun CredentialFormatEnum.toRepresentation() = when (this) {
    CredentialFormatEnum.JWT_VC_SD_UNOFFICIAL -> ConstantIndex.CredentialRepresentation.SD_JWT
    CredentialFormatEnum.VC_SD_JWT -> ConstantIndex.CredentialRepresentation.SD_JWT
    CredentialFormatEnum.MSO_MDOC -> ConstantIndex.CredentialRepresentation.ISO_MDOC
    else -> ConstantIndex.CredentialRepresentation.PLAIN_JWT
}

fun Issuer.IssuedCredential.toCredentialResponseParameters() = when (this) {
    is Issuer.IssuedCredential.Iso -> CredentialResponseParameters(
        format = CredentialFormatEnum.MSO_MDOC,
        credential = issuerSigned.serialize().encodeToString(Base64UrlStrict),
    )

    is Issuer.IssuedCredential.VcJwt -> CredentialResponseParameters(
        format = CredentialFormatEnum.JWT_VC,
        credential = vcJws,
    )

    is Issuer.IssuedCredential.VcSdJwt -> CredentialResponseParameters(
        format = CredentialFormatEnum.VC_SD_JWT,
        credential = vcSdJwt,
    )
}

class OAuth2Exception(val error: String, val errorDescription: String? = null) : Throwable(error) {

}
