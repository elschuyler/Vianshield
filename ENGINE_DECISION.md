# Engine Decision: Firestack (Phase 2 Candidate)

## 1. Candidate Overview
- **Library**: `com.celzero:firestack`
- **Pinned Version**: `8134609734`
- **Publication**: Official Maven Central publication (`https://repo1.maven.org/maven2/com/celzero/firestack/8134609734/`)
- **Repository**: [https://github.com/celzero/firestack](https://github.com/celzero/firestack)
- **Maintainer**: Celzero / Rethink DNS Open Source Project

## 2. Licence Information
- **Licence**: Mozilla Public License 2.0 (MPL-2.0)
- **Licence URL**: [https://opensource.org/license/mpl-2-0](https://opensource.org/license/mpl-2-0)
- **Compliance Notes**:
  - The library binaries and source files provided by Firestack are governed by the MPL-2.0.
  - In accordance with MPL-2.0, utilizing Firestack as an external dependency/library in Vianshield does not compel larger application code outside of Firestack source files to be under MPL-2.0.
  - Full third-party attribution and licence notice must be preserved across builds and releases.

## 3. Native Architecture & ABI Support
Firestack is a Go-based userspace networking stack compiled into a native Android shared object (`libgojni.so`). The published AAR packages precompiled binaries for four standard Android ABIs:
1. `arm64-v8a`: 64-bit ARM architecture (standard for modern Android smartphones including low-end 3 GB RAM devices).
2. `armeabi-v7a`: 32-bit legacy ARM architecture.
3. `x86`: 32-bit x86 architecture (emulators and legacy devices).
4. `x86_64`: 64-bit x86 architecture (modern Android emulators).

## 4. Packaging Quirk & Dependency Resolution
- **Packaging in POM**: The Maven Central POM for `com.celzero:firestack:8134609734` specifies `<packaging>jar</packaging>`, but the published artifact on Maven Central is `firestack-8134609734.aar`.
- **Gradle Resolution Fix**: In `app/build.gradle.kts`, the dependency must specify `artifact { type = "aar" }` so Gradle fetches the `.aar` rather than looking for a non-existent `.jar`:
  ```kotlin
  implementation(libs.firestack) {
    artifact {
      type = "aar"
    }
  }
  ```

## 5. APK Size & Hardware Impact Analysis (~3 GB RAM Device)
- **Universal Debug APK Size**: **87 MB** (compared to ~7 MB in Phase 1 without native libraries).
- **Native Library Breakdown**:
  - `lib/arm64-v8a/libgojni.so`: 18,620,760 bytes (~18.6 MB)
  - `lib/armeabi-v7a/libgojni.so`: 17,796,416 bytes (~17.8 MB)
  - `lib/x86/libgojni.so`: 18,090,312 bytes (~18.1 MB)
  - `lib/x86_64/libgojni.so`: 19,772,824 bytes (~19.8 MB)
  - Total native payload across all 4 architectures: ~74.3 MB.
- **Runtime RAM Consideration**: While the universal APK is 87 MB to download/install, Android's dynamic linker loads only the single ABI matching the target device (`arm64-v8a` on modern phones), meaning the phone will not load the other three 18 MB binaries into RAM.
- **Recommendation for Future Steps**: If APK download size on mobile data becomes an issue, ABI splits can be enabled in Gradle to produce single-ABI APKs (~22 MB).

## 6. Current Integration Scope (Phase 2 Step 1)
- **Status**: Evaluated and linked as a build dependency.
- **VPN / TUN Service**: **Not implemented or started.** No background service, packet redirection, TUN interface creation, or simulated firewall toggles have been created.
- **Compatibility**: Android `minSdk = 24` (Android 7.0) is supported by Firestack. No system upgrade or elevated permissions are required for this integration stage.
