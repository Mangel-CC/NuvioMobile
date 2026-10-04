#!/usr/bin/env python3
"""Fork CI helper: writes repository secrets named like build properties into properties files.

Usage: apply-fork-secrets.py KEYS_FILE PROPERTIES_FILE...

Each key listed in KEYS_FILE is read from the environment (the workflow maps it from the secret of
the same name) and written when non-empty. Values are never printed.
"""
import os
import sys


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n")


def main() -> None:
    keys_file, *properties_files = sys.argv[1:]
    with open(keys_file, encoding="utf-8") as handle:
        allowed = [line.strip() for line in handle if line.strip() and not line.startswith("#")]
    values = {key: os.environ[key].strip() for key in allowed if (os.environ.get(key) or "").strip()}

    for path in properties_files:
        lines = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                lines = [
                    line for line in handle.read().splitlines()
                    if line.split("=", 1)[0].strip() not in values
                ]
        lines += [f"{key}={escape(value)}" for key, value in values.items()]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    print(f"Applied {len(values)} config secret(s): {', '.join(sorted(values)) or 'none'}")


if __name__ == "__main__":
    main()
