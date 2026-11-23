# Contributing to CapitalEngine

Thanks for helping. This page covers what a pull request needs; [CLAUDE.md](CLAUDE.md) has the full development rules (they're written for any contributor, human or tool).

## Before you start
- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- **The plans are the spec.** Each module has a `Plan.md`. If your change alters the design (a new API method, a renamed table, a new default), update that `Plan.md` in the same pull request.
- Changes that players can see (menus, commands, messages, fees) also update the module's `docs/player-guide.md` and its `help-*` messages.

## Rules that reviews check
- **Money is a `long`** count of the smallest unit. Never `double` or `float`.
- **Add-ons talk to core only through the API** and never depend on each other.
- **Compile against Paper 1.21.11's API.** No NMS, no experimental APIs.
- **Player text is plain and kid-friendly,** lives in `messages.yml`, and new top-level entries go on one line.
- **No new dependencies** without asking in an issue first.
- **Tests:** anything that moves, splits or rounds money gets a test that fails if the logic breaks. Economy changes keep `./gradlew :sim:test` green.

## Pull requests
- Branch from `main`, run `./gradlew build`, and open a pull request.
- **The title must be a Conventional Commit:** `type(scope): summary`, at most 72 characters, for example `fix(market): stop listing expiry skipping a day`. Scopes are `api`, `core`, `market`, `bank`, `dashboard`, `trade`, `sim` or `repo`, and must match the folders you changed. The title becomes the changelog entry.
- Don't edit `version.txt`, `CHANGELOG.md` or `.release-please-manifest.json`. Releases are automated.
- Pull requests are squash-merged once the `build` check passes and a maintainer approves.

## Licence of contributions
By contributing, you agree that your contribution is licensed under the GNU GPL v3.0 only with the CapitalEngine Add-on Exception, the same terms as the rest of the project ([LICENSE](LICENSE), [ADDON-EXCEPTION.txt](ADDON-EXCEPTION.txt)). Only contribute work you have the right to license this way.

## Reporting security problems
Please don't open public issues for exploits such as duplication bugs or ways to create money. See [SECURITY.md](SECURITY.md).
