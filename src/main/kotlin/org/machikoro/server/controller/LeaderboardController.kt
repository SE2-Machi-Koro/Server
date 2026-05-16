package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.LeaderboardEntryDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/leaderboard")
@Tag(name = "Leaderboard", description = "Global player rankings sorted by total wins")
class LeaderboardController(private val userDao: UserDao) {

    // Returns top-N players ranked by wins; capped at 100
    @GetMapping
    @Operation(
        summary = "Fetch top players sorted by total wins",
        description = "Returns a ranked list of players ordered by total wins descending. " +
            "Ties are broken by total games played descending. Maximum 100 entries per request."
    )
    fun getLeaderboard(
        @Parameter(description = "Number of players to return (1–100)", example = "10")
        @RequestParam(defaultValue = "10") limit: Int
    ): List<LeaderboardEntryDto> {
        val capped = limit.coerceIn(1, 100)
        return userDao.getLeaderboard(capped).mapIndexed { index, user ->
            LeaderboardEntryDto(
                rank = index + 1,
                userId = user.id,
                username = user.username,
                totalWins = user.totalWins,
                totalGamesPlayed = user.totalGamesPlayed,
            )
        }
    }
}