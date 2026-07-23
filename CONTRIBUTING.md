# Contributing to JADX Headless Server (jadx-core-mcp)

First off, thank you for considering contributing to `jadx-core-mcp`! We welcome contributions, bug reports, feature requests, and improvements.

## How to Contribute

### 1. Reporting Bugs & Feature Requests
- Open an issue describing the bug or feature request.
- Include environment details (Java version, OS, JADX SDK version).
- For bug reports, provide steps to reproduce and relevant log snippets (`logs/server.log` or `logs/error.log`).

### 2. Submitting Pull Requests
1. **Fork the Repository**: Create your feature branch (`git checkout -b feature/amazing-feature`).
2. **Coding Standards**:
   - Write clean, readable, and modular Kotlin code.
   - Use English for code comments, KDocs, and log messages.
   - Ensure thread safety when touching core engine data structures.
3. **Run Tests**:
   Before submitting your PR, ensure all tests pass:
   ```bash
   ./gradlew test --rerun-tasks
   ```
4. **Build Verification**:
   Verify that the standalone executable Fat-JAR builds cleanly:
   ```bash
   ./gradlew :app:shadowJar
   ```
5. **Commit Messages**: Write concise and informative commit messages following conventional commits (`feat: ...`, `fix: ...`, `docs: ...`).
6. **Open PR**: Submit a Pull Request targeting the `main` branch with a clear description of changes.

## License
By contributing to `jadx-core-mcp`, you agree that your contributions will be licensed under the [Apache License 2.0](./LICENSE).
