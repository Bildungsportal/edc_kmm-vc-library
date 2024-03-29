# Changelog

Release 3.5.0:
- Kotlin 1.9.23
- Ktor 2.3.9
- Update to latest KMP Crypto 2.5.0
  - Introduces correct mulitbase encoding
  - EC Point Compression
  - **THIS IS A BREAKING CHANGE WRT. SERIALIZATION OF DID-ENCODED KEYS**
    - Given that all EC keys were previously uncompressed, different mutlicodec identifiers are now supported and the old encoding of uncompressed keys does not work anymore, as it was faulty.
    - In addition, the encoding of the mutlibase prefix has changed, since varint-Encoding is now used correctly.
 - Fix name shadowing of gradle plugins by renaming file `Plugin.kt` -> `VcLibConventions.kt`
 - Fix: Add missing iOS exports
 - Add switch to disable composite build (useful for publishing)
 - Get rid of arrays in serializable types and use collections instead
 - Improve interoperability with verifiers and issuers from <https://github.com/eu-digital-identity-wallet/>
 - `OidcSiopVerifier`: Move `credentialScheme` from constructor to `createAuthnRequest`
 - `OidcSiopWallet`: Add constructor parameter to fetch JSON Web Key Sets

Release 3.4.0:
 - Target Java 17
 - Updated dependencies from conventions: Bouncycastle 1.77, Serialization 1.6.3-snapshot (fork), Napier 2.7.1, KMP Crypto 2.3.0
 - Integrate `kmp-crypto` library
 - Change signature parsing and return types to `CryptoSignature` class
 - Change base public key class from`JsonWebKey` to `CryptoPublicKey`
 - Change base algorithm class from `JwsAlgorithm` to `CryptoAlgorithm`
 - Remove all ASN.1 parsing to use `kmp-crypto` functionality instead
 - Change type of X.509 certificates from `ByteArray` to `X509Certificate`
 - Refactor `CryptoService.identifier` to `CryptoService.jsonWebKey.identifier`
 - Refactor `CryptoService.toPublicKey()` to `Crypto.publicKey`
 - Add member `coseKey` to `CryptoService`
 - Support `ES384`, `ES512`, `RS256`, `RS384`, `RS512`, `PS256`, `PS384` and `PS512` signatures in `DefaultCryptoService`
 - Change `DefaultCryptoService` constructor signature: When handing over a private/public key pair, the `CryptoAlgorithm` parameter is now mandatory
 - Change return type of methods in `JwsService` to `KmmResult<T>` to transport exceptions from native implementations
 - Support static QR code use case for OIDC SIOPv2 flows in `OidcSiopVerifier`
 - Move constructor parameters `credentialRepresentation`, `requestedAttributes` from `OidcSiopVerifier` into function calls

Release 3.3.0:
 - Change non-typed attribute types (i.e. Strings) to typed credential schemes (i.e. `ConstantIndex.CredentialScheme`), this includes methods `getCredentials`, `createPresentation` in interface `Holder`, and method `getCredentials` in interface `SubjectCredentialStore`
 - Add `scheme` to `Credential` stored in `IssuerCredentialStore`
 - Add `claimNames` to `ConstantIndex.CredentialScheme` to list names of potential attributes (or claims) of the credential
 - Add `claimNames` (a nullable list of requested claim names) to method `getCredential` in interface `IssuerCredentialDataProvider`, and to method `issueCredential` in interface `Issuer`
 - Add functionality to request only specific claims to OID4VCI implementation
 - Support issuing arbitrary data types in selective disclosure items (classes `ClaimToBeIssued` and `SelectiveDisclosureItem`)

Release 3.2.0:
 - Support representing credentials in all three representations: Plain JWT, SD-JWT and ISO MDOC
 - Remove property `credentialFormat` from interface `CredentialScheme`, also enum `CredentialFormat`
 - Remove property `credentialDefinitionName` from interface `CredentialScheme`, is now automatically converted from `vcType`
 - Add properties `isoNamespace` and `isoDocType` to interface `CredentialScheme`, to be used for representing custom credentials according to ISO 18013-5
 - Remove function `storeValidatedCredentials` from interface `Holder` and its implementation `HolderAgent`
 - Remove class `Holder.ValidatedVerifiableCredentialJws`
 - Add member for `CredentialScheme` to various classes like `CredentialToBeIssued.Vc`, subclasses of `IssuedCredential`, subclasses of `StoreCredentialInput` and subclasses of `StoreEntry`
 - Add parameter for `CredentialScheme` to methods in `SubjectCredentialStore`
 - Remove function `getClaims()` from `CredentialSubject`, logic moved to `IssuerCredentialDataProvider`
 - Add parameter `representation` to method `getCredentialWithType` in interface `IssuerCredentialDataProvider`
 - Add function `storeGetNextIndex(String, String, Instant, Instant, Int)` to interface `IssuerCredentialStore`
 - Remove function `issueCredentialWithTypes(String, CryptoPublicKey?, Collection<String>, CredentialRepresentation)` from interface `Issuer` and its implementation `IssuerAgent`
 - Add function `issueCredential(CryptoPublicKey, Collection<String>, CredentialRepresentation)` to interface `Issuer` and its implementation `IssuerAgent`
 - Remove function `getCredentialWithType(String, CryptoPublicKey?, Collection<String>, CredentialRepresentation` from interface `IssuerCredentialDataProvider`
 - Add function `getCredential(CryptoPublicKey, CredentialScheme, CredentialRepresentation)` to interface `IssuerCredentialDataProvider`
 - Refactor function `storeGetNextIndex()` in `IssuerCredentialStore` to accomodate all types of credentials
 - Add constructor property `representation` to `OidcSiopVerifier` to select the representation of credentials
 - Add constructor property `credentialRepresentation` to `WalletService` (OpenId4VerifiableCredentialIssuance) to select the representation of credentials

Release 3.1.0:
 - Support representing credentials in [SD-JWT](https://drafts.oauth.net/oauth-selective-disclosure-jwt/draft-ietf-oauth-selective-disclosure-jwt.html) format
 - Rename class `Issuer.IssuedCredential.Vc` to `Issuer.IssuedCredential.VcJwt`
 - Several new classes for sealed classes like `Issuer.IssuedCredential`, `Issuer.IssuedCredentialResult`, `Holder.StoreCredentialInput`, `Holder.StoredCredential`, `Parser.ParseVcResult`, `SubjectCredentialStore.StoreEntry`, `Verifier.VerifyCredentialResult`
 - Require implementations of `CredentialSubject` to implement `getClaims()` to process claims when issuing a credential with selective disclosures

Release 3.0.1:
 - Dependency Updates
   - OKIO 3.5.0
   - UUID 0.8.1
   - Encodings 1.2.3
   - JOSE+JWT 9.31
   - JSON 20230618

Release 3.0.0:
 - Creating, issuing, managing and verifying ISO/IEC 18013-5:2021 credentials
 - Kotlin 1.9.10
 - Generic structure for public keys
 - `kotlinx.serialization` fork with CBOR enhancements for COSE support

Release 2.0.2:
 - `vclib-openid`: Add response modes for query and fragment, i.e. Wallet may return the authentication response in query params or as fragment params on a SIOPv2 call
 - `vclib-openid`: Create fresh challenges for every SIOPv2 request
 - `vclib-openid`: Option to set `state` and receive it back in the response

Release 2.0.1:
 - `vclib-openid`: Remove `OidcSiopProtocol`, replace with `OidcSiopVerifier` and `OidcSiopWallet`, remove `AuthenticationResponse` and `AuthenticationRequest` holder classes
 - `vclib-openid`: Update implementation of OIDC SIOPv2 to v1.0.12 (2023-01-01), and of OID4VP to draft 18 (2023-04-21). Still missing requesting single claims, selective disclosure, among other parts

Release 2.0.0:
 - Add `AtomicAttribute2023` as a sample for custom credentials
 - Remove deprecated methods for "attribute names" and `AtomicAttributeCredential`
 - Remove list of known atomic attribute names in `AttributeIndex.genericAttributes`
 - Remove `attributeNames` in `Holder.createPresentation()`, `Holder.getCredentials()`, `SubjectCredentialStore.getCredentials()`
 - Replace `PresentProofProtocol.requestedAttributeNames` with `requestedAttributeTypes`
 - Remove `ConstantIndex.Generic` as the default credential scheme
 - Remove `goalCodeIssue` and `goalCodeRequestProof` from `CredentialScheme`

Release 1.8.0:
 - Remove `JwsContentType`, replace with strings from `JwsContentTypeConstants`
 - Add `JsonWebToken` to use as payload in `JwsHeader` or others
 - Change type of `exp` and `nbf` in `JwsHeader` from `long` to `Instant`
 - Remove all references to "attribute names" in credential subjects, we'll only use types from now on, as in the [W3C VC Data Model](https://w3c.github.io/vc-data-model/#types), e.g. deprecate the usage of methods referencing attribute names
 - Rename `keyId` to `identifier` (calculated from the Json Web Key) in `CryptoService` to decouple identifiers in VC from keyIds
 - Add `identifier` to `Holder`, `Verifier`, `Issuer`, which is by default the `identifier` of the `CryptoService`, i.e. typically the `keyId`
 - Move `extractPublicKeyFromX509Cert` from interface `VerifierCryptoService` (with expected implementations) to expected object `CryptoUtils`
 - Migrate usages of `keyId` to a more general concept of keys in `getKey()` in classes `JwsHeader` and `JweHeader`

Release 1.7.2:
 - Refactor `LibraryInitializer.registerExtensionLibrary`, see Readme

Release 1.7.1:
 - Remove references to `PupilIdCredential`, will be moved to separate library
 
Release 1.6.0:
 - Store attachments with reference to VC (changes `SubjectCredentialStore`)
 
Release 1.5.0:
 - Update dependencies: Kotlin 1.8.0, `KmmResult` 1.4.0
 - Remove "plain" instances of classes, not used on iOS

Release 1.4.0:
 - Remove `photo` from `PupilIdCredential`
 - Remove `pupilId` from `PupilIdCredential`
 - Add `cardId` to `PupilIdCredential`
 - Add `pictureHash` to `PupilIdCredential`
 - Add `scaledPictureHash` to `PupilIdCredential`
 - Transport attachments in `IssueCredential` protocol, which will contain photos (as binary blobs)
 - Update dependencies: Kotlin 1.7.21, Serialization 1.4.1, Kotest 5.5.4

Release 1.3.12:
 - Update to `KmmResult` 1.1

Release 1.3.11:
 - Migrate public API to use `KmmResult`: Return a `failure` with a custom Exception if something goes wrong, otherwise return a `success` with a custom data class.

Release 1.3.10:
 - Implement validating JWS with jsonWebKey and certificates from header
 - Export `BitSet` to Kotlin/Native, i.e. do not `inline` the class

Release 1.3.9:
 - True multiplatform BitSet implementation
