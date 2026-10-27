/**
 * Native, server-side visibility protections: ray traced anti-xray, anti-ESP, anti-freecam and the
 * underground view distance. All of them share {@link io.canvasmc.canvas.anticheat.PlayerVisibility},
 * which owns the per-player state and schedules the asynchronous work.
 */
@NullMarked
package io.canvasmc.canvas.anticheat;

import org.jspecify.annotations.NullMarked;
