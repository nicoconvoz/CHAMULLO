# BouncyCastle is used only through its lightweight API (no JCA provider), so R8 can shrink it freely.
-dontwarn org.bouncycastle.**
