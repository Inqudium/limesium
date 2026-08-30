#!/usr/bin/env python3
"""Copy the module guides into the MkDocs docs_dir for the site build.

The long-form guides live next to their module sources
(<module>/docs/GUIDE.md) so they stay close to the code they describe.
So that the site renders them instead of linking to GitHub, this script
copies each guide to docs/guides/<module>/GUIDE.md - along with every
same-module file the guide references (the activity diagram, the
module-local configuration reference) - and rewrites the relative
links: targets already inside the docs_dir become site-relative, the
sibling module's guide becomes the sibling generated page, and
everything else (module READMEs, sources) becomes a GitHub link.

The pages are GENERATED - they must never be checked in or edited by
hand (docs/guides/ is gitignored), otherwise they become a drifting
mirror of the module guides. MkDocs' --strict build fails if this
script did not run first, which is the intended guard.

Usage: python3 .github/scripts/generate-guide-pages.py
"""

import os
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOCS = REPO / "docs"
OUT_BASE = DOCS / "guides"
MODULES = ["limesium-servlet-logging", "limesium-reactive-logging"]
GITHUB_BLOB = "https://github.com/Inqudium/limesium/blob/main/"

# Inline markdown links: ](target) or ](target#anchor). The guides use
# no reference-style links or raw HTML links (verified 2026-08-30; a
# new one would surface as an unrewritten dead link in --strict).
LINK_RE = re.compile(r"\]\(([^)#]+)(#[^)]*)?\)")


def rewrite_guide(module: str) -> None:
    src_dir = REPO / module / "docs"
    src = src_dir / "GUIDE.md"
    out_dir = OUT_BASE / module
    out_dir.mkdir(parents=True)
    referenced_assets: set[Path] = set()

    def rewrite(match: re.Match) -> str:
        target, anchor = match.group(1), match.group(2) or ""
        if target.startswith(("http://", "https://", "mailto:")):
            return match.group(0)
        resolved = (src_dir / target).resolve()
        if not resolved.exists():
            sys.exit(f"{src}: relative link target does not exist: {target}")
        if resolved.is_relative_to(DOCS):
            # Already a site page/file: point at it site-relatively.
            new = os.path.relpath(resolved, out_dir)
        elif resolved.parent == src_dir:
            # Same-module companion file (diagram, config reference):
            # keep the same-directory link and copy the file alongside.
            referenced_assets.add(resolved)
            return match.group(0)
        elif any(resolved == REPO / m / "docs" / "GUIDE.md" for m in MODULES):
            # The sibling guide: link its generated page, not GitHub.
            new = os.path.relpath(
                OUT_BASE / resolved.parent.parent.name / "GUIDE.md", out_dir
            )
        else:
            # Everything else (module READMEs, sources) is not on the
            # site; send the reader to the file on GitHub.
            new = GITHUB_BLOB + resolved.relative_to(REPO).as_posix()
        return f"]({new}{anchor})"

    rewritten = LINK_RE.sub(rewrite, src.read_text(encoding="utf-8"))
    (out_dir / "GUIDE.md").write_text(rewritten, encoding="utf-8")
    for asset in referenced_assets:
        shutil.copy(asset, out_dir / asset.name)
    print(f"{out_dir / 'GUIDE.md'}: written, {len(referenced_assets)} companion file(s)")


def main() -> None:
    if OUT_BASE.exists():
        shutil.rmtree(OUT_BASE)
    for module in MODULES:
        rewrite_guide(module)


if __name__ == "__main__":
    main()
