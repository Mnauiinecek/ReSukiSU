package com.resukisu.resukisu.data.kernel

import android.app.Application
import com.resukisu.resukisu.Natives
import com.resukisu.resukisu.Natives.KernelPatchImplementation
import com.resukisu.resukisu.data.shell.KsuCliRepository
import com.resukisu.resukisu.data.system.isSELinuxPermissive
import com.resukisu.resukisu.domain.model.KernelFeatureSettings
import com.resukisu.resukisu.domain.model.KernelStatus
import com.resukisu.resukisu.domain.model.ManagerRecord
import com.resukisu.resukisu.domain.model.ManagerRuntimeInfo
import com.resukisu.resukisu.getKernelVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.io.File

class KernelRepository(
    private val application: Application,
    private val ksuCliRepository: KsuCliRepository,
) {
    fun runRootCommand(command: String, timeoutSeconds: Long = 3): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                val output = process.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()

                if (process.exitValue() == 0 && output.isNotEmpty()) {
                    output
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getStatus(): KernelStatus = withContext(Dispatchers.IO) {
        val kernelVersion = getKernelVersion()
        val isManager = runCatching { Natives.isManager }.getOrDefault(false)
        val ksuVersion = if (isManager) Natives.version else null
        val kernelUapi = if (isManager) Natives.kernelUAPIVersion else null
        val managerUapi = runCatching { Natives.managerUAPIVersion }.getOrDefault(1)

        fun getKsudVersion(): String? {
            return runRootCommand("for p in /data/adb/ksu/bin/ksud /data/adb/ksud \$(command -v ksud 2>/dev/null); do [ -x \"\$p\" ] && \"\$p\" -V && exit 0; done; exit 1")
                ?.lineSequence()
                ?.firstOrNull()
                ?.replace(Regex("^ksud\\s+"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "v$it" }
        }

        // One su spawn reads all three custom_manager files, tab-delimited.
        // `exit 0` is required: without it the loop inherits the exit code of the
        // LAST iteration (hook_type), so a missing hook_type would make runRootCommand
        // treat the whole command as failed and discard version + working too.
        val customMap: Map<String, String> =
            if (isManager) {
                runRootCommand(
                    "for f in version working hook_type; do " +
                            "p=\"/data/local/tmp/.custom_manager/\$f\"; " +
                            "[ -f \"\$p\" ] && printf '%s\\t%s\\n' \"\$f\" \"\$(cat \"\$p\")\"; " +
                            "done; exit 0"
                )
                    ?.lineSequence()
                    ?.mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }
                    ?.associate { (k, v) -> k to v.trim() }
                    .orEmpty()
            } else {
                emptyMap()
            }

        val fullVersion =
            if (isManager) {
                customMap["version"]?.takeIf { it.isNotEmpty() }
                    ?: try {
                        Natives.getFullVersion()
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?: getKsudVersion()
                            ?: "Unknown"
                    } catch (_: Exception) {
                        getKsudVersion()
                            ?: "Unknown"
                    }
            } else {
                null
            }

        val customWorking = if (isManager) customMap["working"]?.takeIf { it.isNotEmpty() } else null
        val customHookType = if (isManager) customMap["hook_type"]?.takeIf { it.isNotEmpty() } else null 

        KernelStatus(
            isManager = isManager,
            ksuVersion = ksuVersion,
            managerUAPIVersion = managerUapi,
            kernelUAPIVersion = kernelUapi,
            ksuFullVersion = "$fullVersion (${Natives.version}/$kernelUapi)",
            lkmMode = ksuVersion?.let { if (kernelVersion.isGKI()) Natives.isLkmMode else null },
            kernelVersion = kernelVersion,
            isRootAvailable = runCatching { ksuCliRepository.rootAvailable() }.getOrDefault(false),
            requireNewKernel = runCatching { isManager && Natives.requireNewKernel() }.getOrDefault(
                false
            ),
            uapiMismatch = runCatching { isManager && Natives.checkUAPIMismatch() }.getOrDefault(
                false
            ),
            isSELinuxPermissive = runCatching { isSELinuxPermissive() }.getOrDefault(false),
            isOfficialSignature = runCatching {
                ksuCliRepository.isOfficialSignature(application.packageResourcePath)
            }.getOrDefault(false),
            kernelPatchImplementation = runCatching {
                Natives.getKernelPatchImplementation()
            }.getOrDefault(KernelPatchImplementation.NONE),
            hookType = runCatching { Natives.getHookType() }.getOrDefault(""),
            isSafeMode = runCatching { Natives.isSafeMode }.getOrDefault(false),
            isLateLoadMode = runCatching { Natives.isLateLoadMode }.getOrDefault(false),
            isPrBuild = runCatching { Natives.isPrBuild }.getOrDefault(false),
            customWorking = customWorking,
            customHookType = customHookType,
        )
    }

    suspend fun getManagerRuntimeInfo(): ManagerRuntimeInfo = withContext(Dispatchers.IO) {
        ManagerRuntimeInfo(
            managers = runCatching { Natives.getManagersList()?.managers.orEmpty() }
                .getOrDefault(emptyList())
                .map { ManagerRecord(it.uid, it.signatureIndex) },
            dynamicSignatureEnabled = runCatching {
                Natives.getDynamicManager()?.isValid() == true
            }.getOrDefault(false),
        )
    }

    suspend fun getFeatureSettings(): KernelFeatureSettings = withContext(Dispatchers.IO) {
        KernelFeatureSettings(
            suEnabled = runCatching { Natives.isSuEnabled() }.getOrDefault(false),
            webViewZygoteUmountEnabled = runCatching { Natives.isWebViewZygoteUmountEnabled() }.getOrDefault(false),
            kernelUmountEnabled = runCatching { Natives.isKernelUmountEnabled() }.getOrDefault(false),
            suLogEnabled = runCatching { Natives.isSuLogEnabled() }.getOrDefault(false),
            selinuxHideEnabled = runCatching { Natives.isSelinuxHideEnabled() }.getOrDefault(false),
            defaultUmountModules = runCatching { Natives.isDefaultUmountModules() }.getOrDefault(
                false
            ),
        )
    }

    suspend fun setSuEnabled(enabled: Boolean): Boolean = saveFeature {
        Natives.setSuEnabled(enabled)
    }

    suspend fun setKernelUmountEnabled(enabled: Boolean): Boolean = saveFeature {
        Natives.setKernelUmountEnabled(enabled)
    }

    suspend fun setSuLogEnabled(enabled: Boolean): Boolean = saveFeature {
        Natives.setSuLogEnabled(enabled)
    }

    suspend fun setWebviewZygoteUmountEnabled(enabled: Boolean): Boolean = saveFeature {
        Natives.setWebViewZygoteUmountEnabled(enabled)
    }

    suspend fun setSelinuxHideEnabled(enabled: Boolean): Int = withContext(Dispatchers.IO) {
        Natives.setSelinuxHideEnabled(enabled).also {
            ksuCliRepository.execKsud("feature save", true)
        }
    }

    suspend fun setDefaultUmountModules(enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) { Natives.setDefaultUmountModules(enabled) }

    fun isLateLoadMode(): Boolean = runCatching { Natives.isLateLoadMode }.getOrDefault(false)

    private suspend fun saveFeature(block: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        block().also { success ->
            if (success) ksuCliRepository.execKsud("feature save", true)
        }
    }
}
