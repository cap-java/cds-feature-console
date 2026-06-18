# Change Log

All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](https://semver.org/).
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
### Changed
- Use centrally managed BlackDuck scan action from [cap-java/.github](https://github.com/cap-java/.github) instead of a local copy
- Set BlackDuck scan mode to `RAPID` since the project is not yet registered on sap.blackducksoftware.com
- Override Spring Boot to 3.5.15 and Bouncy Castle to 1.84 to fix HIGH/CRITICAL BlackDuck CVE findings
### Deprecated
### Removed
- Local BlackDuck, CodeQL, and Sonar scan action copies (replaced by cap-java/.github workflows)
### Fixed
### Security


## [1.0.0] - 2025-10-13

### Added
- Initial Release
### Changed
### Deprecated
### Removed
### Fixed
### Security