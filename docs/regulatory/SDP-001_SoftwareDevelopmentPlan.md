# Software Development Plan
## ECG Arrhythmia Monitor — Android Application
### IEC 62304:2015 §5.1

**Document ID:** SDP-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Software Safety Class:** Class B  
**Status:** DRAFT  

---

## 1. Development Environment

| Item | Choice | Rationale |
|------|--------|-----------|
| IDE | Android Studio Narwhal 2025.1.1 | Official Android IDE, current stable release |
| Language | Kotlin 2.0.20 | Type-safe, null-safe, official Android language |
| Min SDK | API 26 (Android 8.0) | Covers >95% of active Android devices |
| Target SDK | API 35 (Android 15) | Latest stable target |
| Build System | Gradle with AGP 8.9.1 | Standard Android build toolchain |
| UI Framework | Jetpack Compose | Declarative, testable UI |
| Architecture | MVVM + Repository | Separation of concerns, testable ViewModels |

---

## 2. Version Control

| Item | Detail |
|------|--------|
| System | Git |
| Remote | GitHub |
| Branching | `main` (stable), `develop` (integration), `feature/*` (individual features) |
| Commit policy | Atomic commits with descriptive messages referencing requirement IDs where applicable (e.g. `FR-BLE-02: Add permission check before scan`) |
| Tagging | Releases tagged as `v1.0.0`, `v1.0.1` etc. following semantic versioning |
| PR policy | All changes to `main` via pull request; no direct commits |

---

## 3. Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | 2024.x | UI framework |
| Room | 2.6.x | Local database (SQLite) |
| Kable | 0.30.0 | Bluetooth LE communication |
| Kotlin Coroutines | 1.7.x | Async operations |
| Hilt / manual DI | — | Dependency injection |
| Timber | 5.x | Structured logging |
| MPAndroidChart | 3.1.0 | ECG waveform rendering |

All dependency versions are pinned in `libs.versions.toml` and reviewed for security advisories before each release.

---

## 4. Software Architecture Summary

The application follows MVVM with a unidirectional data flow:

```
UI Layer (Compose Screens)
    ↕ StateFlow / Events
ViewModel Layer
    ↕ suspend functions
Repository Layer
    ↕
Data Layer (Room DB, BleManager, ConsentManager)
```

Full architectural detail is in SAD-001.

---

## 5. Coding Standards

- Kotlin official style guide enforced via `ktlint`
- No `!!` (force-unwrap) in production code — explicit null handling required
- All database operations in coroutines on `Dispatchers.IO`
- No business logic in Composable functions — ViewModels only
- All user-facing strings in `strings.xml` (no hardcoded strings in code)
- All health status UI text reviewed against IEC 62366 use error analysis

---

## 6. Risk-Driven Development Priorities

Per ISO 14971, features associated with higher residual risks receive additional review:

| Feature | Risk Ref | Additional Measures |
|---------|----------|---------------------|
| Arrhythmia detection algorithm | RISK-001, RISK-002 | Performance testing against MIT-BIH; code review by second developer |
| Health status display | RISK-007 | UI text reviewed against IEC 62366; usability testing |
| BLE disconnection handling | RISK-008 | Integration tests with simulated disconnection |
| Consent and data deletion | RISK-005 | Manual test protocol; audit log verification |

---

## 7. Testing Strategy

Full test plans are defined in SVVP-001. Summary:

| Level | Tool | Scope |
|-------|------|-------|
| Unit tests | JUnit 4 + MockK | ViewModels, Repository, detection algorithm |
| Integration tests | Room in-memory DB | Database operations, migrations |
| UI tests | Compose Testing | Screen navigation, consent flow |
| Manual tests | Test protocol in SVVP-001 | BLE connection, ECG display, export |

Minimum coverage target: **80% for safety-critical classes** (arrhythmia detection, consent management).

---

## 8. Configuration Management

- `libs.versions.toml` is the single source of truth for all dependency versions
- `google-services.json` (Firebase config) is excluded from version control via `.gitignore`; distributed to team via secure channel
- Database schema version tracked in `EcgDatabase.kt`; migrations required for all schema changes — `fallbackToDestructiveMigration()` is prohibited in release builds
- Build variants: `debug` (logging enabled, test data allowed) and `release` (logging disabled, ProGuard enabled)

---

## 9. Problem Resolution

All defects found during development or testing are tracked as GitHub Issues with:
- Severity label (Critical / Major / Minor)
- Reference to affected SRS requirement and risk ID where applicable
- Assigned milestone for resolution
- Linked to the PR that resolves it

Critical defects (those affecting safety-related requirements marked Must in SRS-001) block release until resolved.

---

## 10. Release Checklist

Before any release build:
- [ ] All Must requirements in SRS-001 verified against SVVP-001
- [ ] Risk Management File reviewed — no new unmitigated risks
- [ ] Database migration tested on upgrade from previous version
- [ ] Consent version reviewed — bump `CONSENT_VERSION` if text changed
- [ ] ProGuard rules verified — no safety-critical classes removed
- [ ] `google-services.json` present for release signing

---

## 11. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
