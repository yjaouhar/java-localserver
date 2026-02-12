# Java HTTP Server Project

## Overview
This project is a lightweight HTTP/1.1-compliant server built in Java using **non-blocking I/O** (java.nio) and event-driven architecture.  
It supports static content serving, CGI execution, file uploads, sessions, cookies, and error handling without relying on external server frameworks.

---

## Features

### Server
- Single-process, single-thread, event-driven architecture.
- Handles **GET**, **POST**, and **DELETE** requests.
- Supports multiple ports and server instances.
- Timeout for long requests.
- HTTP/1.1-compliant responses.
- Custom error pages for: 400, 403, 404, 405, 413, 415, 500, 504.
- Handles chunked and unchunked requests.
- Directory listing toggle and default file handling.

### CGI
- Executes Python CGI scripts via `ProcessBuilder`.
- Supports PATH_INFO for full file paths.
- Correct relative path handling.
- Configurable by file extension.

### Configuration
- Configurable host and ports.
- Default server selection.
- Client body size limit.
- Route definitions (methods, redirections, root paths, default files, CGI handling).
- Custom error page paths.

### Sessions & Cookies
- Session management.
- Cookie utilities for request/response.

---

## Project Structure

```
/java-server
├── /src
│   ├── Main.java         # Entry point
│   ├── Server.java       # Handles server lifecycle
│   ├── Router.java       # Routes requests
│   ├── CGIHandler.java   # Manages CGI execution
│   ├── ConfigLoader.java # Parses configuration file
│   ├── error.java        # Error responses
│   ├── handlers/
│   │   ├── CGIContext.java
│   │   ├── DeleteHandler.java
│   │   ├── StaticFileHandler.java
│   │   └── UploadHandler.java
│   ├── http/
│   │   ├── HttpRequest.java
│   │   └── HttpResponse.java
│   ├── session/
│   │   ├── Cookies.java
│   │   ├── Session.java
│   │   └── SessionManager.java
│   └── utils/
│       ├── json/
│       │   ├── AppConfig.java
│       │   ├── ConfigMapper.java
│       │   ├── MiniJsonParser.java
│       │   └── util.java
│       └── JsonFormatValidator.java
├── /cgi-bin/             # CGI scripts (.py)
├── /error_pages/         # Custom error HTML
├── /resources/           # Static content
├── /myapp_tmp/           # Temporary files
├── config.json           # Server configuration
└──  README.md
```

---

## Setup & Usage

1. **Clone the repository**
```bash
git clone <repository_url>
cd java-server
```

2. **Compile the source code and Run the server***

```bash
javac -d bin -sourcepath src src/Main.java && java -cp bin Main
```



3. **Server configuration**
- Edit `config.json` to set host, ports, routes, error pages, and CGI settings.
- Ensure all directories exist (`resources`, `error_pages`, `cgi-bin`).

4. **Testing**
- Use `curl` for manual tests:
```bash
curl http://127.0.0.1:8080/
curl -X POST -F "file=@example.txt" http://127.0.0.1:8080/upload
```
- Stress test with `siege`:
```bash
siege -b http://127.0.0.1:8080
```

---

## Notes
- Avoid hardcoding paths; use `config.json`.
- Sanitize inputs for CGI.
- Ensure proper resource cleanup to prevent memory or file descriptor leaks.
- Extendable for additional CGI handlers or admin metrics endpoints.

---

## Authors
Project developed by the team as part of Java Fullstack Backend learning.

