"""
Normalize, deduplicate, and enrich the Luzon POI datasets used by the app and training tools.

Usage:
  python tools/normalize_luzon_dataset.py --input app/src/main/assets/datasets/luzon_dataset.csv --output app/src/main/assets/datasets/luzon_dataset.csv
  python tools/normalize_luzon_dataset.py --input tools/luzon_dataset.csv --output tools/luzon_dataset.csv
"""

from __future__ import annotations

import argparse
import csv
from collections import OrderedDict
from pathlib import Path


def normalize_spaces(value: str) -> str:
    return " ".join((value or "").replace("\u00a0", " ").split())


def normalize_location(value: str) -> str:
    parts = [normalize_spaces(part) for part in (value or "").split(",")]
    parts = [part for part in parts if part]
    return ", ".join(parts)


def normalize_category(value: str) -> str:
    seen: OrderedDict[str, None] = OrderedDict()
    for token in (value or "").split("/"):
        cleaned = normalize_spaces(token)
        if cleaned:
            seen.setdefault(cleaned, None)
    return "/".join(seen.keys())


def merge_categories(first: str, second: str) -> str:
    seen: OrderedDict[str, None] = OrderedDict()
    for source in (first, second):
        for token in source.split("/"):
            cleaned = normalize_spaces(token)
            if cleaned:
                seen.setdefault(cleaned, None)
    return "/".join(seen.keys())


def split_location(value: str) -> tuple[str, str]:
    parts = [normalize_spaces(part) for part in (value or "").split(",")]
    parts = [part for part in parts if part]
    municipality = parts[0] if parts else ""
    province = parts[-1] if len(parts) >= 2 else municipality
    return municipality, province


def build_tags(category: str) -> str:
    tags: OrderedDict[str, None] = OrderedDict()
    for token in category.split("/"):
        cleaned = normalize_spaces(token)
        if cleaned:
            tags.setdefault(cleaned.lower(), None)
    return "|".join(tags.keys())


def build_photo_hint(name: str, municipality: str, province: str) -> str:
    parts = [name, municipality, province, "Philippines"]
    return " ".join(part for part in parts if part)


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def dedupe_rows(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    merged: OrderedDict[tuple[str, str], dict[str, str]] = OrderedDict()
    for row in rows:
        name = normalize_spaces(row.get("Name", ""))
        location = normalize_location(row.get("Location", ""))
        category = normalize_category(row.get("Category", ""))
        description = normalize_spaces(row.get("Description", ""))
        lat_raw = normalize_spaces(row.get("Lat", ""))
        lon_raw = normalize_spaces(row.get("Lon", ""))
        municipality, province = split_location(location)
        tags = build_tags(category)
        photo_hint = build_photo_hint(name, municipality, province)

        key = (name.casefold(), location.casefold())
        current = merged.get(key)
        if current is None:
            merged[key] = {
                **row,
                "Name": name,
                "Category": category,
                "Location": location,
                "Lat": lat_raw,
                "Lon": lon_raw,
                **({"Description": description} if "Description" in row else {}),
                "Municipality": municipality,
                "Province": province,
                "Tags": tags,
                "PhotoHint": photo_hint,
            }
            continue

        current["Category"] = merge_categories(current.get("Category", ""), category)
        current["Municipality"] = municipality
        current["Province"] = province
        current["Tags"] = build_tags(current.get("Category", ""))
        current["PhotoHint"] = build_photo_hint(current.get("Name", ""), municipality, province)

        if "Description" in current and len(description) > len(current.get("Description", "")):
            current["Description"] = description

        try:
            lat_values = [float(v) for v in [current.get("Lat", ""), lat_raw] if v]
            lon_values = [float(v) for v in [current.get("Lon", ""), lon_raw] if v]
            if lat_values:
                current["Lat"] = f"{sum(lat_values) / len(lat_values):.8f}".rstrip("0").rstrip(".")
            if lon_values:
                current["Lon"] = f"{sum(lon_values) / len(lon_values):.8f}".rstrip("0").rstrip(".")
        except ValueError:
            pass

    return list(merged.values())


def write_rows(path: Path, rows: list[dict[str, str]]) -> None:
    if not rows:
        raise SystemExit(f"No rows to write for {path}")
    preferred = [
        "Name",
        "Category",
        "Location",
        "Lat",
        "Lon",
        "Description",
        "Municipality",
        "Province",
        "Tags",
        "PhotoHint",
    ]
    fieldnames = [name for name in preferred if name in rows[0]] + [
        name for name in rows[0].keys() if name not in preferred
    ]
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, quoting=csv.QUOTE_MINIMAL)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)
    rows = read_rows(input_path)
    cleaned = dedupe_rows(rows)
    write_rows(output_path, cleaned)
    print(f"Normalized {input_path} -> {output_path} ({len(rows)} rows -> {len(cleaned)} rows)")


if __name__ == "__main__":
    main()
