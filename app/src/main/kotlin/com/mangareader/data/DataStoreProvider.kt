package com.mangareader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全局唯一的 DataStore 实例。
 *
 * 各领域 Repository 共享同一个 DataStore，避免在多处定义同名 preferencesDataStore
 * 扩展属性导致的实现分散或重复。
 */
internal val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(name = "manga_reader_settings")
