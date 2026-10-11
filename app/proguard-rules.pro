# BongScanner uses no reflection of its own; library consumer rules cover the rest.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
