package at.asitplus.wallet.lib.cbor

import at.asitplus.KmmResult
import at.asitplus.crypto.datatypes.cose.*
import at.asitplus.crypto.datatypes.jws.JwsAlgorithm
import at.asitplus.wallet.lib.agent.CryptoService
import at.asitplus.wallet.lib.agent.DefaultVerifierCryptoService
import at.asitplus.wallet.lib.agent.VerifierCryptoService
import io.github.aakira.napier.Napier
import kotlinx.serialization.cbor.ByteStringWrapper

/**
 * Creates and parses COSE objects.
 */
interface CoseService {

    /**
     * Algorithm which will be used to sign COSE in [createSignedCose].
     */
    val algorithm: CoseAlgorithm

    /**
     * Creates and signs a new [CoseSigned] object,
     * appends correct value for [CoseHeader.algorithm] into [protectedHeader].
     *
     * @param addKeyId whether to set [CoseHeader.kid] in [protectedHeader]
     * @param addCertificate whether to set [CoseHeader.certificateChain] in [unprotectedHeader]
     *
     */
    suspend fun createSignedCose(
        protectedHeader: CoseHeader? = null,
        unprotectedHeader: CoseHeader? = null,
        payload: ByteArray? = null,
        addKeyId: Boolean = true,
        addCertificate: Boolean = false,
    ): KmmResult<CoseSigned>
}

interface VerifierCoseService {

    fun verifyCose(coseSigned: CoseSigned, signer: CoseKey): KmmResult<Boolean>

}

/**
 * Constant from RFC 9052 - CBOR Object Signing and Encryption (COSE)
 */
private const val SIGNATURE1_STRING = "Signature1"

class DefaultCoseService(private val cryptoService: CryptoService) : CoseService {

    override val algorithm: CoseAlgorithm = cryptoService.algorithm.toCoseAlgorithm()

    override suspend fun createSignedCose(
        protectedHeader: CoseHeader?,
        unprotectedHeader: CoseHeader?,
        payload: ByteArray?,
        addKeyId: Boolean,
        addCertificate: Boolean,
    ): KmmResult<CoseSigned> {
        var copyProtectedHeader = protectedHeader?.copy(algorithm = algorithm)
            ?: CoseHeader(algorithm = algorithm)
        if (addKeyId) copyProtectedHeader =
            copyProtectedHeader.copy(kid = cryptoService.publicKey.didEncoded.encodeToByteArray())

        val copyUnprotectedHeader = if (addCertificate) {
            (unprotectedHeader ?: CoseHeader()).copy(certificateChain = cryptoService.certificate.encodeToDer())
        } else {
            unprotectedHeader
        }

        val signatureInput = CoseSignatureInput(
            contextString = SIGNATURE1_STRING,
            protectedHeader = ByteStringWrapper(copyProtectedHeader),
            externalAad = byteArrayOf(),
            payload = payload,
        ).serialize()

        val signature = cryptoService.sign(signatureInput).getOrElse {
            Napier.w("No signature from native code", it)
            return KmmResult.failure(it)
        }

        return KmmResult.success(
            CoseSigned(
                ByteStringWrapper(copyProtectedHeader),
                copyUnprotectedHeader,
                payload,
                signature
            )
        )
    }
}

class DefaultVerifierCoseService(
    private val cryptoService: VerifierCryptoService = DefaultVerifierCryptoService()
) : VerifierCoseService {

    /**
     * Verifiers the signature of [coseSigned] by using [signer].
     */
    override fun verifyCose(coseSigned: CoseSigned, signer: CoseKey): KmmResult<Boolean> {
        val signatureInput = CoseSignatureInput(
            contextString = SIGNATURE1_STRING,
            protectedHeader = ByteStringWrapper(coseSigned.protectedHeader.value),
            externalAad = byteArrayOf(),
            payload = coseSigned.payload,
        ).serialize()

        val algorithm = coseSigned.protectedHeader.value.algorithm ?: return KmmResult.failure(
            IllegalArgumentException(
                "Algorithm not specified"
            )
        )
        val publicKey = signer.toCryptoPublicKey().getOrElse {
            return KmmResult.failure<Boolean>(IllegalArgumentException("Signer not convertible"))
                .also { Napier.w("Could not convert signer to public key: $signer") }
        }
        val verified = cryptoService.verify(
            input = signatureInput,
            signature = coseSigned.signature,
            algorithm = algorithm.toCryptoAlgorithm(),
            publicKey = publicKey
        )
        val result = verified.getOrElse {
            Napier.w("No verification from native code", it)
            return KmmResult.failure(it)
        }
        return KmmResult.success(result)
    }
}


