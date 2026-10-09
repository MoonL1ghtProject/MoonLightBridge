package example.kotlin

import ru.moonlightproject.bridge.codegen.MoonLightContract
import ru.moonlightproject.bridge.codegen.MoonLightEnumeration
import ru.moonlightproject.bridge.codegen.MoonLightMessage
import ru.moonlightproject.bridge.codegen.MoonLightRpc
import ru.moonlightproject.bridge.codegen.MoonLightService

@MoonLightContract(
    protoPackage = "moonlight.kotlin.v1",
    javaPackage = "example.kotlin.generated",
)
interface MonitoringContract {
    @MoonLightMessage
    data class PingRequest(
        val message: String,
        val attempts: Int?,
        val tags: List<String>,
    )

    @MoonLightMessage
    data class PingResponse(val message: String)

    @MoonLightEnumeration
    enum class DeliveryState { UNKNOWN, READY }

    @MoonLightService
    interface MonitoringService {
        @MoonLightRpc(
            timeoutMs = 1_500,
            maxAttempts = 3,
            idempotency = "idempotent",
            requiredScopes = ["monitoring.ping"],
            compression = "prefer",
        )
        suspend fun ping(request: PingRequest): PingResponse
    }
}
