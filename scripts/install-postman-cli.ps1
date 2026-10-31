# Installs the Postman CLI on Windows, so InvitationApiClient (used by the
# journey tests) has a "postman" executable on PATH to shell out to.
#
# Run once per machine, before `mvn test`:
#   .\scripts\install-postman-cli.ps1
#
# On macOS/Linux, use scripts/install-postman-cli.sh instead - both scripts
# wrap the same official per-OS Postman CLI installers used in
# .github/workflows/appium-tests.yml.
#
# After installing, log in once with:
#   postman login --with-api-key $env:POSTMAN_API_KEY

$ErrorActionPreference = "Stop"

iwr https://dl-cli.pstmn.io/install/win64.ps1 -useb | iex

postman --version
