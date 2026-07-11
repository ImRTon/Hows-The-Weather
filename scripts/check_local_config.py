"""Report credential presence without printing secret values."""

from pathlib import Path


values = {}
for line in Path("local.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()

for key in ("MAPS_API_KEY", "CWA_API_KEY"):
    value = values.get(key, "")
    print(f"{key}: set={bool(value)}, length={len(value)}")
print(f"MAP_ID: {values.get('MAP_ID', '')}")
