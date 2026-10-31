#!/usr/bin/env bash
# Installs the Postman CLI on macOS or Linux, so InvitationApiClient (used by
# the journey tests) has a "postman" executable on PATH to shell out to.
#
# Run once per machine, before `mvn test`:
#   ./scripts/install-postman-cli.sh
#
# On Windows, use scripts/install-postman-cli.ps1 instead - most of the team
# runs Windows locally, not macOS, so both scripts wrap the same official
# per-OS Postman CLI installers used in .github/workflows/appium-tests.yml.
#
# After installing, log in once with:
#   postman login --with-api-key "$POSTMAN_API_KEY"   # key from .env / Postman account settings

set -euo pipefail

case "$(uname -s)" in
	Darwin) curl -o- "https://dl-cli.pstmn.io/install/osx_64.sh" | sh ;;
	Linux)  curl -o- "https://dl-cli.pstmn.io/install/linux64.sh" | sh ;;
	*)
		echo "Unsupported OS: $(uname -s). On Windows, run scripts/install-postman-cli.ps1 instead." >&2
		exit 1
		;;
esac

postman --version
