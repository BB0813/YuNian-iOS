# Keep shell entry classes in main DEX to prevent ClassNotFoundException at startup
-keep class com.lianyu.ai.security.StaticApkShell { *; }
-keep class com.lianyu.ai.security.SActivity { *; }
-keep class com.lianyu.ai.security.MethodRecoveryEngine { *; }
-keep class com.lianyu.ai.MainActivity { *; }
-keep class com.lianyu.ai.LianYuApplication { *; }
