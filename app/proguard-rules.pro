# Релизная сборка не должна писать распознанную речь и счёт в logcat.
# Правила вступят в силу при включении minify (пока isMinifyEnabled = false
# логирование d/i фильтруется в рантайме через util/AppLog.kt).
# w/e оставлены намеренно: это диагностика ошибок без пользовательского текста.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
