# Evaluasi Lokksmith untuk BelajarSSO2

## Hasil

Pengguna mempertimbangkan mengganti alur OAuth manual dan library `kotlin-multiplatform-oidc` dengan Lokksmith 2.6.0. Pengguna membagikan dokumentasi lengkap Installation dan Usage. Belum ada refactor Lokksmith yang dilakukan.

## Kecocokan

Lokksmith menyediakan Authorization Code Flow dengan PKCE untuk Android, iOS, Desktop, dan Web, menggunakan `createLokksmith`, `Client.authorizationCodeFlow`, `prepare`, `AuthFlowLauncher`, serta `Client.tokens` / `runWithTokens`. Integrasi browser dan response handling platform menjadi tanggung jawab library.

Android mensyaratkan manifest placeholder `lokksmithRedirectScheme` dan `SingletonLokksmithProvider` pada startup Application. Compose artifact `lokksmith-compose` menyediakan `rememberAuthFlowLauncher`.

## Perlu dipastikan sebelum implementasi

Server lama memperlihatkan authorization endpoint `/oauth/authorize`, token endpoint `/oauth/token`, profil `/api/user`; issuer `http://10.0.2.2:8000`; client ID latihan dan custom redirect URI. Belum diketahui apakah server menyediakan OIDC discovery `/.well-known/openid-configuration`, mendukung nonce/ID token, dan apakah redirect scheme baru sudah didaftarkan.

Lokksmith berfokus OIDC Authorization Code + PKCE; client creation dalam dokumentasi `getOrCreate` membutuhkan `discoveryUrl`. Jangan memasukkan client secret di app. Cocokkan server dengan API OIDC, discovery document, parameter scopes, dan response token sebelum mengganti.

## Refactor yang diusulkan setelah integrasi API diverifikasi

- Tambah `dev.lokksmith:lokksmith-core:2.6.0` dan `dev.lokksmith:lokksmith-compose:2.6.0` di version catalog/shared commonMain.
- Konfigurasi `lokksmithRedirectScheme` pada `androidApp` dan pertahankan callback lama hanya jika skema cocok dengan request Lokksmith.
- Inisialisasi singleton Android pada `Application`; jangan taruh lifecycle callback dalam Composable.
- Ganti UI demo dengan tombol yang membuat flow, menjalankan `prepare()` dan meluncurkan initiation memakai `rememberAuthFlowLauncher`.
- Amati `client.authFlowResult` atau token flow untuk UI state.
- Hapus implementasi manual PKCE, callback validation, pending transaction store, serta dependency terkait setelah build dan uji Android berhasil.
- Pertahankan konfigurasi server/client yang nyata; jangan menebak discovery URL, scope, atau redirect registration.

## Verifikasi

Gradle Sync, `:androidApp:assembleDebug`, lalu uji emulator dari login, browser redirect, token state dan penanganan error. Keberhasilan build sebelumnya adalah untuk alur manual sebelum perpindahan dan tidak membuktikan kompatibilitas Lokksmith.
