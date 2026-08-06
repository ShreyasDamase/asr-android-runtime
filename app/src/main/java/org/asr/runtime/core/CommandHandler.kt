package org.asr.runtime.core

import org.json.JSONObject

/**
 * Contract for a single dispatchable command handler.
 *
 * Each module registers its own command handlers in [CommandRegistry].
 * This replaces a monolithic `when` block and makes the system extensible
 * without modifying [CommandDispatcher].
 *
 * Example registration in AsrService:
 *   commandRegistry.register(object : CommandHandler {
 *       override val command = "camera.set_fps"
 *       override fun execute(params: JSONObject): Result<String> {
 *           val fps = params.getInt("fps")
 *           cameraManager.configure("""{"fps":$fps}""")
 *           return Result.success("FPS set to $fps")
 *       }
 *   })
 */
interface CommandHandler {
    /** Dot-separated command name matching the "cmd" field in the COMMAND packet JSON. */
    val command: String

    /**
     * Executes the command with the given parameters.
     *
     * @param params  JSONObject from the "params" key of the COMMAND packet.
     * @return        [Result.success] with a status string on success,
     *                [Result.failure] with an exception on error.
     */
    fun execute(params: JSONObject): Result<String>
}
