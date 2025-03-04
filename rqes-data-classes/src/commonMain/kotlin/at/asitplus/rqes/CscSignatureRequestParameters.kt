package at.asitplus.rqes

import at.asitplus.openid.Hashes
import at.asitplus.openid.contentEquals
import at.asitplus.openid.contentHashCode
import at.asitplus.rqes.collection_entries.CscDocumentDigest
import at.asitplus.rqes.enums.OperationMode
import at.asitplus.openid.SignatureQualifier
import at.asitplus.rqes.collection_entries.Document
import at.asitplus.rqes.serializers.CscSignatureRequestParameterSerializer
import at.asitplus.signum.indispensable.Digest
import at.asitplus.signum.indispensable.SignatureAlgorithm
import at.asitplus.signum.indispensable.asn1.Asn1Element
import at.asitplus.signum.indispensable.asn1.ObjectIdentifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable(with = CscSignatureRequestParameterSerializer::class)
sealed interface CscSignatureRequestParameters {
    val credentialId: String?
    val sad: String?
    val operationMode: OperationMode?
    val validityPeriod: Int?
    val responseUri: String?
    val clientData: String?
}

@Serializable
data class SignHashParameters(
    /**
     * REQUIRED.
     * The credentialID as defined in the Input parameter table in `/credentials/info`
     */
    @SerialName("credentialID")
    override val credentialId: String,

    /**
     * REQUIRED-CONDITIONAL.
     * The Signature Activation Data returned by the Credential Authorization
     * methods. Not needed if the signing application has passed an access token in
     * the “Authorization” HTTP header with scope “credential”, which is also good for
     * the credential identified by credentialID.
     * Note: For backward compatibility, signing applications MAY pass access tokens
     * with scope “credential” in this parameter.
     */
    @SerialName("SAD")
    override val sad: String? = null,

    /**
     * OPTIONAL.
     * The type of operation mode requested to the remote signing server
     * The default value is “S”, so if the parameter is omitted then the remote signing
     * server will manage the request in synchronous operation mode.
     */
    @SerialName("operationMode")
    override val operationMode: OperationMode? = OperationMode.SYNCHRONOUS,

    /**
     * OPTIONAL-CONDITIONAL.
     * Maximum period of time, expressed in milliseconds, until which the server
     * SHALL keep the request outcome(s) available for the client application retrieval.
     * This parameter MAY be specified only if the parameter operationMode is “A”.
     */
    @SerialName("validity_period")
    override val validityPeriod: Int? = null,

    /**
     * OPTIONAL-CONDITIONAL.
     * Value of one location where the server will notify the signature creation
     * operation completion, as an URI value. This parameter MAY be specified only if
     * the parameter operationMode is “A”.
     */
    @SerialName("response_uri")
    override val responseUri: String? = null,

    /**
     * OPTIONAL.
     * Arbitrary data from the signature application. It can be used to handle a
     * transaction identifier or other application-spe cific data that may be useful for
     * debugging purposes
     */
    @SerialName("clientData")
    override val clientData: String? = null,

    /**
     * REQUIRED.
     * Input-type is JsonArray - do not use HashesSerializer!
     * One or more base64-encoded hash values to be signed
     */
    @SerialName("hashes")
    val hashes: Hashes,

    /**
     * REQUIRED-CONDITIONAL.
     * String containing the OID of the hash algorithm used to generate the hashes
     */
    @SerialName("hashAlgorithmOID")
    val hashAlgorithmOid: ObjectIdentifier? = null,

    /**
     * REQUIRED.
     * The OID of the algorithm to use for signing. It SHALL be one of the values
     * allowed by the credential as returned in keyAlgo as defined in `credentials/info` or as defined
     * in `credentials/list`
     */
    @SerialName("signAlgo")
    val signAlgoOid: ObjectIdentifier,

    /**
     * REQUIRED-CONDIIONAL.
     * The Base64-encoded DER-encoded ASN.1 signature algorithm parameters if required by
     * the signature algorithm - Necessary for RSASSA-PSS for example
     */
    @SerialName("signAlgoParams")
    @Serializable(with = at.asitplus.rqes.serializers.Asn1EncodableBase64Serializer::class)
    val signAlgoParams: Asn1Element? = null,

    ) : CscSignatureRequestParameters {

    @Transient
    val signAlgorithm: SignatureAlgorithm? = signAlgoOid.getSignAlgorithm(signAlgoParams)

    @Transient
    val hashAlgorithm: Digest = hashAlgorithmOid.getHashAlgorithm(signAlgorithm)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SignHashParameters
        if (!hashes.contentEquals(other.hashes)) return false
        if (credentialId != other.credentialId) return false
        if (sad != other.sad) return false
        if (operationMode != other.operationMode) return false
        if (validityPeriod != other.validityPeriod) return false
        if (responseUri != other.responseUri) return false
        if (clientData != other.clientData) return false
        if (hashAlgorithmOid != other.hashAlgorithmOid) return false
        if (signAlgoOid != other.signAlgoOid) return false
        if (signAlgoParams != other.signAlgoParams) return false
        if (signAlgorithm != other.signAlgorithm) return false
        if (hashAlgorithm != other.hashAlgorithm) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hashes.contentHashCode()
        result = 31 * result + (sad?.hashCode() ?: 0)
        result = 31 * result + operationMode.hashCode()
        result = 31 * result + (validityPeriod ?: 0)
        result = 31 * result + (responseUri?.hashCode() ?: 0)
        result = 31 * result + (clientData?.hashCode() ?: 0)
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + (hashAlgorithmOid?.hashCode() ?: 0)
        result = 31 * result + signAlgoOid.hashCode()
        result = 31 * result + (signAlgoParams?.hashCode() ?: 0)
        result = 31 * result + (signAlgorithm?.hashCode() ?: 0)
        result = 31 * result + hashAlgorithm.hashCode()
        return result
    }
}

@Serializable
data class SignDocParameters(
    /**
     * REQUIRED-CONDITIONAL.
     * The credentialID as defined in the Input parameter table in `/credentials/info`
     * At least one of the two values credentialID and signatureQualifier SHALL be
     * present. Both values MAY be present.
     */
    @SerialName("credentialID")
    override val credentialId: String? = null,

    /**
     * REQUIRED-CONDITIONAL.
     * The Signature Activation Data returned by the Credential Authorization
     * methods. Not needed if the signing application has passed an access token in
     * the “Authorization” HTTP header with scope “credential”, which is also good for
     * the credential identified by credentialID.
     * Note: For backward compatibility, signing applications MAY pass access tokens
     * with scope “credential” in this parameter.
     */
    @SerialName("SAD")
    override val sad: String? = null,

    /**
     * OPTIONAL.
     * The type of operation mode requested to the remote signing server
     * The default value is “S”, so if the parameter is omitted then the remote signing
     * server will manage the request in synchronous operation mode.
     */
    @SerialName("operationMode")
    override val operationMode: OperationMode? = OperationMode.SYNCHRONOUS,


    /**
     * OPTIONAL-CONDITIONAL.
     * Maximum period of time, expressed in milliseconds, until which the server
     * SHALL keep the request outcome(s) available for the client application retrieval.
     * This parameter MAY be specified only if the parameter operationMode is “A”.
     */
    @SerialName("validity_period")
    override val validityPeriod: Int? = null,

    /**
     * OPTIONAL-CONDITIONAL.
     * Value of one location where the server will notify the signature creation
     * operation completion, as an URI value. This parameter MAY be specified only if
     * the parameter operationMode is “A”.
     */
    @SerialName("response_uri")
    override val responseUri: String? = null,

    /**
     * OPTIONAL.
     * Arbitrary data from the signature application. It can be used to handle a
     * transaction identifier or other application-spe cific data that may be useful for
     * debugging purposes
     */
    @SerialName("clientData")
    override val clientData: String? = null,

    /**
     * REQUIRED-CONDITIONAL.
     * Identifier of the signature type to be created, e.g. “eu_eidas_qes” to denote
     * a Qualified Electronic Signature according to eIDAS
     */
    @SerialName("signatureQualifier")
    val signatureQualifier: SignatureQualifier? = null,

    /**
     * REQUIRED-CONDITIONAL.
     * An array containing JSON objects containing a hash value representing one or
     * more SDRs, the respective digest algorithm OID used to calculate this hash
     * value and further request parameters. This parameter or the
     * parameter documents MUST be present in a request.
     */
    @SerialName("documentDigests")
    val documentDigests: Collection<CscDocumentDigest>? = null,

    /**
     * REQUIRED-CONDITIONAL.
     * An array containing JSON objects, each of them containing a base64-encoded
     * document content to be signed and further request parameter. This
     * parameter or the parameter documentDigests MUST be present in a request.
     */
    @SerialName("documents")
    val documents: Collection<Document>? = null,

    /**
     * OPTIONAL.
     * This parameter SHALL be set to “true” to request the service to return the
     * “validationInfo”. The default value is “false”, i.e. no
     * “validationInfo” info is provided.
     */
    @SerialName("returnValidationInformation")
    val returnValidationInformation: Boolean? = false,

    ) : CscSignatureRequestParameters {
    init {
        require(credentialId != null || signatureQualifier != null) { "Either credentialId or signatureQualifier must not be null (both can be present)" }
        require(documentDigests != null || documents != null) { "Either documentDigests or documents must not be null (both can be present)" }
    }
}