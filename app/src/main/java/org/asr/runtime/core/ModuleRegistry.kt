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

    fun startModule(id: String) {
        modules.find { it.id == id }?.start()
    }

    fun stopModule(id: String) {
        modules.find { it.id == id }?.stop()
    }

    fun getModuleStatus(): Map<String, String> {
        return modules.associate { it.id to it.state.name }
    }

    fun getRunningModules(): List<String> {
        return modules.filter { it.state == org.asr.runtime.common.ModuleState.RUNNING }.map { it.id }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : SensorModule> getModule(id: String): T? {
        return modules.find { it.id == id } as? T
    }
}
