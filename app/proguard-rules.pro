# hw2api R8 规则（plan.md §5.3）
# kotlinx-serialization 与 OkHttp 4 自带 consumer 规则，无需手动 keep

# NanoHTTPD（反射敏感）
-keep class fi.iki.elonen.** { *; }

# core 序列化模型（JSON 字段名不得被混淆重命名）
-keep @kotlinx.serialization.Serializable class com.lxseek.hw2api.core.model.** { *; }

# 历史告警抑制
-dontwarn okhttp3.**
-dontwarn okio.**