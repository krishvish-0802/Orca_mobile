package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.model.DiscoveredPrinter
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.discovery.Broadcasts
import com.orcamobile.core.printer.discovery.PrinterDiscoverer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

/**
 * Elegoo printers answer SDCP's discovery broadcast (`M99999` to UDP 3000)
 * with their name, model, address and mainboard id.
 */
internal class SdcpDiscovery @Inject constructor(private val broadcasts: Broadcasts) : PrinterDiscoverer {

    override fun discover(timeoutMillis: Long): Flow<DiscoveredPrinter> =
        broadcasts.ask(DISCOVERY_MESSAGE.toByteArray(), DISCOVERY_PORT, timeoutMillis).mapNotNull { reply -> parse(reply.text, reply.from) }

    internal companion object {
        const val DISCOVERY_MESSAGE = "M99999"
        const val DISCOVERY_PORT = 3000
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String, from: String): DiscoveredPrinter? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val data = root["Data"] as? JsonObject ?: return null
            fun field(name: String) = (data[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val address = field("MainboardIP") ?: from
            val model = field("MachineName")
            return DiscoveredPrinter(
                name = field("Name") ?: model ?: "Elegoo printer",
                model = listOfNotNull(field("BrandName"), model).joinToString(" ").ifBlank { null },
                host = PrintHostConfig(PrintHostType.ELEGOO_LINK, address),
                uniqueId = field("MainboardID"),
            )
        }
    }
}
