package at.asitplus.wallet.lib.jws

import at.asitplus.signum.indispensable.ECCurve
import at.asitplus.signum.indispensable.ECCurve.*
import at.asitplus.signum.indispensable.josef.JweAlgorithm
import at.asitplus.signum.indispensable.josef.JweEncrypted
import at.asitplus.signum.indispensable.josef.JweEncryption
import at.asitplus.signum.indispensable.josef.JweEncryption.*
import at.asitplus.signum.indispensable.nativeDigest
import at.asitplus.signum.indispensable.toJcaPublicKey
import at.asitplus.signum.supreme.HazardousMaterials
import at.asitplus.signum.supreme.hazmat.jcaPrivateKey
import at.asitplus.signum.supreme.sign.EphemeralKey
import at.asitplus.wallet.lib.agent.DefaultCryptoService
import at.asitplus.wallet.lib.agent.EphemeralKeyWithoutCert
import com.benasher44.uuid.uuid4
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDHDecrypter
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.jwk.JWK
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

@OptIn(HazardousMaterials::class)
class JweServiceJvmTest : FreeSpec({

    val configurations: List<Configuration> =
        listOf(
            Configuration(SECP_256_R_1, listOf(A128CBC_HS256, A128GCM)),
            Configuration(SECP_384_R_1, listOf(A192CBC_HS384, A192GCM)),
            Configuration(SECP_521_R_1, listOf(A256CBC_HS512, A256GCM)),
        )

    configurations.forEach { config ->
        val ephemeralKey = EphemeralKey {
            ec {
                curve = config.curve
                digests = setOf(curve.nativeDigest)
            }
        }.getOrThrow()

        val jweAlgorithm = JweAlgorithm.ECDH_ES
        val jvmEncrypter = ECDHEncrypter(ephemeralKey.publicKey.toJcaPublicKey().getOrThrow() as ECPublicKey)
        val jvmDecrypter = ECDHDecrypter(ephemeralKey.jcaPrivateKey as ECPrivateKey)

        val keyPairAdapter = EphemeralKeyWithoutCert(ephemeralKey)
        val cryptoService = DefaultCryptoService(keyPairAdapter)
        val jwsService = DefaultJwsService(cryptoService)
        val randomPayload = JsonPrimitive(uuid4().toString())

        config.encryption.forEach { encryptionMethod ->
            "${config.curve}, ${encryptionMethod}:" - {
                "Encrypted object from ext. library can be decrypted with int. library" {
                    val libJweHeader =
                        JWEHeader.Builder(JWEAlgorithm(jweAlgorithm.identifier), encryptionMethod.joseAlgorithm)
                            .type(JOSEObjectType(JwsContentTypeConstants.DIDCOMM_ENCRYPTED_JSON))
                            .jwk(JWK.parse(cryptoService.keyMaterial.jsonWebKey.serialize()))
                            .contentType(JwsContentTypeConstants.DIDCOMM_PLAIN_JSON)
                            .build()
                    val libJweObject = JWEObject(libJweHeader, Payload(randomPayload.content))
                        .apply { encrypt(jvmEncrypter) }
                    val encryptedJwe = libJweObject.serialize()

                    val parsedJwe = JweEncrypted.deserialize(encryptedJwe).getOrThrow()
                    val result = jwsService.decryptJweObject(parsedJwe, encryptedJwe, JsonPrimitive.serializer()).getOrThrow()
                    result.payload.content shouldBe randomPayload.content
                }

                "Encrypted object from int. library can be decrypted with ext. library" {
                    val encrypted = jwsService.encryptJweObject(
                        JwsContentTypeConstants.DIDCOMM_ENCRYPTED_JSON,
                        randomPayload,
                        JsonElement.serializer(),
                        cryptoService.keyMaterial.jsonWebKey,
                        JwsContentTypeConstants.DIDCOMM_PLAIN_JSON,
                        jweAlgorithm,
                        encryptionMethod,
                    ).getOrThrow().serialize()

                    val parsed = JWEObject.parse(encrypted).shouldNotBeNull()

                    parsed.decrypt(jvmDecrypter)
                    parsed.payload.toBytes().decodeToString() shouldBe "\"${randomPayload.content}\""
                }
            }
        }
    }
})

private data class Configuration(
    val curve: ECCurve,
    val encryption: Collection<JweEncryption>,
)

private val JweEncryption.joseAlgorithm: EncryptionMethod
    get() = when (this) {
        A128GCM -> EncryptionMethod.A128GCM
        A192GCM -> EncryptionMethod.A192GCM
        A256GCM -> EncryptionMethod.A256GCM
        A128CBC_HS256 -> EncryptionMethod.A128CBC_HS256
        A192CBC_HS384 -> EncryptionMethod.A192CBC_HS384
        A256CBC_HS512 -> EncryptionMethod.A256CBC_HS512
    }
