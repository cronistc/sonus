package app.sounos.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.sounos.BuildConfig
import app.sounos.SounosApp
import app.sounos.data.Backups
import app.sounos.extractor.Sounos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Atualização do app inteiro, sem tela de instalação.
 *
 *  - Procura versão nova ao abrir o app e a cada 6 h (arquivo update.json, poucos bytes).
 *  - Achou: baixa o APK em segundo plano, confere tamanho + SHA-256 e se está assinado com a
 *    MESMA chave do Sonus instalado (senão descarta).
 *  - Instala sozinho (PackageInstaller, sem pedir confirmação — Android 12+, o Sonus é o
 *    instalador de si mesmo) quando o app vai para o fundo e nada está tocando.
 *    "Atualizar agora" (tela de atualização) instala na hora.
 *
 * update.json:
 *   { "versionCode": 2, "versionName": "0.2", "apk": "https://…/Sonus.apk", "size": 4000000,
 *     "sha256": "…", "date": "2026-10-21", "notes": ["…", "…"] }
 */
class Updater(private val app: SounosApp) {
    private val prefs = app.getSharedPreferences("sounos.update", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(app.filesDir, "update").apply { mkdirs() }
    private val listeners = mutableListOf<(JSONObject) -> Unit>()
    private var job: Job? = null

    /** idle | checking | uptodate | available | downloading | ready | installing | error */
    @Volatile private var status = "idle"
    @Volatile private var progress = 0.0
    @Volatile private var error: String? = null
    @Volatile var foreground = true

    fun addListener(l: (JSONObject) -> Unit) { listeners += l }
    fun removeListener(l: (JSONObject) -> Unit) { listeners -= l }
    private fun emit() { val s = state(); main.post { listeners.toList().forEach { it(s) } } }

    val currentCode: Long get() = pkg().let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
    val currentName: String get() = pkg().versionName ?: "?"
    private fun pkg(): PackageInfo = app.packageManager.getPackageInfo(app.packageName, 0)

    /** Onde procurar. Nos testes (build de desenvolvimento) dá para apontar para outro endereço. */
    private val manifestUrl: String get() =
        prefs.getString("url", null)?.takeIf { BuildConfig.DEBUG } ?: DEFAULT_URL
    fun setTestUrl(url: String?) { if (BuildConfig.DEBUG) prefs.edit().putString("url", url).apply() }

    private fun info(): JSONObject? = prefs.getString("info", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.optLong("versionCode") > currentCode }
    private fun apkFile(i: JSONObject) = File(dir, "sonus-${i.optLong("versionCode")}.apk")
    fun canInstallSilently() = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    fun state(): JSONObject {
        val i = info()
        val st = when {
            status == "checking" || status == "downloading" || status == "installing" -> status
            i != null && apkFile(i).length() > 0 && prefs.getBoolean("verified:${i.optLong("versionCode")}", false) -> "ready"
            i != null -> if (status == "error") "error" else "available"
            status == "error" -> "error"
            prefs.getLong("lastCheck", 0) > 0 -> "uptodate"
            else -> "idle"
        }
        return JSONObject()
            .put("status", st).put("progress", progress).put("error", error ?: JSONObject.NULL)
            .put("current", JSONObject().put("versionName", currentName).put("versionCode", currentCode)
                .put("notes", prefs.getString("currentNotes", null)?.let { JSONArray(it) } ?: JSONArray()))
            .put("update", i ?: JSONObject.NULL)
            .put("lastCheck", prefs.getLong("lastCheck", 0))
            .put("canInstall", canInstallSilently())
            .put("justUpdated", prefs.getString("justUpdated", null) ?: JSONObject.NULL)
    }

    // ---------------- procurar ----------------
    /** Ao abrir / voltar para o app: só procura se a última busca tiver mais de 6 h. */
    fun maybeCheck() {
        noteInstalledVersion()
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) > SIX_HOURS) check(auto = true)
        else autoContinue()
    }

    fun check(auto: Boolean = false) {
        if (job?.isActive == true) return
        status = "checking"; error = null; emit()
        job = app.io.launch {
            runCatching {
                val body = Sounos.http.newCall(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    r.body!!.string()
                }
                val i = JSONObject(body)
                require(i.optLong("versionCode") > 0 && i.optString("apk").startsWith("https://") || BuildConfig.DEBUG) { "update.json inválido" }
                prefs.edit().putString("info", i.toString()).putLong("lastCheck", System.currentTimeMillis()).apply()
                status = "idle"
            }.onFailure {
                // busca automática que falhou (sem internet etc.) fica em silêncio; só a busca pedida mostra erro
                if (auto) status = "idle" else { status = "error"; error = "Não deu para procurar agora." }
            }
            emit()
            job = null                                   // esta busca terminou: o download pode começar
            if (status != "error") autoContinue()
        }
    }

    /** Tem versão nova: baixa sozinho (no Wi-Fi, ou em qualquer rede se a pessoa não restringiu) e instala quando der. */
    private fun autoContinue() {
        val i = info() ?: return
        if (state().optString("status") == "available" && (!metered() || !app.library.settings().optBoolean("wifiOnlyDownloads"))) download()
        else installIfIdle()
    }

    private fun metered() = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    // ---------------- baixar ----------------
    fun download(thenInstall: Boolean = false) {
        val i = info() ?: return
        if (job?.isActive == true) return
        status = "downloading"; progress = 0.0; error = null; emit()
        job = app.io.launch {
            val out = apkFile(i); val tmp = File(dir, out.name + ".part")
            runCatching {
                dir.listFiles()?.filter { it != out && it != tmp }?.forEach { it.delete() }      // versões antigas
                Sounos.http.newCall(Request.Builder().url(i.getString("apk")).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val total = r.body!!.contentLength().takeIf { it > 0 } ?: i.optLong("size", -1)
                    val md = MessageDigest.getInstance("SHA-256")
                    var have = 0L; var last = 0L
                    r.body!!.byteStream().use { input -> tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); md.update(buf, 0, n); have += n
                            if (total > 0 && System.currentTimeMillis() - last > 200) { last = System.currentTimeMillis(); progress = have.toDouble() / total; emit() }
                        }
                    } }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    check(i.optLong("size", have) == have) { "tamanho diferente" }
                    check(i.optString("sha256").equals(sha, ignoreCase = true)) { "arquivo corrompido" }
                }
                check(sameSigner(tmp)) { "assinatura diferente" }
                check(tmp.renameTo(out))
                prefs.edit().putBoolean("verified:${i.optLong("versionCode")}", true).apply()
                status = "idle"; progress = 1.0
            }.onFailure { tmp.delete(); status = "error"; error = "Não deu para baixar a atualização." }
            emit()
            job = null
            if (status != "error") { if (thenInstall) install() else installIfIdle() }
        }
    }

    /** O APK novo precisa ter a MESMA assinatura do Sonus instalado (senão nem tenta). */
    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean {
        val pm = app.packageManager
        fun certs(p: PackageInfo?) = if (Build.VERSION.SDK_INT >= 28) p?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                                     else p?.signatures?.map { it.toCharsString() }?.toSet()
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val mine = certs(pm.getPackageInfo(app.packageName, flag))
        val theirs = certs(pm.getPackageArchiveInfo(apk.path, flag))
        val p = pm.getPackageArchiveInfo(apk.path, 0)
        return mine != null && mine == theirs && p?.packageName == app.packageName
    }

    // ---------------- instalar ----------------
    /** Instala sozinho só quando ninguém vai perceber: app no fundo e nada tocando. */
    fun installIfIdle() {
        if (state().optString("status") == "ready" && !foreground && !app.player.isPlaying && canInstallSilently()) install()
    }

    fun install() {
        val i = info() ?: return
        val apk = apkFile(i)
        if (apk.length() == 0L) return download(thenInstall = true)
        status = "installing"; error = null; emit()
        app.io.launch {
            runCatching {
                prefs.edit().putString("pendingNotes", JSONArray(i.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList<String>()).toString())
                    .putBoolean("relaunch", foreground).apply()
                // cópia de segurança dos dados do usuário antes de instalar (falha nela não impede a atualização)
                Backups.snapshot(app, currentCode)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(apk.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("sonus.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallResult::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pi.intentSender)
                }
            }.onFailure { status = "error"; error = "Não deu para instalar."; emit() }
        }
    }

    internal fun onInstallResult(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // primeira vez (ou Android antigo): o sistema pede um "Atualizar". Só mostra com o app aberto.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                              else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                status = "idle"; emit()
                if (confirm != null && foreground) app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> { status = "idle"; emit() }        // (normalmente o app já foi fechado pelo sistema)
            else -> { status = "error"; error = "A instalação não terminou."; emit() }
        }
    }

    /** Depois de atualizar: guarda as novidades da versão instalada e limpa o APK baixado. */
    private fun noteInstalledVersion() {
        val seen = prefs.getLong("seenCode", -1)
        if (seen == currentCode) return
        prefs.getString("pendingNotes", null)?.let { prefs.edit().putString("currentNotes", it).remove("pendingNotes").apply() }
        if (seen > 0) prefs.edit().putString("justUpdated", currentName).apply()
        prefs.edit().putLong("seenCode", currentCode).apply()
        dir.listFiles()?.forEach { it.delete() }
    }
    fun clearJustUpdated() = prefs.edit().remove("justUpdated").apply()
    fun consumeRelaunch(): Boolean = prefs.getBoolean("relaunch", false).also { prefs.edit().remove("relaunch").apply() }

    /** Tela do sistema para liberar "instalar apps" para o Sonus (uma vez só). */
    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Ramo "updates" do repositório: só update.json + o APK da versão publicada. */
        const val DEFAULT_URL = "https://raw.githubusercontent.com/cronistc/sonus/updates/update.json"
        private const val SIX_HOURS = 6 * 3_600_000L
    }
}

/** Resultado da instalação (PackageInstaller). */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SounosApp.instance.updater.onInstallResult(intent)
}

/** Sonus atualizado: se a pessoa estava com o app aberto ("Atualizar agora"), abre de novo. */
class Updated : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SounosApp.instance.updater.consumeRelaunch())
            runCatching { context.startActivity(Intent(context, app.sounos.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
