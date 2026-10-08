import csv
from contextlib import contextmanager


class LazyRows:
    def __init__(self, source):
        self.reader = csv.DictReader(source)
        self.rows = None

    def __iter__(self):
        if self.rows is None:
            self.rows = list(self.reader)
        return iter(self.rows)


@contextmanager
def open_rows(path):
    """Yield independent rows; they remain readable after leaving this context."""
    with open(path, newline="") as source:
        yield LazyRows(source)
