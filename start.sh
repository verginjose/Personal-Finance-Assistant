#!/usr/bin/env bash
# Shared project selection from either repository.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${ROOT_DIR}/../Advanced_Hospital_Management_System/scripts/workspace.sh" start "$@"
