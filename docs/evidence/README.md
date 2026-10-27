# Trace evidence

`make traces` writes three files here: `trace-retried.txt` (503, 503, then 200: three attempts under one trace), `trace-stale-after-failure.txt` (provider down: three attempts, then the cached quote, `stale: true`) and `trace-refused-no-provider-call.txt` (every concurrency slot taken: refused at once, no provider call), each next to the raw Tempo JSON. They replace the lesson's screenshots with text.

Pending capacity: the files are produced by `make up && make traces`, which needs Docker.
