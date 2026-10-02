package com.dialex.model

/**
 * One-key council modes shown as buttons. The council itself is built by the Go engine
 * (POST /api/v1/quickstart/apply); [id] must equal the engine's mode id (socratix-engine quickstart/modes.go).
 */
enum class OneKeyMode(val id: String, val title: String, val blurb: String) {
    PREMORTEM("premortem", "Pre-mortem", "Assume the plan already failed. Find out why before you start."),
    RED_TEAM("redteam", "Red team", "A proponent and attackers go three rounds on your plan."),
    TENTH_MAN("tenthman", "Tenth man", "When everyone agrees, one seat must argue the opposite. Dissent is kept."),
}
