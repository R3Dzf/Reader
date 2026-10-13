# Reader for Android

Reader is a read-only Android app for viewing PDFs and Microsoft Office documents on a phone. Word, Excel, and PowerPoint files are rendered inside Reader by an offline engine bundled in the APK; the phone does not need Microsoft 365 or another Office application.

## What works

- Open a PDF stored on the phone and read it page by page inside Reader.
- Open local .docx, .xlsx, and .pptx files in Reader without an Office app or an internet connection.
- Keep the document renderer and its WebAssembly modules inside the APK. The WebView is restricted to app-bundled assets and the selected file in private app storage.
- Sign in to OneDrive and list supported Office documents from the root folder.
- Convert a selected OneDrive Office document to a temporary PDF through Microsoft Graph, then read that PDF inside Reader.
- English and Arabic interface strings; the layout follows the phone's language direction.
- Temporary converted files live in the app cache and can be cleared by Android at any time.

The built-in offline viewer supports .docx, .xlsx, and .pptx. Legacy binary .doc, .xls, and .ppt files can still be previewed from OneDrive using Microsoft Graph's PDF conversion; offline support for these older formats is not included yet.

## Microsoft sign-in setup

The optional OneDrive feature uses delegated `Files.Read` access only. It does not upload local files, request write permission, or change OneDrive documents. A Microsoft Entra app registration is required before the OneDrive button can sign in. Local .docx, .xlsx, .pptx, and PDF files do not use OneDrive.

1. Register a **mobile and desktop public client** application in the Microsoft Entra admin center. Allow personal Microsoft accounts if you want to use a personal OneDrive.
2. Add the Android platform with package name `com.r3dzf.reader`. Register the signing certificate hash for the build you will install.
3. Add the delegated Microsoft Graph permission `Files.Read`.
4. In `app/src/main/res/raw/auth_config_single_account.json`, replace `REPLACE_WITH_ENTRA_CLIENT_ID` and `REPLACE_WITH_SIGNATURE_HASH` with the values from the registration.
5. In `app/src/main/AndroidManifest.xml`, replace `REPLACE_WITH_SIGNATURE_HASH` in the MSAL redirect intent filter with the same signature hash.
6. Build and install the app. The Client ID is public app configuration; **do not add a client secret** to a mobile app or commit one to this repository.

MSAL configuration reference: <https://learn.microsoft.com/en-us/entra/msal/android/configure-your-app>

## Build

Open the repository in Android Studio, allow Gradle sync, then run the `app` configuration on a phone or emulator. The project includes the Gradle Wrapper.

To build from a terminal:

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the project on pushes and pull requests and uploads the APK as a workflow artifact.

## Reading and fidelity

Local .docx, .xlsx, and .pptx files are copied to Reader's private cache and rendered by the bundled @silurus/ooxml engine, which parses the formats on-device. The viewer is read-only; it does not write back to the selected document. Local legacy .doc, .xls, and .ppt files are currently rejected with a clear message. OneDrive previews use Microsoft Graph's PDF conversion endpoint and are stored temporarily in the app cache.

This is not a promise of pixel-perfect compatibility with Microsoft Office for every file. Complex or unsupported OOXML features and fonts that are not embedded or available to Android can change layout. The bundled renderer's MIT license and notices are shipped with its asset bundle.

Graph conversion reference: <https://learn.microsoft.com/en-us/graph/api/driveitem-get-content-format?view=graph-rest-1.0>

## Project stack

- Kotlin and Jetpack Compose
- Android PDF rendering with `PdfRenderer`
- Offline OOXML rendering with @silurus/ooxml (Rust/WebAssembly parser and Canvas renderer, bundled at build time)
- Android WebView restricted to local app assets for Office rendering
- Microsoft Authentication Library (MSAL) for Android
- Microsoft Graph v1.0 for OneDrive listing and PDF conversion
