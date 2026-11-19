# Vien Finder — Android Camera Preview

**Vien Finder v1.1.0** — GPU-accelerated offline camera contrast explorer for Samsung Galaxy A56.

[Download Vien Finder APK](https://github.com/MohamedTaltlo/Vien-Finder/releases/latest) or view the [v1.1.0 release](https://github.com/MohamedTaltlo/Vien-Finder/releases/tag/v1.1.0).

## Controls

- **طبيعي**: normal camera.
- **الأوردة**: smooth adaptive grayscale, spectral red-green plus green reflectance contrast.
- **R−G**: normalized local red/green spectral difference.
- **أخضر**: green-channel contrast.
- **ملون**: smoothly darkened color overlay instead of cyan speckles.
- Sensitivity, contrast, brightness and camera exposure.
- Toggle flashlight (Camera2 repeating request + auto-exposure), freeze frame, invert contrast, refocus, flip the preview direction, hide controls.
- Local GPU OpenGL ES 2.0 processing. No image uploads, no analytics, no accounts, no network permission.

## Install

Download `Vien-Finder.apk` from the [v1.1.0 release](https://github.com/MohamedTaltlo/Vien-Finder/releases/tag/v1.1.0).
Because each GitHub Actions build currently uses a fresh Android debug signing certificate, **uninstall the v1.0.0 app before installing v1.1.0**.

## Known limitations

Visible-light phone cameras cannot detect all deep veins, show true vessel depth or guarantee correspondence between enhanced contrast and actual veins. Artifacts from hair, shadows, color, tattoos and movement may remain. This is a camera-image enhancement prototype, not a validated tool for IV/cannula site selection.

The project is self-contained Java/Android Camera2 + OpenGL ES2, built with Gradle through GitHub Actions.
