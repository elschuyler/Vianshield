# Vianshield

Vianshield is a native Android application built with Kotlin and Jetpack Compose, designed with a minimal footprint suitable for low-memory devices (~3 GB RAM).

## Current Project Status: Initial Shell (Phase 1)

This repository contains the initial, lightweight shell of the Vianshield application.

> **Important**: The networking and firewall engine has **not** been integrated yet. This stage contains only the clean shell UI and automated build pipeline. There are no background packet filters, VPN tunnels, fake statistics, or mock protection switches.

## Build and CI/CD Pipeline

The project is configured for cloud-native compilation via GitHub Actions:
- **Workflow**: `.github/workflows/android-build.yml`
- **Triggers**: Automatic on every push, pull request, or manual `workflow_dispatch`.
- **Target**: Compiles a standalone debug APK without requiring release keys, secrets, or paid external services.
- **Artifact**: Uploads the resulting APK as `vianshield-debug-apk` directly to the workflow run artifacts for on-device installation and testing.

## Technical Specifications
- **Application ID**: `app.vian.vianshield`
- **Language**: Kotlin 2.2.10
- **UI Toolkit**: Jetpack Compose (Material Design 3)
- **Minimum SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 16 (API 36)
- **Build System**: Android Gradle Plugin 9.1.1 & Gradle 9.3.1
- **JDK**: Java 21 (Temurin)

## Architecture Roadmap
1. **Phase 1 (Current)**: Minimal native Android shell, clean M3 UI, verified CI build pipeline.
2. **Phase 2 (Upcoming)**: Network engine architecture review and evaluation.
3. **Phase 3**: Engine integration and local firewall filtering.
