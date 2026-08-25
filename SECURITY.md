# Security Policy

## Supported Versions

Only the latest released version of Limesium receives security fixes.

## Reporting a Vulnerability

Please **do not** report security vulnerabilities through public GitHub issues.

Instead, report them privately via
[GitHub Security Advisories](https://github.com/dirkjink/limesium/security/advisories/new)
or by email to **dirkjink@posteo.de**.

Please include:

- A description of the vulnerability and its impact
- The affected module and version
- Steps to reproduce, or a proof of concept if available

You will receive an acknowledgement within a few days. Please allow a reasonable
time for a fix to be released before any public disclosure.

## Scope notes

Limesium logs data crossing the HTTP boundary. Reports about **sensitive data
leaking into log output** (headers, bodies, query strings that should be masked
or truncated but are not) are explicitly in scope and very welcome.
