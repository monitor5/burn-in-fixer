#!/usr/bin/env python3
"""Fail if the two independent Android applications disagree on wire constants."""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
paths = [root / f"{project}/app/src/main/java/com/burnin/{package}/net/Protocol.kt"
         for project, package in [("burn-in-camera", "scanner"), ("burn-in-fixed", "target")]]
contracts = [dict(re.findall(r"const val (\w+) = (.+)", path.read_text())) for path in paths]
if not contracts[0] or contracts[0] != contracts[1]:
    raise SystemExit("Scanner/Target protocol constants differ")
print(f"Protocol contract: {len(contracts[0])} constants match")
