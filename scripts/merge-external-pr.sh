#!/bin/bash

set -eof pipefail

trap cleanup EXIT

fatal() {
  echo "$@" >&2
  exit 1
}

warn() {
  echo "$@"
}

confirm() {
  read -p "$1 (type 'yes' to continue) " r
  if [[ $r != yes ]]; then
     exit 3
  fi
}

load_config() {
  [[ -f merge-external-pr.conf ]] && . merge-external-pr.conf
  : ${OWNER:=status-im}
  : ${REPO:=status-react}
  : ${REMOTE:=origin}
  : ${BRANCH:=develop}
}

check_pr_prereq() {
  if ! command -v jq >/dev/null; then
    fatal "jq(1) is not found, PR cannot be queried. Use REPO BRANCH"
  fi
  if ! command -v curl >/dev/null; then
    fatal "curl(1) is not found, PR cannot be queried. Use REPO BRANCH"
  fi
}

GH_URL_BASE="https://api.github.com"

get_pr_info() {
  echo '[ Reading PR info ]'
  if [ $# -eq 1 ]; then
    check_pr_prereq
    local pr=$1
    local pr_info_url="$GH_URL_BASE/repos/${OWNER}/${REPO}/pulls/$pr"
    set +e
    local pr_info
    pr_info=$(curl -fsS "$pr_info_url")
    if [ $? -ne 0 ]; then
      fatal "Unable to get PR info from $pr_info_url"
    fi
    set -e
    if [[ $(echo "$pr_info" | jq -r .state) == closed ]]; then
      fatal "PR $pr is closed, will not merge"
    fi
    if [[ $(echo "$pr_info" | jq -r .maintainer_can_modify) == true ]]; then
      RW_PR_REPO=1
    else
      warn "PR does not allow 'edits from maintainers', so it will be kept open"
    fi
    PR_URL=$(echo "$pr_info" | jq -r .head.repo.ssh_url)
    PR_REMOTE_NAME=pr-$pr
    PR_BRANCH=$(echo "$pr_info" | jq -r .head.ref)
    PR_LOCAL_BRANCH=pr-$pr
  else
    PR_URL="$1"
    PR_REMOTE_NAME=${PR_URL##*/}
    PR_REMOTE_NAME=pr-${PR_REMOTE_NAME%.git}
    PR_REMOTE_NAME=pr-${PR_REPO_NAME}
    PR_BRANCH="$2"
    PR_LOCAL_BRANCH=pr-${PR_REPO_NAME}
  fi
}

fetch_pr() {
  echo '[ Fetching PR ]'
  git remote add $PR_REMOTE_NAME $PR_URL
  git fetch $PR_REMOTE_NAME $PR_BRANCH
}

refresh_base_branch() {
  git fetch $REMOTE $BRANCH
}

rebase_pr() {
  git checkout -B $PR_LOCAL_BRANCH $PR_REMOTE_NAME/$PR_BRANCH
  git rebase $BRANCH
}

confirm_pr() {
  git log -p $BRANCH..$PR_LOCAL_BRANCH
  confirm "Do you like this PR?"
}

pr_authors() {
  git log --format='%an <%ae>' $BRANCH..$PR_LOCAL_BRANCH | sort -u
}

squash_pr() {
  git checkout -b $PR_LOCAL_BRANCH-squashed $BRANCH
  if [[ $(git rev-list $BRANCH..$PR_LOCAL_BRANCH | wc -l) == 1 ]]; then
    git merge --no-commit $PR_LOCAL_BRANCH
  else
    git merge --squash $PR_LOCAL_BRANCH
    if [[ $(pr_authors | wc -l) == 1 ]]; then
      PR_AUTHOR=$(pr_authors)
    else
      # Git does not have multi-authored commits, so put this information
      # into the commit message
      pr_authors | sed -e 's/^/Authored-by: /' \
                       >> $(git rev-parse --git-dir)/COMMIT_EDITMSG
    fi
  fi
}

sign_pr() {
  git commit --gpg-sign --signoff ${PR_AUTHOR:+--author=$PR_AUTHOR}
}

verify_pr() {
  git show --show-signature $PR_LOCAL_BRANCH-squashed
  confirm "Is the signature on the commit correct?"
}

merge_pr() {
  # If PR is specified and can be pushed into, do it to mark PR as closed
  if [[ -n $RW_PR_REPO ]]; then
      git push -f $PR_REMOTE_NAME $PR_LOCAL_BRANCH-squashed:$PR_BRANCH
  fi
  git checkout $BRANCH
  git merge --ff-only $PR_LOCAL_BRANCH-squashed
  git push $REMOTE $BRANCH
}

cleanup() {
  git checkout -q $BRANCH
  git branch -q -D $PR_LOCAL_BRANCH 2>/dev/null || :
  git branch -q -D $PR_LOCAL_BRANCH-squashed 2>/dev/null || :
  git remote remove $PR_REMOTE_NAME 2>/dev/null || :
}

run() {
  if [[ $# -lt 1 ]] || [[ $# -gt 2 ]]; then
    cat <<EOF >&2
Usage:
  ./merge-external-pr.sh REPO_URL BRANCH
  ./merge-external.pr.sh PR (if jq(1) and curl(1) are available)
EOF
    exit 2
  fi
  load_config
  get_pr_info "$@"
  cleanup
  fetch_pr
  refresh_base_branch
  rebase_pr
  confirm_pr
  squash_pr
  sign_pr
  verify_pr
  merge_pr
}

run "$@"
