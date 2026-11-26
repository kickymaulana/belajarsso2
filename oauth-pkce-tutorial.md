# Membangun OAuth 2.0 Authorization Code + PKCE di Android

## Tentang buku ini

Tutorial ini membangun ulang alur login yang sudah ada di proyek **BelajarSSO2**, dari konfigurasi Gradle sampai pemulihan sesi. Fokus implementasi: aplikasi Android sebagai **public client**, Ktor sebagai klien HTTP, PKCE sebagai pelindung authorization code, dan KSafe sebagai penyimpanan transaksi login serta token.

Status cakupan:

- **Sudah diimplementasikan:** MVVM dengan `AuthViewModel`, `AuthRepository`, Koin, Ktor, callback custom URI ketat, pertukaran code, pengambilan profil (termasuk ID numerik), simpan/pulihkan/refresh token, dan logout lokal.
- **Struktur:** mengikuti proyek BelajarSSO: `core/network`, `core/storage`, `core/di`, `feature/auth/data/model`, `feature/auth/data`, `feature/auth/presentation`, dan `feature/auth/di`.
- **Konfigurasi aktif:** client ID `02609664-7149-432b-8111-f5d7cdb0e922`, issuer `http://10.0.2.2:8000`, redirect `com.gotechdynamics.belajarsso2://oauth/callback`.
- **Verifikasi build/test:** lihat hasil terbaru pada laporan refactor; login end-to-end tetap memerlukan server OAuth aktif.
- **Belum diimplementasikan:** host iOS, Desktop, Web, serta logout/revocation sesi di server.

> Keamanan: aplikasi tidak boleh menyimpan `client_secret`. APK dapat dibongkar, sehingga Android adalah public client. Jangan log authorization code, `code_verifier`, access token, refresh token, atau isi respons autentikasi mentah. HTTP cleartext pada proyek ini hanya untuk debug emulator dan hanya menuju `10.0.2.2`.

## 1. Arsitektur dan urutan alur

Modul proyek:

```text
BelajarSSO2/
|-- androidApp/   # Activity, Android engine, PKCE Android, KSafe
|-- shared/       # model dan logika OAuth lintas platform
|-- gradle/       # version catalog
`-- oauth-pkce-tutorial.md
```

Alur login:

1. Pengguna menekan tombol login.
2. Aplikasi membuat `state` acak dan `code_verifier` acak.
3. Aplikasi menghitung `code_challenge = BASE64URL(SHA256(code_verifier))`.
4. Aplikasi menyimpan transaksi pending sebelum membuka browser.
5. Browser membuka `/oauth/authorize` dengan `state` dan challenge.
6. Server mengembalikan custom URI callback berisi `code` dan `state`.
7. Aplikasi memvalidasi URI, parameter, umur transaksi, dan kecocokan `state`.
8. Transaksi pending dikonsumsi satu kali.
9. Aplikasi menukar `code` + `code_verifier` ke `/oauth/token`.
10. Aplikasi menyimpan token, memanggil `/api/user`, lalu menampilkan profil.
11. Saat aplikasi dibuka lagi, token dipulihkan; token hampir kedaluwarsa atau respons 401 dapat memicu refresh.
12. Logout lokal menghapus token dan transaksi pending. Sesi server belum dicabut.

Pemisahan tanggung jawab penting:

- `AuthRepository` di `shared/commonMain`: serialisasi login/callback/restore/logout, pertukaran dan refresh token, serta persistensi lewat kontrak store.
- `AuthViewModel` di `shared/commonMain`: `StateFlow<AuthUiState>`, `viewModelScope`, error aman, dan event browser sekali-konsumsi lewat `Channel`.
- `MainActivity`: membuat dependency, mengurai dan memvalidasi bentuk callback Android, mengamati state secara lifecycle-aware, lalu membuka browser.
- `TokenStore` dan `PendingLoginStore`: abstraksi shared; implementasi Android tetap memakai KSafe.
- Server OAuth: autentikasi pengguna, penerbitan code/token, validasi PKCE, dan endpoint profil.

`HttpClient` dimiliki repository melalui satu `KtorOAuthApi`. Client ditutup oleh `AuthViewModel.onCleared()`, bukan `Activity.onDestroy()`, sehingga rotasi tidak mematikan request atau client ViewModel yang masih hidup.

## 2. Dependency dan Gradle version catalog

Version catalog aktual berada di [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Bagian OAuth/Ktor/KSafe yang relevan:

```toml
[versions]
ktor = "3.6.1"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ksafe = { module = "eu.anifantakis:ksafe", version = "3.3.1" }

[plugins]
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Tujuan tiap dependency:

- `ktor-client-core`: API `HttpClient`, request, respons, dan form.
- `ktor-client-content-negotiation`: memilih serializer berdasarkan content type.
- `ktor-serialization-kotlinx-json`: mengubah JSON ke data class `@Serializable`.
- `ktor-client-android`: engine HTTP aktual untuk host Android.
- `ksafe`: persistensi key-value pada Android untuk transaksi pending dan token.
- plugin Kotlin serialization: menghasilkan serializer data class saat kompilasi.

Modul shared menerapkan plugin serialization dan dependency Ktor dalam `commonMain`. Konfigurasi aktual ada di [`shared/build.gradle.kts`](shared/build.gradle.kts):

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}
```

> Catatan proyek: file aktual saat ini mencantumkan dua dependency Content Negotiation dan JSON dua kali. Gradle biasanya menduplikasi deklarasi tanpa mengubah hasil resolusi, tetapi tutorial cukup menunjukkan masing-masing sekali. Tutorial ini tidak mengubah source Gradle.

Host Android menambahkan KSafe dan Android engine. Konfigurasi aktual ada di [`androidApp/build.gradle.kts`](androidApp/build.gradle.kts):

```kotlin
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.ksafe)
    implementation(libs.ktor.android)
}
```

Engine sengaja berada di `androidApp`, bukan `commonMain`: engine adalah detail platform, sedangkan `OAuthApi` cukup menerima abstraksi `HttpClientEngine`.

## 3. Konfigurasi OAuth

Model konfigurasi berada di [`OAuthConfig.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/OAuthConfig.kt):

```kotlin
data class OAuthConfig(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
)
```

Konfigurasi Android saat ini dibuat di [`MainActivity.kt`](androidApp/src/main/kotlin/com/gotechdynamics/belajarsso2/MainActivity.kt):

```kotlin
val oauthConfig = OAuthConfig(
    issuer = "http://10.0.2.2:8000",
    clientId = "02609664-7149-432b-8111-f5d7cdb0e922",
    redirectUri = "com.gotechdynamics.belajarsso2://oauth/callback",
)
```

Makna nilai:

- `issuer`: origin server OAuth. `10.0.2.2` dari emulator Android menunjuk loopback mesin pengembang.
- `clientId`: identitas public client yang terdaftar di server; bukan rahasia.
- `redirectUri`: harus cocok **persis** dengan URI yang didaftarkan di server dan intent filter Android.

Untuk produksi, gunakan HTTPS dan issuer produksi. Jangan menambahkan `client_secret` ke source, resource, `BuildConfig`, atau APK. Jika penyedia OAuth menuntut secret pada token endpoint untuk aplikasi Android, registrasi client-nya perlu diubah menjadi public/native client atau gunakan backend tepercaya.

`MainActivity` membuat satu `OAuthConfig`, lalu memberikannya ke `LoginCoordinator` dan `KtorOAuthApi`. Satu instance mencegah nilai issuer, client ID, atau redirect URI drift.

## 4. PKCE dan `state`

Kontrak bersama berada di [`PkceGenerator.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/PkceGenerator.kt):

```kotlin
interface PkceGenerator {
    fun newVerifier(): String
    fun newState(): String
    fun challenge(verifier: String): String
}
```

Implementasi Android berada di [`AndroidPkceGenerator.kt`](androidApp/src/main/kotlin/com/gotechdynamics/belajarsso2/AndroidPkceGenerator.kt):

```kotlin
class AndroidPkceGenerator : PkceGenerator {
    private val random = SecureRandom()

    override fun newVerifier(): String = randomUrlSafe(64)
    override fun newState(): String = randomUrlSafe(32)

    override fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return encode(digest)
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return encode(bytes)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
}
```

Alasan desain:

- `SecureRandom`, bukan `Random`, menghasilkan nilai yang tidak mudah ditebak.
- Base64 URL-safe menghindari karakter `+`, `/`, dan padding `=` yang mengganggu URL.
- S256 melindungi authorization code. Pihak yang menyadap code tetap tidak dapat menukar code tanpa verifier asli.
- `state` mengikat callback ke login yang dimulai aplikasi, mencegah callback asing atau tertukar.

`state` dan verifier punya fungsi berbeda. Jangan memakai satu nilai untuk keduanya. Jangan menaruh verifier pada authorization URL atau log.

## 5. Membangun authorization URL

Fungsi aktual berada di [`OAuthUrls.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/OAuthUrls.kt):

```kotlin
fun authorizationUrl(
    config: OAuthConfig,
    state: String,
    challenge: String,
): String {
    return URLBuilder("${config.issuer}/oauth/authorize").apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", config.clientId)
        parameters.append("redirect_uri", config.redirectUri)
        parameters.append("state", state)
        parameters.append("code_challenge", challenge)
        parameters.append("code_challenge_method", "S256")
    }.buildString()
}
```

`URLBuilder` penting karena encoding query parameter tidak boleh dirakit dengan konkatenasi manual. Parameter inti:

- `response_type=code`: meminta authorization code, bukan token langsung.
- `client_id`: memilih registrasi client.
- `redirect_uri`: target callback yang diizinkan.
- `state`: korelasi transaksi.
- `code_challenge`: hash verifier.
- `code_challenge_method=S256`: metode PKCE aman.

Jika server membutuhkan `scope`, tambahkan sesuai kontrak server, misalnya `parameters.append("scope", "openid profile email")`. Source saat ini tidak mengirim `scope`, jadi tutorial tidak menganggap scope tersebut wajib.

## 6. Menyimpan transaksi login pending dengan KSafe

Model dan kontrak ada di [`PendingLoginStore.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/PendingLoginStore.kt):

```kotlin
data class PendingLogin(
    val state: String,
    val verifier: String,
    val createdAt: Long,
)

interface PendingLoginStore {
    suspend fun save(transaction: PendingLogin)
    suspend fun load(): PendingLogin?
    suspend fun clear()
}
```

Implementasi Android berada di [`AndroidPendingLoginStore.kt`](androidApp/src/main/kotlin/com/gotechdynamics/belajarsso2/AndroidPendingLoginStore.kt):

```kotlin
class AndroidPendingLoginStore(context: Context) : PendingLoginStore {
    private val safe = KSafe(context.applicationContext)

    override suspend fun save(transaction: PendingLogin) {
        safe.put(
            KEY,
            JSONObject()
                .put("state", transaction.state)
                .put("verifier", transaction.verifier)
                .put("createdAt", transaction.createdAt)
                .toString(),
        )
    }

    override suspend fun load(): PendingLogin? {
        val raw = safe.get<String?>(KEY, null) ?: return null
        val json = JSONObject(raw)
        return PendingLogin(
            json.getString("state"),
            json.getString("verifier"),
            json.getLong("createdAt"),
        )
    }

    override suspend fun clear() {
        safe.delete(KEY)
    }

    private companion object {
        const val KEY = "oauth_pending_login"
    }
}
```

Penyimpanan dibutuhkan karena browser dapat membawa Activity keluar dari foreground atau proses dapat dibuat ulang. State Compose saja tidak cukup. `applicationContext` mencegah store menahan Activity.

Transaksi pending bukan sesi pengguna. Isinya hanya hidup selama satu percobaan login dan harus dihapus setelah konsumsi, pembatalan, kedaluwarsa, atau kegagalan callback.

## 7. Coordinator: satu login aktif dan transaksi satu kali

[`LoginCoordinator.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/LoginCoordinator.kt) menggabungkan PKCE, URL, waktu, dan store:

```kotlin
class LoginCoordinator(
    private val config: OAuthConfig,
    private val pkce: PkceGenerator,
    private val now: () -> Long,
    private val store: PendingLoginStore,
) {
    suspend fun startLogin(): String {
        val pending = store.load()
        val currentTime = now()

        check(
            pending == null ||
                currentTime < pending.createdAt ||
                currentTime - pending.createdAt >= 300_000L
        ) {
            "Transaksi login masih berlangsung."
        }

        val transaction = PendingLogin(
            state = pkce.newState(),
            verifier = pkce.newVerifier(),
            createdAt = now(),
        )

        val url = authorizationUrl(
            config = config,
            state = transaction.state,
            challenge = pkce.challenge(transaction.verifier),
        )

        store.save(transaction)
        return url
    }

    suspend fun cancelLogin() {
        store.clear()
    }

    suspend fun consumeAuthorization(
        callback: OAuthCallback,
    ): ValidatedAuthorization {
        val validated = validateCallback(callback, store.load(), now())
        store.clear()
        return validated
    }
}
```

Aturan 300.000 ms berarti transaksi berlaku kurang dari lima menit. Coordinator menolak klik login baru ketika transaksi lama masih valid. Ini mencegah verifier lama tertimpa lalu callback pertama gagal.

Urutan `store.save(transaction)` sebelum browser dibuka penting. Callback cepat tidak boleh tiba sebelum data validasi tersimpan.

`AuthRepository` memiliki satu `Mutex` untuk login, callback, restore, dan logout. Mutex hidup bersama ViewModel sehingga restore Activity lama tidak berlomba dengan callback setelah recreation:

```kotlin
suspend fun restoreSession(): AuthResult = operationMutex.withLock {
    val token = tokenStore.load() ?: return@withLock AuthResult.SignedOut
    AuthResult.Authenticated(loadProfile(token))
}
```

`AuthViewModel.login()` mengirim `AuthEvent.OpenBrowser` melalui `Channel.BUFFERED`. Compose mengoleksi event dalam `LaunchedEffect`; event tidak diputar ulang pada recomposition. Jika browser tidak ada, Activity meneruskan kegagalan ke ViewModel, repository menghapus pending transaction, lalu UI menampilkan `Browser tidak tersedia.`

## 8. Callback Android: manifest dan debug network security

Manifest utama berada di [`androidApp/src/main/AndroidManifest.xml`](androidApp/src/main/AndroidManifest.xml). Bagian penting:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application ...>
    <activity
        android:name=".MainActivity"
        android:exported="true">

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <category android:name="android.intent.category.BROWSABLE" />

            <data
                android:scheme="com.gotechdynamics.belajarsso2"
                android:host="oauth"
                android:path="/callback" />
        </intent-filter>
    </activity>
</application>
```

Kecocokan URI:

```text
com.gotechdynamics.belajarsso2://oauth/callback
|--------------------------------|  |---| |-------|
              scheme                host     path
```

Activity exported diperlukan agar browser dapat mengirim deep link. Karena komponen exported menerima input dari luar aplikasi, input tidak boleh dipercaya hanya karena intent filter cocok; source tetap memvalidasi ulang URI dan parameter.

Server lokal memakai HTTP. Android modern memblokir cleartext secara default. Proyek membatasi pengecualian ke varian debug melalui [`androidApp/src/debug/AndroidManifest.xml`](androidApp/src/debug/AndroidManifest.xml):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

Konfigurasinya ada di [`network_security_config.xml`](androidApp/src/debug/res/xml/network_security_config.xml):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

Batas keamanan:

- Konfigurasi hanya ada di source set `debug`.
- Semua cleartext ditolak secara default.
- Hanya host emulator `10.0.2.2` diizinkan.
- Release harus menggunakan HTTPS; jangan pindahkan pengecualian ini ke manifest utama.

## 9. Validasi callback dan konsumsi satu kali

`MainActivity.handleOauthIntent()` memeriksa bentuk URI secara ketat:

```kotlin
val isValidUri =
    uri.isHierarchical &&
        uri.scheme == "com.gotechdynamics.belajarsso2" &&
        uri.encodedAuthority == "oauth" &&
        uri.encodedPath == "/callback" &&
        uri.fragment == null
```

Lalu setiap parameter harus muncul paling banyak sekali:

```kotlin
fun singleParameter(name: String): String? {
    val values = uri.getQueryParameters(name)
    require(values.size <= 1) {
        "Parameter callback berulang."
    }
    return values.singleOrNull()
}

val callback = OAuthCallback(
    isValidUri = isValidUri,
    state = singleParameter("state"),
    code = singleParameter("code"),
    error = singleParameter("error"),
)

require(callback.code == null || callback.error == null) {
    "Callback berisi code dan error bersamaan."
}
```

Pemeriksaan parameter ganda mencegah parser atau lapisan berbeda memilih nilai yang berbeda. Callback juga tidak boleh membawa `code` dan `error` bersamaan.

Validasi protokol berada di [`OAuthCallback.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/OAuthCallback.kt):

```kotlin
fun validateCallback(
    callback: OAuthCallback,
    transaction: PendingLogin?,
    now: Long,
): ValidatedAuthorization {
    require(callback.isValidUri) { "URI callback tidak valid." }

    val pending = requireNotNull(transaction) {
        "Transaksi login tidak ditemukan atau sudah diproses."
    }

    require(
        now >= pending.createdAt &&
            now - pending.createdAt < 300_000L
    ) {
        "Transaksi login kadaluarsa. Silahkan masuk kembali."
    }

    require(
        pending.state.isNotBlank() &&
            callback.state == pending.state
    ) {
        "State callback tidak cocok."
    }

    require(pending.verifier.isNotBlank()) {
        "Verifier login tidak tersedia."
    }

    require(callback.error == null) {
        if (callback.error == "access_denied") {
            "Otorisasi dibatalkan."
        } else {
            "Otorisasi gagal."
        }
    }

    val code = callback.code
    require(!code.isNullOrBlank()) {
        "Kode otorisasi tidak ditemukan."
    }

    return ValidatedAuthorization(code, pending.verifier)
}
```

Setelah validasi sukses, coordinator menghapus pending transaction sebelum token exchange. Callback yang diputar ulang tidak memiliki transaksi lagi dan ditolak. Pada kegagalan callback, Activity juga mencoba `cancelLogin()` agar aplikasi tidak tertahan oleh transaksi rusak.

Konsekuensi keamanan: bila token exchange gagal setelah transaksi dikonsumsi, pengguna harus memulai login baru. Ini lebih aman daripada menggunakan ulang authorization code/verifier yang statusnya tidak pasti.

`onCreate()` menangani callback ketika Activity dibuat. `onNewIntent()` menangani callback ketika instance Activity lama dipakai kembali:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleOauthIntent(intent)
}
```

## 10. Ktor client dan Android engine

API berada di [`OAuthApi.kt`](shared/src/commonMain/kotlin/com/gotechdynamics/belajarsso2/core/network/OAuthApi.kt). Model respons:

```kotlin
@Serializable
data class OAuthTokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long? = null,
    val refresh_token: String? = null,
)

@Serializable
data class OAuthUser(
    val id: Int = 0,
    val name: String? = null,
    val email: String? = null,
)
```

Client menerima engine dari host:

```kotlin
class OAuthApi(
    config: OAuthConfig,
    engine: HttpClientEngine,
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = true
    }
}
```

Alasan:

- Shared code tidak mengikat diri ke engine Android.
- `ignoreUnknownKeys = true` membuat tambahan field server tidak merusak parsing.
- `expectSuccess = true` mengubah status HTTP non-2xx menjadi exception, sehingga jalur error eksplisit.

Host Android membuat engine satu kali saat factory ViewModel membuat repository:

```kotlin
val repository = AuthRepository(
    loginCoordinator = LoginCoordinator(config, AndroidPkceGenerator(), System::currentTimeMillis, pendingStore),
    oauthApi = KtorOAuthApi(config, Android.create()),
    tokenStore = AndroidTokenStore(applicationContext),
)
```

Client ditutup saat ViewModel benar-benar selesai, bukan saat Activity hancur karena rotasi:

```kotlin
override fun onCleared() {
    repository.close()
    super.onCleared()
}
```

## 11. Menukar authorization code menjadi token

Request aktual memakai form URL-encoded, bukan JSON:

```kotlin
suspend fun exchange(
    code: String,
    verifier: String,
): OAuthTokenResponse = client.submitForm(
    url = tokenUrl,
    formParameters = Parameters.build {
        append("grant_type", "authorization_code")
        append("client_id", clientId)
        append("code", code)
        append("redirect_uri", redirectUri)
        append("code_verifier", verifier)
    },
).body<OAuthTokenResponse>().also {
    require(it.access_token.isNotBlank()) { "Token kosong." }
}
```

Server harus memverifikasi bahwa:

- code masih valid, belum digunakan, dan diterbitkan untuk client ini;
- `redirect_uri` sama dengan request otorisasi;
- SHA-256 verifier cocok dengan challenge tersimpan;
- code belum kedaluwarsa.

Tidak ada `client_secret`, sesuai public client. Setelah exchange sukses, Activity menyimpan token lalu memuat sesi:

```kotlin
val authorization = loginCoordinator.consumeAuthorization(callback)
val token = oauthApi.exchange(
    authorization.code,
    authorization.verifier,
)
tokenStore.save(token)
loadSession(token)
```

Jangan log request form ini karena memuat code dan verifier.

## 12. Mengambil profil

Request profil memakai Bearer token:

```kotlin
suspend fun profile(accessToken: String): OAuthUser =
    client.get(userUrl) {
        bearerAuth(accessToken)
    }.body()
```

Endpoint aktual dibentuk sebagai `${issuer}/api/user`. UI memilih identitas pertama yang tersedia:

```kotlin
profile = user.name ?: user.email ?: user.id.toString()
```

Kontrak JSON saat ini mengharapkan:

```json
{
  "id": 123,
  "name": "Nama Pengguna",
  "email": "user@example.com"
}
```

`id` bertipe JSON number yang masuk ke Kotlin `Int`. Jika server mengirim UUID/string seperti `"id": "123"`, deserialisasi gagal. Samakan model dengan kontrak server, misalnya ubah menjadi `val id: String = ""` hanya bila API memang mengirim string. Jangan mengubah tipe secara acak untuk menyembunyikan kontrak yang tidak konsisten.

## 13. Persistensi token dengan KSafe

Implementasi berada di [`AndroidTokenStore.kt`](androidApp/src/main/kotlin/com/gotechdynamics/belajarsso2/AndroidTokenStore.kt):

```kotlin
class AndroidTokenStore(context: Context) {
    private val safe = KSafe(context.applicationContext)

    suspend fun save(token: OAuthTokenResponse) {
        require(token.access_token.isNotBlank())
        safe.put(
            KEY,
            JSONObject()
                .put("access", token.access_token)
                .put("refresh", token.refresh_token)
                .put(
                    "expiresAt",
                    token.expires_in?.let {
                        System.currentTimeMillis() + it * 1000
                    },
                )
                .toString(),
        )
    }

    suspend fun load(): OAuthTokenResponse? {
        val raw = safe.get<String?>(KEY, null) ?: return null
        val json = JSONObject(raw)
        return OAuthTokenResponse(
            access_token = json.getString("access"),
            refresh_token = json.optString("refresh")
                .takeIf { it.isNotBlank() },
            expires_in = json.optLong("expiresAt")
                .takeIf { it > 0 }
                ?.let {
                    ((it - System.currentTimeMillis())
                        .coerceAtLeast(0) / 1000)
                },
        )
    }

    suspend fun clear() = safe.delete(KEY)

    private companion object {
        const val KEY = "oauth_token_record"
    }
}
```

Store mengubah `expires_in` relatif menjadi `expiresAt` absolut. Saat dibaca, nilai dikembalikan menjadi sisa detik. Tanpa ini, setiap restart akan menganggap token mendapat masa hidup penuh lagi.

Token adalah kredensial. Jangan masukkan JSON store ke log, crash breadcrumb, analytics, atau screenshot debug. Perlindungan storage lokal mengurangi risiko, tetapi perangkat yang sepenuhnya dikompromikan tetap tidak dapat dianggap aman.

## 14. Restore sesi dan refresh token

Saat Activity dibuka bukan dari callback, source mencoba memulihkan token:

```kotlin
val token = tokenStore.load() ?: return@withLock
loadSession(token)
```

`loadSession()` menjalankan dua strategi refresh:

1. **Proaktif:** refresh bila sisa umur token `<= 30` detik dan refresh token tersedia.
2. **Reaktif:** bila profil menghasilkan HTTP 401, refresh satu kali lalu ulangi profil.

Inti algoritma aktual:

```kotlin
private suspend fun loadSession(saved: OAuthTokenResponse) {
    var token = saved

    suspend fun refresh() {
        val refreshToken = token.refresh_token
            ?.takeIf { it.isNotBlank() }
            ?: error("Login ulang diperlukan.")

        token = oauthApi.refresh(refreshToken).let {
            it.copy(refresh_token = it.refresh_token ?: refreshToken)
        }
        tokenStore.save(token)
    }

    var refreshed = false
    if (
        token.expires_in != null &&
        token.expires_in!! <= 30 &&
        token.refresh_token != null
    ) {
        refresh()
        refreshed = true
    }

    val user = try {
        oauthApi.profile(token.access_token)
    } catch (error: ClientRequestException) {
        if (
            error.response.status.value != 401 ||
            refreshed ||
            token.refresh_token == null
        ) throw error

        refresh()
        oauthApi.profile(token.access_token)
    }

    profile = user.name ?: user.email ?: user.id.toString()
    errorMessage = null
}
```

Refresh request:

```kotlin
suspend fun refresh(refreshToken: String): OAuthTokenResponse =
    client.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("client_id", clientId)
            append("refresh_token", refreshToken)
        },
    ).body<OAuthTokenResponse>().also {
        require(it.access_token.isNotBlank()) { "Token kosong." }
    }
```

Jika server merotasi refresh token, token baru disimpan. Jika respons tidak mengirim refresh token baru, source mempertahankan nilai lama melalui `copy(refresh_token = it.refresh_token ?: refreshToken)`.

Batas satu retry mencegah loop refresh tanpa akhir. Jika refresh gagal atau tidak tersedia, UI meminta pengguna login lagi melalui pesan generik.

## 15. Logout lokal

Handler logout aktual menjalankan:

```kotlin
profile = null
tokenStore.clear()
loginCoordinator.cancelLogin()
errorMessage = null
```

Ini adalah **logout lokal**:

- profil di memori dihapus;
- access/refresh token lokal dihapus;
- transaksi login pending dihapus.

Ini **bukan logout server**. Token atau sesi browser di server mungkin tetap aktif sampai kedaluwarsa atau dicabut. Server-side logout, token revocation, dan end-session endpoint belum diimplementasikan. Jangan menulis dokumentasi produk seolah pengguna sudah keluar dari semua perangkat.

## 16. Penanganan error tanpa membocorkan rahasia

Source menampilkan pesan pengguna yang generik seperti:

- `Gagal memulai login. Coba lagi.`
- `Login gagal. Periksa koneksi lalu coba lagi.`
- `Sesi belum dapat dipulihkan. Coba masuk kembali.`
- `Gagal menghapus sesi tersimpan. Coba keluar lagi.`

Pola ini mencegah detail respons autentikasi bocor ke UI. Saat debugging, log metadata aman saja:

- tahap operasi: `authorize`, `callback`, `exchange`, `profile`, atau `refresh`;
- status HTTP;
- nama exception;
- apakah token/refresh token tersedia sebagai boolean;
- timestamp dan correlation ID non-rahasia.

Jangan log:

- URL callback lengkap;
- query `code` atau `state`;
- verifier/challenge mentah;
- header `Authorization`;
- access/refresh token;
- body token response;
- client secret.

`CancellationException` selalu dilempar ulang. Ini menjaga cancellation coroutine dan lifecycle tetap bekerja, bukan diperlakukan sebagai kegagalan login biasa.

## 17. Pengujian manual dan debugging

### 17.1 Build

Task yang relevan:

```text
:androidApp:assembleDebug
```

Task tersebut **pernah dilaporkan sukses** untuk proyek ini sebelum tutorial ditulis. Tidak ada build atau test baru yang dijalankan sebagai bagian penulisan file ini.

### 17.2 Checklist alur normal

1. Jalankan server lokal pada host port `8000`.
2. Pastikan emulator dapat mengakses `http://10.0.2.2:8000`.
3. Pastikan redirect URI server persis `com.gotechdynamics.belajarsso2://oauth/callback`.
4. Jalankan build debug Android.
5. Tekan login dan selesaikan autentikasi di browser.
6. Pastikan callback kembali ke aplikasi dan profil tampil.
7. Tutup lalu buka aplikasi; pastikan sesi dipulihkan.
8. Uji token hampir kedaluwarsa atau 401 dengan setup server terkontrol; pastikan refresh hanya satu kali.
9. Tekan logout; pastikan profil dan sesi lokal hilang.

Hasil langkah di atas belum diklaim oleh tutorial; jalankan pada lingkungan pengembang.

### 17.3 Error cleartext

Gejala umum:

```text
CLEARTEXT communication to 10.0.2.2 not permitted by network security policy
```

Periksa:

- menjalankan varian `debug`, bukan release;
- debug manifest menunjuk `@xml/network_security_config`;
- file XML berada di `androidApp/src/debug/res/xml/`;
- domain tepat `10.0.2.2`, tanpa scheme atau port;
- release tetap HTTPS.

Jangan memperbaiki dengan `cleartextTrafficPermitted="true"` secara global pada manifest utama.

### 17.4 Error transaksi login masih berlangsung

Pesan:

```text
Transaksi login masih berlangsung.
```

Penyebab: pengguna menekan login lagi dalam lima menit sementara pending transaction masih tersimpan. Ini perlindungan, bukan kerusakan jaringan.

Pemulihan normal:

- selesaikan callback;
- batalkan melalui jalur aplikasi yang memanggil `cancelLogin()`;
- tunggu transaksi kedaluwarsa;
- gunakan logout lokal untuk menghapus pending state.

Saat pengembangan, data aplikasi dapat dihapus untuk reset total, tetapi ini juga menghapus token dan state lain. Jangan menambahkan bypass produksi yang menerima callback tanpa transaksi.

### 17.5 Callback tertahan atau sudah dikonsumsi

Pesan inti:

```text
Transaksi login tidak ditemukan atau sudah diproses.
```

Kemungkinan:

- callback dibuka dua kali;
- aplikasi/data dibersihkan setelah browser dibuka;
- transaksi sudah dikonsumsi sebelum exchange gagal;
- callback berasal dari sesi lama.

Solusi: mulai login baru. Jangan memulihkan verifier dari log atau menerima callback tanpa `state`.

### 17.6 Token response gagal diparse

Model mengharapkan bentuk semacam:

```json
{
  "access_token": "...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "..."
}
```

Kesalahan tipe umum:

```json
{ "expires_in": "3600" }
```

Source mengharapkan JSON number `3600`, bukan string `"3600"`. Perbaikan terbaik ada pada kontrak server. Jika server memang menetapkan string, model/serializer client harus disesuaikan secara eksplisit dan diuji.

Pastikan respons token mengirim header content type JSON yang benar. Field tambahan aman karena `ignoreUnknownKeys = true`. `access_token` kosong tetap ditolak oleh aplikasi.

### 17.7 Profile JSON gagal diparse

Model sekarang:

```kotlin
@Serializable
data class OAuthUser(
    val id: Int = 0,
    val name: String? = null,
    val email: String? = null,
)
```

Kesalahan umum:

- server mengirim `id` sebagai string atau UUID;
- server mengirim array/wrapper seperti `{ "data": { ... } }`;
- endpoint mengembalikan HTML error/login page;
- content type salah;
- nama field berbeda.

Bandingkan kontrak API, status HTTP, content type, dan bentuk JSON. Sanitasi body sebelum debugging; jangan menyalin token atau data pribadi ke issue publik.

### 17.8 State tidak cocok

Pesan:

```text
State callback tidak cocok.
```

Kemungkinan: callback lama, dua login paralel, server tidak mengembalikan state persis, atau storage berubah. Tolak callback dan mulai transaksi baru. Jangan menonaktifkan validasi state.

### 17.9 Emulator tidak dapat mencapai server

`localhost` dari emulator menunjuk emulator sendiri. Untuk server pada komputer pengembang, gunakan `10.0.2.2`. Pastikan server mendengarkan interface yang sesuai, firewall mengizinkan port, dan port `8000` benar.

## 18. Batas platform dan pekerjaan lanjutan

Logika `shared` dirancang agar bagian protokol dapat digunakan lintas platform, tetapi implementasi selesai saat ini hanya Android.

Belum tersedia:

- generator PKCE dan secure storage untuk iOS;
- engine Ktor serta callback handler iOS;
- implementasi Desktop;
- implementasi Web dan batasan browsernya;
- deep-link/app-link setup platform selain Android;
- server-side logout, revocation, atau end-session;
- klaim build/test untuk host selain Android.

Jangan menganggap keberadaan source set Kotlin Multiplatform berarti OAuth otomatis berjalan di semua target. Setiap host masih membutuhkan engine, secure random, penyimpanan aman, lifecycle callback, dan aturan URI masing-masing.

## 19. Ringkasan keputusan keamanan

- Authorization Code + PKCE dipakai karena Android adalah public client.
- Tidak ada `client_secret` di aplikasi.
- Verifier dan state dibuat dengan `SecureRandom`.
- PKCE memakai S256.
- Pending transaction dipersistenkan agar tahan recreate/process lifecycle.
- Callback divalidasi ulang meski sudah melewati intent filter.
- Duplicate query parameter ditolak.
- State, umur transaksi, verifier, code/error divalidasi.
- Pending transaction dikonsumsi satu kali.
- Access/refresh token disimpan melalui KSafe dan tidak boleh dilog.
- Refresh dibatasi agar tidak loop.
- Cleartext hanya untuk debug `10.0.2.2`; produksi wajib HTTPS.
- Logout sekarang hanya lokal; revocation server belum ada.

Dengan pembagian ini, UI hanya memulai aksi dan menampilkan state, Android host menangani integrasi platform, shared code menjaga aturan OAuth, dan server tetap menjadi otoritas autentikasi serta token.