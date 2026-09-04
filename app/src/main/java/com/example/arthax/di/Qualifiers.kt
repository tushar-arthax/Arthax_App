package com.example.arthax.di

import javax.inject.Qualifier

/** Process-lifetime coroutine scope. Never cancelled — used for fire-and-forget logging. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
