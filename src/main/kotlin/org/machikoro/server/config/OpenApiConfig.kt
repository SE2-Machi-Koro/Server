package org.machikoro.server.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Sets up OpenAPI docs shown in Swagger UI
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Machi Koro API")
                .version("1.0.0")
                .description(
                    """
    REST and WebSocket API for the Machi Koro board game.

    ## WebSocket Connection
    Connect via STOMP at ws://localhost:8080/ws.

    ### Endpoints
    | Destination | Payload | Broadcasts to |
    |---|---|---|
    | /app/game.resolveEffects | ResolveEffectsRequest | /topic/public |
    | /app/game.endTurn | EndTurnRequest | /topic/public |
    | /app/chat.send | WebSocketMessage | /topic/public |
    | /app/chat.addUser | WebSocketMessage | /topic/public |

    For Swagger-driven testing of these actions over plain HTTP, see the
    "WebSocket Companion (dev)" tag below — it mirrors each STOMP action as a
    POST under `/api/dev/game/...` and is disabled in the `prod` profile.
    """
                        .trimIndent()
                )
        )
        .tags(
            listOf(
                Tag().name("WebSocket")
                    .description("WebSocket endpoints documented manually — not auto-scanned by Springdoc"),
                Tag().name("Game").description("Game management endpoints")
            )
        )
}