#!/usr/bin/env python3
from pathlib import Path
from zipfile import ZipFile, BadZipFile
import sys


def main() -> int:
    project_root = Path(__file__).resolve().parents[1]
    archive_path = project_root / "docs" / "xml_task_X1.zip"
    output_path = project_root / "app" / "input" / "people.xml"

    if not archive_path.is_file():
        print(f"Missing input archive: {archive_path}", file=sys.stderr)
        return 1

    try:
        with ZipFile(archive_path) as archive:
            if "people.xml" not in archive.namelist():
                print(f"Archive does not contain people.xml: {archive_path}", file=sys.stderr)
                return 1
            output_path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open("people.xml") as source, output_path.open("wb") as target:
                target.write(source.read())
    except BadZipFile:
        print(f"Invalid zip archive: {archive_path}", file=sys.stderr)
        return 1

    print(f"Extracted {archive_path.name}: people.xml -> {output_path}")
    print(f"Size: {output_path.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
