package com.matiasnl.hakiosk.data.device.discovery

/** Option shown by the "camera on screen" select when no camera is open. */
const val NO_CAMERA_OPTION = "Ninguna"

/**
 * Bidirectional, order-stable mapping between the ids the app uses internally (view ids, camera
 * entity ids) and the display names published as `select` options. Home Assistant selects only carry
 * a string, and views/cameras can share a name, so duplicates are disambiguated by appending
 * " (2)", " (3)"... in the order they appear in the source list. Rebuilding a catalog from the same
 * input list always yields the same names, keeping the config published to Home Assistant consistent
 * with the commands this app knows how to resolve.
 */
class OptionCatalog private constructor(
    /** Disambiguated names, in source order; published as the `select`'s `options`. */
    val options: List<String>,
    private val idToName: Map<String, String>,
    private val nameToId: Map<String, String>,
) {
    /** The published name for [id], or null if it's not (or no longer) in this catalog. */
    fun nameFor(id: String?): String? = id?.let(idToName::get)

    fun hasId(id: String): Boolean = idToName.containsKey(id)

    sealed interface Resolution {
        data class Found(val id: String) : Resolution
        data object Unknown : Resolution
    }

    /** Resolves a `select` option (as received in a command) back to its id. */
    fun resolve(name: String): Resolution = nameToId[name]?.let { Resolution.Found(it) } ?: Resolution.Unknown

    companion object {
        val EMPTY: OptionCatalog = OptionCatalog(emptyList(), emptyMap(), emptyMap())

        fun <T> of(items: List<T>, idOf: (T) -> String, nameOf: (T) -> String): OptionCatalog {
            val names = disambiguateNames(items, nameOf)
            val idToName = LinkedHashMap<String, String>(items.size)
            val nameToId = LinkedHashMap<String, String>(items.size)
            items.forEachIndexed { index, item ->
                val id = idOf(item)
                idToName[id] = names[index]
                // Last one wins on a name collision the " (n)" suffixing couldn't avoid (e.g. a view
                // already named "X (2)" alongside two views named "X"): still deterministic given the
                // same input, which is what commands are resolved against.
                nameToId[names[index]] = id
            }
            return OptionCatalog(names, idToName, nameToId)
        }

        internal fun <T> disambiguateNames(items: List<T>, nameOf: (T) -> String): List<String> {
            val occurrences = items.groupingBy(nameOf).eachCount()
            val seen = HashMap<String, Int>()
            return items.map { item ->
                val base = nameOf(item)
                if (occurrences.getValue(base) == 1) return@map base
                val occurrence = (seen[base] ?: 0) + 1
                seen[base] = occurrence
                // First occurrence keeps the bare name; " (2)", " (3)"... mark the later duplicates.
                if (occurrence == 1) base else "$base ($occurrence)"
            }
        }
    }
}
