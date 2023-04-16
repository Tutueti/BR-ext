package com.dramaclub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.util.*
import kotlin.collections.ArrayList
#### FajerShow
class DramaClub : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.dramaclub.one/"
    override var name = "DramaClub"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    

    private fun Element.toSearchResponse(home: Boolean): SearchResponse? {
        val quality = select("span.quality").text().replace("-|p".toRegex(), "")
        if(home == true) {
            val titleElement = select("div.data h3 a")
            val posterUrl = select("img").attr("src")
            val tvType = if (titleElement.attr("href").contains("/filmes/")) TvType.Movie else TvType.TvSeries
            // If you need to differentiate use the url.
            return MovieSearchResponse(
                titleElement.text().replace(".*\\|".toRegex(), ""),
                titleElement.attr("href"),
                this@DramaClub.name,
                tvType,
                posterUrl,
                quality = getQualityFromString(quality)
            )
        } else {
            val posterElement = select("img")
            val url = select("div.thumbnail > a").attr("href")
            return MovieSearchResponse(
                posterElement.attr("alt"),
                url,
                this@DramaClub.name,
                if (url.contains("/filmes/")) TvType.Movie else TvType.TvSeries,
                posterElement.attr("src"),
                quality = getQualityFromString(quality)
            )
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/genero/coreia-do-sul/page/" to "Dramas Coreanos",
        "$mainUrl/genero/china/page/" to "Dramas Chineses",
        "$mainUrl/genero/japao/page/" to "Dramas Japoneses",
        "$mainUrl/genero/tailandes/page/" to "Dramas Tailandeses",
        "$mainUrl/genero/taiwan/page/" to "Dramas Taiwaneses",
        "$mainUrl/genero/no-ar/page/" to "Dramas No Ar",
        "$mainUrl/genero/concluidos/page/" to "Dramas Concluídos",
        "$mainUrl/filmes/page/" to "Filmes",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResponse(true)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(".result-item > article").mapNotNull {
            it.toSearchResponse(false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = url.contains("/filmes/")

        val posterUrl = doc.select("div.poster > img").attr("src")
        val rating = doc.select("span[itemprop=\"ratingValue\"]").text().toIntOrNull()
        val title = doc.select("div.data > h1").text()
        val synopsis = doc.select("div.wp-content > p").text()

        val tags = doc.select("a[rel=\"tag\"]")?.map { it.text() }

        val actors = doc.select("div.person").mapNotNull {
            val name = it.selectFirst("div > a > img")?.attr("alt") ?: return@mapNotNull null
            val image = it.selectFirst("div > a > img")?.attr("src") ?: return@mapNotNull null
            val roleString = it.select("div.data > div.caracter").text()
            val mainActor = Actor(name, image)
            ActorData(actor = mainActor, roleString = roleString)
        }

        return if (isMovie) {
            val recommendations = doc.select(".owl-item article").mapNotNull { element ->
                element.toSearchResponse(true)
            }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
                this.rating = rating
            }
        } else {
            val episodes = doc.select(".se-c ul > li").map {
                Episode(
                    it.select("div.episodiotitle > a").attr("href"),
                    it.select("div.episodiotitle > a").text(),
                    it.select("div.numerando").text().split(" - ")[0].toInt(),
                    it.select("div.numerando").text().split(" - ")[1].toInt(),
                    it.select("div.imagen a img").attr("src")
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.actors = actors
            }
        }
    }

    data class FajerLive (
        @JsonProperty("success"  ) var success  : Boolean?          = null,
        @JsonProperty("data"     ) var data     : ArrayList<Data>   = arrayListOf(),
    )
    data class Data (
        @JsonProperty("file"  ) var file  : String? = null,
        @JsonProperty("label" ) var label : String? = null,
        @JsonProperty("type"  ) var type  : String? = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("li.vid_source_option").not("[data-nume=\"trailer\"]").apmap { source ->
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to source.attr("data-post"),
                    "nume" to source.attr("data-nume"),
                    "type" to source.attr("data-type")
                )
            ).document.select("iframe").attr("src").let {
                val hostname = URI(it).host
                if (it.contains("dramaclub.one")) {
                    val url = URI(it)
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "DramaClub Principal",
                            url.query.replace("&.*|source=".toRegex(), ""),
                            data,
                            Qualities.Unknown.value,
                            url.query.replace("&.*|source=".toRegex(), "").contains(".m3u8")
                        )
                    )
                    println("Drama\n" + url.query.replace("&.*|source=".toRegex(), "") + "\n")
                }
                else loadExtractor(it, data, subtitleCallback, callback)
            }
        }
        return true
    }
}