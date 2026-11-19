# Vien Finder — Android camera enhancement

**v1.2.0** — Offline GPU-accelerated live camera enhancement for Samsung Galaxy A56.

[Download latest APK](https://github.com/MohamedTaltlo/Vien-Finder/releases/tag/v1.2.0).

## v1.2.0 fixes

- Replaces the v1.1 aggressive, fixed-base grayscale transformation that produced clipped whites and blacks with a brightness-preserving, bounded transformation.
- Reduces granular false patterns using spatial smoothing and suppresses abrupt high-contrast edges.
- Introduces **مقارنة**: original left half vs enhanced right half while the camera is live.
- More conservative defaults: sensitivity 45%, contrast 35%; adjustable as desired.
- 5 viewing modes: طبيعي, الأوردة, R−G, أخضر, ملون.
- Continues to use on-device OpenGL ES 2.0 processing; no picture uploads or network access.
- Camera2 continuous video, flashlight/torch request, focus, freeze, exposure and brightness controls.

## Install

Download `Vien-Finder.apk` from the [v1.2.0 release](https://github.com/MohamedTaltlo/Vien-Finder/releases/tag/v1.2.0).
Uninstall v1.0/v1.1 first. Android debug builds are signed with a different certificate on each workflow run.

## Test checklist

1. Use **طبيعي** and confirm the camera image looks normal and not overexposed.
2. Use **مقارنة** to compare the left original view and right enhanced view of the same skin area.
3. Try **الأوردة** with defaults (45/35/50), then raise sensitivity or contrast in small increments.
4. Compare **R−G**, **أخضر** and **ملون**; the best option depends on lighting, skin and device.
5. Toggle **فلاش** and check the actual phone LED, and test focus.
6. Report results with two screenshots: original and best enhanced mode plus slider settings.

Visible-light imaging does not reveal reliable vein depth or position. These enhancement outputs may include hair/shadow artefacts and should not be relied upon to select IV insertion sites without established clinical assessment.

## Build

GitHub Actions compiles the Java/Camera2/OpenGL Android APK, and publishes the debug installable app under [Releases](https://github.com/MohamedTaltlo/Vien-Finder/releases).
