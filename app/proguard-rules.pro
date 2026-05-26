# Add project specific ProGuard rules here.
# Keep app entry points
-keep class com.dataproxy.MainActivity { *; }
-keep class com.dataproxy.service.ProxyService { *; }
-keep class com.dataproxy.DataProxyApplication { *; }

# Keep coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
