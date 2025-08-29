package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.view.View
import android.text.TextWatcher
import android.text.Editable
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.StackCreateRequest
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class StackCreateActivity : AppCompatActivity() {
    private lateinit var editor: CodeEditor
    private var yamlOnly = false
    private var lastYamlFromGuided: String = ""
    private var lastSwarmId: String? = null
    private var useLightTheme: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stack_create)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_stack_create)
        toolbar.title = "Create Stack"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        EdgeToEdge.apply(this, toolbar, findViewById(R.id.container_modes))
        // Confirm on back if there are unsaved inputs
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@StackCreateActivity)
                        .setTitle("Discard changes?")
                        .setMessage("You have unsaved changes. Leave without saving?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Discard") { _, _ -> finish() }
                        .show()
                } else {
                    finish()
                }
            }
        })


        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggle_mode)
        val btnGuided = findViewById<MaterialButton>(R.id.btn_mode_guided)
        val btnYaml = findViewById<MaterialButton>(R.id.btn_mode_yaml)
        val guidedView = findViewById<View>(R.id.view_guided)
        editor = findViewById(R.id.editor_compose)
        editor.isWordwrap = true
        useLightTheme = com.agreenbhm.vibetainer.util.Prefs(this).yamlLightTheme()
        // Initialize TextMate YAML highlighting & theming (same as YamlViewer)
        initTextMateForYaml()
        val switchSwarm = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_swarm)
        val textSwarmStatus = findViewById<android.widget.TextView>(R.id.text_swarm_status)

        // Default to Guided mode
        toggle.check(R.id.btn_mode_guided)
        guidedView.visibility = View.VISIBLE
        editor.visibility = View.GONE

        // Helper texts and validation for first service fields
        runCatching {
            val portsEdit = findViewById<android.widget.EditText>(R.id.input_ports)
            val portsTil = portsEdit.parent as com.google.android.material.textfield.TextInputLayout
            portsTil.helperText = "e.g., 8080:80"
            portsEdit.addTextChangedListener(object: TextWatcher{override fun afterTextChanged(s: Editable?){
                val t=s?.toString()?:""; val bad=t.lines().map{it.trim()}.filter{it.isNotBlank()}.firstOrNull{!it.matches(Regex("""^\d{1,5}:\d{1,5}(/\w+)?$"""))}; portsTil.error = if(bad!=null) "Invalid port: $bad" else null}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int){}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int){}
            })
            val volsEdit = findViewById<android.widget.EditText>(R.id.input_volumes)
            val volsTil = volsEdit.parent as com.google.android.material.textfield.TextInputLayout
            volsTil.helperText = "e.g., /host:/container"
            volsEdit.addTextChangedListener(object: TextWatcher{override fun afterTextChanged(s: Editable?){
                val t=s?.toString()?:""; val bad=t.lines().map{it.trim()}.filter{it.isNotBlank()}.firstOrNull{!it.contains(':')}; volsTil.error = if(bad!=null) "Invalid volume: $bad" else null}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int){}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int){}
            })
            val envEdit = findViewById<android.widget.EditText>(R.id.input_env)
            val envTil = envEdit.parent as com.google.android.material.textfield.TextInputLayout
            envTil.helperText = "e.g., KEY=VALUE"
            envEdit.addTextChangedListener(object: TextWatcher{override fun afterTextChanged(s: Editable?){
                val t=s?.toString()?:""; val bad=t.lines().map{it.trim()}.filter{it.isNotBlank()}.firstOrNull{!it.contains('=') || it.startsWith('=')}; envTil.error = if(bad!=null) "Invalid env: $bad" else null}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int){}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int){}
            })
        }

        fun updateYamlFromGuided() {
            val yaml = buildYamlFromInputs()
            lastYamlFromGuided = yaml
            editor.setText(yaml)
        }

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btn_mode_yaml) {
                updateYamlFromGuided()
                guidedView.visibility = View.GONE
                editor.visibility = View.VISIBLE
            } else {
                val current = editor.text.toString()
                val ok = runCatching { parseYamlToInputs(current) }.isSuccess
                if (!ok) {
                    Snackbar.make(editor, "YAML has multiple services or invalid structure; edit in YAML.", Snackbar.LENGTH_LONG).show()
                    btnYaml.isChecked = true
                    return@addOnButtonCheckedListener
                }
                guidedView.visibility = View.VISIBLE
                editor.visibility = View.GONE
            }
        }


        // Detect swarm; allow manual override
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        val endpointId = prefs.endpointId()
        textSwarmStatus.text = "Detecting swarm…"
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { api.swarmInfo(endpointId) }.getOrNull() }
            if (info?.ID.isNullOrBlank()) {
                lastSwarmId = null
                switchSwarm.isChecked = false
                textSwarmStatus.text = "No swarm detected for this endpoint."
            } else {
                lastSwarmId = info?.ID
                switchSwarm.isChecked = true
                textSwarmStatus.text = "Swarm detected: ${lastSwarmId}"
            }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_service_extra_field).setOnClickListener {
            val container = findViewById<android.widget.LinearLayout>(R.id.additional_fields_container)
            addAdditionalFieldRow(container, "", "")
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_stack_field).setOnClickListener {
            val container = findViewById<android.widget.LinearLayout>(R.id.stack_additional_fields_container)
            addAdditionalFieldRow(container, "", "")
            findViewById<android.widget.TextView>(R.id.text_stack_additional_title).visibility = android.view.View.VISIBLE
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_service).setOnClickListener {
            addServiceBlock(null, emptyMap<String, Any>())
        }
findViewById<MaterialButton>(R.id.btn_create_stack).setOnClickListener {
            val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
            val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
            val endpointId = prefs.endpointId()
            val stackName = findViewById<EditText>(R.id.input_stack_name).text.toString().trim()
            if (stackName.isBlank()) {
                Snackbar.make(it, "Enter a stack name", Snackbar.LENGTH_LONG).show(); return@setOnClickListener
            }
            val yamlText = if (editor.visibility == View.VISIBLE) editor.text.toString() else buildYamlFromInputs()
            // Validate YAML
            val valid = runCatching { Yaml().load<Any?>(yamlText) }.isSuccess
            if (!valid) {
                Snackbar.make(it, "YAML is invalid", Snackbar.LENGTH_LONG).show(); return@setOnClickListener
            }
            lifecycleScope.launch {
                val resp = withContext(Dispatchers.IO) {
                    val wantSwarm = switchSwarm.isChecked
                    val swarmId = if (wantSwarm) {
                        lastSwarmId ?: runCatching { api.swarmInfo(endpointId).ID }.getOrNull()
                    } else null
                    if (wantSwarm and (swarmId.isNullOrBlank())) {
                        null
                    } else {
                        val req = com.agreenbhm.vibetainer.network.StackCreateRequest(
                            name = stackName,
                            stackFileContent = yamlText,
                            env = findViewById<EditText>(R.id.input_env).text.toString().lines().mapNotNull { line ->
                                val t = line.trim(); if (t.isBlank() || !t.contains('=')) null else t
                            }.map { line ->
                                val idx = line.indexOf('=');
                                val k = line.substring(0, idx).trim();
                                val v = line.substring(idx + 1).trim();
                                com.agreenbhm.vibetainer.network.StackEnvVar(k, v)
                            },
                            registries = findViewById<EditText>(R.id.input_registries).text.toString().split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() },
                            fromAppTemplate = false,
                            swarmID = swarmId
                        )
                        if (!swarmId.isNullOrBlank()) api.createStackSwarmFromString(endpointId, req)
                        else api.createStackStandaloneFromString(endpointId, req.copy(swarmID = null))
                    }
                }
                if (resp == null) {
                    Snackbar.make(it, "Swarm not detected and no Swarm ID available; cannot create swarm stack.", Snackbar.LENGTH_LONG).show()
                } else if (resp.isSuccessful) {
                    Snackbar.make(it, "Stack created", Snackbar.LENGTH_LONG).show()
                    finish()
                } else {
                    Snackbar.make(it, "Create failed: ${resp.code()}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun inputText(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun initTextMateForYaml() {
        try {
            // Register asset provider once
            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(applicationContext.assets)
            )
            // Load grammars (expects assets/textmate/languages.json with source.yaml)
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            // Load themes we ship (solarized dark/light), then apply pref
            val themeAssetsPath = "textmate/themes/"
            val darkName = "solarized-dark-color-theme"
            val lightName = "solarized-light-color-theme"
            val reg = ThemeRegistry.getInstance()
            fun load(name: String) {
                val path = themeAssetsPath + name + ".json"
                FileProviderRegistry.getInstance().tryGetInputStream(path)?.use { ins ->
                    reg.loadTheme(ThemeModel(org.eclipse.tm4e.core.registry.IThemeSource.fromInputStream(ins, path, null), name))
                }
            }
            load(darkName)
            load(lightName)

            val useLight = com.agreenbhm.vibetainer.util.Prefs(this).yamlLightTheme()
            applyEditorTheme()
            // Apply YAML language
            val language = TextMateLanguage.create("source.yaml", true)
            editor.setEditorLanguage(language)
            editor.rerunAnalysis()
            editor.invalidate()
        } catch (_: Throwable) {
            // Fallback simple scheme if TextMate init fails
            editor.setColorScheme(SchemeDarcula())
        }
    }

    private fun buildYamlFromInputs(): String {
        val name = inputText(R.id.input_service_name).ifBlank { "app" }
        val image = inputText(R.id.input_image)
        val cmd = inputText(R.id.input_command)
        val entrypoint = inputText(R.id.input_entrypoint)
        val ports = inputText(R.id.input_ports).lines().map { it.trim() }.filter { it.isNotBlank() }
        val volumes = inputText(R.id.input_volumes).lines().map { it.trim() }.filter { it.isNotBlank() }
        val env = inputText(R.id.input_env).lines().mapNotNull {
            val t = it.trim(); if (t.isBlank()) null else t
        }
        val network = inputText(R.id.input_network)
        val restart = inputText(R.id.input_restart)

        val root = linkedMapOf<String, Any>()
        val services = linkedMapOf<String, Any>()
        val svc = linkedMapOf<String, Any>()
        if (image.isNotBlank()) svc["image"] = image
        if (cmd.isNotBlank()) svc["command"] = cmd
        if (entrypoint.isNotBlank()) svc["entrypoint"] = entrypoint
        if (ports.isNotEmpty()) svc["ports"] = ports
        if (volumes.isNotEmpty()) svc["volumes"] = volumes
        if (env.isNotEmpty()) svc["environment"] = env
        if (restart.isNotBlank()) svc["restart"] = restart
        if (network.isNotBlank()) {
            svc["networks"] = listOf(network)
            root["networks"] = linkedMapOf(network to linkedMapOf<String, Any>())
        }
        // Merge any additional dynamic fields
        runCatching {
            val extras = findViewById<android.widget.LinearLayout>(R.id.additional_fields_container)
            for (i in 0 until extras.childCount) {
                val row = extras.getChildAt(i) as? android.widget.LinearLayout ?: continue
                if (row.childCount < 2) continue
                val keyLayout = row.getChildAt(0) as? com.google.android.material.textfield.TextInputLayout ?: continue
                val valLayout = row.getChildAt(1) as? com.google.android.material.textfield.TextInputLayout ?: continue
                val keyStr = keyLayout.editText?.text?.toString()?.trim().orEmpty()
                val valStr = valLayout.editText?.text?.toString()?.trim().orEmpty()
                if (keyStr.isBlank() || valStr.isBlank()) continue
                val parsed: Any? = runCatching { Yaml().load<Any?>(valStr) }.getOrElse { valStr }
                parsed?.let { svc[keyStr] = it }
            }
        }
        services[name] = svc

        // Additional service blocks
        runCatching {
            val svcParent = findViewById<android.widget.LinearLayout>(R.id.services_extra_container)
            for (i in 0 until svcParent.childCount) {
                val block = svcParent.getChildAt(i) as? android.widget.LinearLayout ?: continue
                // name
                val nameLayout = block.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("svc_name")
                val svcName = nameLayout?.editText?.text?.toString()?.trim().orEmpty()
                if (svcName.isBlank()) continue
                val imageLayout = block.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("svc_image")
                val imgVal = imageLayout?.editText?.text?.toString()?.trim().orEmpty()
                val m = linkedMapOf<String, Any>()
                if (imgVal.isNotBlank()) m["image"] = imgVal
                val extraContainer = block.findViewWithTag<android.widget.LinearLayout>("svc_extra_container")
                if (extraContainer != null) {
                    for (j in 0 until extraContainer.childCount) {
                        val row = extraContainer.getChildAt(j) as? android.widget.LinearLayout ?: continue
                        if (row.childCount < 2) continue
                        val keyLayout = row.getChildAt(0) as? com.google.android.material.textfield.TextInputLayout ?: continue
                        val valLayout = row.getChildAt(1) as? com.google.android.material.textfield.TextInputLayout ?: continue
                        val keyStr = keyLayout.editText?.text?.toString()?.trim().orEmpty()
                        val valStr = valLayout.editText?.text?.toString()?.trim().orEmpty()
                        if (keyStr.isBlank() || valStr.isBlank()) continue
                        val parsed: Any? = runCatching { Yaml().load<Any?>(valStr) }.getOrElse { valStr }
                        parsed?.let { m[keyStr] = it }
                    }
                }
                services[svcName] = m
            }
        }

        // Top-level additional fields
        runCatching {
            val stackExtras = findViewById<android.widget.LinearLayout>(R.id.stack_additional_fields_container)
            for (i in 0 until stackExtras.childCount) {
                val row = stackExtras.getChildAt(i) as? android.widget.LinearLayout ?: continue
                if (row.childCount < 2) continue
                val keyLayout = row.getChildAt(0) as? com.google.android.material.textfield.TextInputLayout ?: continue
                val valLayout = row.getChildAt(1) as? com.google.android.material.textfield.TextInputLayout ?: continue
                val keyStr = keyLayout.editText?.text?.toString()?.trim().orEmpty()
                val valStr = valLayout.editText?.text?.toString()?.trim().orEmpty()
                if (keyStr.isBlank() || valStr.isBlank()) continue
                val parsed: Any? = runCatching { Yaml().load<Any?>(valStr) }.getOrElse { valStr }
                if (parsed != null) root[keyStr] = parsed
            }
        }

        root["services"] = services

        val opt = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        val yaml = Yaml(opt)
        val raw = yaml.dump(root)
        // Minor cleanup for quoted version
        return raw.replace("\"3.9\"", "\"3.9\"")
    }

    private fun parseYamlToInputs(yamlText: String) {
        val yaml = Yaml()
        val root = yaml.load<Any?>(yamlText) as? Map<*, *> ?: throw IllegalArgumentException("bad yaml")
        val services = root["services"] as? Map<*, *> ?: throw IllegalArgumentException("no services")
        val entries = services.entries.toList()
        if (entries.isEmpty()) throw IllegalArgumentException("no services")
        val first = entries.first()
        val svcName = first.key?.toString() ?: "app"
        val svc = first.value as? Map<*, *> ?: throw IllegalArgumentException("bad service")
        findViewById<EditText>(R.id.input_service_name).setText(svcName)
        (svc["image"] as? String)?.let { findViewById<EditText>(R.id.input_image).setText(it) }
        (svc["command"] as? String)?.let { findViewById<EditText>(R.id.input_command).setText(it) }
        (svc["entrypoint"] as? String)?.let { findViewById<EditText>(R.id.input_entrypoint).setText(it) }
        (svc["ports"] as? List<*>)?.let { findViewById<EditText>(R.id.input_ports).setText(it.joinToString("\n") { p -> p.toString() }) }
        (svc["volumes"] as? List<*>)?.let { findViewById<EditText>(R.id.input_volumes).setText(it.joinToString("\n") { v -> v.toString() }) }
        (svc["environment"] as? List<*>)?.let { findViewById<EditText>(R.id.input_env).setText(it.joinToString("\n") { e -> e.toString() }) }
        (svc["restart"] as? String)?.let { findViewById<EditText>(R.id.input_restart).setText(it) }
        val nets = svc["networks"] as? List<*>
        val networks = root["networks"] as? Map<*, *>
        val netName = nets?.firstOrNull()?.toString() ?: networks?.keys?.firstOrNull()?.toString()
        if (!netName.isNullOrBlank()) findViewById<EditText>(R.id.input_network).setText(netName)

        // Build dynamic fields for any additional keys under service
        val known = setOf("image","command","entrypoint","ports","volumes","environment","restart","networks")
        val container = findViewById<android.widget.LinearLayout>(R.id.additional_fields_container)
        val title = findViewById<android.widget.TextView>(R.id.text_additional_title)
        container.removeAllViews()
        var added = false
        svc.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            if (!known.contains(key)) {
                addAdditionalFieldRow(container, key, v)
                added = true
            }
        }
        title.visibility = if (added) View.VISIBLE else View.GONE

        // Populate additional service blocks
        val svcParent = findViewById<android.widget.LinearLayout>(R.id.services_extra_container)
        svcParent.removeAllViews()
        if (entries.size > 1) {
            entries.drop(1).forEach { e ->
                val nm = e.key?.toString()
                val mv = e.value as? Map<*, *>
                addServiceBlock(nm, mv)
            }
        }

        // Populate stack-level extras
        val stackKnown = setOf("version","services","networks")
        val stackContainer = findViewById<android.widget.LinearLayout>(R.id.stack_additional_fields_container)
        val stackTitle = findViewById<android.widget.TextView>(R.id.text_stack_additional_title)
        stackContainer.removeAllViews()
        var stackAdded = false
        root.forEach { (k,v) ->
            val key = k?.toString() ?: return@forEach
            if (!stackKnown.contains(key)) { addAdditionalFieldRow(stackContainer, key, v); stackAdded = true }
        }
        stackTitle.visibility = if (stackAdded) View.VISIBLE else View.GONE
    }

    private fun addAdditionalFieldRow(container: android.widget.LinearLayout, key: String, value: Any?) {
        val ctx = container.context
        val wrap = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (8 * ctx.resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, 0)
        }
        val keyLayout = com.google.android.material.textfield.TextInputLayout(ctx)
        keyLayout.hint = "Key"
        val keyEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        keyEdit.setText(key)
        keyLayout.addView(keyEdit)

        val valLayout = com.google.android.material.textfield.TextInputLayout(ctx)
        valLayout.hint = "Value (YAML)"
        val valEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        valEdit.setSingleLine(false)
        valEdit.minLines = 1
        valEdit.maxLines = 6
        val dump = try { Yaml().dump(value).trim() } catch (_: Throwable) { value?.toString() ?: "" }
        valEdit.setText(if (dump == "''") "" else dump)
        valLayout.addView(valEdit)

        wrap.tag = "extra_field"
        keyLayout.tag = "extra_key"
        valLayout.tag = "extra_value"

        wrap.addView(keyLayout)
        wrap.addView(valLayout)
        container.addView(wrap)
    }


    private fun addServiceBlock(name: String?, svc: Map<*, *>?) {
        val ctx = this
        val parent = findViewById<android.widget.LinearLayout>(R.id.services_extra_container)
        val block = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
            background = null
        }
        val nameLayout = com.google.android.material.textfield.TextInputLayout(ctx)
        nameLayout.hint = "Service name"
        val nameEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        nameEdit.setText(name ?: "")
        nameLayout.addView(nameEdit)
        block.addView(nameLayout)

        val imageLayout = com.google.android.material.textfield.TextInputLayout(ctx)
        imageLayout.hint = "Image"
        val imageEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        imageLayout.addView(imageEdit)
        block.addView(imageLayout)

        val commandLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Command"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val commandEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        commandLayout.addView(commandEdit)
        block.addView(commandLayout)

        val entryLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Entrypoint"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val entryEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        entryLayout.addView(entryEdit)
        block.addView(entryLayout)

        val portsLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Ports (one per line)"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val portsEdit = com.google.android.material.textfield.TextInputEditText(ctx).apply { setSingleLine(false); minLines = 2 }
        portsLayout.addView(portsEdit)
        block.addView(portsLayout)

        val volsLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Volumes (host:container, one per line)"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val volsEdit = com.google.android.material.textfield.TextInputEditText(ctx).apply { setSingleLine(false); minLines = 2 }
        volsLayout.addView(volsEdit)
        block.addView(volsLayout)

        val envLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Environment (KEY=VALUE, one per line)"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val envEdit = com.google.android.material.textfield.TextInputEditText(ctx).apply { setSingleLine(false); minLines = 2 }
        envLayout.addView(envEdit)
        block.addView(envLayout)

        val restartLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Restart policy"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val restartEdit = com.google.android.material.textfield.TextInputEditText(ctx)
        restartLayout.addView(restartEdit)
        block.addView(restartLayout)

        val netsLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = "Networks (one per line)"; boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val netsEdit = com.google.android.material.textfield.TextInputEditText(ctx).apply { setSingleLine(false); minLines = 1 }
        netsLayout.addView(netsEdit)
        block.addView(netsLayout)

        val extraTitle = android.widget.TextView(ctx).apply {
            text = "Additional fields"
            setPadding(0, (8*resources.displayMetrics.density).toInt(), 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        block.addView(extraTitle)
        val extraContainer = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        block.addView(extraContainer)
        val addFieldBtn = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Add field"
            setOnClickListener { addAdditionalFieldRow(extraContainer, "", "") }
        }
        block.addView(addFieldBtn)
        // Duplicate button removed per request
        val removeBtn = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Remove Service"
            setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Remove service?")
                .setMessage("This will remove this service block from the stack.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ -> parent.removeView(block) }
                .show()
        }
        }
        block.addView(removeBtn)

        svc?.let { m ->
            imageEdit.setText((m["image"] as? String) ?: "")
            commandEdit.setText((m["command"] as? String) ?: "")
            entryEdit.setText((m["entrypoint"] as? String) ?: "")
            (m["ports"] as? List<*>)?.let { portsEdit.setText(it.joinToString("\n") { v -> v.toString() }) }
            (m["volumes"] as? List<*>)?.let { volsEdit.setText(it.joinToString("\n") { v -> v.toString() }) }
            (m["environment"] as? List<*>)?.let { envEdit.setText(it.joinToString("\n") { v -> v.toString() }) }
            restartEdit.setText((m["restart"] as? String) ?: "")
            (m["networks"] as? List<*>)?.let { netsEdit.setText(it.joinToString("\n") { v -> v.toString() }) }
            val known = setOf("image","command","entrypoint","ports","volumes","environment","restart","networks")
            m.forEach { (kk, vv) ->
                val key = kk?.toString() ?: return@forEach
                if (!known.contains(key)) {
                    addAdditionalFieldRow(extraContainer, key, vv)
                }
            }
        }

        nameLayout.tag = "svc_name"
        imageLayout.tag = "svc_image"
        commandLayout.tag = "svc_command"
        entryLayout.tag = "svc_entrypoint"
        portsLayout.tag = "svc_ports"
        volsLayout.tag = "svc_volumes"
        envLayout.tag = "svc_env"
        restartLayout.tag = "svc_restart"
        netsLayout.tag = "svc_networks"
        extraContainer.tag = "svc_extra_container"
        parent.addView(block)
    }

    private fun applyEditorTheme() {
        val reg = ThemeRegistry.getInstance()
        val theme = if (useLightTheme) "solarized-light-color-theme" else "solarized-dark-color-theme"
        try {
            reg.setTheme(theme)
            editor.colorScheme = TextMateColorScheme.create(reg)
        } catch (_: Throwable) {
            editor.setColorScheme(SchemeDarcula())
        }
        editor.invalidate()
    }



    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_stack_create, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_toggle_light_theme)?.isChecked = useLightTheme
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_light_theme -> {
                useLightTheme = !useLightTheme
                com.agreenbhm.vibetainer.util.Prefs(this).setYamlLightTheme(useLightTheme)
                applyEditorTheme()
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        if (editor.visibility == android.view.View.VISIBLE) {
            if (editor.text.toString().isNotBlank()) return true
        }
        val ids = arrayOf(
            R.id.input_stack_name, R.id.input_service_name, R.id.input_image,
            R.id.input_command, R.id.input_entrypoint, R.id.input_ports,
            R.id.input_volumes, R.id.input_env, R.id.input_network, R.id.input_restart
        )
        ids.forEach { id ->
            val v = findViewById<android.widget.EditText?>(id)
            if (v != null && v.text.toString().isNotBlank()) return true
        }
        val extraContainers = listOf(
            R.id.additional_fields_container,
            R.id.services_extra_container,
            R.id.stack_additional_fields_container
        )
        extraContainers.forEach { cid ->
            val ll = findViewById<android.widget.LinearLayout?>(cid)
            if (ll != null && ll.childCount > 0) return true
        }
        return false
    }
}
