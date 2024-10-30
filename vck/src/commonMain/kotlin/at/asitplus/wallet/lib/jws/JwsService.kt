package at.asitplus.wallet.lib.jws

import at.asitplus.KmmResult
import at.asitplus.catching
import at.asitplus.signum.indispensable.CryptoPublicKey
import at.asitplus.signum.indispensable.Digest
import at.asitplus.signum.indispensable.asn1.encoding.encodeTo4Bytes
import at.asitplus.signum.indispensable.asn1.encoding.encodeTo8Bytes
import at.asitplus.signum.indispensable.io.Base64UrlStrict
import at.asitplus.signum.indispensable.josef.*
import at.asitplus.signum.indispensable.josef.JwsExtensions.prependWith4BytesSize
import at.asitplus.signum.indispensable.josef.JwsSigned.Companion.prepareJwsSignatureInput
import at.asitplus.signum.indispensable.toX509SignatureAlgorithm
import at.asitplus.signum.supreme.asKmmResult
import at.asitplus.wallet.lib.agent.CryptoService
import at.asitplus.wallet.lib.agent.DefaultVerifierCryptoService
import at.asitplus.wallet.lib.agent.EphemeralKeyHolder
import at.asitplus.wallet.lib.agent.VerifierCryptoService
import io.github.aakira.napier.Napier
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToByteArray
import kotlin.random.Random

/**
 * Creates and parses JWS and JWE objects.
 */
interface JwsService {

    /**
     * Algorithm which will be used to sign JWS in [createSignedJws], [createSignedJwt], [createSignedJwsAddingParams].
     */
    val algorithm: JwsAlgorithm

    /**
     * Algorithm which can be used to encrypt JWE, that can be decrypted with [decryptJweObject].
     */
    val encryptionAlgorithm: JweAlgorithm

    /**
     * Encoding which can be used to encrypt JWE, that can be decrypted with [decryptJweObject].
     */
    val encryptionEncoding: JweEncryption

    suspend fun createSignedJwt(
        type: String,
        payload: ByteArray,
        contentType: String? = null
    ): KmmResult<JwsSigned>

    suspend fun createSignedJws(header: JwsHeader, payload: ByteArray): KmmResult<JwsSigned>

    /**
     * Appends correct values for  [JwsHeader.algorithm],
     * [JweHeader.keyId] (if `addKeyId` is `true`),
     * and [JwsHeader.jsonWebKey] (if `addJsonWebKey` is `true`).
     */
    suspend fun createSignedJwsAddingParams(
        header: JwsHeader? = null,
        payload: ByteArray,
        addKeyId: Boolean = true,
        addJsonWebKey: Boolean = true,
        addX5c: Boolean = false
    ): KmmResult<JwsSigned>

    suspend fun encryptJweObject(
        header: JweHeader? = null,
        payload: ByteArray,
        recipientKey: JsonWebKey,
        jweAlgorithm: JweAlgorithm,
        jweEncryption: JweEncryption
    ): KmmResult<JweEncrypted>

    fun encryptJweObject(
        type: String,
        payload: ByteArray,
        recipientKey: JsonWebKey,
        contentType: String? = null,
        jweAlgorithm: JweAlgorithm,
        jweEncryption: JweEncryption
    ): KmmResult<JweEncrypted>

    suspend fun decryptJweObject(
        jweObject: JweEncrypted,
        serialized: String
    ): KmmResult<JweDecrypted>

}

interface VerifierJwsService {

    val supportedAlgorithms: List<JwsAlgorithm>

    fun verifyJwsObject(jwsObject: JwsSigned): Boolean

    fun verifyJws(jwsObject: JwsSigned, signer: JsonWebKey): Boolean

}

class DefaultJwsService(private val cryptoService: CryptoService) : JwsService {

    override val algorithm: JwsAlgorithm =
        cryptoService.keyMaterial.signatureAlgorithm.toJwsAlgorithm().getOrThrow()

    // TODO: Get from crypto service
    override val encryptionAlgorithm: JweAlgorithm = JweAlgorithm.ECDH_ES

    // TODO: Get from crypto service
    override val encryptionEncoding: JweEncryption = JweEncryption.A256GCM

    override suspend fun createSignedJwt(
        type: String,
        payload: ByteArray,
        contentType: String?
    ): KmmResult<JwsSigned> = createSignedJws(
        JwsHeader(
            algorithm = cryptoService.keyMaterial.signatureAlgorithm.toJwsAlgorithm().getOrThrow(),
            keyId = cryptoService.keyMaterial.publicKey.didEncoded,
            type = type,
            contentType = contentType
        ), payload
    )

    override suspend fun createSignedJws(header: JwsHeader, payload: ByteArray) = catching {
        if (header.algorithm != cryptoService.keyMaterial.signatureAlgorithm.toJwsAlgorithm()
                .getOrThrow()
            || header.jsonWebKey?.let { it != cryptoService.keyMaterial.jsonWebKey } == true
        ) {
            throw IllegalArgumentException("Algorithm or JSON Web Key not matching to cryptoService")
        }

        val plainSignatureInput = prepareJwsSignatureInput(header, payload)
        val signature = cryptoService.sign(plainSignatureInput.encodeToByteArray()).asKmmResult().getOrThrow()
        JwsSigned(header, payload, signature, plainSignatureInput)
    }

    override suspend fun createSignedJwsAddingParams(
        header: JwsHeader?,
        payload: ByteArray,
        addKeyId: Boolean,
        addJsonWebKey: Boolean,
        addX5c: Boolean
    ): KmmResult<JwsSigned> = catching {
        var copy = header?.copy(
            algorithm = cryptoService.keyMaterial.signatureAlgorithm.toJwsAlgorithm().getOrThrow()
        )
            ?: JwsHeader(
                algorithm = cryptoService.keyMaterial.signatureAlgorithm.toJwsAlgorithm()
                    .getOrThrow()
            )
        if (addKeyId)
            copy = copy.copy(keyId = cryptoService.keyMaterial.jsonWebKey.keyId)
        if (addJsonWebKey)
            copy = copy.copy(jsonWebKey = cryptoService.keyMaterial.jsonWebKey)
        // Null pointer is a controlled error case inside the catching block
        if (addX5c)
            copy = copy.copy(certificateChain = listOf(cryptoService.keyMaterial.getCertificate()!!))
        createSignedJws(copy, payload).getOrThrow()
    }

    override suspend fun decryptJweObject(
        jweObject: JweEncrypted,
        serialized: String
    ): KmmResult<JweDecrypted> = catching {
        val header = jweObject.header
        val alg = header.algorithm
            ?: throw IllegalArgumentException("No algorithm in JWE header")
        val enc = header.encryption
            ?: throw IllegalArgumentException("No encryption in JWE header")
        val epk = header.ephemeralKeyPair
            ?: throw IllegalArgumentException("No epk in JWE header")
        val z = cryptoService.performKeyAgreement(epk, alg).getOrThrow()
        val intermediateKey = concatKdf(
            z,
            enc,
            header.agreementPartyUInfo,
            header.agreementPartyVInfo,
            enc.encryptionKeyLength
        )
        val key = compositeKey(enc, intermediateKey)
        val iv = jweObject.iv
        val aad = jweObject.headerAsParsed.encodeToByteArray(Base64UrlStrict)
        val ciphertext = jweObject.ciphertext
        val authTag = jweObject.authTag
        val plaintext = cryptoService.decrypt(key.aesKey, iv, aad, ciphertext, authTag, enc).getOrThrow()
        key.hmacKey?.let { hmacKey ->
            val expectedAuthTag = cryptoService.hmac(hmacKey, enc, hmacInput(aad, iv, ciphertext))
                .getOrThrow()
                .take(enc.macLength!!).toByteArray()
            if (!expectedAuthTag.contentEquals(authTag)) {
                throw IllegalArgumentException("Authtag mismatch")
            }
        }
        JweDecrypted(header, plaintext)
    }

    override suspend fun encryptJweObject(
        header: JweHeader?,
        payload: ByteArray,
        recipientKey: JsonWebKey,
        jweAlgorithm: JweAlgorithm,
        jweEncryption: JweEncryption,
    ): KmmResult<JweEncrypted> = catching {
        val crv = recipientKey.curve
            ?: throw IllegalArgumentException("No curve in recipient key")
        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair(crv)
        val jweHeader = (header ?: JweHeader(jweAlgorithm, jweEncryption, type = null)).copy(
            algorithm = jweAlgorithm,
            encryption = jweEncryption,
            jsonWebKey = cryptoService.keyMaterial.jsonWebKey,
            ephemeralKeyPair = ephemeralKeyPair.publicJsonWebKey
        )
        encryptJwe(ephemeralKeyPair, recipientKey, jweAlgorithm, jweEncryption, jweHeader, payload)
    }

    override fun encryptJweObject(
        type: String,
        payload: ByteArray,
        recipientKey: JsonWebKey,
        contentType: String?,
        jweAlgorithm: JweAlgorithm,
        jweEncryption: JweEncryption
    ): KmmResult<JweEncrypted> = catching {
        val crv = recipientKey.curve
            ?: throw IllegalArgumentException("No curve in recipient key")
        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair(crv)
        val jweHeader = JweHeader(
            algorithm = jweAlgorithm,
            encryption = jweEncryption,
            jsonWebKey = cryptoService.keyMaterial.jsonWebKey,
            type = type,
            contentType = contentType,
            ephemeralKeyPair = ephemeralKeyPair.publicJsonWebKey
        )
        encryptJwe(ephemeralKeyPair, recipientKey, jweAlgorithm, jweEncryption, jweHeader, payload)
    }

    private fun encryptJwe(
        ephemeralKeyPair: EphemeralKeyHolder,
        recipientKey: JsonWebKey,
        jweAlgorithm: JweAlgorithm,
        jweEncryption: JweEncryption,
        jweHeader: JweHeader,
        payload: ByteArray
    ): JweEncrypted {
        val z = cryptoService.performKeyAgreement(ephemeralKeyPair, recipientKey, jweAlgorithm)
            .getOrThrow()
        val intermediateKey = concatKdf(
            z,
            jweEncryption,
            jweHeader.agreementPartyUInfo,
            jweHeader.agreementPartyVInfo,
            jweEncryption.encryptionKeyLength
        )
        val key = compositeKey(jweEncryption, intermediateKey)
        val iv = Random.nextBytes(jweEncryption.ivLengthBits / 8)
        val headerSerialized = jweHeader.serialize()
        val aad = headerSerialized.encodeToByteArray()
        val aadForCipher = aad.encodeToByteArray(Base64UrlStrict)
        val ciphertext = cryptoService.encrypt(key.aesKey, iv, aadForCipher, payload, jweEncryption).getOrThrow()
        val authTag = key.hmacKey?.let { hmacKey ->
            cryptoService.hmac(hmacKey, jweEncryption, hmacInput(aadForCipher, iv, ciphertext.ciphertext))
                .getOrThrow()
                .take(jweEncryption.macLength!!).toByteArray()
        } ?: ciphertext.authtag
        return JweEncrypted(jweHeader, aad, null, iv, ciphertext.ciphertext, authTag)
    }

    /**
     * Derives the key, for use in content encryption in JWE,
     * per [RFC 7518](https://datatracker.ietf.org/doc/html/rfc7518#section-5.2.2.1)
     */
    private fun compositeKey(jweEncryption: JweEncryption, key: ByteArray) =
        if (jweEncryption.macLength != null) {
            CompositeKey(key.drop(key.size / 2).toByteArray(), key.take(key.size / 2).toByteArray())
        } else {
            CompositeKey(key)
        }

    /**
     * Input for HMAC calculation in JWE, when not using authenticated encryption,
     * per [RFC 7518](https://datatracker.ietf.org/doc/html/rfc7518#section-5.2.2.1)
     */
    private fun hmacInput(
        aadForCipher: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray
    ) = aadForCipher + iv + ciphertext + (aadForCipher.size * 8L).encodeTo8Bytes()

    /**
     * Concat KDF for use in ECDH-ES in JWE,
     * per [RFC 7518](https://datatracker.ietf.org/doc/html/rfc7518#section-4.6),
     * and [NIST.800-56A](http://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-56Ar2.pdf)
     */
    private fun concatKdf(
        z: ByteArray,
        jweEncryption: JweEncryption,
        apu: ByteArray?,
        apv: ByteArray?,
        encryptionKeyLengthBits: Int
    ): ByteArray {
        val digest = Digest.SHA256
        val repetitions = (encryptionKeyLengthBits.toUInt() + digest.outputLength.bits - 1U) / digest.outputLength.bits
        val algId = jweEncryption.text.encodeToByteArray().prependWith4BytesSize()
        val apuEncoded = apu?.prependWith4BytesSize() ?: 0.encodeTo4Bytes()
        val apvEncoded = apv?.prependWith4BytesSize() ?: 0.encodeTo4Bytes()
        val keyLength = jweEncryption.encryptionKeyLength.encodeTo4Bytes()
        val otherInfo = algId + apuEncoded + apvEncoded + keyLength + byteArrayOf()
        val output = (1..repetitions.toInt()).fold(byteArrayOf()) { acc, step ->
            acc + cryptoService.messageDigest(step.encodeTo4Bytes() + z + otherInfo, digest)
        }
        return output.take(encryptionKeyLengthBits / 8).toByteArray()
    }

}
/**
 * Clients need to retrieve the URL passed in as the only argument, and parse the content to [JsonWebKeySet].
 */
typealias JwkSetRetrieverFunction = (String) -> JsonWebKeySet?

/**
 * Clients get the parsed [JwsSigned] and need to provide a set of keys, which will be used for verification one-by-one.
 */
typealias PublicKeyLookup = (JwsSigned) -> Set<JsonWebKey>?

class DefaultVerifierJwsService(
    private val cryptoService: VerifierCryptoService = DefaultVerifierCryptoService(),
    /**
     * Need to implement if JSON web keys in JWS headers are referenced by a `kid`, and need to be retrieved from
     * the `jku`.
     */
    private val jwkSetRetriever: JwkSetRetrieverFunction = { null },
    /** Need to implement if valid keys for JWS are transported somehow out-of-band, e.g. provided by a trust store */
    private val publicKeyLookup: PublicKeyLookup = { null },
) : VerifierJwsService {

    override val supportedAlgorithms: List<JwsAlgorithm> =
        cryptoService.supportedAlgorithms.map { it.toJwsAlgorithm().getOrThrow() }

    /**
     * Verifies the signature of [jwsObject], by extracting the public key from [JwsHeader.publicKey],
     * or by using [jwkSetRetriever] if [JwsHeader.jsonWebKeySetUrl] is set.
     */
    override fun verifyJwsObject(jwsObject: JwsSigned): Boolean {
        val header = jwsObject.header
        val publicKey = header.publicKey
            ?: header.jsonWebKeySetUrl?.let { jku -> retrieveJwkFromKeySetUrl(jku, header) }
        return if (publicKey != null) {
            verify(jwsObject, publicKey)
        } else publicKeyLookup(jwsObject)?.let { jwks ->
            jwks.mapNotNull { jwk -> jwk.toCryptoPublicKey().getOrNull() }
                .any { pubKey -> verify(jwsObject, pubKey) }
        } ?: false
            .also { Napier.w("Could not extract PublicKey from header: $header") }
    }

    private fun retrieveJwkFromKeySetUrl(jku: String, header: JwsHeader) =
        jwkSetRetriever(jku)?.keys?.firstOrNull { it.keyId == header.keyId }?.toCryptoPublicKey()?.getOrNull()

    /**
     * Verifiers the signature of [jwsObject] by using [signer].
     */
    override fun verifyJws(jwsObject: JwsSigned, signer: JsonWebKey): Boolean {
        val publicKey = signer.toCryptoPublicKey().getOrNull()
            ?: return false
                .also { Napier.w("Could not convert signer to public key: $signer") }
        return verify(jwsObject, publicKey)
    }

    private fun verify(jwsObject: JwsSigned, publicKey: CryptoPublicKey): Boolean = catching {
        cryptoService.verify(
            input = jwsObject.plainSignatureInput.encodeToByteArray(),
            signature = jwsObject.signature,
            algorithm = jwsObject.header.algorithm.toX509SignatureAlgorithm().getOrThrow(),
            publicKey = publicKey,
        ).getOrThrow()
    }.fold(onSuccess = { true }, onFailure = {
        Napier.w("No verification from native code", it)
        false
    })
}


private data class CompositeKey(
    val aesKey: ByteArray,
    val hmacKey: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CompositeKey

        if (!aesKey.contentEquals(other.aesKey)) return false
        if (hmacKey != null) {
            if (other.hmacKey == null) return false
            if (!hmacKey.contentEquals(other.hmacKey)) return false
        } else if (other.hmacKey != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = aesKey.contentHashCode()
        result = 31 * result + (hmacKey?.contentHashCode() ?: 0)
        return result
    }
}




