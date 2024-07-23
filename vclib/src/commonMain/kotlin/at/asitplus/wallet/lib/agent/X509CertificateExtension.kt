package at.asitplus.wallet.lib.agent


import at.asitplus.KmmResult
import at.asitplus.KmmResult.Companion.wrap
import at.asitplus.crypto.datatypes.CryptoPublicKey
import at.asitplus.crypto.datatypes.CryptoSignature
import at.asitplus.crypto.datatypes.X509SignatureAlgorithm
import at.asitplus.crypto.datatypes.asn1.Asn1String
import at.asitplus.crypto.datatypes.asn1.Asn1Time
import at.asitplus.crypto.datatypes.pki.AttributeTypeAndValue
import at.asitplus.crypto.datatypes.pki.RelativeDistinguishedName
import at.asitplus.crypto.datatypes.pki.TbsCertificate
import at.asitplus.crypto.datatypes.pki.X509Certificate
import at.asitplus.crypto.datatypes.pki.X509CertificateExtension
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.random.Random

fun X509Certificate.Companion.generateSelfSignedCertificate(
    publicKey: CryptoPublicKey,
    algorithm: X509SignatureAlgorithm,
    extensions: List<X509CertificateExtension> = listOf(),
    signer: suspend (ByteArray) -> KmmResult<CryptoSignature>,
): X509Certificate {
    val notBeforeDate = Clock.System.now()
    val notAfterDate = notBeforeDate.plus(30, DateTimeUnit.SECOND)
    val tbsCertificate = TbsCertificate(
        version = 2,
        serialNumber = Random.nextBytes(8),
        issuerName = listOf(RelativeDistinguishedName(AttributeTypeAndValue.CommonName(Asn1String.UTF8("Default")))),
        validFrom = Asn1Time(notBeforeDate),
        validUntil = Asn1Time(notAfterDate),
        signatureAlgorithm = algorithm,
        subjectName = listOf(RelativeDistinguishedName(AttributeTypeAndValue.CommonName(Asn1String.UTF8("Default")))),
        publicKey = publicKey,
        extensions = extensions
    )
    val signature = runBlocking {
        runCatching { tbsCertificate.encodeToDer() }
            .wrap()
            .transform { signer(it) }
            .getOrThrow()
    }
    return X509Certificate(tbsCertificate, algorithm, signature)
}