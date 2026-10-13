"""Where the lab keeps its saved results, and how it writes and reads them."""

import json
from pathlib import Path

# A folder of the lab's own, beside the application's data and not inside it.
FOLDER = Path.home() / "Library/Application Support/ByteQuay Lab"


def write(directory, name, made):
    """Replace a saved result in one step, so a reader never sees half a file."""
    directory.mkdir(parents=True, exist_ok=True)
    partial = directory / (name + ".partial")
    partial.write_text(json.dumps(made, indent=2) + "\n")
    partial.replace(directory / name)
    return directory / name

