"""Print JSON key paths without exposing values or API credentials."""

import json
import os
from collections import deque
from pathlib import Path


def describe(path: Path) -> None:
    with path.open(encoding="utf-8") as source:
        root = json.load(source)
    print(f"FILE {path.name}")
    root_data = root["cwaopendata"]["dataset"]
    parameters = root_data["datasetInfo"]["parameterSet"]
    content = root_data["contents"]["content"]
    safe_fields = (
        "StartPointLongitude", "StartPointLatitude", "GridResolution",
        "DateTime", "GridDimensionX", "GridDimensionY", "Precipitation", "Reflectivity",
    )
    print("METADATA", {key: parameters.get(key) for key in safe_fields if key in parameters})
    print("CONTENT", {"length": len(content), "prefix": content[:120]})
    print("DESCRIPTION", root_data["contents"].get("contentDescription"))
    queue = deque([("", root, 0)])
    while queue:
        prefix, value, depth = queue.popleft()
        if depth > 8 or not isinstance(value, dict):
            continue
        for key, child in value.items():
            child_path = f"{prefix}.{key}" if prefix else key
            if isinstance(child, dict):
                kind = "object"
                queue.append((child_path, child, depth + 1))
            elif isinstance(child, list):
                kind = f"array[{len(child)}]"
                if child and isinstance(child[0], dict):
                    queue.append((f"{child_path}[0]", child[0], depth + 1))
            else:
                kind = type(child).__name__
            print(f"{child_path} : {kind}")


if __name__ == "__main__":
    temp = Path(os.environ["TEMP"])
    describe(temp / "cwa_f_b0046_001.json")
    describe(temp / "cwa_o_a0059_001.json")
