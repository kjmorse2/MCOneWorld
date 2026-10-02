#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Dependency, license, and (optionally) vulnerability report for the root build.

Reads every committed gradle.lockfile (shared modules and build-logic), finds each locked
artifact's POM in the local Gradle cache, follows parent POMs for the license, and classifies the
artifact as production, test, benchmark, or build tooling. With --osv it also asks the public OSV
database (https://osv.dev) about every coordinate: only public Maven coordinates are sent.

Run after a build has populated the Gradle cache:
    scripts/dependency-report.py [--osv] [--json out.json]
Exit status 1 if a production dependency has an unknown or non-permissive license, or (with
--osv) any locked artifact has a known advisory. Prompt 11 audit; see
docs/architecture/shared-release-audit.md.
"""
import argparse
import glob
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

CACHE = os.path.join(os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle")),
                     "caches/modules-2/files-2.1")
PERMISSIVE = re.compile(r"apache|mit|bsd|public domain", re.I)


def tag(element):
    return re.sub(r"\{.*?\}", "", element.tag)


def pom(group, artifact, version):
    found = glob.glob(f"{CACHE}/{group}/{artifact}/{version}/*/{artifact}-{version}.pom")
    return found[0] if found else None


def license_of(group, artifact, version, depth=0):
    path = pom(group, artifact, version)
    if not path or depth > 8:
        return None
    root = ET.parse(path).getroot()
    names = [child.text.strip() for e in root.iter() if tag(e) == "license"
             for child in e if tag(child) == "name" and child.text]
    if names:
        return "; ".join(dict.fromkeys(names))
    parent = [e for e in root if tag(e) == "parent"]
    if parent:
        p = {tag(x): x.text for x in parent[0]}
        return license_of(p.get("groupId"), p.get("artifactId"), p.get("version"), depth + 1)
    return None


def scope(uses):
    out = set()
    for module, configuration in uses:
        if module == "build-logic" or "errorprone" in configuration.lower() \
                or "annotationProcessor" in configuration:
            out.add("build tooling")
        elif configuration in ("runtimeClasspath", "compileClasspath") and module != "test-fixtures":
            out.add("production")
        elif configuration.startswith("bench"):
            out.add("benchmark")
        else:
            out.add("test")
    return sorted(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osv", action="store_true", help="query osv.dev for advisories")
    parser.add_argument("--json", help="write the full report here")
    args = parser.parse_args()
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    uses = {}
    for lockfile in sorted(["build-logic/gradle.lockfile"] + glob.glob("shared/*/gradle.lockfile")):
        module = lockfile.split("/")[-2] if lockfile.startswith("shared") else "build-logic"
        for line in open(lockfile):
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("empty="):
                continue
            coordinate, configurations = line.split("=", 1)
            for configuration in configurations.split(","):
                uses.setdefault(coordinate, set()).add((module, configuration))
    rows = []
    for coordinate in sorted(uses):
        group, artifact, version = coordinate.split(":")
        rows.append({"artifact": coordinate, "scope": scope(uses[coordinate]),
                     "license": license_of(group, artifact, version) or "UNKNOWN", "advisories": []})
    if args.osv:
        query = {"queries": [{"package": {"ecosystem": "Maven", "name": r["artifact"].rsplit(":", 1)[0]},
                              "version": r["artifact"].rsplit(":", 1)[1]} for r in rows]}
        request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(query).encode(),
                                         headers={"Content-Type": "application/json"})
        results = json.load(urllib.request.urlopen(request, timeout=60))["results"]
        for row, result in zip(rows, results):
            row["advisories"] = [v["id"] for v in result.get("vulns", [])]
    failed = False
    for r in rows:
        bad_license = "production" in r["scope"] and not PERMISSIVE.search(r["license"])
        failed |= bad_license or bool(r["advisories"])
        if "production" in r["scope"] or bad_license or r["advisories"] or r["license"] == "UNKNOWN":
            print(f'{r["artifact"]:60} {",".join(r["scope"]):30} {r["license"]} {" ".join(r["advisories"])}')
    by_scope = {}
    for r in rows:
        for s in r["scope"]:
            by_scope[s] = by_scope.get(s, 0) + 1
    print(f"{len(rows)} locked artifacts; by scope {by_scope}; "
          f"advisories {'checked' if args.osv else 'not checked'}: "
          f"{sum(1 for r in rows if r['advisories'])}")
    if args.json:
        with open(args.json, "w") as out:
            json.dump(rows, out, indent=1)
            out.write("\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
