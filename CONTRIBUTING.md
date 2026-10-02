# Contributing to Turbo Optimizer

Thank you for your interest in contributing to the **Turbo Optimizer** project! This repository is dedicated to providing an open-source, educational, and high-performance Android system tuning utility.

We welcome contributions from developers, researchers, and enthusiasts who share our commitment to clean code, responsible software engineering, and platform security.

---

## 1. Ethical Standards & Responsible Development

To maintain full compliance with **GitHub's Acceptable Use Policies** and adhere to industry security standards, all contributors must strictly observe the following principles:

1. **Device-Level Scope Only**: All features must operate exclusively at the Android operating system and device configuration level (e.g., standard Android settings, power management hints, and display refresh rates).
2. **Zero Binary or Memory Tampering**: Contributions that attempt to inspect, alter, inject code into, hook, or reverse-engineer third-party application binaries, runtime memory, or assets will be rejected immediately.
3. **No Circumvention or Exploits**: This project strictly prohibits code intended to bypass DRM, anti-cheat frameworks, application sandboxing, device lockouts, or network access controls.
4. **No Remote Traffic Interception**: The local network optimization component must remain strictly an on-device loopback filter via Android's native `VpnService` API. Contributions introducing remote proxies, external tunneling, packet sniffing, or man-in-the-middle (MITM) capabilities are strictly disallowed.
5. **Privacy First**: No telemetry, tracking identifiers, or personally identifiable information (PII) may be collected, stored, or transmitted.

---

## 2. Code of Conduct

By participating in this project, you agree to:
- Treat all community members with respect, dignity, and professional courtesy.
- Foster an open, collaborative, and harassment-free environment.
- Respect intellectual property rights and only submit code and documentation you have the legal right to contribute under the project's [MIT License](LICENSE).

---

## 3. How to Contribute

### Reporting Bugs
If you identify a bug or unexpected behavior:
1. Search the existing [GitHub Issues](../../issues) to verify the problem has not already been reported.
2. Open a new issue providing:
   - Device model, Android OS version, and Shizuku status (Wireless Debugging or ADB).
   - Clear steps to reproduce the issue.
   - Expected behavior vs. actual behavior.
   - Relevant logcat snippets (ensure no private credentials or sensitive personal information are included).

### Requesting Features
We encourage proposals for new system administration and resource management capabilities. When submitting a feature request:
- Detail the practical use case and educational benefit.
- Ensure the requested feature aligns with our ethical standards (device-level tuning only).
- Describe the underlying Android system APIs or Shizuku capabilities required.

### Submitting Pull Requests (PRs)
1. **Fork the Repository**: Clone your fork locally.
2. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Follow Coding Standards**:
   - Write idiomatic Kotlin following official Android style guidelines.
   - Use declarative UI patterns with Jetpack Compose.
   - Maintain strict separation of concerns (UI, domain use cases, and system service abstractions).
   - Validate and sanitize all inputs before passing them to system shell or Shizuku IPC calls.
4. **Run Tests and Verification**:
   - Ensure all existing unit tests pass:
     ```bash
     ./gradlew testDebugUnitTest
     ```
   - Verify that the project builds cleanly:
     ```bash
     ./gradlew assembleDebug
     ```
5. **Never Commit Secrets**:
   - Verify that your commit does **not** include keystores (`*.jks`, `*.keystore`), `gradle.properties`, or `local.properties`.
6. **Submit PR**: Open a pull request against the `main` branch with a clear description of your changes and motivation.

---

## 4. Responsible Security Disclosure

If you discover a security vulnerability or platform concern within this repository, please do **not** open a public issue. Instead, report it responsibly to the maintainers via GitHub's Private Vulnerability Reporting or by contacting the repository administrator directly.

We review all security reports promptly and collaborate with reporters to address verified concerns before public release.

---

## 5. Licensing

All contributions submitted to this project will be licensed under the [MIT License](LICENSE). By submitting a pull request, you confirm that your contribution is original work and that you agree to its release under these terms.
