"""Shared legal-path and manifest-only link resolution for bootstrap assembly/audit."""
from pathlib import PurePosixPath
import posixpath


def is_legal(name):
    parts = PurePosixPath(name).parts
    if len(parts) < 3 or parts[0] != "share":
        return False
    return (parts[1].lower() == "licenses" or
            (parts[1] == "doc" and any(word in parts[-1].lower()
             for word in ("copyright", "license", "licence", "copying", "notice"))))


def resolve(name, files, links):
    """Resolve only declared relative links, never the host filesystem."""
    seen = set()
    for _ in range(64):
        if name.startswith("/") or ".." in PurePosixPath(name).parts:
            raise ValueError("Legal link leaves bootstrap prefix")
        if name in seen:
            raise ValueError("Cyclic bootstrap legal link")
        seen.add(name)
        parts = PurePosixPath(name).parts
        for i in range(1, len(parts) + 1):
            component = "/".join(parts[:i])
            if component in links:
                target = links[component]
                if target.startswith("/"):
                    raise ValueError("Absolute bootstrap manifest legal link")
                name = posixpath.normpath(posixpath.join(
                    posixpath.dirname(component), target, *parts[i:]))
                break
        else:
            if name not in files:
                raise ValueError("Missing bootstrap legal link target: " + name)
            return name
    raise ValueError("Bootstrap legal link depth exceeded")
