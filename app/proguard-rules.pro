# ProGuard rules

# junrar treats SLF4J as optional; the app intentionally excludes that logging backend.
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
