package at.asitplus.wallet.lib.oidvci

import at.asitplus.wallet.lib.data.VcDataModelConstants.VERIFIABLE_CREDENTIAL
import at.asitplus.wallet.lib.oidc.AuthenticationRequestParameters
import at.asitplus.wallet.lib.oidc.OpenIdConstants.GRANT_TYPE_CODE
import at.asitplus.wallet.lib.oidc.OpenIdConstants.TOKEN_TYPE_BEARER
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.random.Random

class SerializationTest : FunSpec({

    fun createAuthorizationRequest() = AuthenticationRequestParameters(
        responseType = GRANT_TYPE_CODE,
        clientId = randomString(),
        authorizationDetails = AuthorizationDetails(
            type = randomString(),
            format = CredentialFormatEnum.JWT_VC,
            types = arrayOf(VERIFIABLE_CREDENTIAL, randomString()),
        ),
        redirectUrl = randomString(),
        scope = randomString(),
        walletIssuer = randomString(),
        userHint = randomString(),
        issuerState = randomString()
    )

    fun createTokenRequest() = TokenRequestParameters(
        grantType = GRANT_TYPE_CODE,
        code = randomString(),
        redirectUrl = "https://wallet.a-sit.at/app/${randomString()}",
        clientId = randomString(),
        preAuthorizedCode = randomString(),
        codeVerifier = randomString(),
        userPin = randomString(),
    )

    fun createTokenResponse() = TokenResponseParameters(
        accessToken = randomString(),
        refreshToken = randomString(),
        tokenType = TOKEN_TYPE_BEARER,
        expires = Random.nextInt(),
        scope = randomString(),
        clientNonce = randomString(),
        clientNonceExpiresIn = Random.nextInt(),
        authorizationPending = false,
        interval = Random.nextInt(),
    )

    fun createCredentialRequest() = CredentialRequestParameters(
        format = CredentialFormatEnum.JWT_VC,
        types = arrayOf(randomString(), randomString()),
        proof = CredentialRequestProof(
            proofType = randomString(),
            jwt = randomString()
        )
    )

    fun createCredentialResponse() = CredentialResponseParameters(
        format = CredentialFormatEnum.JWT_VC,
        credential = randomString(),
        acceptanceToken = randomString(),
        clientNonce = randomString(),
        clientNonceExpiresIn = Random.nextInt(),
    )

    test("createAuthorizationRequest as GET") {
        val params = createAuthorizationRequest()
        val baseUrl = "https://wallet.a-sit.at/authorize"

        val intermediateMap = params.encodeToParameters()
        val url = "$baseUrl?${intermediateMap.formUrlEncode()}"
        println(url)

        url shouldContain baseUrl
        url shouldContain "response_type=${params.responseType}"
        url shouldContain "client_id=${params.clientId}"
        val parsed: AuthenticationRequestParameters = intermediateMap.decode()
        parsed shouldBe params
    }

    test("createAuthorizationRequest as POST") {
        val params = createAuthorizationRequest()
        val intermediateMap = params.encodeToParameters()
        val formEncoded = intermediateMap.formUrlEncode()
        println(formEncoded)
        formEncoded shouldContain "response_type=${params.responseType}"
        formEncoded shouldContain "client_id=${params.clientId}"
        formEncoded shouldContain "authorization_details=" + "{\"type\":".encodeURLParameter()
        val parsed: AuthenticationRequestParameters = intermediateMap.decode()
        parsed shouldBe params
        val parsedToo: AuthenticationRequestParameters = formEncoded.decodeFromPostBody()
        parsedToo shouldBe params
    }

    test("createTokenRequest as POST") {
        val params = createTokenRequest()
        val intermediateMap = params.encodeToParameters()
        val formEncoded = intermediateMap.formUrlEncode()
        println(formEncoded)
        val parsed: TokenRequestParameters = intermediateMap.decode()
        parsed shouldBe params
        val parsedToo: TokenRequestParameters = formEncoded.decodeFromPostBody()
        parsedToo shouldBe params
    }

    test("createTokenResponse as JSON") {
        val params = createTokenResponse()
        val json = at.asitplus.wallet.lib.oidc.jsonSerializer.encodeToString(params)
        println(json)
        json shouldContain "\"access_token\":"
        json shouldContain "\"token_type\":"
        json shouldContain "\"expires_in\":"
        json shouldContain "\"c_nonce\":"
        json shouldContain "\"c_nonce_expires_in\":"
        val parsed: TokenResponseParameters = at.asitplus.wallet.lib.oidc.jsonSerializer.decodeFromString(json)
        parsed shouldBe params
    }

    test("createCredentialRequest as JSON") {
        val params = createCredentialRequest()
        val json = at.asitplus.wallet.lib.oidc.jsonSerializer.encodeToString(params)
        println(json)
        json shouldContain "\"types\":["
        json shouldContain "\"${params.types.first()}\""
        val parsed: CredentialRequestParameters =
            at.asitplus.wallet.lib.oidc.jsonSerializer.decodeFromString<CredentialRequestParameters>(json)
        parsed shouldBe params
    }

    test("createCredentialResponse as JSON") {
        val params = createCredentialResponse()
        val json = at.asitplus.wallet.lib.oidc.jsonSerializer.encodeToString(params)
        println(json)
        json shouldContain "\"format\":"
        val parsed = at.asitplus.wallet.lib.oidc.jsonSerializer.decodeFromString<CredentialResponseParameters>(json)
        parsed shouldBe params
    }
})

val charPool = ('A'..'Z') + ('a'..'z') + ('0'..'9')

fun randomString() = (1..16)
    .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
    .joinToString("")
