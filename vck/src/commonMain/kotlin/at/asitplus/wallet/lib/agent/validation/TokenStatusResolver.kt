package at.asitplus.wallet.lib.agent.validation

import at.asitplus.KmmResult
import at.asitplus.KmmResult.Companion.wrap
import at.asitplus.wallet.lib.DefaultZlibService
import at.asitplus.wallet.lib.ZlibService
import at.asitplus.wallet.lib.agent.SubjectCredentialStore
import at.asitplus.wallet.lib.cbor.VerifyCoseSignature
import at.asitplus.wallet.lib.cbor.VerifyCoseSignatureFun
import at.asitplus.wallet.lib.data.Status
import at.asitplus.wallet.lib.data.VerifiableCredentialJws
import at.asitplus.wallet.lib.data.VerifiableCredentialSdJwt
import at.asitplus.wallet.lib.data.rfc.tokenStatusList.StatusListTokenPayload
import at.asitplus.wallet.lib.data.rfc.tokenStatusList.StatusListTokenValidator
import at.asitplus.wallet.lib.data.rfc.tokenStatusList.primitives.TokenStatus
import at.asitplus.wallet.lib.iso.IssuerSigned
import at.asitplus.wallet.lib.jws.VerifyJwsObject
import at.asitplus.wallet.lib.jws.VerifyJwsObjectFun
import kotlinx.datetime.Clock

fun interface TokenStatusResolver {
    suspend operator fun invoke(status: Status): KmmResult<TokenStatus>
}

fun StatusListTokenResolver.toTokenStatusResolver(
    clock: Clock = Clock.System,
    zlibService: ZlibService = DefaultZlibService(),
    verifyJwsObjectIntegrity: VerifyJwsObjectFun = VerifyJwsObject(),
    verifyCoseSignature: VerifyCoseSignatureFun<StatusListTokenPayload> = VerifyCoseSignature(),
) = TokenStatusResolver { status ->
    runCatching {
        val token = this(status.statusList.uri)

        val payload = token.validate(
            verifyJwsObject = verifyJwsObjectIntegrity,
            verifyCoseSignature = verifyCoseSignature,
            statusListInfo = status.statusList,
            isInstantInThePast = {
                it < kotlinx.datetime.Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
            },
        ).getOrThrow()

        StatusListTokenValidator.extractTokenStatus(
            statusList = payload.statusList,
            statusListInfo = status.statusList,
            zlibService = zlibService,
        ).getOrThrow()
    }.wrap()
}

suspend operator fun TokenStatusResolver.invoke(issuerSigned: IssuerSigned) = invoke(CredentialWrapper.Mdoc(issuerSigned))
suspend operator fun TokenStatusResolver.invoke(sdJwt: VerifiableCredentialSdJwt) = invoke(CredentialWrapper.SdJwt(sdJwt))
suspend operator fun TokenStatusResolver.invoke(vcJws: VerifiableCredentialJws) = invoke(CredentialWrapper.VcJws(vcJws))

suspend operator fun TokenStatusResolver.invoke(storeEntry: SubjectCredentialStore.StoreEntry) = when (storeEntry) {
    is SubjectCredentialStore.StoreEntry.Iso -> invoke(CredentialWrapper.Mdoc(storeEntry.issuerSigned))
    is SubjectCredentialStore.StoreEntry.SdJwt -> invoke(CredentialWrapper.SdJwt(storeEntry.sdJwt))
    is SubjectCredentialStore.StoreEntry.Vc -> invoke(CredentialWrapper.VcJws(storeEntry.vc))
}

suspend operator fun TokenStatusResolver.invoke(credentialWrapper: CredentialWrapper) = when (credentialWrapper) {
    is CredentialWrapper.Mdoc -> credentialWrapper.issuerSigned.issuerAuth.payload?.status
    is CredentialWrapper.SdJwt -> credentialWrapper.sdJwt.credentialStatus
    is CredentialWrapper.VcJws -> credentialWrapper.verifiableCredentialJws.vc.credentialStatus
}?.let {
    invoke(it)
}

