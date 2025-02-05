package at.asitplus.wallet.lib.openid

import at.asitplus.KmmResult
import at.asitplus.catching
import at.asitplus.dif.*
import at.asitplus.jsonpath.JsonPath
import at.asitplus.jsonpath.core.NormalizedJsonPath
import at.asitplus.jsonpath.core.NormalizedJsonPathSegment
import at.asitplus.openid.*
import at.asitplus.signum.indispensable.cosef.io.coseCompliantSerializer
import at.asitplus.signum.indispensable.io.Base64UrlStrict
import at.asitplus.signum.indispensable.josef.*
import at.asitplus.wallet.lib.agent.*
import at.asitplus.wallet.lib.cbor.DefaultVerifierCoseService
import at.asitplus.wallet.lib.cbor.VerifierCoseService
import at.asitplus.wallet.lib.data.ConstantIndex
import at.asitplus.wallet.lib.data.ConstantIndex.supportsSdJwt
import at.asitplus.wallet.lib.data.ConstantIndex.supportsVcJwt
import at.asitplus.wallet.lib.data.VerifiablePresentationJws
import at.asitplus.wallet.lib.data.vckJsonSerializer
import at.asitplus.wallet.lib.iso.*
import at.asitplus.wallet.lib.jws.*
import at.asitplus.wallet.lib.oidvci.*
import com.benasher44.uuid.uuid4
import io.github.aakira.napier.Napier
import io.ktor.http.*
import io.matthewnelson.encoding.base16.Base16
import io.matthewnelson.encoding.core.Decoder.Companion.decodeToByteArray
import io.matthewnelson.encoding.core.Encoder.Companion.encodeToString
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Combines Verifiable Presentations with OpenId Connect.
 * Implements [OpenID for VP](https://openid.net/specs/openid-connect-4-verifiable-presentations-1_0.html) (2024-12-02)
 * as well as [SIOP V2](https://openid.net/specs/openid-connect-self-issued-v2-1_0.html) (2023-11-28).
 *
 * This class creates the Authentication Request, [verifier] verifies the response. See [at.asitplus.wallet.lib.oidc.OidcSiopWallet] for the holder.
 */
class OpenId4VpVerifier(
    private val clientIdScheme: ClientIdScheme,
    private val keyMaterial: KeyMaterial = EphemeralKeyWithoutCert(),
    val verifier: Verifier = VerifierAgent(identifier = clientIdScheme.clientId),
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
    private val supportedAlgorithms = verifierJwsService.supportedAlgorithms.map { it.identifier }
    private val supportedSignatureVerificationAlgorithm =
        (verifierJwsService.supportedAlgorithms.firstOrNull { it == JwsAlgorithm.ES256 }?.identifier
            ?: verifierJwsService.supportedAlgorithms.first().identifier)
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
            issuer = clientIdScheme.issuerUri ?: clientIdScheme.clientId,
            jsonWebKeySet = JsonWebKeySet(setOf(jwsService.keyMaterial.jsonWebKey))
        )
    }

    /**
     * Creates the [at.asitplus.openid.RelyingPartyMetadata], without encryption (see [metadataWithEncryption])
     */
    val metadata by lazy {
        RelyingPartyMetadata(
            redirectUris = listOfNotNull((clientIdScheme as? ClientIdScheme.RedirectUri)?.redirectUri),
            jsonWebKeySet = JsonWebKeySet(listOf(keyMaterial.publicKey.toJsonWebKey())),
            authorizationSignedResponseAlgString = supportedSignatureVerificationAlgorithm,
            subjectSyntaxTypesSupported = setOf(
                OpenIdConstants.URN_TYPE_JWK_THUMBPRINT,
                OpenIdConstants.PREFIX_DID_KEY,
                OpenIdConstants.BINDING_METHOD_JWK
            ),
            vpFormats = FormatHolder(
                msoMdoc = containerJwt,
                jwtVp = containerJwt,
                jwtSd = containerSdJwt,
                sdJwt = containerSdJwt
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


    sealed class CreationOptions {
        /**
         * Creates authentication request with parameters encoded as URL query parameters to [walletUrl].
         */
        data class Query(val walletUrl: String) : CreationOptions()

        /**
         * Appends [requestUrl] to [walletUrl], callers need to call [CreatedRequest.loadRequestObject] with the
         * Wallet's request to actually create the authn request object.
         **/
        data class RequestByReference(
            val walletUrl: String,
            val requestUrl: String,
            val requestUrlMethod: String = "post",
        ) : CreationOptions()

        /** Appends authentication request as signed object to [walletUrl] */
        data class SignedRequestByValue(val walletUrl: String) : CreationOptions()

        /**
         * Appends [requestUrl] to [walletUrl], callers need to call [CreatedRequest.loadRequestObject] with the
         * Wallet's request to actually create the authn request object (which will be signed).
         */
        data class SignedRequestByReference(
            val walletUrl: String,
            val requestUrl: String,
            val requestUrlMethod: String = "post",
        ) : CreationOptions()
    }

    data class CreatedRequest(
        /** URL to invoke the wallet/holder */
        val url: String,
        /**
         *  Optional content that needs to be served under the previously passed in `requestUrl`
         *  with content type `application/oauth-authz-req+jwt`
         *  Pass in the [RequestObjectParameters] that the Wallet may have sent when requesting the request object.
         */
        val loadRequestObject: (suspend (RequestObjectParameters?) -> KmmResult<String>)? = null,
    )

    suspend fun createAuthnRequest(
        requestOptions: RequestOptions,
        creationOptions: CreationOptions,
    ): KmmResult<CreatedRequest> = catching {
        when (creationOptions) {
            is CreationOptions.Query -> {
                require(clientIdScheme !is ClientIdScheme.CertificateSanDns) // per OpenID4VP d23 5.10.4
                URLBuilder(creationOptions.walletUrl).apply {
                    createAuthnRequest(requestOptions).encodeToParameters()
                        .forEach { parameters.append(it.key, it.value) }
                }.buildString().toCreatedRequest()
            }

            is CreationOptions.RequestByReference -> {
                require(clientIdScheme !is ClientIdScheme.CertificateSanDns) // per OpenID4VP d23 5.10.4
                URLBuilder(creationOptions.walletUrl).apply {
                    AuthenticationRequestParameters(
                        clientId = clientIdScheme.clientId,
                        requestUri = creationOptions.requestUrl,
                        requestUriMethod = creationOptions.requestUrlMethod,
                    ).encodeToParameters()
                        .forEach { parameters.append(it.key, it.value) }
                }.buildString().toCreatedRequest {
                    catching {
                        createAuthnRequest(requestOptions, it).serialize()
                    }
                }
            }

            is CreationOptions.SignedRequestByValue -> {
                require(clientIdScheme !is ClientIdScheme.RedirectUri) // per OpenID4VP d23 5.10.4
                URLBuilder(creationOptions.walletUrl).apply {
                    AuthenticationRequestParameters(
                        clientId = clientIdScheme.clientId,
                        request = createAuthnRequestAsSignedRequestObject(requestOptions).getOrThrow().serialize(),
                    ).encodeToParameters()
                        .forEach { parameters.append(it.key, it.value) }
                }.buildString().toCreatedRequest()
            }

            is CreationOptions.SignedRequestByReference -> {
                require(clientIdScheme !is ClientIdScheme.RedirectUri) // per OpenID4VP d23 5.10.4
                URLBuilder(creationOptions.walletUrl).apply {
                    AuthenticationRequestParameters(
                        clientId = clientIdScheme.clientId,
                        requestUri = creationOptions.requestUrl,
                        requestUriMethod = creationOptions.requestUrlMethod,
                    ).encodeToParameters()
                        .forEach { parameters.append(it.key, it.value) }
                }.buildString()
                    .toCreatedRequest {
                        catching {
                            createAuthnRequestAsSignedRequestObject(requestOptions, it).getOrThrow().serialize()
                        }
                    }
            }
        }
    }

    private fun String.toCreatedRequest(): CreatedRequest = CreatedRequest(this)
    private fun String.toCreatedRequest(loadRequestObject: suspend (RequestObjectParameters?) -> KmmResult<String>): CreatedRequest =
        CreatedRequest(this, loadRequestObject)


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
    suspend fun createAuthnRequestAsSignedRequestObject(
        requestOptions: RequestOptions,
        requestObjectParameters: RequestObjectParameters? = null,
    ): KmmResult<JwsSigned<AuthenticationRequestParameters>> = catching {
        val requestObject = createAuthnRequest(requestOptions, requestObjectParameters)
        val attestationJwt = (clientIdScheme as? ClientIdScheme.VerifierAttestation)?.attestationJwt?.serialize()
        val certificateChain = (clientIdScheme as? ClientIdScheme.CertificateSanDns)?.chain
        val siopClientId = "https://self-issued.me/v2"
        val issuer = when (clientIdScheme) {
            is ClientIdScheme.PreRegistered -> clientIdScheme.issuerUri ?: clientIdScheme.clientId
            else -> siopClientId
        }
        jwsService.createSignedJwsAddingParams(
            header = JwsHeader(
                algorithm = jwsService.algorithm,
                attestationJwt = attestationJwt,
                certificateChain = certificateChain,
                type = JwsContentTypeConstants.OAUTH_AUTHZ_REQUEST
            ),
            payload = requestObject.copy(
                audience = siopClientId,
                issuer = issuer,
            ),
            serializer = AuthenticationRequestParameters.Companion.serializer(),
            addJsonWebKey = certificateChain == null,
        ).getOrThrow()
    }

    /**
     * Creates [AuthenticationRequestParameters], to be encoded in the URL of the wallet somehow,
     * see [createAuthnRequest]
     */
    @Suppress("DEPRECATION")
    suspend fun createAuthnRequest(
        requestOptions: RequestOptions,
        requestObjectParameters: RequestObjectParameters? = null,
    ) = AuthenticationRequestParameters(
        responseType = requestOptions.responseType,
        clientId = clientIdScheme.clientId,
        clientIdScheme = clientIdScheme.scheme, // still set this for our own older implementations
        redirectUrl = if (!requestOptions.isAnyDirectPost) clientIdScheme.redirectUri else null,
        responseUrl = requestOptions.responseUrl,
        //scope = requestOptions.buildScope(), // TODO verify if this is needed
        nonce = nonceService.provideNonce(),
        walletNonce = requestObjectParameters?.walletNonce,
        clientMetadata = clientMetadata(requestOptions),
        clientMetadataUri = requestOptions.clientMetadataUrl,
        idTokenType = IdTokenType.SUBJECT_SIGNED.text,
        responseMode = requestOptions.responseMode,
        state = requestOptions.state,
        presentationDefinition = PresentationDefinition(
            id = uuid4().toString(),
            inputDescriptors = requestOptions.credentials.map { it.toInputDescriptor() },
        ),
    ).also { stateToAuthnRequestStore.put(requestOptions.state, it) }

    // OpenID4VP: Metadata MUST be passed as parameter if client_id_scheme is "redirect_uri"
    private fun clientMetadata(options: RequestOptions): RelyingPartyMetadata? =
        if (options.clientMetadataUrl != null && clientIdScheme !is ClientIdScheme.RedirectUri) {
            null
        } else {
            if (options.encryption) metadataWithEncryption else metadata
        }

    private fun RequestOptions.buildScope() =
        listOf(OpenIdConstants.SCOPE_OPENID, OpenIdConstants.SCOPE_PROFILE).joinToString(" ")

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
        requestedAttributes?.createConstraints(representation, credentialScheme, false)?.toSet()
            ?: setOf()

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

    private fun Set<String>.createConstraints(
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

    /**
     * Validates the OpenID Authentication Response from the Wallet, where [content] are the HTTP POST encoded
     * [at.asitplus.openid.AuthenticationResponseParameters], e.g. `id_token=...&vp_token=...`
     */
    @Deprecated("Use validateAuthnResponse", ReplaceWith("validateAuthnResponse"))
    suspend fun validateAuthnResponseFromPost(content: String): AuthnResponseResult =
        validateAuthnResponse(content)

    /**
     * Validates an Authentication Response from the Wallet, where [input] is a map of POST parameters received.
     */
    suspend fun validateAuthnResponse(input: Map<String, String>): AuthnResponseResult =
        runCatching {
            ResponseParametersFrom.Post(input.decode<AuthenticationResponseParameters>())
        }.getOrElse {
            Napier.w("Could not parse authentication response: $input", it)
            return AuthnResponseResult.Error("Can't parse input", null)
        }.let { validateAuthnResponse(it) }

    /**
     * Validates an Authentication Response from the Wallet, where [input] is either:
     * - a URL, containing parameters in the fragment, e.g. `https://example.com#id_token=...`
     * - a URL, containing parameters in the query, e.g. `https://example.com?id_token=...`
     * - parameters encoded as a POST body, e.g. `id_token=...&vp_token=...`
     */
    suspend fun validateAuthnResponse(input: String): AuthnResponseResult =
        runCatching {
            responseParser.parseAuthnResponse(input)
        }.getOrElse {
            Napier.w("Could not parse authentication response: $input", it)
            return AuthnResponseResult.Error("Can't parse input", null)
        }.let {
            validateAuthnResponse(it)
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
        if (responseType?.contains(OpenIdConstants.VP_TOKEN) == true) {
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

        if (responseType?.contains(OpenIdConstants.ID_TOKEN) == true) {
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
        val jwsSigned = JwsSigned.Companion.deserialize<IdToken>(
            IdToken.Companion.serializer(), idTokenJws,
            vckJsonSerializer
        ).getOrNull()
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
            input = SdJwtSigned.Companion.parse(relatedPresentation.jsonPrimitive.content)
                ?: throw IllegalArgumentException("relatedPresentation"),
            challenge = expectedNonce
        )

        ClaimFormat.JWT_VP -> verifier.verifyPresentationVcJwt(
            input = JwsSigned.Companion.deserialize<VerifiablePresentationJws>(
                VerifiablePresentationJws.Companion.serializer(),
                relatedPresentation.jsonPrimitive.content,
                vckJsonSerializer
            ).getOrThrow(),
            challenge = expectedNonce
        )

        ClaimFormat.MSO_MDOC -> {
            // if the response is not encrypted, the wallet could not transfer the mdocGeneratedNonce,
            // so we'll use the empty string
            val apuDirect = (input as? ResponseParametersFrom.JweDecrypted)
                ?.jweDecrypted?.header?.agreementPartyUInfo
            val apuNested = ((input as? ResponseParametersFrom.JwsSigned)?.parent as? ResponseParametersFrom.JweForJws)
                ?.jweDecrypted?.header?.agreementPartyUInfo
            val mdocGeneratedNonce = apuDirect?.decodeToString()
                ?: apuNested?.decodeToString()
                ?: ""
            verifier.verifyPresentationIsoMdoc(
                input = relatedPresentation.jsonPrimitive.content.decodeToByteArray(Base64UrlStrict)
                    .let { DeviceResponse.Companion.deserialize(it).getOrThrow() },
                challenge = expectedNonce,
                verifyDocument = verifyDocument(mdocGeneratedNonce, clientId, responseUrl, expectedNonce)
            )
        }

        else -> throw IllegalArgumentException("descriptor.format")
    }

    /**
     * Performs verification of the [at.asitplus.wallet.lib.iso.SessionTranscript] and [at.asitplus.wallet.lib.iso.DeviceAuthentication],
     * acc. to ISO/IEC 18013-5:2021 and ISO/IEC 18013-7:2024, if required (i.e. response is encrypted)
     */
    @Throws(IllegalArgumentException::class)
    private fun verifyDocument(
        mdocGeneratedNonce: String,
        clientId: String?,
        responseUrl: String?,
        expectedNonce: String,
    ): (MobileSecurityObject, Document) -> Boolean = { mso, document ->
        val deviceSignature = document.deviceSigned.deviceAuth.deviceSignature ?: run {
            Napier.w("DeviceSignature is null: ${document.deviceSigned.deviceAuth}")
            throw IllegalArgumentException("deviceSignature")
        }

        val walletKey = mso.deviceKeyInfo.deviceKey
        if (clientId != null && responseUrl != null) {
            val deviceAuthentication =
                document.calcDeviceAuthentication(expectedNonce, mdocGeneratedNonce, clientId, responseUrl)
            val expectedPayload = coseCompliantSerializer
                .encodeToByteArray(deviceAuthentication.serialize())
                .wrapInCborTag(24)
                .also { Napier.d("Device authentication for verification is ${it.encodeToString(Base16())}") }
            verifierCoseService.verifyCose(
                deviceSignature,
                walletKey,
                detachedPayload = expectedPayload
            ).onFailure {
                val expectedBytes = expectedPayload.encodeToString(Base16)
                Napier.w("DeviceSignature not verified: $deviceSignature for detached payload $expectedBytes", it)
                throw IllegalArgumentException("deviceSignature", it)
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
     * Performs calculation of the [at.asitplus.wallet.lib.iso.SessionTranscript] and [at.asitplus.wallet.lib.iso.DeviceAuthentication],
     * acc. to ISO/IEC 18013-5:2021 and ISO/IEC 18013-7:2024
     */
    private fun Document.calcDeviceAuthentication(
        challenge: String,
        mdocGeneratedNonce: String,
        clientId: String,
        responseUrl: String,
    ): DeviceAuthentication {
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
        return DeviceAuthentication(
            type = "DeviceAuthentication",
            sessionTranscript = sessionTranscript,
            docType = docType,
            namespaces = deviceSigned.namespaces
        )
    }

    private fun Verifier.VerifyPresentationResult.mapToAuthnResponseResult(state: String) = when (this) {
        is Verifier.VerifyPresentationResult.InvalidStructure ->
            AuthnResponseResult.Error("parse vp failed", state)
                .also { Napier.w("VP error: $this") }

        is Verifier.VerifyPresentationResult.ValidationError ->
            AuthnResponseResult.ValidationError("vpToken", state)
                .also { Napier.w("VP error: $this", cause) }

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
