#!/usr/bin/env python3
"""Report Android resource names defined by more than one CarLink module.

Libraries merged into one APK share a single resource table, so a name defined by both
engines is silently resolved to one definition. Run from the repository root:

    python3 scripts/resource_collisions.py            # summary + every collision
    python3 scripts/resource_collisions.py --names    # "type name" lines only
    python3 scripts/resource_collisions.py --check    # fail on collisions not in the allowlist
"""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

MODULES = {
    "app": Path("app/src/main/res"),
    "carlink-core": Path("carlink-core/src/main/res"),
    "carplay-common": Path("vendor/diplay/common/src/main/res"),
    "carplay-shared": Path("vendor/diplay/shared/src/main/res"),
    "androidauto": Path("vendor/diauto/app/src/main/res"),
    "androidauto-github": Path("vendor/diauto/app/src/github/res"),
    "androidauto-contract": Path("vendor/diauto/contract/src/main/res"),
}
# Modules belonging to the same engine may legitimately override each other.
ENGINE = {
    "app": "app", "carlink-core": "app",
    "carplay-common": "carplay", "carplay-shared": "carplay",
    "androidauto": "androidauto", "androidauto-github": "androidauto",
    "androidauto-contract": "androidauto",
}
VALUE_TAGS = {
    "string": "string", "plurals": "plurals", "string-array": "array", "array": "array",
    "integer-array": "array", "color": "color", "dimen": "dimen", "bool": "bool",
    "integer": "integer", "style": "style", "attr": "attr", "declare-styleable": "styleable",
    "drawable": "drawable", "item": None, "fraction": "fraction", "id": "id",
}


def resources(root: Path):
    """Yield (type, name, qualifier-dir) for one res/ directory."""
    if not root.is_dir():
        return
    for folder in sorted(root.iterdir()):
        if not folder.is_dir():
            continue
        base = folder.name.split("-")[0]
        if base == "values":
            for xml in folder.glob("*.xml"):
                try:
                    tree = ET.parse(xml)
                except ET.ParseError as error:
                    print(f"warning: {xml}: {error}", file=sys.stderr)
                    continue
                for element in tree.getroot():
                    name = element.get("name")
                    if not name:
                        continue
                    kind = VALUE_TAGS.get(element.tag, element.tag)
                    if kind is None:
                        kind = element.get("type", "item")
                    yield kind, name, folder.name
                    if element.tag == "declare-styleable":
                        for attr in element.iter("attr"):
                            if attr.get("name") and not attr.get("name").startswith("android:"):
                                yield "attr", attr.get("name"), folder.name
        else:
            for item in folder.iterdir():
                yield base, item.name.split(".")[0], folder.name


def main():
    owners = defaultdict(lambda: defaultdict(set))  # (type, name) -> module -> qualifiers
    for module, root in MODULES.items():
        for kind, name, qualifier in resources(root):
            owners[(kind, name)][module].add(qualifier)

    collisions = {
        key: modules for key, modules in owners.items()
        if len({ENGINE[m] for m in modules}) > 1
    }
    if "--check" in sys.argv:
        allowlist = Path(__file__).with_name("resource_collisions_allowlist.txt")
        reviewed = {tuple(line.split()) for line in allowlist.read_text().splitlines()
                    if line.strip() and not line.startswith("#")}
        new = sorted(set(collisions) - reviewed)
        for kind, name in new:
            where = "; ".join(sorted(collisions[(kind, name)]))
            print(f"unreviewed collision: {kind} {name} ({where})", file=sys.stderr)
        print(f"{len(collisions)} collisions, {len(new)} unreviewed")
        sys.exit(1 if new else 0)
    if "--names" in sys.argv:
        for kind, name in sorted(collisions):
            print(kind, name)
        return
    by_type = defaultdict(int)
    for kind, _ in collisions:
        by_type[kind] += 1
    print(f"{len(collisions)} cross-engine collisions: " +
          ", ".join(f"{k}={v}" for k, v in sorted(by_type.items())))
    for (kind, name), modules in sorted(collisions.items()):
        where = "; ".join(f"{m}[{','.join(sorted(q))}]" for m, q in sorted(modules.items()))
        print(f"  {kind:10} {name:40} {where}")
    sys.exit(1 if collisions else 0)


if __name__ == "__main__":
    main()
