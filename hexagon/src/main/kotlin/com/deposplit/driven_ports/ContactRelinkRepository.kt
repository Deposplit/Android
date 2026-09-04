package com.deposplit.driven_ports

import com.deposplit.value_objects.ContactRelink
import java.util.UUID

/* Records which contacts are known to hold this device's current key. Latest-wins per contact:
 * save() replaces any earlier record, since only the most recent evidence matters. */
interface ContactRelinkRepository {
    fun getAll(): List<ContactRelink>
    fun get(contactId: UUID): ContactRelink?
    fun save(relink: ContactRelink)
}
