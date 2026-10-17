# AirBridgeTouch ProGuard/R8 rules.
# Keep error messages readable in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization is not used for cross-boundary reflection here,
# so no serializer keeps are required. Add rules here if that changes.
