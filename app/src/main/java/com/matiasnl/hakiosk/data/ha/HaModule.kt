package com.matiasnl.hakiosk.data.ha

import android.content.Context
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore

/** Wires the Home Assistant data layer. Owned by the ha-client work stream. */
class HaModule(@Suppress("unused") private val context: Context) {
    val configStore: HaConfigStore by lazy { InMemoryHaConfigStore() }
    val repository: HaRepository by lazy { FakeHaRepository() }
}
