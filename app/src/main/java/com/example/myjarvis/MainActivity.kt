package com.example.myjarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

const val WAKE_WORD = "jarvis"
const val API_KEY = "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"

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private var listeningForCommand = false
    private val client = OkHttpClient()

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        scrollView = findViewById(R.id.scrollContainer)
        val startBtn: Button = findViewById(R.id.startButton)

        tts = TextToSpeech(this, this)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 100)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        startBtn.setOnClickListener {
            log("Sun raha hoon... '$WAKE_WORD' bolke shuru karo")
            startListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("hi", "IN")
        }
    }

    private fun speak(text: String) {
        log("Assistant: $text")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun log(text: String) {
        runOnUiThread {
            logView.append("\n$text")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                log("Suna: $heard")
                handleHeardText(heard)
            }

            override fun onError(error: Int) {
                startListening()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    private fun handleHeardText(heard: String) {
        if (!listeningForCommand) {
            if (heard.contains(WAKE_WORD)) {
                listeningForCommand = true
                speak("Haan bhai, bolo")
                startListening()
            } else {
                startListening()
            }
            return
        }

        listeningForCommand = false
        processCommand(heard)
    }

    private fun processCommand(command: String) {
        when {
            command.startsWith("call ") -> {
                val name = command.removePrefix("call ").trim()
                makeCall(name)
            }
            command.startsWith("message ") || command.startsWith("send message ") -> {
                val rest = command.substringAfter("message ").trim()
                val parts = rest.split(" ", limit = 2)
                if (parts.size == 2) sendMessage(parts[0], parts[1])
                else speak("Kisko aur kya message bhejna hai, poora bolo")
            }
            command.startsWith("open ") -> {
                val appName = command.removePrefix("open ").trim()
                openApp(appName)
            }
            else -> {
                askAI(command)
            }
        }
        startListening()
    }

    private fun makeCall(contactName: String) {
        val number = findContactNumber(contactName)
        if (number == null) {
            speak("$contactName ka number nahi mila")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            startActivity(intent)
            speak("$contactName ko call kar raha hoon")
        } else {
            speak("Call karne ki permission nahi hai")
        }
    }

    private fun sendMessage(contactName: String, text: String) {
        val number = findContactNumber(contactName)
        if (number == null) {
            speak("$contactName ka number nahi mila")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            speak("$contactName ko message bhej diya")
        } else {
            speak("Message bhejne ki permission nahi hai")
        }
    }

    private fun findContactNumber(name: String): String? {
        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val contactName = it.getString(nameIdx)
                if (contactName != null && contactName.lowercase(Locale.getDefault()).contains(name)) {
                    return it.getString(numIdx)
                }
            }
        }
        return null
    }

    private fun openApp(appName: String) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(appName)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            startActivity(launchIntent)
            speak("$appName khol raha hoon")
        } else {
            speak("$appName nahi mila")
        }
    }

    private fun askAI(question: String) {
        speak("Sochne do...")

        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 300)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", question)
                }
            ))
        }

        val body = RequestBody.create(
            MediaType.parse("application/json"), json.toString()
        )
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", API_KEY)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                speak("Internet me dikkat aa rahi hai")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val respBody = response.body()?.string() ?: ""
                    val obj = JSONObject(respBody)
                    val content = obj.getJSONArray("content").getJSONObject(0).getString("text")
                    speak(content)
                } catch (e: Exception) {
                    Log.e("MyJarvis", "Parse error", e)
                    speak("Jawab samajhne me dikkat aayi")
                }
            }
        })
    }
}
