#!/usr/bin/env python3
import os
import sys
import urllib.parse

print("Content-Type: text/plain")
print()

print("=== SCRIPT.PY ===")

method = os.environ.get("REQUEST_METHOD", "")
print("Method:", method)

if method == "POST":
    length = int(os.environ.get("CONTENT_LENGTH", 0))
    body = sys.stdin.read(length)

    data = urllib.parse.parse_qs(body)
    print("Parsed POST data:")
    for k, v in data.items():
        print(f"{k} = {v}")
