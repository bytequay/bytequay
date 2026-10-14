"""Where the lab keeps its saved results, and how it writes and reads them."""

from datetime import datetime, timezone
import json
from pathlib import Path

# A folder of the lab's own, beside the application's data and not inside it.
FOLDER = Path.home() / "Library/Application Support/ByteQuay Lab"


def stamp(moment):
    """A moment in seconds as the saved files write it: UTC, to the second."""
    return datetime.fromtimestamp(moment, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_text(directory, name, text):
    """Replace a saved file in one step, so a reader never sees half of it."""
    directory.mkdir(parents=True, exist_ok=True)
    partial = directory / (name + ".partial")
    partial.write_text(text)
    partial.replace(directory / name)
    return directory / name


def write(directory, name, made):
    """Save a result, replacing an earlier one in one step."""
    return write_text(directory, name, json.dumps(made, indent=2) + "\n")


def read(directory, name):
    """A saved result, or None when the lab has not saved it yet. A file in another shape is an error and never an empty result."""
    try:
        made = json.loads((directory / name).read_text())
    except FileNotFoundError:
        return None
    if not isinstance(made, dict) or made.get("version") != 1:
        raise ValueError(f"{name} was saved in a shape this version does not read")
    return made
