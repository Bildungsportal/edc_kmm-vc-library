package at.asitplus.wallet.lib.agent

import at.asitplus.wallet.lib.data.ConstantIndex
import at.asitplus.wallet.lib.data.vckJsonSerializer
import at.asitplus.wallet.lib.iso.CborCredentialSerializer
import at.asitplus.wallet.lib.iso.IssuerSigned
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.matthewnelson.encoding.base64.Base64
import io.matthewnelson.encoding.core.Decoder.Companion.decodeToByteArray
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.encodeToString

class StoreEntrySerializationTest : FreeSpec({

    lateinit var issuer: Issuer
    lateinit var holder: Holder
    lateinit var holderKeyMaterial: KeyMaterial
    lateinit var issuerCredentialStore: IssuerCredentialStore
    lateinit var holderCredentialStore: SubjectCredentialStore

    beforeEach {
        issuerCredentialStore = InMemoryIssuerCredentialStore()
        holderCredentialStore = InMemorySubjectCredentialStore()
        issuer = IssuerAgent(EphemeralKeyWithSelfSignedCert(), issuerCredentialStore)
        holderKeyMaterial = EphemeralKeyWithoutCert()
        holder = HolderAgent(holderKeyMaterial, holderCredentialStore)
    }

    "serialize stored VC" {
        val credentials = issuer.issueCredential(
            DummyCredentialDataProvider.getCredential(
                holderKeyMaterial.publicKey,
                ConstantIndex.AtomicAttribute2023,
                ConstantIndex.CredentialRepresentation.PLAIN_JWT,
            ).getOrThrow()
        ).getOrThrow()
            .shouldBeInstanceOf<Issuer.IssuedCredential.VcJwt>()

        val entry = holder.storeCredential(credentials.toStoreCredentialInput()).getOrThrow()
            .shouldBeInstanceOf<Holder.StoredCredential.Vc>()
            .storeEntry

        val serialized = vckJsonSerializer.encodeToString(entry)

        vckJsonSerializer.decodeFromString<SubjectCredentialStore.StoreEntry.Vc>(serialized) shouldBe entry

    }

    "serialize stored SD-JWT" {
        val credentials = issuer.issueCredential(
            DummyCredentialDataProvider.getCredential(
                holderKeyMaterial.publicKey,
                ConstantIndex.AtomicAttribute2023,
                ConstantIndex.CredentialRepresentation.SD_JWT,
            ).getOrThrow()
        ).getOrThrow()
            .shouldBeInstanceOf<Issuer.IssuedCredential.VcSdJwt>()

        val entry = holder.storeCredential(credentials.toStoreCredentialInput()).getOrThrow()
            .shouldBeInstanceOf<Holder.StoredCredential.SdJwt>()
            .storeEntry

        val serialized = vckJsonSerializer.encodeToString(entry)

        vckJsonSerializer.decodeFromString<SubjectCredentialStore.StoreEntry.SdJwt>(serialized) shouldBe entry
    }

    "serialize stored ISO mDoc" {
        CborCredentialSerializer.register(
            mapOf(
                ConstantIndex.AtomicAttribute2023.CLAIM_PORTRAIT to ByteArraySerializer(),
                ConstantIndex.AtomicAttribute2023.CLAIM_DATE_OF_BIRTH to LocalDate.serializer(),
            ),
            ConstantIndex.AtomicAttribute2023.isoNamespace
        )
        val credentials = issuer.issueCredential(
            DummyCredentialDataProvider.getCredential(
                holderKeyMaterial.publicKey,
                ConstantIndex.AtomicAttribute2023,
                ConstantIndex.CredentialRepresentation.ISO_MDOC,
            ).getOrThrow()
        ).getOrThrow()
            .shouldBeInstanceOf<Issuer.IssuedCredential.Iso>()

        val entry = holder.storeCredential(credentials.toStoreCredentialInput()).getOrThrow()
            .shouldBeInstanceOf<Holder.StoredCredential.Iso>()
            .storeEntry

        val serialized = vckJsonSerializer.encodeToString(entry)

        vckJsonSerializer.decodeFromString<SubjectCredentialStore.StoreEntry.Iso>(serialized) shouldBe entry
    }

    "from OID4VCI credential response" {
        val input = """
            ompuYW1lU3BhY2VzoXVhdC5ndi5pZC1hdXN0cmlhLjIwMjOG2BhYcKRoZGlnZXN0SUQAZnJhbmRvbVANNfVntNqVZrEiz788RhxacWVsZW1lb
            nRJZGVudGlmaWVyY2Jwa2xlbGVtZW50VmFsdWV4KFhGTis0MzY5MjBmOlYvUzQzVjZKeUk0T0JsR3ZUT1NMZGQyYjEvTT3YGFhVpGhkaWdlc3
            RJRAFmcmFuZG9tUMQYtxNfo2zH_NXd9MdhZd5xZWxlbWVudElkZW50aWZpZXJpZmlyc3RuYW1lbGVsZW1lbnRWYWx1ZWhYWFhHZXJkYdgYWGO
            kaGRpZ2VzdElEAmZyYW5kb21QEhicTyJmeql0frwdoqA1LXFlbGVtZW50SWRlbnRpZmllcmhsYXN0bmFtZWxlbGVtZW50VmFsdWV3WFhYTXVz
            dGVyZnJhdSBFcndhY2hzZW7YGFhepGhkaWdlc3RJRANmcmFuZG9tUJtwaw0B4rR0Xo11JbCnCMBxZWxlbWVudElkZW50aWZpZXJtZGF0ZS1vZ
            i1iaXJ0aGxlbGVtZW50VmFsdWXZA-xqMTk5MC0wMS0wMdgYWUz6pGhkaWdlc3RJRARmcmFuZG9tUMfXvvbGq4FZMyotnj7pHtlxZWxlbWVudE
            lkZW50aWZpZXJocG9ydHJhaXRsZWxlbWVudFZhbHVleUysLzlqLzRBQVFTa1pKUmdBQkFnRUJMQUVzQUFELzJ3QkRBQkFMREE0TUNoQU9EUTR
            TRVJBVEdDZ2FHQllXR0RFakpSMG9Pak05UERrek9EZEFTRnhPUUVSWFJUYzRVRzFSVjE5aVoyaG5QazF4ZVhCa2VGeGxaMlAvMndCREFSRVNF
            aGdWR0M4YUdpOWpRamhDWTJOalkyTmpZMk5qWTJOalkyTmpZMk5qWTJOalkyTmpZMk5qWTJOalkyTmpZMk5qWTJOalkyTmpZMk5qWTJOalkyU
            C93QUFSQ0FJVEFaMERBU0lBQWhFQkF4RUIvOFFBR3dBQUF3RUJBUUVCQUFBQUFBQUFBQUFBQUFJREJBVUJCZ2YveEFBK0VBQUNBZ0VEQWdRRU
            JRSUVCUU1GQVFBQUFnTVNJZ1F5UWhOU0FRVWpZaEV6Y29JR0ZDR1Nvakd5RlVOVHdpUmhjK0x3UVZIU0pUUmpnNVB5LzhRQUZ3RUJBUUVCQUF
            BQUFBQUFBQUFBQUFBQUFBSUJBLy9FQUJvUkFRRUJBQU1CQUFBQUFBQUFBQUFBQUFBQ0VnRVJFeUwvMmdBTUF3RUFBaEVERVFBL0FQa0FBQUdB
            QUFVUXJVVUJCUm1BQlI0OFhDOVV4Q05tdUF6S3JTNGxWV3pzckx0UEkxdWJXZ3RIMnlxRXNETHpWaWtjdWQxKzlDdlE2aWJLdVF4WDZ3cHJWY
            lIzWGFwTmtxK0pYUnN2VEtORjFZOGQ2aEtTeFlMM2pzdTVlU2d6UDFGenlVWFBydGJrQkprZTVwV0hvYVpmY1FzblNiNnFsNTM5T0oxN0FwS0
            9tU0tldFZiZDdPSldqM1BaS1laNU1BbUs3UVdXdTE4bUphbkRhTXE3RkE5WmtSdnEza21XMmRzaHBFOW9LM3FvclkxQTlyUkYvbUdmVCtvYXJ
            zNFJ0WDdRS1JyWDdRYWpwU24xTVNXVDI4N0N0WnR1SUJHNzV1dThaWWxTOHN2RWRVUklGdDNqZmJYN2dNVWlaWFpLcDJrTUxXTjhqb3Ircll4
            eVN1MHZ5bC9hQWxxaksxaHBLU1ZxdFhINlZkd0NDc1V3RmF0UUlNS013b0FBRGdBQUFBQURBS01vQUF3eWt5aWdWS2VIOUNhbHZCUDBLVXpLZ
            WdCS1RBQ2pBS0paeHdxQXBKaXBJQUdXZ3BTUE5xQVYwa1diR3haY3pISEpXU2c3ZW5MWmNpaG9uYVNCbGJpVGtTMVpPNG8wcXZBcU45cDR1MV
            VKU1JaT2svdHVhV2x5N1RKUGkxU3F1ekpsdG9GUGIzYzliR1hJU0FOU3JOSUF0RTZiTjdpa2JOMDE5cE9TblNyeUxNdFVRQ2JLeXh0SXo3bXp
            GanE5MmI3U3NtMzJrbzhVeXk3UUlNMmRmM0YxL3dCWHRGVlU2K1NEVnZGVHVZQ1Y4Ylh4UEZlazk5dzhsTGRMaXBGa2ZxVVlDMGpjRkZreCto
            ZzZqT24wZ0JPMjlpcTVya0kzdExyRXJaTlFBamhlVmVCYWZTVzBpdmZOWHFWamo2VHFsa3MzQXZKakF2NzZCTGlaclphRUdUUExFN0xRUE1tU
            Ek1c2kwc245NFVuQXEweWJrYU5Tcms5SW5WbHJ5TmNrRHJxMVJjZ09mMC8ybnNpMFZEclR3UmFORjl4bW5rZVhGWWxLSEtZVXUzMEVsaUpEcW
            x3WktGRmFpMi9hTFlDWURNSEFCUmdVQUFBQUJobEZHVUtWVXQ0VitCRlQzd2Y5Q2dnd294S1Fvd293Q2lqQ2dETmZjU1lZVUFxWGp4ejVFb0V
            zNWRmYUFxM2ZMRXFyTzBWQ1RZUGtDNHkzQXV5UFF0QkpmY2hDemhIdXFCczFNUFY5cG1qaVpWYjJtMk5GbVRwTnVGNlR1MVZaT3F2OGdsbWJr
            bzhjbGFXR1pHYWRyWTI0a0t0ZkxGMWNLTXl2dnJ1WWZxczhqVysyNUpuckJsM2xHWlduN2NNUUk3b0xXR3BSWTBZdjB2VFd1eHY3aVU3SmJsV
            UJWeW5aK05Ra2QzbldOVitrRmE4VFZXcWt1cjBwR2w3ZG9DLzVqV0NSbit3YTdxMjdjZXF0cTdhTUJMYXFGSThrMkUyWkVwWGFvMGRtNVZBZG
            EvU05IVmMvM2tHYmdDeVBXak1CclY1WmEyZXlHdGZTcmJFd1Iyc2RScVN4cW0xbEFsNmtlcFZ0dnRGODEweU1uWFhsdUt6eDZtUEZvcmMxWmV
            JelNyTHBsdHViM0ZKY1Nyd1NxeTNPL1BIMDFTZW1GYkhOamdWNWVsN3NUc3EvVjBsRzM3QU9iUGZXVlhrUm5qNkZlLzZUYnBzWmJmNlNtYnpL
            S3JLeTdiQWMzVTZaNzI0Tm1TcFZhdHlPaHE0blhUUVNzMmJZcjlCbTZkYWUxQ1ZJejRZZHBBcFlWRVo5c2JOOUlBQ2p0RXlwWmxaZnFVWlY5S
            UJLaFVMREtCT294U3R5WUFNdTBGR1VwUnhxaWplSGo4ZkQ0Z1RHRkdKU0JoUmdGRkdCZ0VaQmFYY3JsVUkxcTRGRmlyVDNBcTlwZU43MlZnal
            FDRWlWSVdvWG51ajNvTFd5MUFxc3VPSXlzanZzMm1aVnE2bWxscW9HdUJhcXJVK2h6UzBienAxVjNLWk5KUDA5M3kyM0t4MU9nanRlQmx4M2x
            KY3VTMlZueUY2N05SSmNqVHJvSzU4VzVuTms5T1RJa2E5V3FZVlczY1NqVHF1cVgycVJ0azBiWkthSTI2VldYanZBRmtwZUxjWm05VTJTSWla
            cTlqSXVUdUZHa2tyRjZSQlZkMXRRYjZ0dGg0R3lmcTlvRTIzL0FFaksyRE5nSnVseDJpeWZNeEFCYjFYSVpzM3hGOWpZZ0l1VDFHakNPcHNWV
            nA2WDNnQ3FpUGRueE5Lc3lONlV1NWRwZ2s2a1MzR2picXZ2cXdIYVdlMGRGdmZ0c1o0NTRGYkhldkR1SUsvK1kyUEU4MWNWVzZxbEphcDZ1MT
            RrcnpONjlOdE5kVXlsVTVta2w2cis5VHFhU0tzRHg1V2JhQkJWNkViVzM4eEo0dXZvMVp1NFpXU1YyWGJ3RlphK1VVWjl0Z09mNWsvVjZEZHl
            HWFUzV1A2ajNVeTJsZ1ZlS2wxMDNYWldseFJkaEttWFRhVDQrS3UyM3VZN3VtbFhRb2t0Rlh0ZVE1N1M5TE9xWTdUSTBrczh0NVd5WXBMNkZ2
            TmROTzd2S2tFdmRnY3Z6TFRhYVRMVEt2c3FaTWRsVWIvem1TYVpWZTBUS3IvVmlGTTIxNkROVjl1TER5TGxidUpNM3NRa01vOGkxekZabVlaY
            2xLRWlpaTFHVUQwZndKcVdWSCtBVWtNSW81S1RBS05ZQUc2ZUl0Z2piTGNBTHZvRExtVVpEMk44c2dGalp1cmMyMnd4TXlyblc5VFRBbU5XWU
            NESmRETkdtM0kxNmxHV1hIYVo1RmRXMmdXNlNzdTQ5cThzZEc3Um05WFJxeThjSElTTjA4dHkvd0JvRGFac0dSaXpUeTZhczYrckhzQ0JZNUs
            xZFZlbXgrUjQwRXNVZUsrbjJkb0ZsOHd1bnEvS2JhL2FTa1NKNU1zZmNaRlN0MDRzWHAxOUxTK2E0Z0xxWUt2azlmOEFjU1dWNzVmZVhWcEtV
            M0w3aUxRTlptZys5ZTBEb3h4Sk5wcnEyVmQ1aWtmMU1TbWsxUFExUzJYRGtOcVZYcTlTTGF3RVdXeUxYYVBJeXBGa2xsUGROQTh0NG1GYUo5T
            kkxZ00yUzdSZnN5Tk9mRkxBc2F0Sngrb0RNMi9FSzRjRFcybVRvWDl4UG9aWXVyQVFhSXJsME1pbTdCdDZsMmdka1dYSmxBZ3JJclpMdTd4L3
            l5dktqcnVZOWJUTStlNHZCRnNxOXY4QWFBc21lakthYWttbXJMeUt5UkpMRXlMdjdETm9YckwwcCtLdUVyZVc2UDhBNG1TM2FkQnZUMU1VaTd
            hRS9LNTBWSFJtUWFkc1ZhKzF2M0ZDYzZkRFdUdHdiTkRKcTVuV0I0dUxGSkpPckYvK1NJNStwbDZ1ZkZrQWxCQjFOU3FjalZycE9ndE9LN2Zj
            ZWVXNHl5eU51VXplWVg2cUVxUlpubFRlTkhzb3VTbW1EVEpYcXlvblQvYllKTlQwS2RKTW1BbXNHRzFLdHVaaG0wRXRheHhZODJ0WW91cGwyZ
            FZ5cXlJN3NqUnY3blZhaExrU1JNdURZMUZWVU9scm8rNStxamJaVEMxZ3BOdCtKZU5xd043aVZUMlJhcUI0dTRyWVNNMEt0c0ZLQ0YxK0VTTD
            FQNitQNmlkT3VYQW03djR0OFFwTVlVWWxJR3NLQUNqS0EwZUxaQU5aSzhobHF4Tm5GVzFnTDdYTk1iT3pZbVJXdTlHS1JwWEZRTGF1NjBiaXh
            uNjh1enROMXNhdGtyR05vcWJXeEFJSjByOHVyRjFnNithcld4alZwVmJFdkhQMHMxZkwyQVRiVFN4TzlZM04razZpb2xYdDlZc0d1bDNaRzdU
            YWxKTnVQMmdKcVkwNlhWYUxQMkdkWWtSRy93QjZIVForbXU1R1ZpY2JRY1ZSV0E1ZlNnWi9WdU5KcHFyZGN2Y2h1a1c3NUhsNllOSGdCelpOS
            mgxRlhKZDVwVmV0QnRSblgzRzZPR0pvM2ZKcmJpYTZaRWZISktnWmxneDlKdVhQdkJZb3BGeSs1U3pRZExOY1RUZTI3ZDNneTViUTBkcXF6SV
            UvTFlXclg3RHBMRXJaMzNFNkx5eUJselk0R2piZGFOaTY2SlpYWjR0djhqYzBEZllValZWVEhFblNzc0xhUmJxN1hWKzZoZlNKUzBUSTJSdWF
            QbmM4clRhTkdXU1R5MUY5UlZxdllPdWh4eGlUYWRCWlBiYXd5dlQvQUdnY2xkREtqcThxNEV0VHBxNGNEc3lTV3dFYUplbFZrc29Ndm51bThF
            dHVCNnN2VjAwc1gzS2RQVTZIcEl6eGJUak5lTGJ4WXJTY3BTT3lzdGUwamJxUU1uSWFSckxkZTRtejFlNEdsWHRCZFFhTlpaMWxiSk9ZTDJkM
            jA5c2w1RmJrdGNBSXo2bTliWkt2QW5IRjFKMmt1SlhESTB4MWpXak51Y0NpN242U3ZqdWM4a25pcTZkT3R1Yk9lU1VvN3l5MmJqR2hta3lBME
            sxdEcwVzZyV1doSm8xN250OUlMUkV4Y3BIWC9xKzBCVjBiTWwxcFl5TWozTnNrdHNhTXBCZ0p4cVdYQW5kdi9GRytseWdNd3Z3K1A2a20zRGV
            ETjhBUEJoUmlRQ2pDZ0N0bU16WkNubTRCOXduRUdQWTN6QVpiMHNhNDI1OFRPdTFxbnF5c3NiS0JlU2ZhSzFHSnN5bm1JRDFhTDNGRnN5MmJG
            VkozYSs4dFhBQ0ZXa2JsVTFhWk9sdHNNcWxOcEttenF1MGRHeTk0aXFFYVdYM0Y2K3dOQ3JUNlQyaVB1UGFqclhqdUlVSTQrbHlOYW9sek10M
            zNHbGZiWUE2YU00clFKdXFNMjdjQ3RrVWt2VHhCY1h4cllaZ3JtU290Um1ERUZYSUIxUVpnVlJxZHdDOHhTbFFxQUsvY0ZiN1JxOXdWcHRBak
            l1SEpXT1BxWTBabXg2VC9BTVdPNTlwRFV3ZFZTaDh1MGJ4b3lzcG5aY0RxNnVDUlVmQTU5Y0dzV2cwRCtwbHQzQXFQMTJkUlZmTysxU2kzVmN
            lUVN5TmwwbFVxMGl5Nm4wbStvU1QwbStyY1oybnF2K3dDOGpSd002UmJ1NXlEVHN6OXorNGx1R3lBZFdadHpsYlVTdUtrS3ZiaVdqUkV6YjFX
            QXZCcE5UcVU5S0tXVTFyNVRya1d6UlJyL3dEdHlOV204d2x1bHVrcUw3VG82Wm8yaXluamliZm1wU1hDa1NlTlBWaVZrRVhSSk90NHBFdC9wT
            2RlUkdhVm1nZlJ6LzhBU2FwZ2tSOEUydXZIa29ITWFCNG1kR1IxSi9hZFJ0U3pMMDU5ckdPYlN6ZUQvQmY2QlRFT0lWcGhZa0FsVDBaZ0dyZ0
            N3SHFvOUNpM1JBRWtqeHhKcVVrZk5BV1JPUUMxNXF4SnR4b1pMYlNUQUl1VEY2azFMUnJjQjQxb213c3Fkd3NkalRCRm5rdWJISlJsaXdIVmZ
            1YzByQmgyb1ZWSWwzTlp3dEtPTnIzMm1rUlpWWGlPMDhmdmI3UUJZMEt4d0VsbjlqMitrdXM5UDh0djJnVVZVVnNWUFNQWGt2dHFPc3M3N2tR
            QjIzQm1ETEpROFZaUUZaKzBQdUd6WGNvOVhvQkpWTHhvS3E1Rk9aUWVvVTdpbHNQKzBac2VDZ1JyOWFqTkZYZ04zWWpaVTJFaU8zY1VxRE43U
            1M0OHdQV0ViYVBKWVRHb0hQMWE1bkxuaHhvcW5ZbjM3REZKUnRybG9weDFpem9VWnVLbDVQbnRiYXBqNnI1TVVsbTFOcEdJTEJmTm16UFoydE
            pmYjk0M3VZQTZTTFlUN1MxbzJYSXNxNmJCMm5YOW9HTlYrNGRWOW44anF3TEE3NDVmYXgwNCtsVHFMYi8rVlFuVGphYUt6S2pRU3EzR2g5RER
            vNVpVcld2MWJqMlBVckZIMVduWlZYOXpITG4vQUJKT3p0MEZxbjE3aWhwMWZrbWQ0cDBabDk5VGlhbUJvM3JVZVR6VFV6N24vd0RrZWZtWlZY
            KzlMZ1Z3YUtyRW9Xc253K1B4K0g2ZkViOHpaSzhUTkluajRTTjRSLzArSUdRdXEyU3BKUzY0c1NwN0hBNVJZcThEV3EwVzNNbGFtNHBJanBTa
            FpZbFp6T3pLNDZ1bkVCSjRHY3hTUlAybS9ycHlJeU1qYldKVXhacWVYdTVXU29RUjJRQmwybDR5VExWeXNhc3pLVFNteVBwcStYOERVdHVLcW
            91bWpxbkFwc3JZaGFzYTJxek1YV0xNV05HazI1S2FWV25hQXJVMlUvWU1xL1NOOVA3NmxWci9BT0lBUnhxdkFaZ1dSZTFoc2Q5V0FWWXN4ZWx
            sMmwvcGpZOFZzOG9nSjFidkc1MXNnN1NwZnRQZXBFdi9BUGtCV2l3RnJnVWFkSGZGd2FmcWJZaWg0dU5Cc0h6d0ZXVkZmWXkvV3BSVzAwcWNU
            UlZWUnZhSTFrK2tib0t1Uy93QnI4bnpBV3ljVkRCV3MyOEsvd0RjRmZkOWhBV1R0cVNXTEFac01WeUoyZkhBQ3NlTzRrdzZzaVBsdFladjRnW
            WRTMkp6WjhYNnAyWklySnNPZkpFV09WSjZpeW5PWnNQY2RlZUtrdE5xdGhZNVU2VWRrOXhUa3c3dUlLbnNLdDdoVjM3NmdValRMTEd4ZVBwMC
            93QjVLbDNWbTJxeFhTTDF0WWljUU9sSExIVzhVZVM3cEJvSlozZnEzNlNOeWR3WkU2U1ZUcE15bUNkSlc5Sm5lcThDa3RQbUhtREphTEJuL2N
            jK05KNXJQMHFpVlZkdVRkNW9XWC9WZEtmOUlsUm8vTDNlbG1pVlY0MnlZYWZTVDlzVExUYWhwMDNtRUVFRlZnNjdlL0VSdGRMTGwrVWlpVUpa
            dnljckl1RmZhN0I0YVdUdzhQaDRWK0JmckpQbkttM21YcHArZncrUHRLSEVYY2FWVXpSNUcyTmE1aFN0NktRYWZ1UU9vL0lXcElicTQ3QmJEZ
            EtpM0lTV0E4a2ZNaEk5U2paRW1VQ0s1SFJnK1VaRlhJMHJqRXdvays3SmlrRXFvL3U0bWJKc1YyRjQwcHRJZFhTZ3ZKdWZIMm11T0pLYkNFQz
            NWY3FtdVBEYW1YSzV6VTBMN2hySUowbmZrYUZYT3ZJb0l1WE12VVZWcXcwajhRRDdobys0VlZ4MnFWYmFFbXNGaFkvY01ySTc5eFFhbjNNR1Z
            TdlZSZUxrcEpNTm1JRS8vSEpSczE4a0JtWjZvcVlsZlZaZHRTUWJnV083WlJJZXE3LzZXZjBsVmxSZHhRbDBFcGl6cjlBNnE5RlJwRys0dGd3
            N0tpOFRSbWFUdVhIdlVOMjRibUt5MTRFQ1RQVjZ0dFBmdEdyWU5xdUJML05Ic050YklVQlBZeW1UVXBWcUttWnJrcVRrVzMxbHBjclV4UFM3T
            Dd6bHpyVlBmVEpENktSRjVXcWMzVjZaYzJYY1VodzJYdVN5cUw5cHAxTVRkWExMSGFUL0FNaGJBVFoyVGNtQnE4b2c5ZThTUCs0anExOUpiTn
            VIMGw0cFZrVjZ4S0IzTlhGZlRMTFdzWnk1MjlCWHJaVlhPNTNKSXI2R1dKYjJiWWNlT04rcEluY25UVDNGSmNlU2Q4YXJFdjBEUVdta29yKzl
            Ra2pvbkNuKzhJTVpiMXNTcFRUUDZpTzNxdTIxRFV5TlJIbEpSd05lN09rU2U0dTBtbWoyeXRLL2VBVHl0cG9PQzlxR1h3bmtsOExmQ3Y4QXlQ
            V2xzOTZqcXIvRGFCamphakc1czF4T1pYTTZTdFdCU2g2dUtpZFZVRGM0clJFaXZWUjl5Q1dUWXhObzNFWldBOWtpWGNyR2RpcEpnRGtYYmlwb
            TVtdXVBb2tLaGVCQ2FtdUNKdXFxSEtuVjBOSkZzeE4zVE04RVQweU4wYTNaUW84YVY0bDJaZnUra0ZXcmxGZDdoTEpXMlFjNkdtbDNKU1IrdT
            dxb1VGK2dHWWZpRmNBSlNQVmRvMGJ0VWxyaytYMm5xdFpnTHExVHhzM1czRVhwZG9yWXZsdUtTMFIwdjNHaGFiVENyS1hqZGpSdVduWWVkSlh
            DUGFWV29HVnRNanR0UEdpYllybXV0eWJZQVFwOW9NcFN4Tm15SUV1WXU1c3R4ZHFDTXRkb0F5NFY0bWR2YnVMY3FzTEl2YnVBeWN4VnN2REV0
            SXRzd2F5c21TZldXaGpabVpNdG9qT3RibEpKY3FNWlBsU1pKdUtHWFZ4STkzYmY3REIrV2JvZFgzRytmcU8zcFltVmNaSFMxV0NYa2tFZU94d
            lozR0xVeVcxUHNYWWljVHBhbVZMc2tYYWNxdVlIMVBsRTZUNmFLenV2KzF6bHlNeXp2bHR2bDd5RUdwckVxeFk3Nm9HcGxzM1U1TWxIS1N6Un
            p5enBMRjdkNUQ4eXlyWEQ3ejJCc3FjanlSWFdYRmJLeEtsT292OEFwcUdVdTJqZllMSFdURm5rVi9vS3JFbTlwSlFHcFI4a1BGbWs4UEQ0ZkR
            3OGYrYW1oWC95bXlUNmlUZUtwNC9Dd0dTTkxPYlZpd1BZMVZVR1ZpaGRZbFJCYnJ5TThrcmsrUVN2SkpGVXpkUUdiRGFabVptSlVxejNNclpG
            ZllLQjVwbHZLcHVaTnhpanhuTHlTOUtQTGtLVktrY3FHL1RUd0x1ZEYrcytlNjl4bFoyNUU1TlBxLzhBRUk3MFZqZEJQelBqWTdLMjg3R2gxT
            Hg0TXd5dlQ2ZFo3SlVGWldrTVdtWldqc3BvV1JMNGtEZEh1UW4zZDF3Z2t3WVg3aWg0eTJIcmdNcUhqUllCU09wbHNpbUsyWnAxS3VpbWE5Tn
            lraldzdlRYM0hQMVBtRUNPOXBWNnZhWG5sdEUvZWMyUHkrSi9WbFMwckZwVVh6ZU5qb1IrWVFVWExjZlA2dlJScSsremV3eExlTjk1U05QdEY
            4eTZmSXQvaUVmSGNmSmFhU1JkeldVM1J2MWIxdmJ1QStnL3hWS1pJYVlOU2svTStjajZsTWoyT2VTQ2NscjZaaWRTV20xa1U5RFQ5Smlpc2dM
            dnlHNEU4MXBZa0kyTEh1TERNdk1LNEFRVkszU200aE9qWHlOYXI2ZkpqSlBSSHZUY1dsZ24zSmdqZHhLUmJSWFdsYUdtZGZUK281bW1razAwc
            mNsNXFVaDdxV3JWRy9laHo1R1Q1VjlwMUorZzhIVmlPYkpGYmRBd0VlaEkxV0VuZ2w1TmdWWlgzcWoxOTU0c0RObmZkN0FJcjJyZXE3aXZzM2
            R1UjYwVTZ0dlQ3VHpvTTZQbFVETmZvWjRzMXpQRy9iaWJtaTZ0VWFyTXEvdUl0QjA4YVpBZXg1YnBYVTBXbFdQZFpQZXBDUHFjVS9lYVYwMGp
            MZGtaYmN3SVJ0Ry96WXN1OUhOUC93Qk8vd0EzeFptLzl5a0drbG5hcTZaV1ZlL2FVOEl0UEg0VVpFYnhYLzFWUWxnM0ZOcUhqWXFRNnRuS0Zh
            dXpublNvK1FLMVVGWjdraEdjWDdTcWlTZGloU2JZRW1LMDdpVEFld0xhY2xxWmJTbnF2Unp4WTdPQktwV05HT2xwTkRYNXFHNWRGSGJIK3dHW
            E1qajlwZU5rR2swelJQaVJiRGNGTzc1ZlA2V08wM0syWjh2cHRXMm1mSEtNN1dtMWFTWkV0ZG5xb3NWd1Y3blBacmN6WkcvdUNuUWoyWklISW
            xIM1dMcXk3UU0yc1ZxZXd4c3FOdU9nekpzYmtSWmVCZ3h0QlJDVFFKMHNtNW5ScmNoSTZyanlOR0Z0TjFIZHFiaUgrREk1MEdiSVhxNGJob3l
            oSDVUR3UwMXI1ZlhhS3M5ZVNsUHpPZHNiRFJsNStXZGZjVWJUSklsSlZHajFqeXBrcUYrcmI1UzFiM2pSbHo0STMwenVuRTZtbW54cEt6a01X
            d2JmM2gwblRhNFMyVy9hSkp1SnJkdHYzRlczKzBoUmVKVGh0SjdTaWdUWkRQVytMSjlSczVXVXh6NFNiZHhSVEUyN29OeTVqU2FaR1h1SGtTe
            kszTVJ1TFJidnFMbHljMVlyTXk3dWRSMmxrNlRMMHJSKzQxdEZGTGxSZmZVRmdzdnpYdmJaN0NrdVhWbXhpVzFlSjUwV2JkSzZzZEJ0Slg1SH
            pGL2tTazY2TGY4QXZDR0J0TThHM2tRazAwNlYzMTVIWmswMXJ5MDkvdE15bzlickxsKzVXQ25MWmVvemVsVmxDT1ZrcmFzcm0vVjZicG9zcXg
            yNVZ0aVlGV08vVnRVTk1zaXpTMG92OXByZ2lqZkpaWFpsNElabFJKUG10WDNWM0d5Tk5EMEtkV3VmQURYSjFXU2pLL1ZyaWlPVGlnOElmaXJh
            aFlmSDQ3U1ZvSlhYL2phcjdGT2pwdEpvMVR4OFpkU3NyZVBqOGJNb0h5OGo4U0Zjd1ZyTWFZMVFrZXJsd0pNV2FSSzR1VFprS0Mxc0l0VlBHZ
            XUwOEpVR0l5YmlqT1RrM0FJdS9JM1FJcTFkY25NUE0wd1B6SnBVdXZIa21TN2l5eFhpNnJQVXhmbVZpUzB2MklSVnBkUzNVYWZwS3ZGVEZ0N1
            JPOWNkeEdTQjJVcCtVZXVXc2xVUHkycFQ1R3BTWDJNYU9YUEE2NXFFR3NlSnFtdmM3SktuU2w3VERxWVVYYUV1cnB0Y2p2Vmp0NlowWGF4OFR
            wcCtsS2ZWK1VUckxHRk83QStCZXRpR20rNDAxYnNjQ2NrSE1sSWhwa2ZpWnBMQVRaekd6RGFtZEZmZWMvcU4xZG1CQ2cwOXAvcFBaTmRFbUZY
            WnZZcERVNmxWZWk3eUN6OU44UU4wYzd0dDBiL3VxRWtHcFpQUVhwUDduRVhWdDlSdWdXVmtBNXZYMWVtK2ZCOTZuUzBtc2ltV3k3aTlXMk1oe
            jlUNWJsMWRNOVpRT3ZISmJHVlNtMDV1azFmVjlLVmF5cWRLTmw2Vm1TeFNRcnB4TDdrb1FaZjlMYjJESzM3QUs4QVc0MktjQmxKQzdVTTBrYl
            NLNXBaeGRwUTU4YmROOHNWcUZiSzZTNDlqRnBJVWQ4ak16ZExDM3BGeTVVZXJ4cy9KZTFDdURMV203dkViTmJydTVETEhXTEd6TFRFdEtUUlN
            xMTF5cVJuYVZZN3N0bDdhRzJTK01xdG1UWmtkcnN5bEljdFpPbXRvRXRDMjVPMHpTUkxJc2pMaktwMVB5eVFQakZheGprb3JMVkdaRjRFajNU
            TDFkSHRxNm1hU0hxNU0zcWM2S2FmWFd5N2UwbEhlVGQwL2RRd1pPckV0Y0haTzQyYVNEUlR2YUpyTXZjWTI4djZXMXJXYlk1UmZMMmI1VWllO
            UViYUZLejZab1c2dEltaTc5eGo4ZGQ0V2JwcGJ3LzhBZjRWTDJuaWRvR2VOb200aWVFY1NNM2g0VC9wOFExeUkwektNUVZxbEZDaWdETmtBQz
            hoV1lZaElTSzN1b3U1eEZZclVDVEtiTkl0cGF0dElNdUlOTFNKNmtxUFBQMVovYXA3K2I2Zkl5THhPaDVKcG81OVNsa0ttVTFRYnpMVTF1c2V
            LODNJd2VjU1F2YnBveHY4QXhQWkdpalZLb3FuSjh1MU1HbDFYV25nU2RWOFBqNFJ2dDhmRXJKeFRzK1BtdWkxNmVFY3ErRVRlUGQ0LzdqbmF1
            SjRIN2w0bW56anpiU2ViUjZWWWRFdW5hTmFzeWtaOUpxZE5wSTJadXJBeTJiMms1VnB6MlBvZnc2MTNiQTREZTArbS9EVUZVdjNFMHVYMWVrW
            EhlYWpMSExnWHNZRnlzU2tUSEVyWVdmNWVKbytkOHdabGtNbXAxUFFpOXgyTlRwTC9BRjd6aWF1RG9JelQydXpZa0tZTDF5WjYyTk9rMDJyMW
            Z5SStsRzNKeE5OcHJhbEgxTlZYdFBxZEp2dkZRdVN2bDh6NWw1VzJoMHpTejZtVm00cWh4OUg0YXVlYndqMG5qTkpKNHJhcXNmWGZpdlRTeTZ
            aSlZURlQ0K0dmVWFONU9pekt6ZUZmRmwzSFRMaHAyRzEvbmZreVJMckZ0SEt0bFNRNldrODkwMnE4VjhQaTBNdmh4WTRubUhtV3Y4eThkT3Vy
            Wlc2YTRWT29uNGVXVHlaWlc5S2JjckUwcWFkTFZvcnFyOXZJMCtYNm0xbzVmbXFmTmFIV1Q2WmxnMXlZTnRrT25IRzdaUVNQMW90dCtTRXJmU
            lZQS3E3bVRRNnZyclZzVzVLYnVlTG1CbFdvWGV3eTBDdVlDVnRZVnNtS3JndVFwSVZWeU1tcGp6eDJHMVVJVDViYUZvWnRKZGxveXRkZGpLRU
            VycS9heWxOREpleWNpc2kxendzeUhSekVpV1hGZmVjL1V6OUtTN0xheDBJR3N1SkRVd0k4ZFczYjl4YUdiNTZyVjZvM1prWjUxNkh6VmI2cUN
            Lc3NFOUZyWGtiSTVKSlVScGRyUCswRE15eGF0VnM2VnIzR1dUU1J4UDFJcnQ3ckhRYUtEcTIydDd6TnFmTGVwZVNLZHlSQ041THlvcnZWdURF
            MmtrVjFyUldJTlpIWE4yY1hVNnVzckxYNkRGUE5YZnIzNEdCclhiOWZqL3pPa3M2VHhldlczRmlEVitQOVBqL3pKYTVxakNnVTZHQWNTb0FUa
            0tFMkNVRjNHeU5GbzVqYkZpa0UzY1NORWo0VUlTYmkwajRHWm15Q2syUzIwM2VYeXRCS3VCbGcrYmRzaTY2bTI2cWdmUWE3eTMvRW9FbFdUUG
            dmTjZuUVR3UFdTTmxOdW0xdlNldlZaVjdFS2Y0aHo2VnZyR2laSjVib0kxOWVmWXZEdUc4MzFuNXVxTGRWN0hQSi9NSDZkRlV3U1N5U1BaZ1p
            KMDg2SDFmbEMwZ1ErWjBpOVNkRDdUUXJSVXdKcGN1aEd4ZS9jUVhhTlV3TnpKU05TeDd1R2JNMVRIT3pkSlpWYlBzTVdwZVBXSXJzMWFuUWtq
            d2ZpckhEblI0SHB3TVNlU3NzQ3hLa1YxYjVuSTE2R1JvSHlheW5QYUx0MkJHczZKNlQ3alhSOUsycjAwOERSTnRZK2ExUGtTeU16d1R4MTdHR
            1hXYW1QNXFmd09qcE5iRmEzNU5HOWhXbkR6UzhvOGhnamw2czlwV1hncW5YMXlTeXA2dEZpN1ZQRjFldGxiMDZRSjdTYXdaWGxrZG1GVVRMTT
            JoZ25zaXA2VmR4Z2pnazBrdU9TcnRPNnNYcnMvOEFBYVNCWFJ5RnVldWI5VlV6NUhTMHpXUXhmbHE4cW1tTkhWdTVmWVNPZ29XY1NOaWxMMEE
            4cUp0S3NTWnM2Z0syNGhNVmJjU2E3dmtCaGpaSXA3WDNianFSMG9ybkpram81ZlRhbnBlbEx4Mm5XYVJVdWdxOU42bWJVeFBKVjRGcTZzVVda
            Sk5yRTVMeE5maVU1OU0wL1ZaZG4ya09oMUY2VjZxWG4xTkh4ZXYya21sVzF0dFFaVFhUWjFhY1prNkh5c2pOcnRaRkYvM2kvbVVlQzQwWlZua
            VIzYXFaTWNxVFRMYXZNbzNtVVViNU5iNkJXMXlTNXFnR2FkR2pTaWtsOFBIeDhQajQrRHQ0L3dEdWJ1b205UlhraXR0WURqSzR5aTFHcUhRM0
            VVY0FKTUt4UmliQkpTRFlzV2JhUjNNQnI0a0dpTlBGQjFoWmxzcEttWlU5TkNpeFg0Wmk3TVdOTWJQUzIwbHBsMHlwdXF2dUtkSlZ3VmJGWUZ
            0dXlLeU1xbzIwS1lmeXpkcGtuYXVDN2pYcWRaalhreGkweWRmV0xiNm1DWFE4djAxWGpQcmRNdUMyT0RvY3RUZmlwM285cERvMHMySUsySkpt
            QldkWHFVSGF5alJzSEE4WEZnUGY5eGoxMm02aTNYY3B1Yk45d3krNDFMNTlVcTlhWUdwWUV1YTlkcE1FZUxZWlk1V1ZxTnRJVXFzSFZYS2pGM
            WpYc1g5cGVPclljQ25RVGk1U1VhKzFSbFhtWDZmY3A1Vkc1R2lhNUEyS01WV0xlRExaQ0JLQmJtdFlsUkNjZnBLTjFNZ0hYMmxHYXBqamxzN1
            lmU1ZrdTFhNGdQSTlWRUJuZFZGWnNBSnllMGt6bFpING1aVnpBOVpWWXp5TFY3Y2l5NU1RMWQycWloU2VrV3I5dGk4ak11RFpLQ3FzRWZ1TU9
            wMWEzU0ptQWVTRHE3WGVudUpORlB4ay9pZExUTWtpVlVxMFdaYVh6WG1FRWtrV1c0ckJHOFhsK3pnYlBNSWxiRTgxZnBlWDFidERLZktOL3dE
            Y3Ria1hneFRMRlNja3FLK0tFbWt0eTJsT2JaSE5WL1lONHpSZVBqOFRIdEt4czFRQmxGS01TWXBUMER4bkZBR0ZiY0RDaEpwTnBtNUYyWWdCd
            WpXeUliNEZWSU1qbmFSdlNOOGZISW1sUzU4aTFrZHlxcjNiRFRJdDN4MnFTald6NWJTVm5qa281bDFMWk4vTTB6cnpNVWpYQWxJM0kwZVhydW
            ZreGxwWjZuYzh2MGI0R0RvZVh3MFgzSFRWTDh5TWFWVTByaXVKTG9hb3NsaG81ZTRWdmFFbXRVcXBKVno3aXFyWlFHdHlHMmdxbFZoS0FzVjA
            zMk1PcjBUSXZVaVN4MEZXb3dTNVVEWTEydDd6ZEJLeTROdUZrMHlXc0tzRGNYYjZEVk5kbnR4REIycVFWM2V5c3VRM3lNbTJoSjI5SVhOaGxi
            cVpWK2taWXZlWUl0QTdQaStQTThXQ2kxdWJGUzU1aHRzU0p4cFYrNGVTaTUwR3hxU2tiaXdBMlNrY3luRWt6WUJTYkM3U2pYSnN2Y0JKV3pZU
            zFwQjcrcmlDcjZvU25PajdqRHEvTGJ3STNJN3JSWUtnODhIcGw1Tk9EcEdlREZqczZackljK2VLckc3US9JQ21SbzFsMUpoODVsb3ZUVTZtbV
            cwa3NySHpmbUUzWDFiT1U1VTU4OEhVaXNxR1JjRzJablhza2EvVVlaNExOWlE1cDV5ZlFlS3pyNGZBZGxaVm91NGJ3OGFlRlFvTVFaaG1jaXp
            GS05ZQ0Znc0Vxc3dvbGhiQUxJd3BSc3hTUnAwM3lqWEhMc09mcHVScHRpb0hTeHFRa2xSZThUcllwOVJHU1ROaVhVODhobWJJR2JJS2RlZU9K
            ZHpOUURUNWZwck9qbjBPbVZWMms1SVl0Tk9rRWZGRGRCRWlrS2xhTkxGYTFCVkx4d081UWxTNmdxMU5qUVZKTkVuWUJrdlJ3V1J4Sk54NnZBb
            FRmcHAwNUc1b3JwanlPUkhsWkdOZWsxTFJ5OUtVcE5LMXhwbUxlaW1saURSV1kxSmJES3dNbUdRdFFHVmM3N1dGM3gwYmsrUTN1eURkdDRnS3
            JKWnZhVmpJTmJqWElhT3E5NEY5MjNjQXUwOXNydGp1SUhoSnFNeUlWWVJscG5RS0t5MUliMnkyRitKUGtBM0F5dGEvdEx0amx3SnlaSUJCcFI
            xK2FKd0hYY29TM3daeUtYMUxxWW81bGdSdThTU2Q1RHFJYWxiUGlYVmVob2ZlVGpnWm5LYWw3TXNTaEtNN2ZsdkwyUGx1aDFlcXgxdnhCcTYx
            Z1Z6bDZhZEZ3WUlwalozYkExVHFrY1h2Q1JVNjkxRmticnBRcExMV3VkeHZCLzBDU1BCU2RaT1A5QU16RW1IWWtIUW9EQ2twQW9BQUFBb0ZJT
            jVkV00wZTR0R0JWc214RGNDZzJBV1ZodE0vUzEwVW5hNUpoNDFWcEZ0dEpVNzhFM1gxMGoyT3JHM2FmSTZTZm9UMFBxZEpKYXJCVXVyQWgwWT
            RscWNtT1UxOWVpRkpiSjNNYk5aQ2M4L3VFV2V4Q2l0Rzl4K21YV296VTNBWS9sTWpqNmxiSjFlU25tcDJzdzJtZjBLMTRtbEgwT3BzbVJkbnA
            3amtOYlRUbTZPVkdRSmE4MjVncTRoRzExSHpBU29iZnBIekViTk1nQmxKWFJYeWNxTEpGMjRzQTE2dld1SVIwNGdxMFJlUVIwNGtBeEZVWmxC
            dG9FK09JcmQ1UmlEYmdIYmFTazJieG1GYmFCbVlhTmJ5cW5jQTBIejBBMXg2U3BWb01EWmE0akZqREcxY09ZZFBwSzBySHFmTWN6ZWE2dEZpZ
            GJWY3BENG56dlV0TDVnemRwQloyWkRYUG9FczdNN21PU0JJbXBFRTZYNnJ1bEZGV1I0eWFwSXZFWmRTdXhnS2RkN0ZGbC9RaTBpQy9tVjdDa3
            NnakFGZ29FeGhTUUFGaExBZWdBZ0RMdUw3U0M3alVCYU1XVE1GMmdGRnFEWWxLazVDUkxySmZ0T3o1YnJkcXMyU256N0lQSE8wVGxaSnA5ekh
            yTEduOHppZkh4K1lQVTNhVFd5eVBSVkpkZE8rMHFvbG1ZNWVyODcvQUM3K25ESklhWTlIMVd0TzJKdnZvb3ZRcFpsR1RUNXlQOFZOMVBVaFpW
            OWpIZjBubWtXcGlWMUkrWWVXNk9mTllGT2ZCcE9nejlKVytnSmRxZlVveUhRZ1NzQ21EeS9RdFpKWmYyblVYQW9wbTFlbXRHWVkycXlxeDJUb
            TZ1Q2pYSm9sc2diYWExMlpITjB6SFFWekJSaWJETTl1WXJlMDBJSEFWaHVBQXVRS3ZKaDE3djRpTVFGYmNEZGcxYWlOajNnZU0zTWt0eC9aKz
            RUdUFPWXJaTGlWWEZTWElDRGJob0Y5Yzk0bnVqK2V3RzdUU1kxTGtLNVhVcXBZbEl0RVoxUGg5ZDVrNzY1MWJiYyswMTBuVDAwcnR4US9OWjd
            UVHM3Y21LUlR0UitZUVV5SGJWNlpVdXFaSEVXVjF3WXRHa2M4dVQxRG5sZlY2eEoxMjFKd2FPTnJQSzVKb3VtOU54VFRTeHhmTlVETk8rZFZU
            RXpHblY2bFpYOUpLcVpBb3Rnc1NBQnJIb2xnQUxBS0FEQ2dBREx1TktzWkRTdTFRTktqS1RiRUZ5MjdncFZtUlUzR1pwRTdBa1pOdDdOL0V6N
            Cs0Sk96ZTBXdHVPUlN5TDNEVmRheXJqMnFCZlI2Uzhpb3lPek51UTdQVGkwV2EyT1QvaUR4cFNKVlh2OEFjWjExay9WZXJPdHZ1QTdPdTgxWm
            5WVmF6THdPckJyVTFPbVptaWRWNWUwK1I2cnJQMVd4Y3ZCcVhiQldmSUQ2TFRhMTJSWGlYdndPem9kVEhxNGxldVI4eDVWcTFndHhxeHNnOHd
            2cVoveTI3ZXFoVDZsWFRpVXNjQ0R6RHF1dUQ5WG5oaXB0ajExcTlWcWdkSWt5WFRJbkpxVmlURVRyMmxyZXJjQTFPanh6OXByamZBenRJc3Q0
            MithcDdITCs1U1ZOMXNGUFYzKzBRTEFGUWJhS0RPWUdDbWRnNVpCaXJnRWpDdHRFazJieWF0WkNROThQZVNWdzJvQ3J6QW8yTEVScllCdFgzQ
            VRiMm1GdFdzR3M2VnpjeHcvTXRNOCtwV1JRUG9vOVRkU3JhdUtKYnMxVlBrZFRQUHBvTHJLNXgyMTArcFdzczdGb2ZSZVplY3ByWlB5MEczdU
            9WSkNzUzNiSXd3WGplNm5RbmE4Vm01RkpwaW5qaWJOWEpSc0syRCswTDlvRjVFenVRa2ZpVXZSU0xCS1Fvd3RncEFVQUFBQUFBQUFBQUFBQzh
            KQXBBQnFiRmNpVFNQbmxpTkkvRThhcXgvRGZYY0F2aDRMNHhzemNlS2tiWUhyTlpRWktQa1VHVk1Mc05uTGwyazF0Smp4UEs0RWhtdnRGeUhW
            YS9VeDQzdUFMWmpxMUt1cE5uZXg2ck9CdTZ5OVBwTXViRmZMWjBqbmF6dXRseGM1NjU3bjJnclZrdUIzZksvTW5TZVN6UHR5OXhSdGM2d05Gb
            nV3YzR1a2s2VS9VWGlYMU9wdFAxVjVLQjlESHJ0c1VyMngzMnlJdHJaUEw5U2pzek45cHhtMVBYWEszVTRua21wbGtTclB0NHNGUHFXOHdnMT
            BFVTZ2bGFsVzNGMWtsZ1RxTmtoOGxwbTZITkdYaGx0T3pwUE1JT3F5MlpsOXpnZlVSeUs4Uys0YzVta2w2VmF0aWJ1cXJjaVdxM3NtUVdKV0J
            XejNrS1ZzNUpzcFVYaGNkdnJEMkZCR1o3MlhiMmsyeVVhUnVPMG12dFFrVVgrSTI1c1JWeGEyQWJVS1NHM0lLREJ0QWxKdE0zU3NoV1R0R2JI
            TUtjVHpsUFFvZk94eFoxUHJkVEIxMVpxMlUrZFpQVWN1WEtrMmllbDFQSTlTNjROdExyYkluUEJaaWtrWjFjbXk5cmcxVklNeEtsN0N0a1NzT
            0I1VW1YRHBnYzhBQUFBQUFBQUFBQUFBSGczaUF1NERUbDNIdGNmYlhJOEh1aXdVYnVBemJIeVU4SGFyTnRGOEZ0NDFLSGk0bFZaWTErV3R2Y1
            RaV1QyaUFYajNXTkxRcExIaStaa1dOMzJsRmprWGlTb3l4cnk1Y2ljaXZGNDlQai9jVm8zK200eXExS1Vzb01wd01xeHlXNUtTdmFxOFRXdWl
            sa1ZsVlN2OEFoTXFyL3dCd1RsajJwaTFocjJ3WHNvZXlhWm8yeVBZSTdQeUFyWDBrczFmckZ2YU5iYmwzRDZtS2VXdUw0a0ZqYTJ5d0hxc3l0
            M0dycElyOVNKMmJ2VHRNdHFWZGRuYTNFckJQMG4yV1h2QTYybTh3NkRMbXJMM24wK2tablN6VSswK05hQldyTHBtU1dObTNVeVUrazhvbnZBa
            WRxMUpVNmJVWk40TndFdG5YaU90WHQzbU5lTDFGVWYzY2x4SU52OW95dlovWVNvOVZZWldzdjBpdFN2OEFNRzNGQVZ0NFpXRjVDTXhxRE52c3
            VSN3d1Sjl3ck9ZMHZKQzJrMHphNlNuSGtRV041WlVWVXlZK2kwbW1UU2FiKzQxamovaURwK1crWHNpOGo0RnI3anIvQUlsODEvUDY1MFg1VVd
            3NUZjem81cXhyYmNYcmdUeFZ5alM5b0hQMWNUR1JUcU5udU9mcVl1bTlpVkZVdkdRVXZHQlpVS2VFWDZIa2FtaFYvUUQ1NEFBQUFBQUFBQUFB
            QUFBQUFxdVNsTWFlOHpxeG9YRmdEb1hTd3JLYjRGaVpMTVIxS2dZL3VIcGRSRktxdUFGSUZOa0ZXZFVNMGRsNGw0NUVXUzFIRHJMU3lZcjlCc
            mdnVnpDdXBpYUt0SHRZMUxyTWNkckVxMDJ4NmFKVytWWnU0MXJvN290a1JVTWtFN1hya2JZSnVyaU5EUEo1Tkd6WExSNkNLTjhWT2d2OFI2ZG
            9TeC9rMWxWckp3T1ZydktPVVZMbjBmQUdqUmtJSHc4M2xiUVBpM3BTYlc3V0UwMmthU1RwMnE1OWZxZElqSld1SnlaNEpZSHV2RWFNczJrOHJ
            aR3VzcnF4MjlOcGxqemJjWmROTzZweU9ncmRSZG9EMlQ1WVc3ZHdFR1hPd0Y3WDNGTElxME15eVd0VXJaTU1BTFlMU3dxNzhlUjcwMDVjVHl5
            S2hTU3MySlBLd3pQYTc3UVVBdCswVm43UldPcjVQNWQ4UFhuWDZWSmExZVY2TG9MMVpmbU1jcjhXK2JMcHRLMmxpYjFKTjN0T3Y1djVoSDVib
            21rYmR4VS9OZGRxWmRYTzBrdTVqcTVzZDZPVjZnaXhQUUZ3S1NyVzZYR1ZLQzlSYWV3cUJKaFpJN29VQURtdEZSaXNacDZWMHlKOUtya3FXak
            5TZjBNc1pvVCtnSHpnQUFBS01BQ2dBQU1Bb3dBQUFBRkkzSmdCdGdrNFhDZGlNYkZXeVVDVVpwajNFbG9hNE15YVZMVkJBcnJaVFgrV1ZZcnF
            tWkRRdDA4T1RHNVd3K29oMVpWank2cktsU3JJcThHVXEybWUyUDdPMDg2RW5MYUFzYkplemZZYm9NQ01Ha2RZdlZ5ejJHaUNGNmJQM0VqVEcr
            QmRXcHRJUm95c092TW9YdEt3MjdFaEhKdDRqTXpWeEtTcTJhL1NZWjQ3ZlNYVnNGR1piSVNPTkFyUlNPdmR2T2hBeTBKc2pxOTdGRmlWZnA3Q
            UtzeTdTVW0yb3RyUFdnckFVanoybFkwN2NTVWJJaWpxMmU4Sld5NUN0anpGYVIrL01SbVoxOXhUQ3MxUHJCcFJXTmZsZWdiV3oyYkdGZDN1Sk
            d2eW5RZGVzMGkrbXUzM0hka2tTR05uZnhxcWdxcXFWWGFwOGgrTGZPTGY4RHBuL3dDcFU2ektYRy9FWG16ZVphMXF0NlVXMVRqbm1TbnF1NnV
            Va3lpS2VqTGlBTWlVRXM2NEdtUEZIc0wwbGVXM0VDZlVLTFYwSDZjVW90ZWs0SGpMVndwZFQxbnVYV0xDNEdXTmwybXZ3VDlEQnFjWFVhUFd2
            NEw4Q1J4Z0FBb0NnTUFBQUFBQUFBQUFBQUFBQ3RVMHE1bUdqZW9HbGFGNDhYSUtWamJOQ1ZOY2Q3TGJpZFRTYjFPWEJuTFZUc3dSVnErTmlGd
            GEzYTJRNm82MXNKRzZKSHZ5WWVCczczS0d0YmI5djFDWHUxRnk3ank5OERUVjFjQks5dVF0T0h2SzF5N1JHaXJYdUFOKzBGcWtsQjhHWGJVVD
            NtZ1hjRFdxS3RIYTRNMVhNU055VUlMaml1MHF1MGcyL3dDa2xTVGZUbVR0YmJpZU5WaDQwYW9EV3F0aXF2OEFhd3E1S0ZxOENnelZ2Wm5GWjY
            1Q3M0a2F5YXFaWTQxc3pZcVNocDh2MDBtdDFLeHJ0M00vYWZZd1FwQkVzY2ExUmY2ZUJtOHQwQ2FEVGVFUytObThkekNlYmVaUitXNlJwWkc4
            TGNWTG1Vc0g0azg1WHkvVGVNVWJmQ2VWY1BhZkFNMThtM3NHdTFNbXUxajZtVjhtSWRUSTZKREM3OXhXNk5SMUg2VjJ1QWl3L2NWYkZUek9Lc
            k1hRmxUcDEzQVo0OHR3NnBkcVd4RzZTdHQyZ3EveEFXVEYwcUp1R2tpelhJOXEyNER6cE1iTUVRakhsRU15VmdBNW11YThwRk5vMG1UMlBQQm
            wrQkttQUFBQUFBQUFBQUFBQUFBQUFBQUFBQlFBdEc1ZXhrTHE5d09oQWx0cjdUdHExWXNlSjgvbzl4MjlJMk5yMjdTVlN1dXBzM0FSZFo2clZ
            4VXQrUldXTldYRmpsYW5xUVRwallMZGxaN3RkVHBMT3JJZk42YWZIMG1PbEJMZVgvdUlIV1Z2U29GMHRXeG1XVmVJOXE0bEM3ZTBrdGw1ayty
            N3p6cjMydUJScXMyd25JMlRZRTJuVDdDRW1wM3FCU1NldkF6U2FtaVVZelNhbDIybWJkVnIvWVNscmpuUm41bXBXdDlaampXdUJxMnFGS1J0a
            FN0VkVhVTh0N1NtazBXcDE4bFlWc3ZLUnRxZ1NWWlo1MGlpc3pOeFBzZktQSzA4dmlzMlV6Ym1EeXZ5dUh5MkxIS1J0MGh1a2tXSldaMnFxbF
            RMa25xOVRGcElHbWxhcUtmbVhudm1rdm0ydGFTM3BMc1EzZmlmejMvRXAzZ2dmMEl2NUh6dG5WL3FMQzJjWnBWWkxBdUwxWVdxc0JhTkN0ODZ
            rdW15cnVGcGR5a3RqTmprU1ZIM2lxenE5VGNyNDFVRE9yOXcvVTZTWTVXQmt0aC9NcDBuWlZWVUFpejlUNnhWa2ZrZU10Sk8wcXEyZmNBMEVr
            U1NaRTlYSlJEYXF3Y2t5T1g1bEwxWjZydFVER3ppK0g5QitPUkh3SlV6Z0FBQUFBQUFBQUFBQUFBQUFLQXdDakFLQXg2ckhnQVhqbHpPbHBOU
            3l0UTR4cDB6WmdmWExxMXJkYXQ5NURYUEZQQjlSdzFuZUQzRlY4d3pSR2JBbDFDczhVOWw0bStEV1pYWTV6U1J5dmkrZGhGWmx0L2NvUytpam
            12eitvb3VwdXZ1UG5vOVo5cGVQV3R0VlFyVHN0cWFXczFpRGF1dUpoOWViYWxWSFhTVTNYSUZHMUx1K1A4QUFicHl2dWtHV0pQcEw4WC9BTGd
            sbVdORkN2SXJKdjNpTlRibGwvSU1FZk43akxrOWR6TnRPaDVmNUJxOVhXeWRDTHViY2ZUZVcrVDZiUUw2YTJrLzFHM0c1SEo4cjhnYWFzK3Nz
            c2ZDTC81SDBrVU1jS1ZpUlZWZUtsUkdlaDBTR2FxbnhQNHAvRUZyYVRUTmh6WTAvaXY4UTlCZnkybWJOdHg4SzB6eXRrVWtjQmxTcjd3V3JGV
            1dpVVhrQTI3Yis4RldxSStJVzlLaS9lQ3h2SytLZ0c1bnJ4R1ZVMzhnYUpPbmxhM01GWEQzQVZWVW5mTGlXVlZSOFhzcENCRlJIWm5xTEkxV3
            VxQVhWMWcyOHVCTnAzWG5ZOGFlS1Z0bFNMTFY5d0dscFg2ZGNNZ2d2ZGJHWldhVnNqZEJaWmNseEEwYXQrakc3bkMzWjl4dDFlc2VWM2l0aXB
            ud3ZrQkJzVkplRmZnTk94TWxUTUFBQUFBQUFBQUFBQUFBS01BQUFvQUF3QUtNQUFCU0Q1cWt4bDNBZFJrdWhpbmlhSnpvUjVSQkpCMVVBNXFz
            WFZrTTBrVHhQa011MERmSFI4S0d5UEhpaHlZNWN6VkhxYWtxZGVCc2JsTEpzWTVzR3BRckhKWjhVc1FOZlVIYWVqVlhJYlRlVno2bkpzVlBwL
            0tQS290SmFXdG5ydVkzSnB4OUQ1QnJOZFZwL1FpL2tmU2VYK1RhUFE1UlIyZnVZM3J0R09tVWdZVVJwRVRjQXpNcXJrZk0vaUx6OU5ORTBVVF
            pNSDRnODlYVFJNaXRrZkFhblV2cVpPcXhTUlBPODdXYmVLcTMyaUs1YkQ2U1ZQVldtMXdiS3FEeHJudVE4dXQ4VXpLU09yZ2xUUXJkSjFheEJ
            XZTcrMHQxWXBVYXFaTUEvd0NaU2Q4c1NEV1hrVWFKK21pVnpDQmFJcnR5QWFTcXhWNUF2cHVyTGw3RDJTWHF1NDNUYWw2YnR3RW1uUzdXWGNR
            c2c3ZW5ja3RHa3hBMVJvekxqeEc2OHF3WHR1SkxaR291S0VkWlAxTVZZQkY5V3dNMVFXaU9LeEtrbXlZWkY4UGhsL1VVY0RDQUFBQUFBQUFBQ
            UFBQUFBQUFBQUFBQUFBQXdDZ01LQjFkQzFvcUcyUGNjdnk5OHFuWGpUTXBLMnA4cC9Od1dpM25BVlhobGVLVmNqN2p5M2NwTDhSZmgxTlhCK1
            oweStxdjhpVlBqWkllU2tiR21OblY2dHVVdEhvdnpjbFlsQVRRckxQSXF4SDJQbC9sdlFpVm1VYnlieWlQVFJMamtmUVJ4cW9DUVFLaUd5TWt
            QRzRGb1BrSU9aMWF0a0k2dlhSUUpkbVJRTk1rNktwODM1ejUya1NPaXVjYnpmOEFGZVRSNmJMM0h6cytwa25kM25mSXBKOWRxWDFNN3V6Y2pL
            b3JaUzlvNm8zMmtxVVZTdUdOanhWczQ3VVNOVVpXS1NNY3VMY0R5dnBOYjl4NHVjcTF5cWV0bHdxQXE0TFplUmVOVVp1M3VZV09KZVF6UzlvR
            jJsV0JPbFcyUTdJa3FXaXhiY1kxdmV4ZU9YR2lzQkJyOVQybFZua1cyZGE4VHhsclZtM0VHZmZaYmU4QmZtMmE1WHBQRXZWVW1xcExrdTgwd1
            FLeTJaMnI3QUVadlFkMmVybVNQdUs2bVMyQm5ad3BWdG51RlpycFVUaUZpUXJiaW5pL3g4ZmlUWjBaZzhQNkFaQUdBQlFHcUtBQUFBQUFBQUt
            NQUFBQUFBQUFDakFwVUNRcFJpWUY5TTFaRHN4eXAzSHo1ZFpYb0I5ZnBOZEhHdVVxSFVnL0UvbDhDVmxrUHpxN2Q0eXFVbDlGNXpONWI1aFAx
            OUppemJ6cGZoK09CSThXelBpMU9wcEovd0F0RjFWbHo3UmxXbjZOR3BWVDVIVGVlNm1LUHFyNnE5aDBOTitLZEZQODMwbjk0eWw5QU1waWo4e
            TAwNmVsT3JmZVVYVXIzZ2N2OFMrZC93Q0V5cWlwWjVVeFBndk1QTk5UNWhMZVdUN1Q2TDhkeXJQMEg3VDVGVjVrcVhqeHpZOVo3aU5sdGNaVm
            RuQU5yR21DU3FFWkVUR295N2NhQVh6Ymp0R2dsU1RjcEtDZXRzOTNjSzE3clpDa3J4d2Jxdlh1Y1d1YlpBekpCVm9tZHU1Q1RQbGRnTktyYVZ
            WWk5wVDA0bnVSdG10bXJYY2FhcjArazFmckF6TXRsYW9xckwxWHR4R2FKb3R1Uzk0dG14c0JTZVhxUlBaVXhiY1FhVjUzNHFUa3p5Q1BHVWxU
            ZEIwcUlzcTFadVo1SXlRYWFTcmJoWTA2MjdGZThqNWhPczh0SWtxcWxKUVZzTGlXSHJoa0x3VmUwbFQzaUxidFBiT2Vjd0NuM0RlQ2ZvS2JFV
            1Q0WTArQUhNcUZUcS9rUnZ5SUhJcUt4MTVOSFZOcHpkU3RYQWdBQUFBQUFBQUtBd29BQXdBTW9ES2c3RExnU1lCV0ZBWUQxVXVhR2lwRlliVF
            JqNnZzVURNdnVCdXdPQlhUUWRlMlpTVW80aTIzY2JJTk5YVFBMU3lLWnBHczZGNUJCTkxGYXJZa0pHeWUyNDJyR3FQYmladFN5TUJGWlpZODR
            tZFRUSDV4clk5czdNWThhaTFPU203VStaVDY5VldmYXBtM2ZTRmUwb3E0ZmVBcXBUM0ZZMXRtMklMZzlOcDVicU5pQThrVEpFbS92RzNaOXcz
            VlNqNTdsSjlYQlFLVlZVVjFZbmVYcVg3UmwrVlRrZXRYTEpNUUVrbHZJemdxMlhKdHBCbXpObW1yUzlXc0EwZEUzR3ZxMGtzeUswVEtLcmRPc
            jBWbjVrSkpWVmNkaFNUU1hTenEyTEdSbWRuQm0ySW9sc0NWUFRUcG9uZFd6K295eHRWLzdqWkhISzhkRnJTKzhCOVMzVGd0ZExOdFU1NjVaam
            F4bGFmZmF2TVJWQWJjZ3U0UGVLMjBBNURLK2RpYXFWcm5pQlR0VlJ2Z3paZDM2N2hZM3FtT0xIaVJyNC9IeCtIaXY2LzBZRDZ6cFI5dmdOMG8
            rM3dBQUUxTVNkTGI0SHgzbUh6d0FETUFBQUNnQURDZ0FEQUFBQlJRQUNyRUdBQUZHWGNBQWRLUDVSazhmbWdCU1N0dU5PbTNBQm8xUU8zNVYv
            QzNqOENFZmg0QUIwUWFUK2h6NU53QVRTNWJOTWkrS3A4VjhCdFlpK0RmcDRlQUFjbE1hbWxmMFQ5QUFDS2o2ZmtBQVA4QTFmOEFVOTAzaDRkT
            1g5QUFCby8wbC9UdUl6LzdBQUJZOXpHcUwrdngvd0RXNEFVbHJidzhPaDRHTnZIeHovVUFBaUl2QUFKVTNSLzVYMWw5UmhETFg5TjRBVWx5Rl
            BXMnFBRXFVVGFoSnR5QUFBbzBlNUFBQ3NIenlxK0hoUmYwQUNrdi85az3YGFhPpGhkaWdlc3RJRAVmcmFuZG9tUObFnKHz8taV2gb4ABJDYEl
            xZWxlbWVudElkZW50aWZpZXJrYWdlLW92ZXItMThsZWxlbWVudFZhbHVl9Wppc3N1ZXJBdXRohEOhASahGCFZAVQwggFQMIH4oAMCAQICBGYz
            VpAwCgYIKoZIzj0EAwIwMTETMBEGA1UECgwKQS1TSVQgUGx1czEaMBgGA1UEAwwRV2FsbGV0IEJhY2tlbmQgTTEwHhcNMjQwNTAyMDkwMjA4W
            hcNMjkwNTAyMDkwMjA4WjAxMRMwEQYDVQQKDApBLVNJVCBQbHVzMRowGAYDVQQDDBFXYWxsZXQgQmFja2VuZCBNMTBZMBMGByqGSM49AgEGCC
            qGSM49AwEHA0IABIV70srAvHTfb4BHtR-b2j2-647WI5bDRb8EzovL7Pb3SZcjW3vC_yph_qcHZpr1PzYQrEsHKBiTuW93GeXwde0wCgYIKoZ
            Izj0EAwIDRwAwRAIgTojT8d4cBUzjLZQarjLN1IBzCKUygA9kUK_7cTtTQHMCIE_bry51UVk0jWpUAKQijEzl3DDBpvHS6Xpu9zS7V78WWQI1
            2BhZAjCmZ3ZlcnNpb25jMS4wb2RpZ2VzdEFsZ29yaXRobWdTSEEtMjU2bHZhbHVlRGlnZXN0c6F1YXQuZ3YuaWQtYXVzdHJpYS4yMDIzpgBYI
            PCHkvJtN3puJ0sZtYSI3TejqSy3no3iZCI8dCppfyBRAVggP_tn16-tNyv2-0smIMkiZqg_lo3lsPZyyhQdHD2CAoMCWCDz7o1Kb0Y4qS0tK1
            jFEav6g1igLo7yjEFF8k1TZ9zhhQNYICFOSFT3KBRvlt4jjJLOjElRsR7UXlA20-g02Afjk5NZBFggpzdA90XM5OhPV1IxGbyfi8J_WaTx2WW
            iKgkcmicvg9YFWCB5xWyAlnT6BPccdsplKMv_6MRwztm_fqR2bXZ7aKsd6m1kZXZpY2VLZXlJbmZvoWlkZXZpY2VLZXmkAQIgASFYIOqiK_w8
            hiuzTYuJFacSi4BNkvlr9cg5qdyHDx7X9ZOkIlggn8MN4vguK71ABKl5gTjR-HFBcVXa_Jy85l30AWww1Q1nZG9jVHlwZXgZYXQuZ3YuaWQtY
            XVzdHJpYS4yMDIzLmlzb2x2YWxpZGl0eUluZm-jZnNpZ25lZMB4HjIwMjQtMTItMTBUMTY6NDk6NDcuNDkyMDM5NTUzWml2YWxpZEZyb23AeB
            4yMDI0LTEyLTEwVDE2OjQ5OjQ3LjQ5MjAzOTU1M1pqdmFsaWRVbnRpbMB4HjIwMjUtMTItMDVUMTY6NDk6NDcuNDg3MTQ0NzE3WlhANeEZ4_f
            9TJvI8dGCZwTaOlEbouZIZc8Si8Jbyi0wjv8hif0TjI-2mXy_o3AUSpXaQMYYleYDIsy_-FYLpe9kvA
        """.trimIndent()

        val credentialsInput = input.decodeToByteArray(Base64())
            .let { IssuerSigned.deserialize(it) }.getOrNull()
            ?.let { Holder.StoreCredentialInput.Iso(it, ConstantIndex.AtomicAttribute2023) }
            .shouldNotBeNull()

        val entry = holder.storeCredential(credentialsInput).getOrThrow()
            .shouldBeInstanceOf<Holder.StoredCredential.Iso>()
            .storeEntry

        val serialized = vckJsonSerializer.encodeToString(entry)

        vckJsonSerializer.decodeFromString<SubjectCredentialStore.StoreEntry.Iso>(serialized) shouldBe entry
    }


})