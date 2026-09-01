package com.videoclub.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class VideoclubApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }

    /**
     * Posters are the whole app, so they get a real cache.
     *
     * Every image is a TMDB URL that never changes, at one of two fixed sizes — a 600×900 poster or
     * a 1280-wide backdrop. That makes them perfectly cacheable, and 256 MB holds a few thousand of
     * them: enough that scrolling back up a home screen is instant and costs nothing.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_FRACTION).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("posters").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(CROSSFADE_MILLIS)
            .build()

    private companion object {
        const val MEMORY_CACHE_FRACTION = 0.20
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
        const val CROSSFADE_MILLIS = 180
    }
}
