# Repository-context evaluation cases

These are optional, disposable checks of specific repository-context behavior.
They are not part of the ByteQuay app or its normal Maven and frontend test
commands. Saved result JSON records observations from earlier provider runs;
it is not a current benchmark or a human-approved quality score.

Run the offline corpus check without an app or provider:

```bash
python3 -B scripts/test-repository-context-cases.py
```

The scenario directories contain their own case descriptions and instructions
for recreating frozen repositories. Live Learn, review, and development
comparisons require a separate disposable backend, database, and provider
conversation; follow the relevant case README when intentionally repeating
one. Keep generated runtimes outside this repository. The Python scripts are
manual fixture tools, not production knowledge storage or agent behavior.
