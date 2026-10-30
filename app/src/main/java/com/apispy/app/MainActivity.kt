package com.apispy.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// JS that hooks fetch + XHR and reports JSON responses
const val HOOK = """
(function(){
if(window.__cap)return;window.__cap=1;
function abs(u){try{return new URL(u,location.href).href}catch(e){return u}}
function send(m,u,s,b){try{Bridge.cap(JSON.stringify({m:m,u:abs(u),s:s,b:(b||'').slice(0,30000)}))}catch(e){}}
var of=window.fetch;
if(of){window.fetch=function(i,o){
 var u=(typeof i==='string')?i:(i&&i.url);var m=(o&&o.method)||(i&&i.method)||'GET';
 return of.apply(this,arguments).then(function(r){
  try{r.clone().text().then(function(t){send(m,u,r.status,t)})}catch(e){}
  return r})}}
var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return oo.apply(this,arguments)};
XMLHttpRequest.prototype.send=function(){var x=this;
 x.addEventListener('load',function(){try{
  if(x.responseType===''||x.responseType==='text'){send(x.__m,x.responseURL||x.__u,x.status,x.responseText)}
 }catch(e){}});
 return os.apply(this,arguments)};
})();
"""

// Scroll + open tabs/accordions (never logout/delete-like things)
const val STEP = """
(function(){
var bad=/logout|log out|sign ?out|delete|remove|cancel|unsubscribe/i;
window.scrollTo(0,document.body.scrollHeight);
var els=document.querySelectorAll('[role=tab],[data-toggle],[data-bs-toggle],.nav-link,.tab,.accordion-button');
var n=0;for(var i=0;i<els.length&&n<15;i++){var e=els[i];
 if(bad.test(e.innerText||'')||(e.getAttribute('href')||'').match(/logout|signout/i))continue;
 try{e.click();n++}catch(x){}}
})();
"""

// Find endpoint-looking strings inside the page's JS files
const val SCAN = """
(async function(){
var found={};
var srcs=[].slice.call(document.scripts).map(function(s){return s.src}).filter(Boolean);
var re=/["'`]((?:https?:\/\/[^"'`\s]+)?\/?[A-Za-z0-9_\-\/]+\.json(?:\?[^"'`\s<>]*)?|(?:https?:\/\/[^"'`\s]+)?\/(?:api|rest|graphql|ajax|services?|v[0-9]+)\/[^"'`\s<>]*)["'`]/gi;
for(var k=0;k<srcs.length;k++){try{
 var t=await (await fetch(srcs[k])).text();var m;
 while((m=re.exec(t)))found[m[1]]=1}catch(e){}}
try{Bridge.hidden(JSON.stringify(Object.keys(found)))}catch(e){}
})();
"""

// Same-origin links for auto-explore
const val LINKS = """
(function(){
var bad=/logout|signout|sign-out|delete|remove/i,out={};
[].slice.call(document.querySelectorAll('a[href]')).forEach(function(a){
 if(a.origin===location.origin&&!bad.test(a.href)&&a.href.indexOf('#')<0)out[a.href.split('#')[0]]=1});
Bridge.links(JSON.stringify(Object.keys(out)));
})();
"""

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var resBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val caps = LinkedHashMap<String, String>()
    private val hidden = LinkedHashSet<String>()
    private val queue = ArrayDeque<String>()
    private val visited = HashSet<String>()
    private val noise = Regex(
        "google-analytics|googletagmanager|doubleclick|facebook|hotjar|clarity\\.ms|sentry|segment|mixpanel|adservice|firebaselogging|gstatic|iconify|unisvg|browser-session-activity|getLoginPageConfiguration|getBanner|ProfileImage|ServerUserNotification",
        RegexOption.IGNORE_CASE
    )

    inner class Bridge {
        @JavascriptInterface fun cap(j: String) {
            try {
                val o = JSONObject(j)
                val u = o.getString("u")
                if (noise.containsMatchIn(u)) return
                val b = o.getString("b").trim()
                if (!(b.startsWith("{") || b.startsWith("["))) return
                val lines = ArrayList<String>()
                flat(JSONTokener(b).nextValue(), "", lines)
                val m = o.getString("m").uppercase()
                val path = u.substringAfter("//").substringAfter("/")
                val text = "▶ $m /$path\n" + (if (lines.isEmpty()) "  (empty)" else lines.joinToString("\n")) + "\n"
                ui.post { caps["$m $u"] = text; resBtn.text = "Results (${caps.size})" }
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun hidden(j: String) {
            try { val a = JSONArray(j); ui.post { for (i in 0 until a.length()) hidden.add(a.getString(i)) } } catch (e: Exception) { }
        }
        @JavascriptInterface fun links(j: String) {
            try {
                val a = JSONArray(j)
                ui.post {
                    if (queue.isEmpty()) {
                        for (i in 0 until a.length()) {
                            val l = a.getString(i)
                            if (queue.size < 8 && visited.add(l)) queue.add(l)
                        }
                        if (queue.isNotEmpty()) next() else toast("Explore done")
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun clean(s: String): String {
        var t = s
        if (t.contains("%3C") || t.contains("%20")) t = try { java.net.URLDecoder.decode(t, "UTF-8") } catch (e: Exception) { t }
        t = t.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return if (t.length > 60) t.take(60) + "…" else t
    }

    private fun flat(v: Any?, p: String, out: MutableList<String>) {
        if (out.size >= 12) return
        when (v) {
            is JSONObject -> v.keys().forEach { flat(v.get(it), if (p.isEmpty()) it else "$p.$it", out) }
            is JSONArray -> if (v.length() > 0) {
                flat(v.get(0), "$p[0]", out)
                if (v.length() > 1) out.add("  ($p has ${v.length()} items)")
            }
            JSONObject.NULL -> {}
            else -> { val x = clean(v.toString()); if (x.isNotEmpty()) out.add("  $p: $x") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun next() {
        val u = queue.removeFirstOrNull() ?: run { toast("Explore done"); return }
        web.loadUrl(u)
        ui.postDelayed({
            web.evaluateJavascript(STEP, null)
            web.evaluateJavascript(SCAN, null)
            ui.postDelayed({ next() }, 4000)
        }, 4000)
    }

    private fun explore() {
        toast("Exploring...")
        visited.add(web.url ?: "")
        web.evaluateJavascript(STEP, null)
        web.evaluateJavascript(SCAN, null)
        ui.postDelayed({ web.evaluateJavascript(LINKS, null) }, 2500)
    }

    private fun report(): String {
        val sb = StringBuilder("=== API CALLS (${caps.size}) ===\n\n")
        caps.values.forEach { sb.append(it).append("\n") }
        val seenCalls = caps.keys.map { it.substringAfter(' ') }
        val extra = hidden.filter { h -> seenCalls.none { it.endsWith(h) } }
        sb.append("=== HIDDEN ENDPOINTS IN JS (${extra.size}) ===\n")
        extra.forEach { sb.append(it).append("\n") }
        return sb.toString()
    }

    private fun showResults() {
        val tv = TextView(this).apply {
            text = report(); setTextIsSelectable(true); textSize = 12f; setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report())
                }, "Share"))
            }
            .setNegativeButton("Clear") { _, _ ->
                caps.clear(); hidden.clear(); resBtn.text = "Results (0)"
            }
            .show()
    }

    private fun go() {
        var u = urlBox.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        web.loadUrl(u)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urlBox = EditText(this).apply {
            hint = "Enter website URL"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        val goBtn = Button(this).apply { text = "Go"; setOnClickListener { go() } }
        root.addView(LinearLayout(this).apply { addView(urlBox); addView(goBtn) })

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val expBtn = Button(this).apply { text = "Auto-explore"; setOnClickListener { explore() } }
        resBtn = Button(this).apply { text = "Results (0)"; setOnClickListener { showResults() } }
        val w = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        root.addView(LinearLayout(this).apply { addView(expBtn, w()); addView(resBtn, w()) })
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.addJavascriptInterface(Bridge(), "Bridge")
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(web, HOOK, setOf("*"))
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                if (!docStart) v.evaluateJavascript(HOOK, null)
                urlBox.setText(url)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
