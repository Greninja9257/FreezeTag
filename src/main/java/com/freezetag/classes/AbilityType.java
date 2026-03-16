package com.freezetag.classes;

/**
 * Enumerates all ability types available to player classes.
 */
public enum AbilityType {
    /** Temporary speed effect for the caster. */
    SPEED_BOOST,
    /** Temporary jump boost for the caster. */
    JUMP_BOOST,
    /** Brief invisibility — useful for runner escapes and shadow taggers. */
    INVISIBILITY,
    /** Launch the player forward at high velocity. */
    DASH,
    /** Freeze all runners within a configurable radius (tagger ability). */
    FREEZE_AOE,
    /** Apply slowness to all runners within a configurable radius (tagger ability). */
    SLOW_AOE,
    /** Unfreeze all frozen runners within a configurable radius (runner support ability). */
    UNFREEZE_AOE,
    /** Spawn a fake player (armor stand) as a decoy at the current location. */
    DECOY,
    /** Brief damage immunity / resistance for the caster. */
    SHIELD
}
