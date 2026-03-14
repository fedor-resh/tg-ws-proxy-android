package org.flowseal.tgwsproxy.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Patterns
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import org.flowseal.tgwsproxy.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ProxyConfigStore
    private var updatingForm = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ProxyConfigStore(this)
        ProxyLog.configure(this, configStore.load().verbose)
        ProxyLog.i("MainActivity created")
        requestNotificationPermissionIfNeeded()
        populateForm(configStore.loadDraftOrConfig())
        installDraftAutosave()
        refreshState()

        binding.startButton.setOnClickListener {
            runCatching { collectConfig() }
                .onSuccess { config ->
                    configStore.save(config)
                    ProxyLog.configure(this, config.verbose)
                    runCatching {
                        ProxyService.start(this)
                    }.onSuccess {
                        Toast.makeText(this, "Proxy start requested", Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        ProxyLog.e("Failed to request proxy start", error)
                        showError(error.message ?: "Failed to start service")
                    }
                    refreshState()
                }
                .onFailure { error ->
                    showError(error.message ?: "Invalid configuration")
                }
        }

        binding.stopButton.setOnClickListener {
            ProxyService.stop(this)
            refreshState()
        }

        binding.instructionsButton.setOnClickListener {
            showInstructions()
        }

        binding.openInTelegramButton.setOnClickListener {
            openProxyInTelegram()
        }

        binding.logsButton.setOnClickListener {
            showLogs()
        }

        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onPause() {
        saveDraft()
        super.onPause()
    }

    private fun populateForm(draft: ProxyDraft) {
        updatingForm = true
        binding.hostInput.setText(draft.hostText)
        binding.portInput.setText(draft.portText)
        binding.dcIpInput.setText(draft.dcIpText)
        binding.verboseCheckbox.isChecked = draft.verbose
        updatingForm = false
    }

    private fun installDraftAutosave() {
        binding.hostInput.doAfterTextChanged { saveDraft() }
        binding.portInput.doAfterTextChanged { saveDraft() }
        binding.dcIpInput.doAfterTextChanged { saveDraft() }
        binding.verboseCheckbox.setOnCheckedChangeListener { _, _ -> saveDraft() }
    }

    private fun saveDraft() {
        if (updatingForm) {
            return
        }
        configStore.saveDraft(
            ProxyDraft(
                hostText = binding.hostInput.text?.toString().orEmpty(),
                portText = binding.portInput.text?.toString().orEmpty(),
                dcIpText = binding.dcIpInput.text?.toString().orEmpty(),
                verbose = binding.verboseCheckbox.isChecked,
            ),
        )
    }

    private fun collectConfig(): ProxyConfig {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        require(host == "localhost" || Patterns.IP_ADDRESS.matcher(host).matches()) {
            "Host must be an IP address or localhost"
        }

        val port = binding.portInput.text?.toString()?.trim().orEmpty().toIntOrNull()
            ?: throw IllegalArgumentException("Port must be a number")
        require(port in 1..65535) { "Port must be in 1..65535" }

        val dcLines = DcAddressParser.normalizeText(
            binding.dcIpInput.text?.toString().orEmpty(),
        )
        require(dcLines.isNotEmpty()) { "At least one DC mapping is required" }
        DcAddressParser.parse(dcLines)

        return ProxyConfig(
            host = host,
            port = port,
            dcIp = dcLines,
            verbose = binding.verboseCheckbox.isChecked,
        )
    }

    private fun refreshState() {
        val status = if (ProxyService.isRunning()) {
            getString(R.string.status_running)
        } else {
            val error = ProxyService.lastErrorMessage()
            if (error.isNullOrBlank()) {
                getString(R.string.status_stopped)
            } else {
                "${getString(R.string.status_stopped)}\n$error"
            }
        }
        binding.statusText.text = status
    }

    private fun showInstructions() {
        val config = runCatching { collectConfig() }.getOrElse { configStore.load() }
        AlertDialog.Builder(this)
            .setTitle("Telegram setup")
            .setMessage(
                buildString {
                    append("1. Open Telegram on Android.\n")
                    append("2. Go to Settings -> Data and Storage -> Proxy.\n")
                    append("3. Add SOCKS5 proxy.\n")
                    append("4. Server: ${config.host}\n")
                    append("5. Port: ${config.port}\n")
                    append("6. Leave username and password empty.\n\n")
                    append("If Telegram refuses localhost proxy from another app, the next step is VpnService-based interception.")
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openProxyInTelegram() {
        runCatching { collectConfig() }
            .onSuccess { config ->
                configStore.save(config)
                val link = buildTelegramProxyLink(config)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(intent)
                    Toast.makeText(this, getString(R.string.telegram_opening), Toast.LENGTH_SHORT).show()
                } catch (_: ActivityNotFoundException) {
                    copyTextToClipboard("tg-ws-proxy-link", link)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.telegram_not_found_title)
                        .setMessage(getString(R.string.telegram_not_found_message, link))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .onFailure { error ->
                showError(error.message ?: "Invalid configuration")
            }
    }

    private fun showLogs() {
        val logText = ProxyLog.readTail(this)
        val textView = TextView(this).apply {
            text = logText
            movementMethod = ScrollingMovementMethod()
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
        }
        AlertDialog.Builder(this)
            .setTitle("Proxy log")
            .setView(scroll)
            .setNeutralButton(R.string.action_copy_logs) { _, _ ->
                copyLogsToClipboard(logText)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyLogsToClipboard(logText: String = ProxyLog.readTail(this)) {
        copyTextToClipboard("tg-ws-proxy-log", logText)
        Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun buildTelegramProxyLink(config: ProxyConfig): String {
        return Uri.Builder()
            .scheme("tg")
            .authority("socks")
            .appendQueryParameter("server", config.host)
            .appendQueryParameter("port", config.port.toString())
            .build()
            .toString()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Invalid settings")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
