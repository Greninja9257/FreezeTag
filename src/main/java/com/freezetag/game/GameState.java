package com.freezetag.game;

/**
 * Represents the current state of a FreezeTag game.
 */
public enum GameState {
    /** No game running; arena is idle. */
    WAITING,
    /** Players are in the lobby, countdown has started. */
    STARTING,
    /** Game is actively running. */
    IN_GAME,
    /** Game is over, cleanup in progress. */
    ENDING
}
