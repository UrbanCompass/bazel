#!/bin/bash
set -e
set -x
export LANG=C # Workaround for sed
if ! which -s gsed; then
  # Install gsed which supports \b regex
  echo "Installing gsed"
  brew install gsed
fi
find . -type f | grep -E '\.(md|patch|vm|html|bzl|java|c|cc|h|proto|txt|sh|bash|git_log)$' | xargs gsed -r -i"" -e 's/\bBUILD\b/UCBUILD/g'
find . -type f | grep -E '(_bazel|Makefile|WORKSPACE|UCBUILD)' | xargs gsed -r -i"" -e 's/\bBUILD\b/UCBUILD/g'
find . -type f | grep 'UCBUILD' | xargs rename 's/\bBUILD\b/UCBUILD/'
