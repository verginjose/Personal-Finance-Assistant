#!/usr/bin/env bash
# Open (or find) the image-promotion PR using this job's short-lived token.
set -euo pipefail

: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
: "${BRANCH:?BRANCH is required}"
: "${TAG:?TAG is required}"

api="${GITHUB_API_URL:-https://api.github.com}/repos/${GITHUB_REPOSITORY}/pulls"
owner="${GITHUB_REPOSITORY%%/*}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
headers=(-H "Authorization: Bearer ${GITHUB_TOKEN}"
         -H "Accept: application/vnd.github+json"
         -H "X-GitHub-Api-Version: 2022-11-28")

# Reuse an existing open PR if this step is retried after successful creation.
curl --fail-with-body --silent --show-error "${headers[@]}" --get "$api" \
  --data-urlencode "state=open" --data-urlencode "head=${owner}:${BRANCH}" \
  --data-urlencode "base=${GITHUB_REF_NAME}" > "$tmp/existing.json"
url="$(jq -r '.[0].html_url // empty' "$tmp/existing.json")"
if [[ -z "$url" ]]; then
  {
    printf 'Promotes the successfully built service images to `%s`.\n\n' "$TAG"
    printf 'Source commit: `%s`\n\n' "${GITHUB_SHA:-unknown}"
    printf 'Build run: %s/%s/actions/runs/%s\n\n' \
      "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY" "${GITHUB_RUN_ID:-unknown}"
    printf 'Review and merge this PR to let Argo CD deploy the images.\n'
  } > "$tmp/body.md"
  jq -n --arg title "chore(deploy): promote built images to ${TAG}" \
    --arg head "$BRANCH" --arg base "$GITHUB_REF_NAME" \
    --rawfile body "$tmp/body.md" \
    '{title: $title, head: $head, base: $base, body: $body}' > "$tmp/request.json"
  if ! curl --fail-with-body --silent --show-error "${headers[@]}" \
      -H 'Content-Type: application/json' --request POST "$api" \
      --data-binary "@$tmp/request.json" > "$tmp/created.json"; then
    echo '::error::Could not create the promotion PR. Grant the promote job pull-requests: write and enable Settings > Actions > General > Allow GitHub Actions to create and approve pull requests.' >&2
    cat "$tmp/created.json" >&2
    exit 1
  fi
  url="$(jq -er '.html_url | select(type == "string" and length > 0)' "$tmp/created.json")"
fi
echo "Pull request: $url"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '\n**Promotion pull request:** %s\n\nMerge it to deploy through Argo CD.\n' "$url" >> "$GITHUB_STEP_SUMMARY"
fi
