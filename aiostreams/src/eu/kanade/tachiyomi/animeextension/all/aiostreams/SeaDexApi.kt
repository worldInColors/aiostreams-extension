package eu.kanade.tachiyomi.animeextension.all.aiostreams
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * SeaDex API Client
 * Fetches "best" release info from https://releases.moe/
 */
object SeaDexApi {
    private const val SEADEX_API_BASE = "https://releases.moe/api/collections/entries/records"
    
    fun getBestInfoHashesForAnime(client: OkHttpClient, anilistId: Int): Set<String> {
        val entryUrl = "$SEADEX_API_BASE?expand=trs&filter=alID=$anilistId&sort=-trs.isBest"
        
        val entryRequest = Request.Builder()
            .url(entryUrl)
            .get()
            .build()
        
        val entryResponse = client.newCall(entryRequest).execute()
        if (!entryResponse.isSuccessful) {
           
            return emptySet()
        }
        
        val json = JSONObject(entryResponse.body.string())
        val items = json.optJSONArray("items") ?: return emptySet()
        
        if (items.length() == 0) {
            return emptySet()
        }
        
        val bestHashes = mutableSetOf<String>()
        val fallbackHashes = mutableSetOf<String>()
        
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val expand = item.optJSONObject("expand") ?: continue
            val trsArray = expand.optJSONArray("trs") ?: continue
            
            for (j in 0 until trsArray.length()) {
                val torrent = trsArray.getJSONObject(j)
                val infoHash = torrent.optString("infoHash", "").lowercase()
                val isBest = torrent.optBoolean("isBest", false)
                
              
                if (infoHash.isEmpty() || infoHash == "<redacted>") {
                    continue
                }
                if (isBest) {
                    bestHashes.add(infoHash)
                } else {
                    fallbackHashes.add(infoHash)
                }
            }
        }
        return if (bestHashes.isNotEmpty()) {
            bestHashes
        } else {
            fallbackHashes
        }
    }
    
    fun extractInfoHash(description: String): String? {
        // Look for hash after the 🧩 emoji (puzzle piece)
        val puzzlePattern = Regex("🧩\\s*([a-fA-F0-9]{40})")
        val puzzleMatch = puzzlePattern.find(description)
        if (puzzleMatch != null) {
            return puzzleMatch.groupValues[1].lowercase()
        }
        
        // Fallback: Look for any 40-char hex string (generic infohash patterns)
        val patterns = listOf(
            Regex("infohash[:\\s]+([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE),
            Regex("\\b([a-fA-F0-9]{40})\\b"),
        )
        
        for (pattern in patterns) {
            val match = pattern.find(description)
            if (match != null) {
                return match.groupValues[1].lowercase()
            }
        }
        
        return null
    }
}