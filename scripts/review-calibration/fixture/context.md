The change delays CSV decoding until a caller iterates the rows. Preserve the
existing open_rows contract and repeated iteration behavior. The project uses
Python's standard library. Review the changed implementation.
