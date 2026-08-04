#!/usr/bin/env python3
from pathlib import Path
import base64, io, tarfile
root = Path(__file__).resolve().parents[1]
payload = "".join((root / f"tools/v12_payload_{index}.txt").read_text() for index in range(1, 9))
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(payload)), mode="r:gz") as archive:
    for member in archive.getmembers():
        target = (root / member.name).resolve()
        if root not in target.parents:
            raise RuntimeError(f"Unsafe path: {member.name}")
    archive.extractall(root)
print("Applied v1.2 payload")
