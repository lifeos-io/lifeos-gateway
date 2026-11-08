# lifeos-gateway

Single entry point: JWT validation, rate limiting, routing.

Port: 8080

No database. Redis is used only for rate-limit counters.

Parent: `lifeos-root-pom`. Tables and events are described in the "Life OS — service boundaries and data model" document.
