#!/usr/bin/env python3
"""Allocate CI versions from the local record and remote release history."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys


VERSION_PATTERN = re.compile(r"(\d{2}\.\d{2}\.\d{2})-(\d{5})[ADBTCRPH]")


def read_record(path):
    properties = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator:
                raise ValueError("Invalid version.properties entry")
            properties[key.strip()] = value.strip()
    code = int(properties["lastVersionCode"])
    name = properties["lastVersionName"]
    if code < 1 or not VERSION_PATTERN.fullmatch(name):
        raise ValueError("Invalid locally allocated version")
    return code, name


def next_version(date, stage, run_number, last_code, last_name, tags, releases):
    datetime.datetime.strptime(date, "%y.%m.%d")
    if stage not in "ADBTCRPH" or len(stage) != 1 or run_number < 1:
        raise ValueError("Invalid build stage or workflow run number")
    sequence = 0
    for name in [last_name, *tags]:
        match = VERSION_PATTERN.fullmatch(name)
        if match and match[1] == date:
            sequence = max(sequence, int(match[2]))
    if sequence >= 99999:
        raise ValueError("Today's five-digit version sequence is exhausted")
    remote_code = 0
    for release in releases:
        body = release.get("body") or ""
        for match in re.finditer(r"versionCode:\s*`?(\d+)", body, re.IGNORECASE):
            remote_code = max(remote_code, int(match[1]))
    code = max(last_code + run_number, remote_code + 1)
    if code > 2100000000:
        raise ValueError("Android versionCode limit exceeded")
    return code, f"{date}-{sequence + 1:05d}{stage}"


def read_remote(command):
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"Remote version read failed: {command[0]} (exit {result.returncode})")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--run-number", type=int, required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--properties", default="version.properties")
    parser.add_argument("--output", default=os.environ.get("GITHUB_ENV"))
    args = parser.parse_args()
    if not args.repository or not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        raise ValueError("A valid GitHub repository is required")
    last_code, last_name = read_record(args.properties)
    remote_tags = read_remote(["git", "ls-remote", "--tags", "origin"])
    tags = [line.split()[1].removeprefix("refs/tags/").removesuffix("^{}")
            for line in remote_tags.splitlines() if line.strip()]
    pages = json.loads(read_remote([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{args.repository}/releases?per_page=100",
    ]))
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError("Unexpected GitHub releases response")
    releases = [release for page in pages for release in page]
    if any(not isinstance(release, dict) or
           not isinstance(release.get("body") or "", str) for release in releases):
        raise ValueError("Unexpected GitHub release metadata")
    code, name = next_version(args.date, args.stage, args.run_number,
                              last_code, last_name, tags, releases)
    if args.output:
        with Path(args.output).open("a", encoding="utf-8") as output:
            output.write(f"VERSION_CODE={code}\nVERSION_NAME={name}\n")
    print(f"Build version: {name} (versionCode={code})")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, OSError, RuntimeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
