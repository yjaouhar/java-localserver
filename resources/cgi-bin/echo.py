#!/usr/bin/env python3
import os

print("Content-Type: text/plain")
print()

print("=== ECHO CGI ===")

for k, v in sorted(os.environ.items()):
    if k.startswith(("HTTP_", "REQUEST_", "CONTENT_")):
        print(f"{k} = {v}")
