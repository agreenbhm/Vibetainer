package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import android.util.Base64
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import org.eclipse.tm4e.core.registry.IThemeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import android.view.Menu
import android.view.MenuItem
import android.view.View

class ConfigDetailActivity : AppCompatActivity() {
    private var cfgIdForMenu: String? = null
    private var cloneModeForMenu: Boolean = false
    private lateinit var editor: CodeEditor
    private var useLightTheme: Boolean = false
    private val themeRegistry = ThemeRegistry.getInstance()
    private val darkName = "solarized-dark-color-theme"
    private val lightName = "solarized-light-color-theme"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_detail)

        val endpointId = intent.getIntExtra("endpoint_id", -1)
        val configId = intent.getStringExtra("config_id")
        cfgIdForMenu = configId

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_config)
        // We'll set a dynamic title/subtitle after we know if this is view or create/clone
        toolbar.title = ""
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.editor_config_data))

        val editName = findViewById<android.widget.EditText>(R.id.input_config_name)
        editor = findViewById(R.id.editor_config_data)

        var isCloneMode = false
        // If started with clone extras, prefill and allow editing as a new config
        val cloneContent = intent.getStringExtra("clone_content")
        val cloneName = intent.getStringExtra("clone_name")
        if (!cloneContent.isNullOrBlank()) isCloneMode = true
        cloneModeForMenu = isCloneMode

        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        val api = PortainerApi.create(this, prefs.baseUrl(), prefs.token())
        useLightTheme = prefs.yamlLightTheme()

        // Initialize editor theme/grammar and set content
        editor.isWordwrap = prefs.yamlWordWrap()
        editor.setLineNumberEnabled(true)
        editor.nonPrintablePaintingFlags = io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION

        // (selection callback clearing removed)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(applicationContext.assets))
                    GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
                    // Load TextMate themes (dark/light) like YAML viewer
                    val themeAssetsPath = "textmate/themes/"
                    runCatching {
                        themeRegistry.loadTheme(
                            ThemeModel(
                                IThemeSource.fromInputStream(
                                    FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath + darkName + ".json"),
                                    themeAssetsPath + darkName + ".json",
                                    null
                                ),
                                darkName
                            )
                        )
                        themeRegistry.loadTheme(
                            ThemeModel(
                                IThemeSource.fromInputStream(
                                    FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath + lightName + ".json"),
                                    themeAssetsPath + lightName + ".json",
                                    null
                                ),
                                lightName
                            )
                        )
                    }
                    delay(50)
                    themeRegistry.setTheme(if (useLightTheme) lightName else darkName)
                    val colorScheme = TextMateColorScheme.create(themeRegistry)
                    editor.setColorScheme(colorScheme)
//                    val language = TextMateLanguage.create("source.yaml", true)
//                    editor.setEditorLanguage(language)
                    delay(100)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    editor.setColorScheme(SchemeDarcula())
                }
            }
        }

        if (!configId.isNullOrBlank() && !isCloneMode) {
            // Viewing existing config: load and make read-only
            lifecycleScope.launch {
                try {
                    val inspect = api.inspectConfig(endpointId, configId)
                    val cfgName = inspect.Spec?.Name ?: "Config"
                    // show name in toolbar
                    supportActionBar?.title = cfgName
                    supportActionBar?.subtitle = prefs.endpointName()
                    // hide the inline name field when viewing
                    editName.visibility = View.GONE
                    editName.setText(cfgName)
                    val dataB64 = inspect.Spec?.Data ?: ""
                    val decoded = if (dataB64.isNotBlank()) String(Base64.decode(dataB64, Base64.DEFAULT)) else ""
                    editor.setText(decoded)
                    editor.isEditable = false
                } catch (e: Exception) {
                    Snackbar.make(editor, "Failed to load: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        } else {
            // New config or clone: prefills
            // title/subtitle reflect new config and endpoint
            supportActionBar?.title = "New Config"
            supportActionBar?.subtitle = prefs.endpointName()
            editName.visibility = View.VISIBLE
            if (isCloneMode) {
                editName.setText(cloneName ?: "")
                editor.setText(cloneContent ?: "")
            }
            editor.isEditable = true
        }

        val btnSave = findViewById<android.view.View>(R.id.btn_save_config)
        btnSave.setOnClickListener {
            val name = editName.text.toString().trim()
            val dataText = editor.text.toString()
            if (name.isBlank()) { Snackbar.make(editor, "Name required", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener }
            val b64 = Base64.encodeToString(dataText.toByteArray(), Base64.NO_WRAP)
            MaterialAlertDialogBuilder(this)
                .setTitle("Save config")
                .setMessage("Create config?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val dlg = MaterialAlertDialogBuilder(this).setView(android.widget.ProgressBar(this)).setCancelable(false).create()
                    dlg.show()
                    lifecycleScope.launch {
                        try {
                            val req = com.agreenbhm.vibetainer.network.ConfigCreateRequest(name, b64)
                            api.createConfig(endpointId, req)
                            dlg.dismiss()
                            Snackbar.make(editor, "Saved", Snackbar.LENGTH_LONG).show()
                            finish()
                        } catch (e: Exception) {
                            dlg.dismiss()
                            Snackbar.make(editor, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }

        // Adjust visibility: hide save button for existing (non-clone) configs
        if (!configId.isNullOrBlank() && !isCloneMode) {
            btnSave.visibility = android.view.View.GONE
        }

        // Use the activity options menu for the Clone action so it appears reliably
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_config_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val cloneItem = menu.findItem(R.id.action_clone)
        cloneItem?.isVisible = !cfgIdForMenu.isNullOrBlank() && !cloneModeForMenu
        val deleteItem = menu.findItem(R.id.action_delete)
        deleteItem?.isVisible = !cfgIdForMenu.isNullOrBlank() && !cloneModeForMenu
        menu.findItem(R.id.action_toggle_wrap)?.isChecked = editor.isWordwrap
        menu.findItem(R.id.action_toggle_light_theme)?.isChecked = useLightTheme
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clone -> {
                // Start this activity in clone/new mode with current content
                val editName = findViewById<android.widget.EditText>(R.id.input_config_name)
                val editor = findViewById<CodeEditor>(R.id.editor_config_data)
                val name = editName.text.toString()
                val content = editor.text.toString()
                val i = android.content.Intent(this, ConfigDetailActivity::class.java)
                i.putExtra("endpoint_id", intent.getIntExtra("endpoint_id", -1))
                i.putExtra("clone_name", "clone-of-$name")
                i.putExtra("clone_content", content)
                startActivity(i)
                true
            }
            R.id.action_delete -> {
                val cfg = intent.getStringExtra("config_id") ?: return true
                val ep = intent.getIntExtra("endpoint_id", -1)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete config")
                    .setMessage("Delete this config? This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        val dlg = MaterialAlertDialogBuilder(this).setView(android.widget.ProgressBar(this)).setCancelable(false).create()
                        dlg.show()
                        lifecycleScope.launch {
                            try {
                                val prefs = com.agreenbhm.vibetainer.util.Prefs(this@ConfigDetailActivity)
                                val api = PortainerApi.create(this@ConfigDetailActivity, prefs.baseUrl(), prefs.token())
                                api.deleteConfig(ep, cfg)
                                dlg.dismiss()
                                val editor = findViewById<CodeEditor>(R.id.editor_config_data)
                                Snackbar.make(editor, "Deleted", Snackbar.LENGTH_LONG).show()
                                finish()
                            } catch (e: Exception) {
                                dlg.dismiss()
                                val editor = findViewById<CodeEditor>(R.id.editor_config_data)
                                Snackbar.make(editor, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
                true
            }
            R.id.action_toggle_wrap -> {
                editor.isWordwrap = !editor.isWordwrap
                com.agreenbhm.vibetainer.util.Prefs(this).setYamlWordWrap(editor.isWordwrap)
                invalidateOptionsMenu()
                true
            }
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

    private fun applyEditorTheme() {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                runCatching {
                    val currentText = editor.text.toString()
                    themeRegistry.setTheme(if (useLightTheme) lightName else darkName)
                    val colorScheme = TextMateColorScheme.create(themeRegistry)
                    editor.setColorScheme(colorScheme)
                    delay(50)
                    if (currentText.isNotEmpty()) {
                        editor.setText("")
                        delay(25)
                        editor.setText(currentText)
                    }
                    editor.rerunAnalysis()
                    editor.invalidate()
                }.onFailure {
                    editor.setColorScheme(SchemeDarcula())
                    editor.rerunAnalysis()
                    editor.invalidate()
                }
            }
        }
    }
    
}
