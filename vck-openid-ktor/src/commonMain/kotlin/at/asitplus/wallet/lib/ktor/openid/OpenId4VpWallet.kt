package at.asitplus.wallet.lib.ktor.openid

import at.asitplus.KmmResult
import at.asitplus.catching
import at.asitplus.openid.AuthenticationRequestParameters
import at.asitplus.openid.RequestParametersFrom
import at.asitplus.wallet.lib.agent.CredentialSubmission
import at.asitplus.wallet.lib.agent.CryptoService
import at.asitplus.wallet.lib.agent.HolderAgent
import at.asitplus.wallet.lib.data.vckJsonSerializer
import at.asitplus.wallet.lib.jws.DefaultJwsService
import at.asitplus.wallet.lib.oidc.AuthenticationResponseResult
import at.asitplus.wallet.lib.oidc.OidcSiopWallet
import at.asitplus.wallet.lib.oidc.helpers.AuthorizationResponsePreparationState
import io.github.aakira.napier.Napier
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Implements the wallet side of
 * [Self-Issued OpenID Provider v2 - draft 13](https://openid.net/specs/openid-connect-self-issued-v2-1_0.html)
 * and
 * [OpenID for Verifiable Presentations - draft 21](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)
 */
class OpenId4VpWallet(
    /**
     * Used to display the success page to the user
     */
    private val openUrlExternally: suspend (String) -> Unit,
    /**
     * ktor engine to use to make requests to issuing service
     */
    engine: HttpClientEngine,
    /**
     * Additional configuration for building the HTTP client, e.g. callers may enable logging
     */
    httpClientConfig: (HttpClientConfig<*>.() -> Unit)? = null,
    cryptoService: CryptoService,
    holderAgent: HolderAgent,
) {
    private val client: HttpClient = HttpClient(engine) {
        followRedirects = false
        install(ContentNegotiation) {
            json(vckJsonSerializer)
        }
        install(DefaultRequest) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }
        httpClientConfig?.let { apply(it) }
    }
    val oidcSiopWallet = OidcSiopWallet(
        holder = holderAgent,
        agentPublicKey = cryptoService.keyMaterial.publicKey,
        jwsService = DefaultJwsService(cryptoService),
        remoteResourceRetriever = { url ->
            withContext(Dispatchers.IO) {
                client.get(url).bodyAsText()
            }
        },
        requestObjectJwsVerifier = { _ -> true }, // unsure about this one?
    )

    suspend fun parseAuthenticationRequestParameters(input: String): KmmResult<RequestParametersFrom<AuthenticationRequestParameters>> =
        oidcSiopWallet.parseAuthenticationRequestParameters(input)

    suspend fun startAuthorizationResponsePreparation(
        request: RequestParametersFrom<AuthenticationRequestParameters>,
    ): KmmResult<AuthorizationResponsePreparationState> =
        oidcSiopWallet.startAuthorizationResponsePreparation(request)

    suspend fun startAuthorizationResponsePreparation(
        input: String,
    ): KmmResult<AuthorizationResponsePreparationState> =
        oidcSiopWallet.startAuthorizationResponsePreparation(input)

    /**
     * Calls [oidcSiopWallet] to create the authentication response.
     * In case the result shall be POSTed to the verifier, we call [client] to do that,
     * and optionally [openUrlExternally] with the `redirect_uri` of that POST.
     * In case the result shall be sent as a redirect to the verifier, we call [openUrlExternally].
     */
    suspend fun startPresentation(
        request: RequestParametersFrom<AuthenticationRequestParameters>,
    ): KmmResult<Unit> = catching {
        Napier.i("startPresentation: $request")
        oidcSiopWallet.createAuthnResponse(request).getOrThrow().let {
            when (it) {
                is AuthenticationResponseResult.Post -> postResponse(it)
                is AuthenticationResponseResult.Redirect -> redirectResponse(it)
            }
        }
    }

    /**
     * Calls [oidcSiopWallet] to finalize the authentication response.
     * In case the result shall be POSTed to the verifier, we call [client] to do that,
     * and optionally [openUrlExternally] with the `redirect_uri` of that POST.
     * In case the result shall be sent as a redirect to the verifier, we call [openUrlExternally].
     */
    suspend fun finalizeAuthorizationResponse(
        request: RequestParametersFrom<AuthenticationRequestParameters>,
        preparationState: AuthorizationResponsePreparationState,
        inputDescriptorSubmission: Map<String, CredentialSubmission>,
    ): KmmResult<Unit> = catching {
        Napier.i("startPresentation: $request")
        oidcSiopWallet.finalizeAuthorizationResponse(
            request = request,
            preparationState = preparationState,
            inputDescriptorSubmissions = inputDescriptorSubmission
        ).getOrThrow().let {
            when (it) {
                is AuthenticationResponseResult.Post -> postResponse(it)
                is AuthenticationResponseResult.Redirect -> redirectResponse(it)
            }
        }
    }

    private suspend fun postResponse(it: AuthenticationResponseResult.Post) {
        Napier.i("postResponse: $it")
        handlePostResponse(
            client.submitForm(
                url = it.url,
                formParameters = parameters {
                    it.params.forEach { append(it.key, it.value) }
                }
            ))
    }

    @Throws(Exception::class)
    private suspend fun handlePostResponse(response: HttpResponse) {
        Napier.i("handlePostResponse: response $response")
        when (response.status.value) {
            HttpStatusCode.InternalServerError.value -> throw Exception(response.bodyAsText())
            in 200..399 -> response.extractRedirectUri()?.let { openUrlExternally.invoke(it) }
            else -> throw Exception(response.readBytes().decodeToString())
        }
    }

    private suspend fun redirectResponse(it: AuthenticationResponseResult.Redirect) {
        Napier.i("redirectResponse: ${it.url}")
        openUrlExternally.invoke(it.url)
    }
}

@Serializable
data class OpenId4VpSuccess(
    @SerialName("redirect_uri")
    val redirectUri: String,
)

private suspend fun HttpResponse.extractRedirectUri(): String? =
    headers[HttpHeaders.Location]?.let {
        it.ifEmpty { null }
    } ?: runCatching { body<OpenId4VpSuccess>() }.getOrNull()?.let {
        it.redirectUri.ifEmpty { null }
    }
