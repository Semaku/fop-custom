# fop-custom library changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [2.2.0] - 2020-11-13
### Changed
- Limited support to FOP 2.1
### Fixed
- Added support for auto table layout (https://issues.apache.org/jira/browse/FOP-2469)

## [2.1.2] - 2020-03-31
### Fixed
- Reapplied lost fix for the issue with linear gradient stop offsets resulting in broken PDFs (https://issues.apache.org/jira/browse/FOP-2549)

## [2.1.1] - 2019-07-02
### Changed
- Support both FOP 2.1 and FOP 2.3
### Fixed
- Added fix for https://issues.apache.org/jira/browse/FOP-2549 again to support FOP 2.1

## [2.1.0] - 2019-07-02
### Changed
- Upgraded to FOP 2.3
### Fixed
- Added fix for https://issues.apache.org/jira/browse/FOP-2617
- Removed obsolete fix for https://issues.apache.org/jira/browse/FOP-2549 (already fixed in 2.3)

## [2.0.0] - 2018-04-16
### Changed
- Upgraded to FOP 2.1
- Removed obsolete customization
### Fixed
- Added fix for https://issues.apache.org/jira/browse/FOP-2536
- Added fix for https://issues.apache.org/jira/browse/FOP-2549

## [1.0.1] - 2016-02-24
### Changed
- Support Java 6+
### Fixed
- Fix issue with wrong avalon group id at fop dependency (https://issues.apache.org/jira/browse/FOP-2151)

## [1.0.0] - 2015-07-21
### Added
- Added custom URIResolver that supports user info
