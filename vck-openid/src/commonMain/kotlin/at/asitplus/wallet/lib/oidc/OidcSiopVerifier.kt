package at.asitplus.wallet.lib.oidc

import at.asitplus.KmmResult
import at.asitplus.catching
import at.asitplus.dif.*
import at.asitplus.jsonpath.JsonPath
import at.asitplus.jsonpath.core.NormalizedJsonPath
import at.asitplus.jsonpath.core.NormalizedJsonPathSegment
import at.asitplus.openid.*
import at.asitplus.openid.OpenIdConstants.BINDING_METHOD_JWK
import at.asitplus.openid.OpenIdConstants.ClientIdScheme.*
import at.asitplus.openid.OpenIdConstants.ID_TOKEN
import at.asitplus.openid.OpenIdConstants.PREFIX_DID_KEY
import at.asitplus.openid.OpenIdConstants.SCOPE_OPENID
import at.asitplus.openid.OpenIdConstants.SCOPE_PROFILE
import at.asitplus.openid.OpenIdConstants.URN_TYPE_JWK_THUMBPRINT
import at.asitplus.openid.OpenIdConstants.VP_TOKEN
import at.asitplus.signum.indispensable.cosef.io.ByteStringWrapper
import at.asitplus.signum.indispensable.io.Base64UrlStrict
import at.asitplus.signum.indispensable.josef.*
import at.asitplus.signum.indispensable.pki.CertificateChain
import at.asitplus.wallet.lib.agent.*
import at.asitplus.wallet.lib.cbor.DefaultVerifierCoseService
import at.asitplus.wallet.lib.cbor.VerifierCoseService
import at.asitplus.wallet.lib.data.*
import at.asitplus.wallet.lib.data.ConstantIndex.supportsSdJwt
import at.asitplus.wallet.lib.data.ConstantIndex.supportsVcJwt
import at.asitplus.wallet.lib.iso.*
import at.asitplus.wallet.lib.jws.*
import at.asitplus.wallet.lib.oidvci.*
import at.asitplus.wallet.lib.openid.ResponseParser
import com.benasher44.uuid.uuid4
import io.github.aakira.napier.Napier
import io.ktor.http.*
import io.matthewnelson.encoding.base16.Base16
import io.matthewnelson.encoding.core.Decoder.Companion.decodeToByteArray
import io.matthewnelson.encoding.core.Decoder.Companion.decodeToByteArrayOrNull
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToString
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit
import kotlin.time.toDuration


/**
 * Combines Verifiable Presentations with OpenId Connect.
 * Implements [OIDC for VP](https://openid.net/specs/openid-connect-4-verifiable-presentations-1_0.html) (2023-04-21)
 * as well as [SIOP V2](https://openid.net/specs/openid-connect-self-issued-v2-1_0.html) (2023-01-01).
 *
 * This class creates the Authentication Request, [verifier] verifies the response. See [OidcSiopWallet] for the holder.
 */
@Deprecated(
    message = "Replace with OpenId4VpVerifier",
    ReplaceWith("OpenId4VpVerifier", "at.asitplus.wallet.lib.openid")
)
class OidcSiopVerifier(
    private val clientIdScheme: ClientIdScheme,
    private val keyMaterial: KeyMaterial = EphemeralKeyWithoutCert(),
    private val verifier: Verifier = VerifierAgent(identifier = clientIdScheme.clientId),
    private val jwsService: JwsService = DefaultJwsService(DefaultCryptoService(keyMaterial)),
    private val verifierJwsService: VerifierJwsService = DefaultVerifierJwsService(DefaultVerifierCryptoService()),
    private val verifierCoseService: VerifierCoseService = DefaultVerifierCoseService(DefaultVerifierCryptoService()),
    timeLeewaySeconds: Long = 300L,
    private val clock: Clock = Clock.System,
    private val nonceService: NonceService = DefaultNonceService(),
    /** Used to store issued authn requests, to verify the authn response to it */
    private val stateToAuthnRequestStore: MapStore<String, AuthenticationRequestParameters> = DefaultMapStore(),
) {

    private val responseParser = ResponseParser(jwsService, verifierJwsService)
    private val timeLeeway = timeLeewaySeconds.toDuration(DurationUnit.SECONDS)

    @Deprecated(
        message = "Replace with external class",
        ReplaceWith("ClientIdScheme", "at.asitplus.wallet.lib.openid")
    )
    sealed class ClientIdScheme(
        val scheme: OpenIdConstants.ClientIdScheme,
        open val clientId: String,
    ) {
        /**
         * This Client Identifier Scheme allows the Verifier to authenticate using a JWT that is bound to a certain
         * public key. When the Client Identifier Scheme is `verifier_attestation`, the Client Identifier MUST equal
         * the `sub` claim value in the Verifier attestation JWT. The request MUST be signed with the private key
         * corresponding to the public key in the `cnf` claim in the Verifier attestation JWT. This serves as proof of
         * possession of this key. The Verifier attestation JWT MUST be added to the `jwt` JOSE Header of the request
         * object. The Wallet MUST validate the signature on the Verifier attestation JWT. The `iss` claim value of the
         * Verifier Attestation JWT MUST identify a party the Wallet trusts for issuing Verifier Attestation JWTs.
         * If the Wallet cannot establish trust, it MUST refuse the request. If the issuer of the Verifier Attestation
         * JWT adds a `redirect_uris` claim to the attestation, the Wallet MUST ensure the `redirect_uri` request
         * parameter value exactly matches one of the `redirect_uris` claim entries. All Verifier metadata other than
         * the public key MUST be obtained from the `client_metadata` parameter.
         */
        data class VerifierAttestation(
            val attestationJwt: JwsSigned<JsonWebToken>,
            override val clientId: String,
        ) : ClientIdScheme(VerifierAttestation, attestationJwt.payload.subject!!)

        /**
         * When the Client Identifier Scheme is x509_san_dns, the Client Identifier MUST be a DNS name and match a
         * `dNSName` Subject Alternative Name (SAN) [RFC5280](https://www.rfc-editor.org/info/rfc5280) entry in the leaf
         * certificate passed with the request. The request MUST be signed with the private key corresponding to the
         * public key in the leaf X.509 certificate of the certificate chain added to the request in the `x5c` JOSE
         * header [RFC7515](https://www.rfc-editor.org/info/rfc7515) of the signed request object.
         *
         * The Wallet MUST validate the signature and the trust chain of the X.509 certificate.
         * All Verifier metadata other than the public key MUST be obtained from the `client_metadata` parameter.
         * If the Wallet can establish trust in the Client Identifier authenticated through the certificate, e.g.
         * because the Client Identifier is contained in a list of trusted Client Identifiers, it may allow the client
         * to freely choose the `redirect_uri` value. If not, the FQDN of the `redirect_uri` value MUST match the
         * Client Identifier.
         */
        data class CertificateSanDns(
            val chain: CertificateChain,
            override val clientId: String,
        ) : ClientIdScheme(X509SanDns, clientId)

        /**
         * This value indicates that the Verifier's Redirect URI (or Response URI when Response Mode `direct_post` is
         * used) is also the value of the Client Identifier. The Authorization Request MUST NOT be signed.
         * The Verifier MAY omit the `redirect_uri` Authorization Request parameter (or `response_uri` when Response
         * Mode `direct_post` is used). All Verifier metadata parameters MUST be passed using the `client_metadata`
         * parameter.
         */
        data class RedirectUri(
            override val clientId: String,
        ) : ClientIdScheme(RedirectUri, clientId)

        /**
         *  This value represents the RFC6749 default behavior, i.e., the Client Identifier needs to be known to the
         *  Wallet in advance of the Authorization Request. The Verifier metadata is obtained using RFC7591 or through
         *  out-of-band mechanisms.
         */
        data class PreRegistered(
            override val clientId: String,
        ) : ClientIdScheme(PreRegistered, clientId)
    }

    private val supportedAlgorithms = verifierJwsService.supportedAlgorithms.map { it.identifier }
    private val containerJwt = FormatContainerJwt(algorithmStrings = supportedAlgorithms)
    private val containerSdJwt = FormatContainerSdJwt(
        algorithmStrings = supportedAlgorithms.toSet(),
        sdJwtAlgorithmStrings = supportedAlgorithms.toSet(),
        kbJwtAlgorithmStrings = supportedAlgorithms.toSet()
    )

    /**
     * Serve this result JSON-serialized under `/.well-known/jar-issuer`
     * (see [OpenIdConstants.PATH_WELL_KNOWN_JAR_ISSUER]),
     * so that SIOP Wallets can look up the keys used to sign request objects.
     */
    val jarMetadata: JwtVcIssuerMetadata by lazy {
        JwtVcIssuerMetadata(
            issuer = clientIdScheme.clientId,
            jsonWebKeySet = JsonWebKeySet(setOf(jwsService.keyMaterial.jsonWebKey))
        )
    }

    /**
     * Creates the [RelyingPartyMetadata], without encryption (see [metadataWithEncryption])
     */
    val metadata by lazy {
        RelyingPartyMetadata(
            redirectUris = listOfNotNull((clientIdScheme as? ClientIdScheme.RedirectUri)?.clientId),
            jsonWebKeySet = JsonWebKeySet(listOf(keyMaterial.publicKey.toJsonWebKey())),
            subjectSyntaxTypesSupported = setOf(URN_TYPE_JWK_THUMBPRINT, PREFIX_DID_KEY, BINDING_METHOD_JWK),
            vpFormats = FormatHolder(
                msoMdoc = containerJwt,
                jwtVp = containerJwt,
                jwtSd = containerSdJwt,
                sdJwt = containerSdJwt,
            )
        )
    }

    /**
     * Creates the [RelyingPartyMetadata], but with parameters set to request encryption of pushed authentication
     * responses, see [RelyingPartyMetadata.authorizationEncryptedResponseAlg]
     * and [RelyingPartyMetadata.authorizationEncryptedResponseEncoding].
     */
    val metadataWithEncryption by lazy {
        metadata.copy(
            authorizationEncryptedResponseAlgString = jwsService.encryptionAlgorithm.identifier,
            authorizationEncryptedResponseEncodingString = jwsService.encryptionEncoding.text
        )
    }

    /**
     * Create a URL to be displayed as a static QR code for Wallet initiation.
     * URL is the [walletUrl], with query parameters appended for [clientMetadataUrl], [requestUrl] and
     * [ClientIdScheme.clientId] from [clientIdScheme].
     */
    fun createQrCodeUrl(
        walletUrl: String,
        clientMetadataUrl: String,
        requestUrl: String,
    ): String {
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = clientIdScheme.clientId,
            clientMetadataUri = clientMetadataUrl,
            requestUri = requestUrl,
        ).encodeToParameters()
            .forEach { urlBuilder.parameters.append(it.key, it.value) }
        return urlBuilder.buildString()
    }

    /**
     * Creates a JWS containing signed [RelyingPartyMetadata],
     * to be served under a `client_metadata_uri` at the Verifier.
     */
    suspend fun createSignedMetadata(): KmmResult<JwsSigned<RelyingPartyMetadata>> =
        jwsService.createSignedJwsAddingParams(
            payload = metadata,
            serializer = RelyingPartyMetadata.serializer(),
            addKeyId = true,
            addX5c = false
        )

    @Deprecated(
        message = "Replace with external class",
        ReplaceWith("RequestOptions", "at.asitplus.wallet.lib.openid")
    )
    data class RequestOptions(
        /**
         * Requested credentials, should be at least one
         */
        val credentials: Set<RequestOptionsCredential>,
        /**
         * Response mode to request, see [OpenIdConstants.ResponseMode],
         * by default [OpenIdConstants.ResponseMode.Fragment].
         * Setting this to any other value may require setting [responseUrl] too.
         */
        val responseMode: OpenIdConstants.ResponseMode = OpenIdConstants.ResponseMode.Fragment,
        /**
         * Response URL to set in the [AuthenticationRequestParameters.responseUrl],
         * required if [responseMode] is set to [OpenIdConstants.ResponseMode.DirectPost] or
         * [OpenIdConstants.ResponseMode.DirectPostJwt].
         */
        val responseUrl: String? = null,
        /**
         * Response type to set in [AuthenticationRequestParameters.responseType],
         * by default only `vp_token` (as per OpenID4VP spec).
         * Be sure to separate values by a space, e.g. `vp_token id_token`.
         */
        val responseType: String = VP_TOKEN,
        /**
         * Opaque value which will be returned by the OpenId Provider and also in [AuthnResponseResult]
         */
        val state: String = uuid4().toString(),
        /**
         * Optional URL to include [metadata] by reference instead of by value (directly embedding in authn request)
         */
        val clientMetadataUrl: String? = null,
        /**
         * Set this value to include metadata with encryption parameters set. Beware if setting this value and also
         * [clientMetadataUrl], that the URL shall point to [OidcSiopVerifier.metadataWithEncryption].
         */
        val encryption: Boolean = false,
    )

    data class RequestOptionsCredential(
        /**
         * Credential type to request, or `null` to make no restrictions
         */
        val credentialScheme: ConstantIndex.CredentialScheme,
        /**
         * Required representation, see [ConstantIndex.CredentialRepresentation]
         */
        val representation: ConstantIndex.CredentialRepresentation = ConstantIndex.CredentialRepresentation.PLAIN_JWT,
        /**
         * List of attributes that shall be requested explicitly (selective disclosure),
         * or `null` to make no restrictions
         */
        val requestedAttributes: List<String>? = null,
        /**
         * List of attributes that shall be requested explicitly (selective disclosure),
         * but are not required (i.e. marked as optional),
         * or `null` to make no restrictions
         */
        val requestedOptionalAttributes: List<String>? = null,
    )

    /**
     * Creates an OIDC Authentication Request, encoded as query parameters to the [walletUrl].
     */
    @Deprecated(
        "Replace with new class",
        ReplaceWith("createAuthnRequest(requestOptions, OpenId4VpVerifier.CreationOptions.Query(walletUrl)).getOrThrow().url")
    )
    suspend fun createAuthnRequestUrl(
        walletUrl: String,
        requestOptions: RequestOptions,
    ): String {
        val urlBuilder = URLBuilder(walletUrl)
        createAuthnRequest(requestOptions).encodeToParameters()
            .forEach { urlBuilder.parameters.append(it.key, it.value) }
        return urlBuilder.buildString()
    }

    /**
     * Creates an OIDC Authentication Request, encoded as query parameters to the [walletUrl],
     * containing a JWS Authorization Request (JAR, RFC9101) in `request`, containing the request parameters itself.
     */
    @Deprecated(
        "Replace with new class",
        ReplaceWith("createAuthnRequest(requestOptions, OpenId4VpVerifier.CreationOptions.SignedRequestByValue(walletUrl)).url")
    )
    suspend fun createAuthnRequestUrlWithRequestObject(
        walletUrl: String,
        requestOptions: RequestOptions,
    ): KmmResult<String> = catching {
        val jar = createAuthnRequestAsSignedRequestObject(requestOptions).getOrThrow()
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = clientIdScheme.clientId,
            request = jar.serialize(),
        ).encodeToParameters()
            .forEach { urlBuilder.parameters.append(it.key, it.value) }
        urlBuilder.buildString()
    }

    /**
     * Creates an OIDC Authentication Request, encoded as query parameters to the [walletUrl],
     * containing a reference (`request_uri`, see [AuthenticationRequestParameters.requestUri]) to the
     * JWS Authorization Request (JAR, RFC9101), containing the request parameters itself.
     *
     * @param requestUrl the URL where the request itself can be loaded by the client
     * @return The URL to display to the Wallet, and the JWS that shall be made accessible under [requestUrl]
     */
    @Deprecated(
        "Replace with new class",
        ReplaceWith("createAuthnRequest(requestOptions, OpenId4VpVerifier.CreationOptions.SignedRequestByReference(walletUrl, requestUrl))")
    )
    suspend fun createAuthnRequestUrlWithRequestObjectByReference(
        walletUrl: String,
        requestUrl: String,
        requestOptions: RequestOptions,
    ): KmmResult<Pair<String, String>> = catching {
        val jar = createAuthnRequestAsSignedRequestObject(requestOptions).getOrThrow()
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = clientIdScheme.clientId,
            requestUri = requestUrl,
        ).encodeToParameters()
            .forEach { urlBuilder.parameters.append(it.key, it.value) }
        urlBuilder.buildString() to jar.serialize()
    }

    /**
     * Creates an JWS Authorization Request (JAR, RFC9101), wrapping the usual [AuthenticationRequestParameters].
     *
     * To use this for an Authentication Request with `request_uri`, use the following code,
     * `jar` being the result of this function:
     * ```
     * val urlToSendToWallet = io.ktor.http.URLBuilder(walletUrl).apply {
     *    parameters.append("client_id", clientId)
     *    parameters.append("request_uri", requestUrl)
     * }.buildString()
     * // on an GET to requestUrl, return `jar.serialize()`
     * ```
     */
    @Deprecated(
        "Replace with new class",
        ReplaceWith("createAuthnRequest(requestOptions, OpenId4VpVerifier.CreationOptions.SignedRequestByReference(walletUrl, requestUrl))")
    )
    suspend fun createAuthnRequestAsSignedRequestObject(
        requestOptions: RequestOptions,
    ): KmmResult<JwsSigned<AuthenticationRequestParameters>> = catching {
        val requestObject = createAuthnRequest(requestOptions)
        val attestationJwt = (clientIdScheme as? ClientIdScheme.VerifierAttestation)?.attestationJwt?.serialize()
        val certificateChain = (clientIdScheme as? ClientIdScheme.CertificateSanDns)?.chain
        val issuer = (clientIdScheme as? ClientIdScheme.PreRegistered)?.clientId ?: "https://self-issued.me/v2"
        jwsService.createSignedJwsAddingParams(
            header = JwsHeader(
                algorithm = jwsService.algorithm,
                attestationJwt = attestationJwt,
                certificateChain = certificateChain,
                type = JwsContentTypeConstants.OAUTH_AUTHZ_REQUEST
            ),
            payload = requestObject.copy(audience = "https://self-issued.me/v2", issuer = issuer),
            serializer = AuthenticationRequestParameters.serializer(),
            addJsonWebKey = certificateChain == null,
        ).getOrThrow()
    }

    /**
     * Creates [AuthenticationRequestParameters], to be encoded as query params appended to the URL of the Wallet,
     * e.g. `https://example.com?repsonse_type=...` (see [createAuthnRequestUrl])
     *
     * Callers may serialize the result with `result.encodeToParameters().formUrlEncode()`
     */
    suspend fun createAuthnRequest(
        requestOptions: RequestOptions,
    ) = AuthenticationRequestParameters(
        responseType = requestOptions.responseType,
        clientId = clientIdScheme.clientId,
        redirectUrl = if (!requestOptions.isAnyDirectPost) clientIdScheme.clientId else null,
        responseUrl = requestOptions.responseUrl,
        clientIdScheme = clientIdScheme.scheme,
        scope = requestOptions.buildScope(),
        nonce = nonceService.provideNonce(),
        clientMetadata = if (requestOptions.clientMetadataUrl != null) {
            null
        } else {
            if (requestOptions.encryption) metadataWithEncryption else metadata
        },
        clientMetadataUri = requestOptions.clientMetadataUrl,
        idTokenType = IdTokenType.SUBJECT_SIGNED.text,
        responseMode = requestOptions.responseMode,
        state = requestOptions.state,
        presentationDefinition = PresentationDefinition(
            id = uuid4().toString(),
            inputDescriptors = requestOptions.credentials.map { it.toInputDescriptor() },
        ),
    ).also { stateToAuthnRequestStore.put(requestOptions.state, it) }

    private fun RequestOptions.buildScope() = (
            listOf(SCOPE_OPENID, SCOPE_PROFILE)
                    + credentials.mapNotNull { it.credentialScheme.sdJwtType }
                    + credentials.mapNotNull { it.credentialScheme.vcType }
                    + credentials.mapNotNull { it.credentialScheme.isoNamespace }
            ).joinToString(" ")

    private val RequestOptions.isAnyDirectPost
        get() = (responseMode == OpenIdConstants.ResponseMode.DirectPost) ||
                (responseMode == OpenIdConstants.ResponseMode.DirectPostJwt)

    //TODO extend for InputDescriptor interface in case QES
    private fun RequestOptionsCredential.toInputDescriptor() = DifInputDescriptor(
        id = buildId(),
        format = toFormatHolder(),
        constraints = toConstraint(),
    )

    /**
     * doctype is not really an attribute that can be presented,
     * encoding it into the descriptor id as in the following non-normative example fow now:
     * https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#appendix-A.3.1-4
     */
    private fun RequestOptionsCredential.buildId() =
        if (credentialScheme.isoDocType != null && representation == ConstantIndex.CredentialRepresentation.ISO_MDOC)
            credentialScheme.isoDocType!! else uuid4().toString()

    private fun RequestOptionsCredential.toConstraint() =
        Constraint(fields = (requiredAttributes() + optionalAttributes() + toTypeConstraint()).filterNotNull())

    private fun RequestOptionsCredential.requiredAttributes() =
        requestedAttributes?.createConstraints(representation, credentialScheme, false)
            ?: listOf()

    private fun RequestOptionsCredential.optionalAttributes() =
        requestedOptionalAttributes?.createConstraints(representation, credentialScheme, true)
            ?: listOf()

    private fun RequestOptionsCredential.toTypeConstraint() = when (representation) {
        ConstantIndex.CredentialRepresentation.PLAIN_JWT -> this.credentialScheme.toVcConstraint()
        ConstantIndex.CredentialRepresentation.SD_JWT -> this.credentialScheme.toSdJwtConstraint()
        ConstantIndex.CredentialRepresentation.ISO_MDOC -> null
    }

    private fun RequestOptionsCredential.toFormatHolder() = when (representation) {
        ConstantIndex.CredentialRepresentation.PLAIN_JWT -> FormatHolder(jwtVp = containerJwt)
        ConstantIndex.CredentialRepresentation.SD_JWT -> FormatHolder(jwtSd = containerSdJwt, sdJwt = containerSdJwt)
        ConstantIndex.CredentialRepresentation.ISO_MDOC -> FormatHolder(msoMdoc = containerJwt)
    }

    private fun ConstantIndex.CredentialScheme.toVcConstraint() = if (supportsVcJwt)
        ConstraintField(
            path = listOf("$.type"),
            filter = ConstraintFilter(
                type = "string",
                pattern = vcType,
            )
        ) else null

    private fun ConstantIndex.CredentialScheme.toSdJwtConstraint() = if (supportsSdJwt)
        ConstraintField(
            path = listOf("$.vct"),
            filter = ConstraintFilter(
                type = "string",
                pattern = sdJwtType!!
            )
        ) else null

    private fun List<String>.createConstraints(
        representation: ConstantIndex.CredentialRepresentation,
        credentialScheme: ConstantIndex.CredentialScheme?,
        optional: Boolean,
    ): Collection<ConstraintField> = map {
        if (representation == ConstantIndex.CredentialRepresentation.ISO_MDOC)
            credentialScheme.toConstraintField(it, optional)
        else
            ConstraintField(path = listOf("\$[${it.quote()}]"), optional = optional)
    }

    private fun ConstantIndex.CredentialScheme?.toConstraintField(
        attributeType: String,
        optional: Boolean,
    ) = ConstraintField(
        path = listOf(
            NormalizedJsonPath(
                NormalizedJsonPathSegment.NameSegment(this?.isoNamespace ?: "mdoc"),
                NormalizedJsonPathSegment.NameSegment(attributeType),
            ).toString()
        ),
        intentToRetain = false,
        optional = optional,
    )


    sealed class AuthnResponseResult {
        /**
         * Error in parsing the URL or content itself, before verifying the contents of the OpenId response
         */
        data class Error(val reason: String, val state: String?) : AuthnResponseResult()

        /**
         * Error when validating the `vpToken` or `idToken`
         */
        data class ValidationError(val field: String, val state: String?) : AuthnResponseResult()

        /**
         * Wallet provided an `id_token`, no `vp_token` (as requested by us!)
         */
        data class IdToken(val idToken: at.asitplus.openid.IdToken, val state: String?) : AuthnResponseResult()

        /**
         * Validation results of all returned verifiable presentations
         */
        data class VerifiablePresentationValidationResults(val validationResults: List<AuthnResponseResult>) :
            AuthnResponseResult()

        /**
         * Successfully decoded and validated the response from the Wallet (W3C credential)
         */
        data class Success(val vp: VerifiablePresentationParsed, val state: String?) :
            AuthnResponseResult()

        /**
         * Successfully decoded and validated the response from the Wallet (W3C credential in SD-JWT)
         */
        data class SuccessSdJwt(
            val sdJwtSigned: SdJwtSigned,
            val verifiableCredentialSdJwt: VerifiableCredentialSdJwt,
            val reconstructed: JsonObject,
            val disclosures: Collection<SelectiveDisclosureItem>,
            val state: String?,
        ) : AuthnResponseResult()

        /**
         * Successfully decoded and validated the response from the Wallet (ISO credential)
         */
        data class SuccessIso(val documents: Collection<IsoDocumentParsed>, val state: String?) :
            AuthnResponseResult()
    }

    /**
     * Validates the OIDC Authentication Response from the Wallet, where [content] are the HTTP POST encoded
     * [AuthenticationResponseParameters], e.g. `id_token=...&vp_token=...`
     */
    @Deprecated("Use validateAuthnResponse", ReplaceWith("validateAuthnResponse"))
    suspend fun validateAuthnResponseFromPost(content: String): AuthnResponseResult =
        validateAuthnResponse(content)

    /**
     * Validates an Authentication Response from the Wallet, where [input] is a map of POST parameters received.
     */
    suspend fun validateAuthnResponse(input: Map<String, String>): AuthnResponseResult {
        val paramsFrom = runCatching {
            ResponseParametersFrom.Post(input.decode<AuthenticationResponseParameters>())
        }.getOrElse {
            Napier.w("Could not parse authentication response: $input", it)
            return AuthnResponseResult.Error("Can't parse input", null)
        }
        return validateAuthnResponse(paramsFrom)
    }

    /**
     * Validates an Authentication Response from the Wallet, where [input] is either:
     * - a URL, containing parameters in the fragment, e.g. `https://example.com#id_token=...`
     * - a URL, containing parameters in the query, e.g. `https://example.com?id_token=...`
     * - parameters encoded as a POST body, e.g. `id_token=...&vp_token=...`
     */
    suspend fun validateAuthnResponse(input: String): AuthnResponseResult {
        val paramsFrom = runCatching {
            responseParser.parseAuthnResponse(input)
        }.getOrElse {
            Napier.w("Could not parse authentication response: $input", it)
            return AuthnResponseResult.Error("Can't parse input", null)
        }
        return validateAuthnResponse(paramsFrom)
    }

    /**
     * Validates [AuthenticationResponseParameters] from the Wallet
     */
    suspend fun validateAuthnResponse(input: ResponseParametersFrom): AuthnResponseResult {
        val params = input.parameters
        val state = params.state
            ?: return AuthnResponseResult.ValidationError("state", params.state)
                .also { Napier.w("Invalid state: ${params.state}") }
        val authnRequest = stateToAuthnRequestStore.get(state)
            ?: return AuthnResponseResult.ValidationError("state", state)
                .also { Napier.w("State not associated with authn request: $state") }

        val responseType = authnRequest.responseType
        if (responseType?.contains(VP_TOKEN) == true) {
            val expectedNonce = authnRequest.nonce
                ?: return AuthnResponseResult.ValidationError("state", state)
                    .also { Napier.w("State not associated with nonce: $state") }
            val presentationSubmission = params.presentationSubmission
                ?: return AuthnResponseResult.ValidationError("presentation_submission", state)
                    .also { Napier.w("presentation_submission empty") }
            val descriptors = presentationSubmission.descriptorMap
                ?: return AuthnResponseResult.ValidationError("presentation_submission", state)
                    .also { Napier.w("presentation_submission contains no descriptors") }
            val verifiablePresentation = params.vpToken
                ?: return AuthnResponseResult.ValidationError("vp_token is null", state)
                    .also { Napier.w("No VP in response") }

            val validationResults = descriptors.map { descriptor ->
                val relatedPresentation = JsonPath(descriptor.cumulativeJsonPath)
                    .query(verifiablePresentation).first().value
                val result = runCatching {
                    verifyPresentationResult(
                        descriptor,
                        relatedPresentation,
                        expectedNonce,
                        input,
                        authnRequest.clientId,
                        authnRequest.responseUrl
                    )
                }.getOrElse {
                    Napier.w("Invalid presentation format: $relatedPresentation", it)
                    return AuthnResponseResult.ValidationError("Invalid presentation", state)
                }
                result.mapToAuthnResponseResult(state)
            }
            return validationResults.firstOrList()
        }

        if (responseType?.contains(ID_TOKEN) == true) {
            val idToken = params.idToken?.let { idToken ->
                catching {
                    extractValidatedIdToken(idToken)
                }.getOrElse {
                    return AuthnResponseResult.ValidationError("idToken", state)
                }
            } ?: return AuthnResponseResult.ValidationError("idToken", state)
                .also { Napier.w("State not associated with response type: $state") }
            return AuthnResponseResult.IdToken(idToken, state)
        }

        return AuthnResponseResult.Error("Neither id_token nor vp_token", state)
    }

    private fun List<AuthnResponseResult>.firstOrList(): AuthnResponseResult =
        if (size == 1) this[0]
        else AuthnResponseResult.VerifiablePresentationValidationResults(this)

    @Throws(IllegalArgumentException::class, CancellationException::class)
    private suspend fun extractValidatedIdToken(idTokenJws: String): IdToken {
        val jwsSigned = JwsSigned.deserialize<IdToken>(IdToken.serializer(), idTokenJws, vckJsonSerializer).getOrNull()
            ?: throw IllegalArgumentException("idToken")
                .also { Napier.w("Could not parse JWS from idToken: $idTokenJws") }
        if (!verifierJwsService.verifyJwsObject(jwsSigned))
            throw IllegalArgumentException("idToken")
                .also { Napier.w { "JWS of idToken not verified: $idTokenJws" } }
        val idToken = jwsSigned.payload
        if (idToken.issuer != idToken.subject)
            throw IllegalArgumentException("idToken.iss")
                .also { Napier.d("Wrong issuer: ${idToken.issuer}, expected: ${idToken.subject}") }
        if (idToken.audience != clientIdScheme.clientId)
            throw IllegalArgumentException("idToken.aud")
                .also { Napier.d("audience not valid: ${idToken.audience}") }
        if (idToken.expiration < (clock.now() - timeLeeway))
            throw IllegalArgumentException("idToken.exp")
                .also { Napier.d("expirationDate before now: ${idToken.expiration}") }
        if (idToken.issuedAt > (clock.now() + timeLeeway))
            throw IllegalArgumentException("idToken.iat")
                .also { Napier.d("issuedAt after now: ${idToken.issuedAt}") }
        if (!nonceService.verifyAndRemoveNonce(idToken.nonce)) {
            throw IllegalArgumentException("idToken.nonce")
                .also { Napier.d("nonce not valid: ${idToken.nonce}, not known to us") }
        }
        if (idToken.subjectJwk == null)
            throw IllegalArgumentException("idToken.sub_jwk")
                .also { Napier.d("sub_jwk is null") }
        if (idToken.subject != idToken.subjectJwk!!.jwkThumbprint)
            throw IllegalArgumentException("idToken.sub")
                .also { Napier.d("subject does not equal thumbprint of sub_jwk: ${idToken.subject}") }
        return idToken
    }

    /**
     * Extract and verifies verifiable presentations, according to format defined in
     * [OpenID for VCI](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html),
     * as referenced by [OpenID for VP](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html).
     */
    @Suppress("DEPRECATION")
    private suspend fun verifyPresentationResult(
        descriptor: PresentationSubmissionDescriptor,
        relatedPresentation: JsonElement,
        expectedNonce: String,
        input: ResponseParametersFrom,
        clientId: String?,
        responseUrl: String?,
    ) = when (descriptor.format) {
        ClaimFormat.JWT_SD, ClaimFormat.SD_JWT -> verifier.verifyPresentationSdJwt(
            input = SdJwtSigned.parse(relatedPresentation.jsonPrimitive.content)
                ?: throw IllegalArgumentException("relatedPresentation"),
            challenge = expectedNonce
        )

        ClaimFormat.JWT_VP -> verifier.verifyPresentationVcJwt(
            input = JwsSigned.deserialize<VerifiablePresentationJws>(
                VerifiablePresentationJws.serializer(),
                relatedPresentation.jsonPrimitive.content,
                vckJsonSerializer
            ).getOrThrow(),
            challenge = expectedNonce
        )

        ClaimFormat.MSO_MDOC -> {
            val mdocGeneratedNonce = (input as? ResponseParametersFrom.JweDecrypted)?.jweDecrypted
                ?.header?.agreementPartyUInfo?.decodeToByteArrayOrNull(Base64UrlStrict)?.decodeToString()
            verifier.verifyPresentationIsoMdoc(
                input = relatedPresentation.jsonPrimitive.content.decodeToByteArray(Base64UrlStrict)
                    .let { DeviceResponse.deserialize(it).getOrThrow() },
                challenge = expectedNonce,
                verifyDocument = verifyDocument(mdocGeneratedNonce, clientId, responseUrl, expectedNonce)
            )
        }

        else -> throw IllegalArgumentException("descriptor.format")
    }

    /**
     * Performs verification of the [SessionTranscript] and [DeviceAuthentication],
     * acc. to ISO/IEC 18013-5:2021 and ISO/IEC 18013-7:2024, if required (i.e. response is encrypted)
     */
    @Throws(IllegalArgumentException::class)
    private fun verifyDocument(
        mdocGeneratedNonce: String?,
        clientId: String?,
        responseUrl: String?,
        expectedNonce: String,
    ): (MobileSecurityObject, Document) -> Boolean = { mso, document ->
        val deviceSignature = document.deviceSigned.deviceAuth.deviceSignature ?: run {
            Napier.w("DeviceSignature is null: ${document.deviceSigned.deviceAuth}")
            throw IllegalArgumentException("deviceSignature")
        }

        val walletKey = mso.deviceKeyInfo.deviceKey
        if (mdocGeneratedNonce != null && clientId != null && responseUrl != null) {
            val deviceAuthentication =
                document.calcDeviceAuthentication(expectedNonce, mdocGeneratedNonce, clientId, responseUrl)
            Napier.d("Device authentication is ${deviceAuthentication.encodeToString(Base16())}")
            verifierCoseService.verifyCose(
                deviceSignature,
                walletKey,
                detachedPayload = deviceAuthentication
            ).onFailure {
                Napier.w("DeviceSignature not verified: ${document.deviceSigned.deviceAuth}", it)
                throw IllegalArgumentException("deviceSignature")
            }
        } else {
            verifierCoseService.verifyCose(deviceSignature, walletKey).onFailure {
                Napier.w("DeviceSignature not verified: ${document.deviceSigned.deviceAuth}", it)
                throw IllegalArgumentException("deviceSignature")
            }
            val deviceSignaturePayload = deviceSignature.payload ?: run {
                Napier.w("DeviceSignature does not contain challenge")
                throw IllegalArgumentException("challenge")
            }
            if (!deviceSignaturePayload.contentEquals(expectedNonce.encodeToByteArray())) {
                Napier.w("DeviceSignature does not contain correct challenge")
                throw IllegalArgumentException("challenge")
            }
        }
        true
    }

    /**
     * Performs calculation of the [SessionTranscript] and [DeviceAuthentication],
     * acc. to ISO/IEC 18013-5:2021 and ISO/IEC 18013-7:2024
     */
    private fun Document.calcDeviceAuthentication(
        challenge: String,
        mdocGeneratedNonce: String,
        clientId: String,
        responseUrl: String,
    ): ByteArray {
        val clientIdToHash = ClientIdToHash(clientId = clientId, mdocGeneratedNonce = mdocGeneratedNonce)
        val responseUriToHash = ResponseUriToHash(responseUri = responseUrl, mdocGeneratedNonce = mdocGeneratedNonce)
        val sessionTranscript = SessionTranscript(
            deviceEngagementBytes = null,
            eReaderKeyBytes = null,
            handover = OID4VPHandover(
                clientIdHash = clientIdToHash.serialize().sha256(),
                responseUriHash = responseUriToHash.serialize().sha256(),
                nonce = challenge
            ),
        )
        val deviceAuthentication = DeviceAuthentication(
            type = "DeviceAuthentication",
            sessionTranscript = sessionTranscript,
            docType = docType,
            namespaces = deviceSigned.namespaces
        )
        return deviceAuthentication.serialize()
    }

    private fun Verifier.VerifyPresentationResult.mapToAuthnResponseResult(state: String) = when (this) {
        is Verifier.VerifyPresentationResult.InvalidStructure ->
            AuthnResponseResult.Error("parse vp failed", state)
                .also { Napier.w("VP error: $this") }

        is Verifier.VerifyPresentationResult.ValidationError ->
            AuthnResponseResult.ValidationError("vpToken", state)
                .also { Napier.w("VP error: $this") }

        is Verifier.VerifyPresentationResult.Success ->
            AuthnResponseResult.Success(vp, state)
                .also { Napier.i("VP success: $this") }

        is Verifier.VerifyPresentationResult.SuccessIso ->
            AuthnResponseResult.SuccessIso(documents, state)
                .also { Napier.i("VP success: $this") }

        is Verifier.VerifyPresentationResult.SuccessSdJwt ->
            AuthnResponseResult.SuccessSdJwt(
                sdJwtSigned = sdJwtSigned,
                verifiableCredentialSdJwt = verifiableCredentialSdJwt,
                reconstructed = reconstructedJsonObject,
                disclosures = disclosures,
                state = state
            ).also { Napier.i("VP success: $this") }
    }

}


private val PresentationSubmissionDescriptor.cumulativeJsonPath: String
    get() {
        var cummulativeJsonPath = this.path
        var descriptorIterator = this.nestedPath
        while (descriptorIterator != null) {
            cummulativeJsonPath += descriptorIterator.path.substring(1)
            descriptorIterator = descriptorIterator.nestedPath
        }
        return cummulativeJsonPath
    }
