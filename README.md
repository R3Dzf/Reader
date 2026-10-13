# Reader for Android

Reader is a read-only Android app for viewing PDFs and Microsoft Office documents on a phone.

## What works

- Open a PDF stored on the phone and read it page by page inside Reader.
- Open a local Word, Excel, or PowerPoint file with an installed Microsoft 365 app. Reader grants read access to the selected file and does not save changes to it.
- Sign in to OneDrive and list supported Office documents from the root folder.
- Convert a selected OneDrive Office document to a temporary PDF through Microsoft Graph, then read that PDF inside Reader.
- English and Arabic interface strings; the layout follows the phone's language direction.
- Temporary converted files live in the app cache and can be cleared by Android at any time.

Supported in-app OneDrive conversion: `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, and `.pptx`.

## Microsoft sign-in setup

The app uses delegated `Files.Read` access only. It does not upload local files, request write permission, or change OneDrive documents. A Microsoft Entra app registration is required before the OneDrive button can sign in.

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

For local Office files, Reader hands the document to an installed Office app with read-only URI access. For OneDrive files, Microsoft Graph's conversion endpoint creates a PDF preview; Reader downloads that preview to its private cache. The original document is not modified. Rendering can still vary for protected files, unsupported document features, unavailable fonts, or content that Office does not place in a static PDF.

Graph conversion reference: <https://learn.microsoft.com/en-us/graph/api/driveitem-get-content-format?view=graph-rest-1.0>

## Project stack

- Kotlin and Jetpack Compose
- Android PDF rendering with `PdfRenderer`
- Microsoft Authentication Library (MSAL) for Android
- Microsoft Graph v1.0 for OneDrive listing and PDF conversion
