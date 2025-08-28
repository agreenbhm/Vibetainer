package com.agreenbhm.vibetainer.ui

import android.os.Bundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agreenbhm.vibetainer.R
import com.agreenbhm.vibetainer.network.PortainerApi
import com.agreenbhm.vibetainer.network.StackUpdateRequest
import com.google.android.material.appbar.MaterialToolbar
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
import org.eclipse.tm4e.core.registry.IThemeSource
import org.yaml.snakeyaml.Yaml


class YamlViewerActivity : AppCompatActivity() {
    private lateinit var editor: CodeEditor
    private var original: String = ""
    private var editing: Boolean = false
    private var stackId: Int = -1
    private var endpointId: Int = -1
    private var originalTitle: String = "YAML"
    private var useLightTheme: Boolean = false
    // No-op state; reserved for future editor event-based handling
    private var themeRegistry = ThemeRegistry.getInstance()
    private var darkName = "solarized-dark-color-theme"
    private var lightName = "solarized-light-color-theme"

// Replace your entire onCreate method with this:

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yaml_viewer)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_yaml)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "YAML"
        originalTitle = title
        supportActionBar?.title = title
        com.agreenbhm.vibetainer.ui.EdgeToEdge.apply(this, toolbar, findViewById(R.id.editor_yaml))

        // Get intent data
        original = intent.getStringExtra(EXTRA_CONTENT) ?: ""
        stackId = intent.getIntExtra(EXTRA_STACK_ID, -1)
        endpointId = intent.getIntExtra(EXTRA_ENDPOINT_ID, -1)
        val prefs = com.agreenbhm.vibetainer.util.Prefs(this)
        useLightTheme = prefs.yamlLightTheme()
        editor = findViewById(R.id.editor_yaml)
        editor.isWordwrap = prefs.yamlWordWrap()
        editor.setLineNumberEnabled(true)
        editor.nonPrintablePaintingFlags = CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION

        // Set a temporary empty text first
        editor.setText("")

        // Initialize TextMate in a coroutine to handle async operations properly
        lifecycleScope.launch {
            initializeTextMateAndSetContent()
        }

        // Set up back press handler
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val hasChanges = editor.text.toString() != original
                if (editing && hasChanges) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@YamlViewerActivity)
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
    }

    private suspend fun initializeTextMateAndSetContent() {
        try {
            withContext(Dispatchers.Main) {
                // Add file provider
                FileProviderRegistry.getInstance().addFileProvider(
                    AssetsFileResolver(applicationContext.assets)
                )

                // Load grammars first
                GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

                // Small delay to ensure grammar loading completes
                delay(50)

                // Load themes
                val themeAssetsPath = "textmate/themes/"

                themeRegistry.loadTheme(
                    ThemeModel(
                        IThemeSource.fromInputStream(
                            FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath + darkName + ".json"),
                            themeAssetsPath + darkName + ".json",
                            null
                        ),
                        darkName
                    ).apply {}
                )

                themeRegistry.loadTheme(
                    ThemeModel(
                        IThemeSource.fromInputStream(
                            FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath + lightName + ".json"),
                            themeAssetsPath + lightName + ".json",
                            null
                        ),
                        lightName
                    ).apply { }
                )

                // Another small delay for theme loading
                delay(50)

                // Set the theme first
                themeRegistry.setTheme(if (useLightTheme) lightName else darkName)

                // Apply color scheme
                val colorScheme = TextMateColorScheme.create(themeRegistry)
                editor.setColorScheme(colorScheme)

                // Set language
                val languageScopeName = "source.yaml"
                val language = TextMateLanguage.create(languageScopeName, true)
                editor.setEditorLanguage(language)

                // Final delay before setting text to ensure everything is ready
                delay(100)

                // Now set the actual content
                editor.setText(original)

                // Force analysis and refresh
                editor.rerunAnalysis()
                editor.invalidate()

                setEditing(false)

                android.util.Log.d("YamlViewer", "TextMate initialization completed successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("YamlViewer", "Failed to initialize TextMate", e)
            withContext(Dispatchers.Main) {
                // Fallback to simple setup
                editor.setColorScheme(SchemeDarcula())
                editor.setText(original)
                setEditing(false)
            }
        }
    }

    // Also update your applyEditorTheme method:
    private fun applyEditorTheme() {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                runCatching {
                    // Get current text to preserve it
                    val currentText = editor.text.toString()

                    // Set the theme
                    themeRegistry.setTheme(if (useLightTheme) lightName else darkName)

                    // Apply the color scheme
                    val colorScheme = TextMateColorScheme.create(themeRegistry)
                    editor.setColorScheme(colorScheme)

                    // Small delay to ensure theme application
                    delay(50)

                    // Reset text to trigger re-highlighting
                    if (currentText.isNotEmpty()) {
                        editor.setText("")
                        delay(50)
                        editor.setText(currentText)
                    }

                    // Force refresh
                    editor.rerunAnalysis()
                    editor.invalidate()

                }.onFailure { e ->
                    android.util.Log.e("YamlViewer", "TM theme apply failed", e)
                    editor.setColorScheme(SchemeDarcula())
                    editor.rerunAnalysis()
                    editor.invalidate()
                }
            }
        }
    }

    // No TextMate setup; keep editor simple

    private fun setEditing(enable: Boolean) {
        editing = enable
        editor.isEditable = enable
        supportActionBar?.title = if (enable) "Edit YAML" else originalTitle
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_yaml_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_edit)?.isVisible = !editing
        menu.findItem(R.id.action_save)?.isVisible = editing
        menu.findItem(R.id.action_cancel)?.isVisible = editing
        menu.findItem(R.id.action_toggle_wrap)?.isChecked = editor.isWordwrap
        menu.findItem(R.id.action_toggle_light_theme)?.isChecked = useLightTheme
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit -> { setEditing(true); true }
            R.id.action_cancel -> { editor.setText(original); setEditing(false); true }
            R.id.action_save -> { saveYaml(); true }
            R.id.action_suggest -> { showSuggestions(); true }
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


    private fun showSuggestions() {
        if (!editing) { setEditing(true) }
        val ctx = detectComposeContext()
        val (labels, snippets) = when (ctx.section) {
            ComposeSection.SERVICE -> buildServiceSuggestions(ctx)
            ComposeSection.SERVICES_ROOT -> buildServicesRootSuggestions(ctx)
            else -> buildTopLevelSuggestions()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Insert snippet")
            .setItems(labels.toTypedArray()) { _, which ->
                val snippet = snippets[which]
                insertSnippetAtCursor(snippet)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun insertSnippetAtCursor(snippetRaw: String) {
        val cursor = editor.cursor?.left ?: editor.text.length
        val text = editor.text.toString()
        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val currentIndent = text.substring(lineStart, cursor).takeWhile { it == ' ' }.length
        val snippet = snippetRaw.trimEnd('\n').lines().joinToString("\n") { " ".repeat(currentIndent) + it }
        val before = text.substring(0, cursor)
        val after = text.substring(cursor)
        val needsLeadingNewline = before.isNotEmpty() && !before.endsWith("\n")
        val newText = buildString {
            append(before)
            if (needsLeadingNewline) append('\n')
            append(snippet)
            append('\n')
            append(after)
        }
        editor.setText(newText)
        val newPos = (before.length + (if (needsLeadingNewline) 1 else 0) + snippet.length + 1)
        try { editor.setSelection(newPos, newPos) } catch (_: Throwable) {}
    }

    private enum class ComposeSection { TOP, SERVICES_ROOT, SERVICE, OTHER }
    private data class ComposeContext(val section: ComposeSection, val serviceName: String?, val indent: Int)
    private fun detectComposeContext(): ComposeContext {
        val cursor = editor.cursor?.left ?: 0
        val text = editor.text.toString()
        val upToCursor = text.substring(0, cursor)
        val lines = upToCursor.split('\n')
        var inServices = false
        var currentService: String? = null
        var currentIndent = 0
        for (raw in lines) {
            val line = raw.trimEnd()
            val indent = raw.takeWhile { it == ' ' }.length
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.startsWith(" ")) {
                // top-level
                currentService = null
                inServices = line.startsWith("services:")
            } else if (inServices && line.trimEnd().endsWith(":") && indent >= 2 && !line.trim().startsWith("- ")) {
                // service key under services
                currentService = line.trim().removeSuffix(":")
            }
            currentIndent = indent
        }
        return when {
            inServices && currentService != null -> ComposeContext(ComposeSection.SERVICE, currentService, currentIndent)
            inServices -> ComposeContext(ComposeSection.SERVICES_ROOT, null, currentIndent)
            else -> ComposeContext(ComposeSection.TOP, null, currentIndent)
        }
    }

    private fun buildTopLevelSuggestions(): Pair<List<String>, List<String>> {
        val labels = listOf(
            "version",
            "services",
            "volumes",
            "networks",
            "configs",
            "secrets"
        )
        val snippets = listOf(
            "version: \"3.9\"",
            "services:\n  app:\n    image: nginx:latest",
            "volumes:\n  data:",
            "networks:\n  default:",
            "configs:\n  my_config:",
            "secrets:\n  my_secret:"
        )
        return labels to snippets
    }

    private fun buildServicesRootSuggestions(ctx: ComposeContext): Pair<List<String>, List<String>> {
        val labels = listOf("service name")
        val snippets = listOf("app:\n  image: nginx:latest")
        return labels to snippets
    }

    private fun buildServiceSuggestions(ctx: ComposeContext): Pair<List<String>, List<String>> {
        val labels = listOf(
            "image",
            "build",
            "command",
            "entrypoint",
            "ports",
            "environment",
            "volumes",
            "depends_on",
            "restart",
            "deploy",
            "networks"
        )
        val snippets = listOf(
            "image: nginx:latest",
            "build: .",
            "command: [\"sh\", \"-c\", \"echo hello\"]",
            "entrypoint: [\"/docker-entrypoint.sh\"]",
            "ports:\n  - \"8080:80\"",
            "environment:\n  - KEY=VALUE",
            "volumes:\n  - data:/var/lib/data",
            "depends_on:\n  - db",
            "restart: unless-stopped",
            "deploy:\n  replicas: 1\n  restart_policy:\n    condition: on-failure",
            "networks:\n  - default"
        )
        return labels to snippets
    }

    // Auto-dash behavior removed per request

    private fun saveYaml() {
        val content = editor.text.toString()
        if (stackId <= 0 || endpointId <= 0) {
            Snackbar.make(editor, "Missing stack or endpoint ID", Snackbar.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                // Validate YAML before sending
                runCatching { Yaml().load<Any?>(content) }.onFailure {
                    Snackbar.make(editor, "YAML validation failed: ${it.message}", Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                val api = PortainerApi.create(this@YamlViewerActivity, com.agreenbhm.vibetainer.util.Prefs(this@YamlViewerActivity).baseUrl(), com.agreenbhm.vibetainer.util.Prefs(this@YamlViewerActivity).token())
                val resp = withContext(Dispatchers.IO) { api.updateStack(stackId, endpointId, StackUpdateRequest(content, Prune = false)) }
                if (resp.isSuccessful) {
                    original = content
                    setEditing(false)
                    Snackbar.make(editor, "Stack updated", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(editor, "Update failed: ${resp.code()}", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(editor, "Update failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_STACK_ID = "stack_id"
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
    }
}
