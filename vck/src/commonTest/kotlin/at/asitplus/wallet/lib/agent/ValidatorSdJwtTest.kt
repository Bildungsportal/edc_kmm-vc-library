package at.asitplus.wallet.lib.agent

import at.asitplus.signum.indispensable.josef.ConfirmationClaim
import at.asitplus.signum.indispensable.josef.toJsonWebKey
import at.asitplus.wallet.lib.agent.SdJwtCreator.toSdJsonObject
import at.asitplus.wallet.lib.data.ConstantIndex
import at.asitplus.wallet.lib.data.SdJwtConstants
import at.asitplus.wallet.lib.data.VerifiableCredentialSdJwt
import at.asitplus.wallet.lib.data.vckJsonSerializer
import at.asitplus.wallet.lib.jws.JwsContentTypeConstants
import at.asitplus.wallet.lib.jws.JwsHeaderCertOrJwk
import at.asitplus.wallet.lib.jws.SdJwtSigned
import at.asitplus.wallet.lib.jws.SignJwt
import at.asitplus.wallet.lib.jws.SignJwtFun
import com.benasher44.uuid.uuid4
import io.github.aakira.napier.Napier
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject


class ValidatorSdJwtTest : FreeSpec() {

    private lateinit var issuer: Issuer
    private lateinit var holderKeyMaterial: KeyMaterial
    private lateinit var validator: Validator

    init {
        beforeEach {
            validator = Validator()
            issuer = IssuerAgent()
            holderKeyMaterial = EphemeralKeyWithoutCert()
        }

        "credentials are valid for holder's key" {
            val credential = issuer.issueCredential(buildCredentialData()).getOrThrow()
                .shouldBeInstanceOf<Issuer.IssuedCredential.VcSdJwt>()

            validator.verifySdJwt(SdJwtSigned.parse(credential.vcSdJwt)!!, holderKeyMaterial.publicKey)
                .shouldBeInstanceOf<Verifier.VerifyCredentialResult.SuccessSdJwt>()
        }

        "credentials are not valid for some other key" {
            val credential = issuer.issueCredential(buildCredentialData()).getOrThrow()
                .shouldBeInstanceOf<Issuer.IssuedCredential.VcSdJwt>()

            validator.verifySdJwt(SdJwtSigned.parse(credential.vcSdJwt)!!, EphemeralKeyWithoutCert().publicKey)
                .shouldBeInstanceOf<Verifier.VerifyCredentialResult.ValidationError>()
        }

        "credentials without cnf are not valid" {
            val credential = issueVcSd(
                buildCredentialData(),
                holderKeyMaterial,
                buildCnf = false,
            ).shouldBeInstanceOf<Issuer.IssuedCredential.VcSdJwt>()

            validator.verifySdJwt(SdJwtSigned.parse(credential.vcSdJwt)!!, holderKeyMaterial.publicKey)
                .shouldBeInstanceOf<Verifier.VerifyCredentialResult.ValidationError>()
        }

        "credentials with random subject are valid" {
            val credential = issueVcSd(
                buildCredentialData(),
                holderKeyMaterial,
                scrambleSubject = true,
            ).shouldBeInstanceOf<Issuer.IssuedCredential.VcSdJwt>()

            validator.verifySdJwt(SdJwtSigned.parse(credential.vcSdJwt)!!, holderKeyMaterial.publicKey)
                .shouldBeInstanceOf<Verifier.VerifyCredentialResult.SuccessSdJwt>()
        }
    }

    private fun buildCredentialData(): CredentialToBeIssued.VcSd = DummyCredentialDataProvider.getCredential(
        holderKeyMaterial.publicKey,
        ConstantIndex.AtomicAttribute2023,
        ConstantIndex.CredentialRepresentation.SD_JWT,
    ).getOrThrow().shouldBeInstanceOf<CredentialToBeIssued.VcSd>()
}


private suspend fun issueVcSd(
    credential: CredentialToBeIssued.VcSd,
    holderKeyMaterial: KeyMaterial,
    buildCnf: Boolean = true,
    scrambleSubject: Boolean = false,
): Issuer.IssuedCredential {
    val issuanceDate = Clock.System.now()
    val signIssuedSdJwt: SignJwtFun<JsonObject> = SignJwt(holderKeyMaterial, JwsHeaderCertOrJwk())
    val vcId = "urn:uuid:${uuid4()}"
    val expirationDate = credential.expiration
    val subjectId = credential.subjectPublicKey.didEncoded
    val (sdJwt, disclosures) = credential.claims.toSdJsonObject()
    val vcSdJwt = VerifiableCredentialSdJwt(
        subject = if (scrambleSubject) subjectId.reversed() else subjectId,
        notBefore = issuanceDate,
        issuer = holderKeyMaterial.identifier,
        expiration = expirationDate,
        issuedAt = issuanceDate,
        jwtId = vcId,
        verifiableCredentialType = credential.scheme.sdJwtType ?: credential.scheme.schemaUri,
        selectiveDisclosureAlgorithm = SdJwtConstants.SHA_256,
        confirmationClaim = if (!buildCnf) null else
            ConfirmationClaim(jsonWebKey = credential.subjectPublicKey.toJsonWebKey())
    )
    val vcSdJwtObject = vckJsonSerializer.encodeToJsonElement(vcSdJwt).jsonObject
    val entireObject = buildJsonObject {
        sdJwt.forEach {
            put(it.key, it.value)
        }
        vcSdJwtObject.forEach {
            put(it.key, it.value)
        }
    }
    // inclusion of x5c/jwk may change when all clients can look up the issuer-signed key web-based,
    // i.e. this issuer provides `.well-known/jwt-vc-issuer` file
    val jws = signIssuedSdJwt(
        JwsContentTypeConstants.SD_JWT,
        entireObject,
        JsonObject.serializer(),
    ).getOrElse {
        Napier.w("Could not wrap credential in SD-JWT", it)
        throw RuntimeException("Signing failed", it)
    }
    val vcInSdJwt = (listOf(jws.serialize()) + disclosures).joinToString("~", postfix = "~")
    Napier.i("issueVcSd: $vcInSdJwt")
    return Issuer.IssuedCredential.VcSdJwt(vcInSdJwt, credential.scheme)
}
