#!/bin/bash
set -eu

alias validate-docs="docker run --rm -v $(pwd):/src advancedtelematic/swagger-cli swagger validate"
validate-docs docs/swagger/sota-resolver.yml
validate-docs docs/swagger/sota-device_registry.yml
validate-docs docs/swagger/sota-core.yml
