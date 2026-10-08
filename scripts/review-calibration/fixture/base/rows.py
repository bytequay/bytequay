import csv
from contextlib import contextmanager


@contextmanager
def open_rows(path):
    """Yield independent rows; they remain readable after leaving this context."""
    with open(path, newline="") as source:
        yield list(csv.DictReader(source))
