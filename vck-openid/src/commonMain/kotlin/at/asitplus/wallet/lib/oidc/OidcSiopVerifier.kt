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
import at.asitplus.signum.indispensable.josef.*
import at.asitplus.signum.indispensable.pki.CertificateChain
import at.asitplus.signum.indispensable.pki.leaf
import at.asitplus.wallet.lib.agent.*
import at.asitplus.wallet.lib.data.*
import at.asitplus.wallet.lib.data.ConstantIndex.supportsSdJwt
import at.asitplus.wallet.lib.data.ConstantIndex.supportsVcJwt
import at.asitplus.wallet.lib.jws.DefaultJwsService
import at.asitplus.wallet.lib.jws.DefaultVerifierJwsService
import at.asitplus.wallet.lib.jws.JwsService
import at.asitplus.wallet.lib.jws.VerifierJwsService
import at.asitplus.wallet.lib.oidvci.*
import com.benasher44.uuid.uuid4
import io.github.aakira.napier.Napier
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
class OidcSiopVerifier private constructor(
    private val verifier: Verifier,
    private val relyingPartyUrl: String?,
    private val jwsService: JwsService,
    private val verifierJwsService: VerifierJwsService,
    timeLeewaySeconds: Long = 300L,
    private val clock: Clock = Clock.System,
    private val nonceService: NonceService = DefaultNonceService(),
    private val clientIdScheme: ClientIdScheme = ClientIdScheme.RedirectUri,
    private val stateToNonceStore: MapStore<String, String> = DefaultMapStore(),
    private val stateToResponseTypeStore: MapStore<String, String> = DefaultMapStore(),
) {

    private val timeLeeway = timeLeewaySeconds.toDuration(DurationUnit.SECONDS)

    sealed class ClientIdScheme(val clientIdScheme: OpenIdConstants.ClientIdScheme) {
        /**
         * Verifier Attestation JWT to include (in header `jwt`) when creating request objects as JWS,
         * to allow the Wallet to verify the authenticity of this Verifier.
         * OID4VP client id scheme `verifier attestation`,
         * see [at.asitplus.openid.OpenIdConstants.ClientIdScheme.VERIFIER_ATTESTATION].
         */
        data class VerifierAttestation(val attestationJwt: JwsSigned) : ClientIdScheme(VERIFIER_ATTESTATION)

        /**
         * Certificate chain to include in JWS headers and to extract `client_id` from (in SAN extension), from OID4VP
         * client id scheme `x509_san_dns`,
         * see [at.asitplus.openid.OpenIdConstants.ClientIdScheme.X509_SAN_DNS].
         */
        data class CertificateSanDns(val chain: CertificateChain) : ClientIdScheme(X509_SAN_DNS)

        /**
         * Simple: `redirect_uri` has to match `client_id`
         */
        data object RedirectUri : ClientIdScheme(REDIRECT_URI)
    }

    constructor(
        keyMaterial: KeyMaterial = EphemeralKeyWithoutCert(),
        verifier: Verifier = VerifierAgent(keyMaterial),
        relyingPartyUrl: String? = null,
        verifierJwsService: VerifierJwsService = DefaultVerifierJwsService(DefaultVerifierCryptoService()),
        jwsService: JwsService = DefaultJwsService(DefaultCryptoService(keyMaterial)),
        timeLeewaySeconds: Long = 300L,
        clock: Clock = Clock.System,
        nonceService: NonceService = DefaultNonceService(),
        clientIdScheme: ClientIdScheme = ClientIdScheme.RedirectUri,
        /**
         * Used to store the nonce, associated to the state, to first send [AuthenticationRequestParameters.nonce],
         * and then verify the challenge in the submitted verifiable presentation in
         * [AuthenticationResponseParameters.vpToken].
         */
        stateToNonceStore: MapStore<String, String> = DefaultMapStore(),
        stateToResponseTypeStore: MapStore<String, String> = DefaultMapStore(),
    ) : this(
        verifier = verifier,
        relyingPartyUrl = relyingPartyUrl,
        jwsService = jwsService,
        verifierJwsService = verifierJwsService,
        timeLeewaySeconds = timeLeewaySeconds,
        clock = clock,
        nonceService = nonceService,
        clientIdScheme = clientIdScheme,
        stateToNonceStore = stateToNonceStore,
        stateToResponseTypeStore = stateToResponseTypeStore,
    )

    private val containerJwt =
        FormatContainerJwt(algorithms = verifierJwsService.supportedAlgorithms.map { it.identifier })

    val metadata by lazy {
        RelyingPartyMetadata(
            redirectUris = relyingPartyUrl?.let { listOf(it) },
            jsonWebKeySet = JsonWebKeySet(listOf(verifier.keyMaterial.publicKey.toJsonWebKey())),
            subjectSyntaxTypesSupported = setOf(URN_TYPE_JWK_THUMBPRINT, PREFIX_DID_KEY, BINDING_METHOD_JWK),
            vpFormats = FormatHolder(
                msoMdoc = containerJwt,
                jwtVp = containerJwt,
                jwtSd = containerJwt,
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
            authorizationEncryptedResponseAlg = jwsService.encryptionAlgorithm,
            authorizationEncryptedResponseEncoding = jwsService.encryptionEncoding
        )
    }

    /**
     * Create a URL to be displayed as a static QR code for Wallet initiation.
     * URL is the [walletUrl], with query parameters appended for [relyingPartyUrl], [clientMetadataUrl], [requestUrl].
     */
    fun createQrCodeUrl(
        walletUrl: String,
        clientMetadataUrl: String,
        requestUrl: String,
    ): String {
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = this.clientId,
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
    suspend fun createSignedMetadata(): KmmResult<JwsSigned> = jwsService.createSignedJwsAddingParams(
        payload = metadata.serialize().encodeToByteArray(),
        addKeyId = true,
        addX5c = false
    )

    data class RequestOptions(
        /**
         * Requested credentials, should be at least one
         */
        val credentials: Set<RequestOptionsCredential>,
        /**
         * Response mode to request, see [OpenIdConstants.ResponseMode],
         * by default [OpenIdConstants.ResponseMode.FRAGMENT].
         * Setting this to any other value may require setting [responseUrl] too.
         */
        val responseMode: OpenIdConstants.ResponseMode = OpenIdConstants.ResponseMode.FRAGMENT,
        /**
         * Response URL to set in the [AuthenticationRequestParameters.responseUrl],
         * required if [responseMode] is set to [OpenIdConstants.ResponseMode.DIRECT_POST] or
         * [OpenIdConstants.ResponseMode.DIRECT_POST_JWT].
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
    )

    /**
     * Creates an OIDC Authentication Request, encoded as query parameters to the [walletUrl].
     */
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
    suspend fun createAuthnRequestUrlWithRequestObject(
        walletUrl: String,
        requestOptions: RequestOptions,
    ): KmmResult<String> = catching {
        val jar = createAuthnRequestAsSignedRequestObject(requestOptions).getOrThrow()
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = relyingPartyUrl,
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
    suspend fun createAuthnRequestUrlWithRequestObjectByReference(
        walletUrl: String,
        requestUrl: String,
        requestOptions: RequestOptions,
    ): KmmResult<Pair<String, String>> = catching {
        val jar = createAuthnRequestAsSignedRequestObject(requestOptions).getOrThrow()
        val urlBuilder = URLBuilder(walletUrl)
        AuthenticationRequestParameters(
            clientId = relyingPartyUrl,
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
     *    parameters.append("client_id", relyingPartyUrl)
     *    parameters.append("request_uri", requestUrl)
     * }.buildString()
     * // on an GET to requestUrl, return `jar.serialize()`
     * ```
     */
    suspend fun createAuthnRequestAsSignedRequestObject(
        requestOptions: RequestOptions,
    ): KmmResult<JwsSigned> = catching {
        val requestObject = createAuthnRequest(requestOptions)
        val requestObjectSerialized = jsonSerializer.encodeToString(
            requestObject.copy(audience = relyingPartyUrl, issuer = relyingPartyUrl)
        )
        val attestationJwt = (clientIdScheme as? ClientIdScheme.VerifierAttestation)?.attestationJwt?.serialize()
        val certificateChain = (clientIdScheme as? ClientIdScheme.CertificateSanDns)?.chain
        jwsService.createSignedJwsAddingParams(
            header = JwsHeader(
                algorithm = jwsService.algorithm,
                attestationJwt = attestationJwt,
                certificateChain = certificateChain,
            ),
            payload = requestObjectSerialized.encodeToByteArray(),
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
        responseType = requestOptions.responseType
            .also { stateToResponseTypeStore.put(requestOptions.state, it) },
        clientId = clientId,
        redirectUrl = requestOptions.buildRedirectUrl(),
        responseUrl = requestOptions.responseUrl,
        clientIdScheme = clientIdScheme.clientIdScheme,
        scope = requestOptions.buildScope(),
        nonce = nonceService.provideNonce()
            .also { stateToNonceStore.put(requestOptions.state, it) },
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
            inputDescriptors = requestOptions.credentials.map {
                it.toInputDescriptor()
            },
        ),
    )

    private fun RequestOptions.buildScope() = (
            listOf(SCOPE_OPENID, SCOPE_PROFILE)
                    + credentials.mapNotNull { it.credentialScheme.sdJwtType }
                    + credentials.mapNotNull { it.credentialScheme.vcType }
                    + credentials.mapNotNull { it.credentialScheme.isoNamespace }
            ).joinToString(" ")

    private val clientId: String? by lazy {
        clientIdFromCertificateChain ?: relyingPartyUrl
    }

    private val clientIdFromCertificateChain: String? by lazy {
        (clientIdScheme as? ClientIdScheme.CertificateSanDns)?.chain
            ?.let { it.leaf.tbsCertificate.subjectAlternativeNames?.dnsNames?.firstOrNull() }
    }

    private fun RequestOptions.buildRedirectUrl() = if ((responseMode == OpenIdConstants.ResponseMode.DIRECT_POST)
        || (responseMode == OpenIdConstants.ResponseMode.DIRECT_POST_JWT)
    ) null else relyingPartyUrl

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
        Constraint(fields = (toAttributeConstraints() + toTypeConstraint()).filterNotNull())

    private fun RequestOptionsCredential.toAttributeConstraints() =
        requestedAttributes?.createConstraints(representation, credentialScheme)
            ?: listOf()

    private fun RequestOptionsCredential.toTypeConstraint() = when (representation) {
        ConstantIndex.CredentialRepresentation.PLAIN_JWT -> this.credentialScheme.toVcConstraint()
        ConstantIndex.CredentialRepresentation.SD_JWT -> this.credentialScheme.toSdJwtConstraint()
        ConstantIndex.CredentialRepresentation.ISO_MDOC -> null
    }

    private fun RequestOptionsCredential.toFormatHolder() = when (representation) {
        ConstantIndex.CredentialRepresentation.PLAIN_JWT -> FormatHolder(jwtVp = containerJwt)
        ConstantIndex.CredentialRepresentation.SD_JWT -> FormatHolder(jwtSd = containerJwt)
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
    ): Collection<ConstraintField> = map {
        if (representation == ConstantIndex.CredentialRepresentation.ISO_MDOC)
            credentialScheme.toConstraintField(it)
        else
            ConstraintField(path = listOf("\$[${it.quote()}]"))
    }

    private fun ConstantIndex.CredentialScheme?.toConstraintField(attributeType: String) = ConstraintField(
        path = listOf(
            NormalizedJsonPath(
                NormalizedJsonPathSegment.NameSegment(this?.isoNamespace ?: "mdoc"),
                NormalizedJsonPathSegment.NameSegment(attributeType),
            ).toString()
        ),
        intentToRetain = false
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
            val jwsSigned: JwsSigned,
            val sdJwt: VerifiableCredentialSdJwt,
            val disclosures: List<SelectiveDisclosureItem>,
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
    suspend fun validateAuthnResponseFromPost(content: String): AuthnResponseResult {
        val params: AuthenticationResponseParameters = content.decodeFromPostBody()
            ?: return AuthnResponseResult.Error("content", null)
                .also { Napier.w("Could not parse authentication response: $it") }
        return validateAuthnResponse(params)
    }

    /**
     * Validates the OIDC Authentication Response from the Wallet, where [url] is the whole URL, containing the
     * [AuthenticationResponseParameters] as the fragment, e.g. `https://example.com#id_token=...`
     */
    suspend fun validateAuthnResponse(url: String): AuthnResponseResult {
        val params = kotlin.runCatching {
            val parsedUrl = Url(url)
            if (parsedUrl.fragment.isNotEmpty())
                parsedUrl.fragment.decodeFromPostBody<AuthenticationResponseParameters>()
            else
                parsedUrl.encodedQuery.decodeFromUrlQuery<AuthenticationResponseParameters>()
        }.getOrNull()
            ?: return AuthnResponseResult.Error("url not parsable", null)
                .also { Napier.w("Could not parse authentication response: $url") }
        return validateAuthnResponse(params)
    }

    /**
     * Validates [AuthenticationResponseParameters] from the Wallet
     */
    suspend fun validateAuthnResponse(params: AuthenticationResponseParameters): AuthnResponseResult {
        val state = params.state
            ?: return AuthnResponseResult.ValidationError("state", params.state)
                .also { Napier.w("Invalid state: ${params.state}") }
        params.response?.let { response ->
            JwsSigned.deserialize(response).getOrNull()?.let { jarmResponse ->
                if (!verifierJwsService.verifyJwsObject(jarmResponse)) {
                    return AuthnResponseResult.ValidationError("response", state)
                        .also { Napier.w { "JWS of response not verified: ${params.response}" } }
                }
                AuthenticationResponseParameters.deserialize(jarmResponse.payload.decodeToString())
                    .getOrNull()?.let { return validateAuthnResponse(it) }
            }
            JweEncrypted.deserialize(response).getOrNull()?.let { jarmResponse ->
                jwsService.decryptJweObject(jarmResponse, response).getOrNull()?.let { decrypted ->
                    AuthenticationResponseParameters.deserialize(decrypted.payload.decodeToString())
                        .getOrNull()?.let { return validateAuthnResponse(it) }
                }
            }
        }
        val responseType = stateToResponseTypeStore.get(state)
            ?: return AuthnResponseResult.ValidationError("state", state)
                .also { Napier.w("State not associated with response type: $state") }

        val idToken: IdToken? = if (responseType.contains(ID_TOKEN)) {
            params.idToken?.let { idToken ->
                catching {
                    extractValidatedIdToken(idToken)
                }.getOrElse {
                    return AuthnResponseResult.ValidationError("idToken", state)
                }
            } ?: return AuthnResponseResult.ValidationError("idToken", state)
                .also { Napier.w("State not associated with response type: $state") }
        } else null

        if (responseType.contains(VP_TOKEN)) {
            val expectedNonce = stateToNonceStore.get(state)
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
                val relatedPresentation =
                    JsonPath(descriptor.cumulativeJsonPath).query(verifiablePresentation).first().value
                val result = runCatching {
                    verifyPresentationResult(descriptor, relatedPresentation, expectedNonce)
                }.getOrElse {
                    return AuthnResponseResult.ValidationError("Invalid presentation format", state)
                        .also { Napier.w("Invalid presentation format: $relatedPresentation") }
                }
                result.mapToAuthnResponseResult(state)
            }

            return if (validationResults.size != 1) {
                AuthnResponseResult.VerifiablePresentationValidationResults(validationResults)
            } else validationResults[0]
        }

        return idToken?.let { AuthnResponseResult.IdToken(it, state) }
            ?: AuthnResponseResult.Error("Neither id_token nor vp_token", state)
    }


    @Throws(IllegalArgumentException::class, CancellationException::class)
    private suspend fun extractValidatedIdToken(idTokenJws: String): IdToken {
        val jwsSigned = JwsSigned.deserialize(idTokenJws).getOrNull()
            ?: throw IllegalArgumentException("idToken")
                .also { Napier.w("Could not parse JWS from idToken: $idTokenJws") }
        if (!verifierJwsService.verifyJwsObject(jwsSigned))
            throw IllegalArgumentException("idToken")
                .also { Napier.w { "JWS of idToken not verified: $idTokenJws" } }
        val idToken = IdToken.deserialize(jwsSigned.payload.decodeToString()).getOrThrow()
        if (idToken.issuer != idToken.subject)
            throw IllegalArgumentException("idToken.iss")
                .also { Napier.d("Wrong issuer: ${idToken.issuer}, expected: ${idToken.subject}") }
        val validAudiences = listOfNotNull(relyingPartyUrl, clientIdFromCertificateChain)
        if (idToken.audience !in validAudiences)
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
    private fun verifyPresentationResult(
        descriptor: PresentationSubmissionDescriptor,
        relatedPresentation: JsonElement,
        challenge: String
    ) = when (descriptor.format) {
        ClaimFormatEnum.JWT_SD,
        ClaimFormatEnum.MSO_MDOC,
        ClaimFormatEnum.JWT_VP -> when (relatedPresentation) {
            is JsonPrimitive -> verifier.verifyPresentation(relatedPresentation.content, challenge)
            else -> throw IllegalArgumentException()
        }

        else -> throw IllegalArgumentException()
    }

    private fun Verifier.VerifyPresentationResult.mapToAuthnResponseResult(state: String) = when (this) {
        is Verifier.VerifyPresentationResult.InvalidStructure ->
            AuthnResponseResult.Error("parse vp failed", state)
                .also { Napier.w("VP error: $this") }

        is Verifier.VerifyPresentationResult.NotVerified ->
            AuthnResponseResult.ValidationError("vpToken", state)
                .also { Napier.w("VP error: $this") }

        is Verifier.VerifyPresentationResult.Success ->
            AuthnResponseResult.Success(vp, state)
                .also { Napier.i("VP success: $this") }

        is Verifier.VerifyPresentationResult.SuccessIso ->
            AuthnResponseResult.SuccessIso(documents, state)
                .also { Napier.i("VP success: $this") }

        is Verifier.VerifyPresentationResult.SuccessSdJwt ->
            AuthnResponseResult.SuccessSdJwt(jwsSigned, sdJwt, disclosures, state)
                .also { Napier.i("VP success: $this") }
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
