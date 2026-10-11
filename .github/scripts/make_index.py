#!/usr/bin/env python3
"""Builds the official Aniyomi `index.min.json` from the built extension artifacts.

Usage: make_index.py <apk_artifact_dir> <repo_dir> <workspace_root>

Reads every `source-info.json` produced by the build, copies the matching APK
and icon into the repo branch layout and (re)writes index.min.json:
    <repo>/index.min.json
    <repo>/apk/<apk name>
    <repo>/icon/<package name>.png
"""
import glob
import json
import os
import shutil
import sys


def main() -> None:
    artifact_dir, repo_dir, workspace = sys.argv[1], sys.argv[2], sys.argv[3]

    apks = glob.glob(os.path.join(artifact_dir, "**", "*.apk"), recursive=True)
    infos = glob.glob(os.path.join(artifact_dir, "**", "source-info.json"), recursive=True)
    if not apks or not infos:
        print("no apks or source-info.json found", file=sys.stderr)
        sys.exit(1)

    apk_by_name = {os.path.basename(p): p for p in apks}
    os.makedirs(os.path.join(repo_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(repo_dir, "icon"), exist_ok=True)

    entries = []
    for info_path in sorted(infos):
        with open(info_path, encoding="utf-8") as f:
            meta = json.load(f)

        pkg = meta["packageName"]
        version_name = meta["versionName"]          # e.g. "17.1"
        ext_code = int(version_name.split(".")[1])  # extension versionCode
        module = meta["module"]                     # e.g. "all.cinemabox"
        lang_dir, name_dir = module.split(".")
        apk_name = f"aniyomi-{module}-v{version_name}.apk"

        src = apk_by_name.get(apk_name)
        if src is None:
            candidates = [
                p for p in apks
                if os.path.basename(p).startswith(f"aniyomi-{module}-v{version_name}")
            ]
            src = candidates[0] if candidates else None
        if src is None:
            print(f"warn: apk not found for {module} v{version_name}", file=sys.stderr)
            continue
        shutil.copyfile(src, os.path.join(repo_dir, "apk", apk_name))

        icon_src = os.path.join(
            workspace, "src", lang_dir, name_dir, "res", "mipmap-xxxhdpi", "ic_launcher.png",
        )
        if os.path.exists(icon_src):
            shutil.copyfile(icon_src, os.path.join(repo_dir, "icon", f"{pkg}.png"))

        sources = [
            {
                "name": s["name"],
                "lang": s["lang"],
                "id": str(s["id"]),
                "baseUrl": s.get("baseUrl", ""),
            }
            for s in (meta.get("sources") or [])
        ]

        entries.append({
            "name": f"Aniyomi: {meta['name']}",
            "pkg": pkg,
            "apk": apk_name,
            "lang": sources[0]["lang"] if sources else "all",
            "code": ext_code,
            "version": version_name,
            "nsfw": 1 if meta.get("contentWarning", 0) > 1 else 0,
            "sources": sources,
        })

    with open(os.path.join(repo_dir, "index.min.json"), "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))

    # Aniyomi fetches this when the repository is added.
    repo_meta = {
        "meta": {
            "name": "mo7AmMeD64 Aniyomi",
            "shortName": "AR",
            "website": "https://github.com/mo7AmMeD64/ar_aniyomi",
            "signingKeyFingerprint": "352acef809093a7fbbe1210e4bb557f3337a3dcb581d8cd6cf1e7cd4303c181a",
        },
    }
    with open(os.path.join(repo_dir, "repo.json"), "w", encoding="utf-8") as f:
        json.dump(repo_meta, f, ensure_ascii=False, separators=(",", ":"))

    print(f"index.min.json: {len(entries)} extension(s)")


if __name__ == "__main__":
    main()
