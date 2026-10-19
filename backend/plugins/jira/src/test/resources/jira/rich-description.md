## Triage data

| Metric | Value |
| --- | --- |
| **Failures \(7d\)** | **42** |
| **Distinct accounts affected** | **3** |
| Survey window | 2026\-01\-05 00:00 → 2026\-01\-12 00:00 UTC \(pinned\) |
| Error code | `GENERIC_INTERNAL_ERROR` \(65536\) / `INTERNAL_ERROR` |
| Should be | `EXTERNAL` \(remote data/metadata mismatch\) or `USER_ERROR` |

Priority intentionally left at the project default — to be set in a separate triage pass\.

## Summary

Reading a `DECIMAL` column from the Harbor connector through its parallel \(Arrow\) page source fails with a bare `java.lang.ArithmeticException: Rounding necessary` whenever the value Harbor returns has more fractional digits than the scale Harbor reported for that column\. 42 failures in a week across three accounts\.

The message is four words\. It names no column, no value, no table, and no expected scale, and it is reported as an internal error — so neither the customer nor support can tell what happened or that the data is the cause\.

## Fix site

`example/harbor`, `plugin/harbor/src/main/java/io/example/harbor/writer/DecimalValueWriter.java:44`:

```java
type.writeObject(output, Decimals.valueOf(reader.toBigDecimal(row).setScale(max(scale, 0), UNNECESSARY)));
```
