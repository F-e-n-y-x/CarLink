#!/usr/bin/env python3
"""Check relative links and image paths in CarLink's Markdown docs.

Usage: python3 scripts/check_links.py [FILE.md ...]

Without arguments it checks the README, CONTRIBUTING, SECURITY, CHANGELOG, docs/*.md (not the
screenshot index's 600+ image links unless named) and design/**/*.md. For every relative link,
image or HTML src/href/srcset it checks that the target exists, and for a "#anchor" into a
Markdown file that a heading produces that anchor (GitHub's slug rules). External URLs are not
fetched. Exits 1 when anything is broken.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MD_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HTML_ATTR = re.compile(r"""\b(?:src|href|srcset)\s*=\s*["']([^"']+)["']""")
FENCE = re.compile(r"^\s*(```|~~~)")


def default_files():
    files = ["README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md"]
    files += sorted(glob.glob("docs/*.md", root_dir=ROOT))
    files += sorted(glob.glob("docs/dissection/*.md", root_dir=ROOT))
    files += sorted(glob.glob("design/**/*.md", root_dir=ROOT, recursive=True))
    return [f for f in files if os.path.exists(os.path.join(ROOT, f))]


def slug(heading):
    text = re.sub(r"<[^>]+>", "", heading).strip().lower()
    text = re.sub(r"[`*_~]", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


_anchor_cache = {}


def anchors(path):
    if path not in _anchor_cache:
        found, counts, in_fence = set(), {}, False
        with open(path, encoding="utf-8") as f:
            for line in f:
                if FENCE.match(line):
                    in_fence = not in_fence
                    continue
                m = None if in_fence else re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
                if m:
                    base = slug(m.group(1))
                    n = counts.get(base, 0)
                    counts[base] = n + 1
                    found.add(base if n == 0 else f"{base}-{n}")
                for a in re.findall(r"""<a\s+(?:name|id)=["']([^"']+)["']""", line):
                    found.add(a)
        _anchor_cache[path] = found
    return _anchor_cache[path]


def targets(text):
    in_fence = False
    for lineno, line in enumerate(text.splitlines(), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = re.sub(r"`[^`]*`", "", line)
        for m in MD_LINK.finditer(line):
            yield lineno, m.group(1)
        for m in HTML_ATTR.finditer(line):
            for part in m.group(1).split(","):
                yield lineno, part.strip().split(" ")[0]


def check(rel):
    path = os.path.join(ROOT, rel)
    with open(path, encoding="utf-8") as f:
        text = f.read()
    errors = []
    for lineno, target in targets(text):
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
            continue  # http:, https:, mailto: ...
        file_part, _, anchor = target.partition("#")
        dest = path if not file_part else os.path.normpath(os.path.join(os.path.dirname(path), file_part))
        if not os.path.exists(dest):
            errors.append(f"{rel}:{lineno}: missing {target}")
            continue
        if anchor and dest.endswith(".md") and anchor not in anchors(dest):
            errors.append(f"{rel}:{lineno}: no heading for #{anchor} in {os.path.relpath(dest, ROOT)}")
    return errors


def main():
    files = sys.argv[1:] or default_files()
    errors, count = [], 0
    for rel in files:
        errors += check(rel)
        count += 1
    for e in errors:
        print(e)
    print(f"check_links: {count} files, {len(errors)} broken")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
