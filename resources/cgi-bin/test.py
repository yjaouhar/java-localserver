#!/usr/bin/env python3
import os
import sys
import urllib.parse
import json

print("Content-Type: text/plain")
print()

print("=== CGI TEST.PY ===")

# Method
method = os.environ.get("REQUEST_METHOD", "")
print("Method:", method)

# Query string
qs = os.environ.get("QUERY_STRING", "")
print("Query string:", qs)

if qs:
    params = urllib.parse.parse_qs(qs)
    print("Parsed query:", params)

# POST data
if method == "POST":
    length = int(os.environ.get("CONTENT_LENGTH", 0))
    body = sys.stdin.read(length)

    ctype = os.environ.get("CONTENT_TYPE", "")
    print("Content-Type:", ctype)

    if "application/json" in ctype:
        try:
            data = json.loads(body)
            print("JSON body:", data)
        except Exception as e:
            print("JSON error:", e)
    else:
        print("Raw body:", body)
