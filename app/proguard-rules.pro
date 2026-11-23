# QAA Android 混淆规则
#
# 当前发布构建 isMinifyEnabled = false（docs/ARCHITECTURE.md 第 1 节事实表），
# 因此本文件暂无生效规则。若未来启用 R8 混淆，需按下述事实补全 keep：
#
# GenieX AAR（com.qualcomm.qti:geniex-android）不带 consumer proguard 规则
# （事实来源：docs/research/geniex-api.md 集成小节），JNI 桥接类名/方法名
# 由 native 侧反射查找，混淆将导致 UnsatisfiedLinkError，届时应启用：
#
#   -keep class com.geniex.sdk.** { *; }
#   -keepclasseswithmembernames class * { native <methods>; }
#
# 引擎适配层依赖此契约：com.thatgfsj.qaa.core.inference.GenieXSdkClient /
# GenieXLlmEngine 的 native 调用面亦受上列 keep 保护。
