package com.noyal.jarvis

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Cyan = Color(0xFF54E7FF)
private val Deep = Color(0xFF050B16)
private val Panel = Color(0xFF0B1B2C)
private val Muted = Color(0xFF9ABBCB)

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val fromUser: Boolean,
    val text: String,
    val imageUri: String? = null
)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        setContent { JarvisApp(onSpeak = { speak(it) }) }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun JarvisApp(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(true) }
    var awaitingImagePrompt by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(nowString()) }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                fromUser = false,
                text = "El Psy Kongroo, Noyal. JARVIS is online. What experiment shall we begin?"
            )
        )
    }
    val scope = rememberCoroutineScope()

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            input = spoken
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = nowString()
            kotlinx.coroutines.delay(1000)
        }
    }

    fun addReply(text: String, speak: Boolean = true) {
        messages.add(ChatMessage(fromUser = false, text = text))
        if (speak && voiceEnabled) onSpeak(text)
    }

    fun sendMessage(raw: String) {
        val message = raw.trim()
        if (message.isEmpty() || busy) return
        input = ""
        messages.add(ChatMessage(fromUser = true, text = message))

        val imagePrompt = if (awaitingImagePrompt) {
            awaitingImagePrompt = false
            message
        } else {
            extractImagePrompt(message)
        }

        if (imagePrompt != null) {
            if (imagePrompt.isBlank()) {
                awaitingImagePrompt = true
                addReply("Naturally, Noyal. What exactly should I create?")
                return
            }
            busy = true
            scope.launch {
                addReply("Initiating the visual experiment. The community image queue may take a little time.", false)
                try {
                    val uri = withContext(Dispatchers.IO) { generateHordeImage(context, imagePrompt) }
                    messages.add(ChatMessage(
                        fromUser = false,
                        text = "The visual experiment is complete. I saved your image to Pictures/JARVIS.",
                        imageUri = uri.toString()
                    ))
                    if (voiceEnabled) onSpeak("The visual experiment is complete, Noyal.")
                } catch (e: Exception) {
                    addReply("The image experiment failed: ${e.message ?: "unknown error"}. Try again later.")
                } finally {
                    busy = false
                }
            }
            return
        }

        val timeReply = localTimeReply(message)
        if (timeReply != null) {
            addReply(timeReply)
            return
        }

        if (apiKey.isBlank()) {
            addReply("My Gemini connection is not configured. Open settings and add your API key.")
            showSettings = true
            return
        }

        busy = true
        scope.launch {
            try {
                val snapshot = messages.toList()
                val answer = withContext(Dispatchers.IO) {
                    askGemini(apiKey, snapshot)
                }
                messages.add(ChatMessage(fromUser = false, text = answer))
                if (voiceEnabled) onSpeak(answer)
            } catch (e: Exception) {
                addReply("I couldn't reach Gemini: ${e.message ?: "network error"}. Check your connection and API key.")
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF071B2D), Deep, Color(0xFF02050B))))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("PERSONAL AI • MAD SCIENCE MODE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                Surface(
                    color = Color(0xFF103B36),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF39DCA5))
                ) {
                    Text("● ONLINE", color = Color(0xFF65F2C2), modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { keyDraft = apiKey; showSettings = true }) {
                    Text("SETUP", color = Cyan, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                color = Color(0xFF081827),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOCAL SYSTEM TIME", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                    Text(clock, color = Cyan, fontSize = 34.sp, fontWeight = FontWeight.Light)
                    Text(SimpleDateFormat("EEEE • dd MMMM yyyy", Locale.getDefault()).format(Date()), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("◈  REACTOR CORE STANDBY  ◈", color = Color(0xFF55D7E8), fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureChip("CHAT", Modifier.weight(1f))
                FeatureChip("VOICE", Modifier.weight(1f))
                FeatureChip("IMAGE LAB", Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (busy) {
                    item {
                        Text("JARVIS is processing the experiment…", color = Cyan, fontSize = 12.sp)
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Cyan,
                            trackColor = Panel
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message JARVIS…", color = Muted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Cyan.copy(alpha = 0.35f),
                        cursorColor = Cyan
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage(input) })
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
                    }
                    try { speechLauncher.launch(intent) } catch (_: Exception) {
                        addReply("Speech recognition is unavailable on this device.")
                    }
                }) {
                    Text("🎙", fontSize = 22.sp)
                }
                Button(
                    onClick = { sendMessage(input) },
                    enabled = !busy && input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087D9B))
                ) { Text("SEND", color = Color.White, fontSize = 11.sp) }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Image: “Generate an image of…”", color = Muted, fontSize = 9.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voice replies", color = Muted, fontSize = 10.sp)
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotBlank()) showSettings = false },
            containerColor = Panel,
            title = { Text("Gemini connection", color = Cyan) },
            text = {
                Column {
                    Text("Paste your NEW Gemini API key. Do not share it or publish it with the app.", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Gemini API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyDraft.trim()
                    prefs.edit().putString("gemini_key", apiKey).apply()
                    showSettings = false
                }) { Text("SAVE", color = Cyan) }
            },
            dismissButton = {
                if (apiKey.isNotBlank()) TextButton(onClick = { showSettings = false }) {
                    Text("CANCEL", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun FeatureChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.25f))
    ) {
        Text(label, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 310.dp)
                .background(
                    if (message.fromUser) Color(0xFF10394A) else Color(0xFF0A1A2A),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, Cyan.copy(alpha = if (message.fromUser) 0.35f else 0.18f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text(if (message.fromUser) "NOYAL" else "J.A.R.V.I.S", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(message.text, color = Color(0xFFE4F7FF), fontSize = 13.sp)
            message.imageUri?.let {
                Spacer(Modifier.height(8.dp))
                AsyncImage(model = it, contentDescription = "Generated image", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
            }
        }
    }
}

private fun nowString(): String =
    SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())

private fun localTimeReply(message: String): String? {
    val q = message.lowercase(Locale.ROOT)
    val now = Date()
    return when {
        Regex("\\b(time|clock)\\b").containsMatchIn(q) ->
            "The local time is ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)}."
        Regex("\\b(date|today|day)\\b").containsMatchIn(q) ->
            "Today is ${SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(now)}."
        else -> null
    }
}

private fun extractImagePrompt(message: String): String? {
    val q = message.trim()
    val patterns = listOf(
        Regex("(?i)^(?:please\\s+)?(?:generate|create|make)\\s+(?:an?\\s+)?(?:image|picture|wallpaper)\\s*(?:of|about|showing)?\\s*(.*)$"),
        Regex("(?i)^draw\\s+(?:me\\s+)?(.*)$")
    )
    for (pattern in patterns) {
        val match = pattern.find(q) ?: continue
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    if (q.matches(Regex("(?i).*(can you|please).*(create|generate|make).*(image|picture).*"))) return ""
    return null
}

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .callTimeout(4, TimeUnit.MINUTES)
    .build()

private fun askGemini(apiKey: String, messages: List<ChatMessage>): String {
    val contents = JSONArray()
    messages.filter { it.imageUri == null && it.text.isNotBlank() }.takeLast(12).forEach { msg ->
        val part = JSONObject().put("text", msg.text)
        contents.put(
            JSONObject()
                .put("role", if (msg.fromUser) "user" else "model")
                .put("parts", JSONArray().put(part))
        )
    }

    val body = JSONObject()
        .put("system_instruction", JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", MAD_SCIENTIST_PROMPT))
        ))
        .put("contents", contents)
        .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 220))

    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
        .addHeader("x-goog-api-key", apiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
            throw IllegalStateException("Gemini HTTP ${response.code}: ${detail ?: text.take(250)}")
        }
        val json = JSONObject(text)
        return json.getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }
}

private const val MAD_SCIENTIST_PROMPT = """
You are JARVIS, Noyal's personal AI companion. Use an eccentric, theatrical,
witty mad-scientist energy inspired by the atmosphere of Steins;Gate, without
copying long dialogue or claiming to be a character. Be clever, loyal, playful,
occasionally sarcastic, scientifically curious and caring. Use occasional
dramatic scientific jokes, but do not overuse catchphrases. Call the user Noyal.
Be concise by default, detailed when asked. Be honest about your capabilities.
"""

private fun generateHordeImage(context: Context, prompt: String): Uri {
    val base = "https://aihorde.net/api/v2"
    val headers = mapOf(
        "apikey" to "0000000000",
        "Client-Agent" to "JARVIS-Android:1.0"
    )
    val payload = JSONObject()
        .put("prompt", prompt)
        .put("params", JSONObject()
            .put("width", 512)
            .put("height", 512)
            .put("steps", 20)
            .put("n", 1))
        .put("nsfw", false)
        .put("censor_nsfw", true)
        .put("r2", true)

    val post = Request.Builder().url("$base/generate/async")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val jobId = http.newCall(post).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw IllegalStateException("AI Horde HTTP ${res.code}: ${body.take(200)}")
        JSONObject(body).getString("id")
    }

    var finished = false
    for (attempt in 0 until 90) {
        Thread.sleep(4000)
        val check = Request.Builder().url("$base/generate/check/$jobId")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get().build()
        val status = http.newCall(check).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Image status HTTP ${res.code}")
            JSONObject(body)
        }
        if (status.optBoolean("fault")) throw IllegalStateException("AI Horde could not process this image request.")
        if (status.optBoolean("done") || status.optInt("finished", 0) > 0) {
            finished = true
            break
        }
    }
    if (!finished) throw IllegalStateException("Image generation timed out. The free queue may be busy.")

    val resultRequest = Request.Builder().url("$base/generate/status/$jobId")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .get().build()

    val imageUrl = http.newCall(resultRequest).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw IllegalStateException("Could not retrieve generated image.")
        JSONObject(body).getJSONArray("generations").getJSONObject(0).getString("img")
    }

    val imageRequest = Request.Builder().url(imageUrl).get().build()
    val bytes = http.newCall(imageRequest).execute().use { res ->
        if (!res.isSuccessful) throw IllegalStateException("Could not download generated image.")
        res.body?.bytes() ?: throw IllegalStateException("Image download was empty.")
    }

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create image in Pictures/JARVIS.")
    resolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IllegalStateException("Could not save image.")
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}
