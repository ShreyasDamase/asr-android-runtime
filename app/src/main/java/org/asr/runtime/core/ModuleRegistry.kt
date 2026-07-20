package org.asr.runtime.core

import org.asr.runtime.common.SensorModule

/**
 * Registry for all active ASR modules.
 */
class ModuleRegistry(private val modules: List<SensorModule>) {

    fun startAll() {
        modules.forEach { it.start() }
    }

    fun stopAll() {
        modules.forEach { it.stop() }
    }

    fun releaseAll() {
        modules.forEach { it.release() }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : SensorModule> getModule(id: String): T? {
        return modules.find { it.id == id } as? T
    }
}
