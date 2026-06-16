# Multi-dex keep file for LianYu One-Piece Shell
# Forces shell/security classes into the main DEX (classes.dex).
# All other classes (business, UI, features) go to secondary DEX files.
# Those secondary DEX files are then encrypted as shell payload.

# Shell Application entry point
-keep class com.lianyu.ai.security.LianYuShellApplication { *; }
-keep class com.lianyu.ai.security.OnePieceShellGate { *; }

# Security gates (G0 facade, CompositeVmpRuntime)
-keep class com.lianyu.ai.security.G0 { *; }
-keep class com.lianyu.ai.security.CompositeVmpRuntime { *; }

# DEX fragment loader
-keep class com.lianyu.ai.security.DexFragmentLoader { *; }

# KMS (native crypto)
-keep class com.lianyu.ai.security.KmsProvider { *; }
-keep class com.lianyu.ai.security.NativeBridge { *; }

# Security state and guard
-keep class com.lianyu.ai.security.SecurityState { *; }
-keep class com.lianyu.ai.security.SecurityGuard { *; }

# Manifest integrity (AEAD)
-keep class com.lianyu.ai.security.TinkAeadProvider { *; }

# Attestation / audit
-keep class com.lianyu.ai.security.HardwareKeyAttestor { *; }
-keep class com.lianyu.ai.security.AttestationDataParser { *; }
-keep class com.lianyu.ai.security.SecureStrings { *; }
