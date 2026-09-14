package com.matiasnl.hakiosk.data.dashboard

/** Deterministic [DashboardIdProvider]: "id-1", "id-2", ... in call order. */
class FakeDashboardIdProvider : DashboardIdProvider {
    private var counter = 0
    override fun newId(): String = "id-${++counter}"
}
