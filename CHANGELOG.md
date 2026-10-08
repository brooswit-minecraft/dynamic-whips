# Changelog

## Versioning

Semantic versioning, assigned at release time from the `bump:` level declared
in each `changelog.d/` fragment (see `changelog.d/README.md`).

- `major`: a breaking change to the public API or to existing world data.
- `minor`: a new feature, block, or API surface.
- `patch`: a fix or internal change with no new surface.

Do not add dated `## [x.y.z]` headings or edit `version=` in
`gradle.properties` by hand; the release workflow does both.
