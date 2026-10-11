# BongScanner

### The completely free document scanner for Android

**No ads. No watermarks. No subscription. No sign-up.**
Everything a paid scanner app charges for, in one small app that keeps your documents on your own phone.

<p>
  <a href="https://github.com/elvinsanity98/DocScanner/raw/main/release/BongScanner-v1.0.0.apk">
    <img alt="Download APK" src="https://img.shields.io/badge/Download-APK%20v1.0.0-00897B?style=for-the-badge&logo=android&logoColor=white">
  </a>
</p>

Developed by **Bongzkie**.

---

## Why switch?

Most document scanner apps are free to install and then charge you for the parts you actually need.
BongScanner has no paid tier at all.

| | Typical scanner app (free tier) | **BongScanner** |
|---|---|---|
| Price | Monthly or yearly subscription for full features | **Free. All features.** |
| Ads | Often | **None** |
| Watermark on your scans | Often, until you pay | **Never** |
| Text recognition (OCR) | Often limited or paid | **Unlimited, works offline** |
| Password-protected PDF | Usually paid | **Included** |
| Searchable PDF | Usually paid | **Included** |
| Account / login | Often required | **Not needed** |
| Where your scans live | Frequently uploaded to a cloud | **On your phone only** |

## Features

**Scan**
- Smart Scan with live edge detection and automatic capture
- Built-in camera with single, batch and ID card modes
- Flash, flashlight, grid lines, tap to focus, pinch to zoom
- Import photos from the gallery or pages from an existing PDF
- "Share to BongScanner" from any other app

**Clean up**
- Automatic page detection, plus drag-the-corners cropping with a magnifier
- Perspective correction, so a photo taken at an angle comes out flat
- Filters: Original, Magic Color, Lighten, Grayscale, B&W Soft, B&W
- Brightness and contrast controls, apply one look to every page
- Non-destructive: the original photo is kept, so you can re-crop or change the filter any time
- Sign or draw on a page with pens and a highlighter

**Read**
- Offline text recognition (OCR): copy, share or save the text
- Search your documents by title or by the text inside them
- QR code and barcode reader

**Export**
- PDF or JPG
- Page sizes: fit to scan, A4, Letter, Legal
- Password lock, text watermark, searchable text layer
- ID card layout: front and back on one A4 sheet
- Share, save to your phone, or print

**Organise**
- Folders, stars, sorting, grid or list view
- Rename, merge documents, split pages into a new document
- Hold and drag to reorder pages
- Light and dark theme

## Install

1. Download **[BongScanner-v1.0.0.apk](https://github.com/elvinsanity98/DocScanner/raw/main/release/BongScanner-v1.0.0.apk)** on your Android phone.
2. Open the file. If Android asks, allow your browser or file manager to install apps.
3. Tap **Install**, then open BongScanner and press **Scan**.

Requires Android 7.0 or newer.

## Privacy

- BongScanner has no servers and no accounts. Your scans are stored on your device and leave it only when you share or export them yourself.
- Text recognition, filters and PDF creation all run on the phone.
- Smart Scan, OCR and the barcode reader use Google ML Kit. Smart Scan needs Google Play services and downloads its scanner once on first use; ML Kit may send anonymous usage statistics to Google. On phones without Google Play services the built-in camera is used instead.

## Build from source

You need JDK 17 and the Android SDK (platform 34).

```bash
git clone https://github.com/elvinsanity98/DocScanner.git
cd DocScanner
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Without a `keystore.properties` file the build is signed with the debug key; add your own keystore to sign a release.

Kotlin, Jetpack Compose (Material 3), CameraX and Google ML Kit. PDFs are produced by a small built-in writer (`app/src/main/java/com/bongzkie/bongscanner/export/PdfWriter.kt`).

## Feedback

This is the first public release (v1.0.0). Found a bug or want a feature? Open an issue on this repository.

---

<p align="center"><b>BongScanner</b> &middot; Developed by Bongzkie &middot; Free, for everyone</p>
