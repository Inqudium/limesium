# Module limesium-servlet-logging

Auto-configured servlet filter for Spring Boot (Tomcat) applications that
logs one structured `endpoint_*` line per HTTP exchange and carries the
exchange identity in the MDC while the request is handled. The
field-and-configuration-identical reactive twin is
`limesium-reactive-logging`; the long-form guide lives in
[docs/GUIDE.md](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md).
